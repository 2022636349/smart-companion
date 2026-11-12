from __future__ import annotations

import asyncio
import hashlib
import logging
from bisect import bisect_right
from collections.abc import Callable
from typing import Protocol

from pydantic import ValidationError

from app.clients.assessment_source import AssessmentSourceClient
from app.contracts.graph_ingestion import (
    ExtractedGraphEdgeCandidate,
    ExtractedGraphNodeCandidate,
    GraphCandidateReview,
    GraphDraftEdge,
    GraphDraftNode,
    GraphDraftSource,
    GraphExtractReviewRequest,
    GraphExtractReviewResponse,
    GraphExtractorOutput,
    GraphExtractorProviderOutput,
    GraphIngestionModels,
    GraphIngestionPrompts,
    GraphModelIdentity,
    GraphPromptIdentity,
    GraphReviewerOutput,
    ValidatedGraphEvidence,
)
from app.core.config import AssessmentSourceSettings, GraphIngestionSettings
from app.prompts.graph_ingestion import (
    GRAPH_EXTRACTOR_PROMPT_SHA256,
    GRAPH_EXTRACTOR_PROMPT_VERSION,
    GRAPH_REVIEWER_PROMPT_SHA256,
    GRAPH_REVIEWER_PROMPT_VERSION,
    build_graph_extractor_messages,
    build_graph_reviewer_messages,
    numbered_document_lines,
)
from app.providers.base import ModelProvider, ModelRequest, ModelResponseError
from app.providers.factory import create_model_provider
from app.services.document_extraction import AssessmentDocumentExtractor, extract_assessment_document


class GraphIngestionAgentError(RuntimeError):
    code = "GRAPH_INGESTION_AGENT_ERROR"


class GraphIngestionRequestIdMismatchError(GraphIngestionAgentError):
    code = "GRAPH_INGESTION_REQUEST_ID_MISMATCH"


class GraphExtractorOutputError(GraphIngestionAgentError):
    code = "GRAPH_EXTRACTOR_INVALID_RESPONSE"


class GraphReviewerOutputError(GraphIngestionAgentError):
    code = "GRAPH_REVIEWER_INVALID_RESPONSE"


class GraphIngestionDeadlineExceededError(GraphIngestionAgentError):
    code = "GRAPH_INGESTION_TIMEOUT"


class GraphSourceReader(Protocol):
    async def download(self, *, source_file_id: int, download_url: str, request_id: str): ...
    async def aclose(self) -> None: ...


ProviderFactory = Callable[[], ModelProvider]
MAX_STRUCTURED_GENERATION_ATTEMPTS = 2
logger = logging.getLogger(__name__)


class GraphIngestionAgent:
    """Transient Extractor -> independent Reviewer -> exact C draft DTO pipeline."""

    def __init__(
        self,
        source_client: GraphSourceReader,
        document_extractor: AssessmentDocumentExtractor,
        *,
        extractor_provider_factory: ProviderFactory = create_model_provider,
        reviewer_provider_factory: ProviderFactory = create_model_provider,
        total_timeout_seconds: float = 80.0,
        max_candidates: int = 300,
    ) -> None:
        self._source_client = source_client
        self._document_extractor = document_extractor
        self._extractor_provider_factory = extractor_provider_factory
        self._reviewer_provider_factory = reviewer_provider_factory
        self._total_timeout_seconds = total_timeout_seconds
        self._max_candidates = max_candidates

    async def aclose(self) -> None:
        await self._source_client.aclose()

    async def extract_and_review(
        self,
        request: GraphExtractReviewRequest,
        *,
        trace_request_id: str,
    ) -> GraphExtractReviewResponse:
        if request.request_id != trace_request_id:
            raise GraphIngestionRequestIdMismatchError(
                "The body request_id must match X-Request-Id."
            )
        try:
            async with asyncio.timeout(self._total_timeout_seconds):
                return await self._run(request, trace_request_id=trace_request_id)
        except TimeoutError as exc:
            raise GraphIngestionDeadlineExceededError(
                "Graph extraction and review exceeded the configured total timeout."
            ) from exc

    async def _run(
        self,
        request: GraphExtractReviewRequest,
        *,
        trace_request_id: str,
    ) -> GraphExtractReviewResponse:
        source = await self._source_client.download(
            source_file_id=request.source_file_id,
            download_url=str(request.download_url),
            request_id=trace_request_id,
        )
        source_hash = hashlib.sha256(source.content).hexdigest()
        document = await extract_assessment_document(
            self._document_extractor,
            filename=request.filename,
            content=source.content,
        )
        try:
            document_lines = numbered_document_lines(document.text)
        except ValueError as exc:
            raise GraphExtractorOutputError(
                "The extracted document cannot be represented with source locators."
            ) from exc

        extractor_failure: Exception | None = None
        for attempt in range(MAX_STRUCTURED_GENERATION_ATTEMPTS):
            try:
                extracted_response = await self._call_extractor(
                    request,
                    document_lines,
                    request_id=trace_request_id,
                    validation_feedback=(
                        _retry_feedback(extractor_failure) if attempt else None
                    ),
                )
                if extracted_response.structured_content is None:
                    raise ValueError("extractor structured content is missing")
                provider_output = GraphExtractorProviderOutput.model_validate(
                    extracted_response.structured_content
                )
                provider_output = _bound_extractor_candidates(
                    provider_output, max_candidates=self._max_candidates
                )
                evidence, repaired_evidence_count = self._restore_and_validate_evidence(
                    provider_output,
                    request=request,
                    document_lines=document_lines,
                    source_hash=source_hash,
                    allow_line_fallback=attempt == MAX_STRUCTURED_GENERATION_ATTEMPTS - 1,
                    line_pages=document.line_pages,
                )
                extracted = GraphExtractorOutput.model_validate(
                    {
                        "nodes": [item.model_dump(mode="json") for item in provider_output.nodes],
                        "edges": [item.model_dump(mode="json") for item in provider_output.edges],
                        "sources": [item.model_dump(mode="json") for item in evidence],
                        "warnings": _evidence_warnings(
                            provider_output.warnings, repaired_evidence_count
                        ),
                    }
                )
                if (
                    len(extracted.nodes) + len(extracted.edges) + len(extracted.sources)
                    > self._max_candidates
                ):
                    raise ValueError("extractor candidate count exceeds the configured limit")
                break
            except (ModelResponseError, ValidationError, ValueError, TypeError) as exc:
                _log_validation_failure("extractor", attempt + 1, trace_request_id, exc)
                extractor_failure = exc
        else:
            raise GraphExtractorOutputError(
                "The extractor returned invalid or untraceable candidates."
            ) from extractor_failure
        self._validate_prompt_metadata(
            extracted_response.metadata.prompt_version,
            GRAPH_EXTRACTOR_PROMPT_VERSION,
            GraphExtractorOutputError,
        )
        extractor_identity = self._validated_model_identity(
            extracted_response.metadata.provider,
            extracted_response.metadata.model,
            GraphExtractorOutputError,
        )

        reviewer_failure: Exception | None = None
        for _attempt in range(MAX_STRUCTURED_GENERATION_ATTEMPTS):
            try:
                reviewer_response = await self._call_reviewer(
                    request,
                    document_lines,
                    extracted,
                    request_id=trace_request_id,
                    validation_feedback=_reviewer_retry_feedback(reviewer_failure),
                )
                if reviewer_response.structured_content is None:
                    raise ValueError("reviewer structured content is missing")
                reviewed = GraphReviewerOutput.model_validate(
                    reviewer_response.structured_content
                )
                review_by_id = self._validate_review_coverage(extracted, reviewed)
                break
            except (ModelResponseError, ValidationError, ValueError, TypeError) as exc:
                _log_validation_failure("reviewer", _attempt + 1, trace_request_id, exc)
                reviewer_failure = exc
        else:
            raise GraphReviewerOutputError(
                "The reviewer returned invalid or incomplete decisions."
            ) from reviewer_failure
        self._validate_prompt_metadata(
            reviewer_response.metadata.prompt_version,
            GRAPH_REVIEWER_PROMPT_VERSION,
            GraphReviewerOutputError,
        )
        reviewer_identity = self._validated_model_identity(
            reviewer_response.metadata.provider,
            reviewer_response.metadata.model,
            GraphReviewerOutputError,
        )

        node_id_by_candidate = {
            item.candidate_id: _stable_id("node", source_hash, item.candidate_id, max_hash=40)
            for item in extracted.nodes
        }
        evidence_by_id = {item.evidence_id: item for item in extracted.sources}
        nodes = [
            self._reviewed_node(
                item,
                review_by_id[item.candidate_id],
                node_id_by_candidate=node_id_by_candidate,
                evidence_by_id=evidence_by_id,
                rights_status=_rights_status(request.source.copyright_status),
            )
            for item in extracted.nodes
        ]
        edges = [
            self._reviewed_edge(
                item,
                review_by_id[item.candidate_id],
                node_id_by_candidate=node_id_by_candidate,
                evidence_by_id=evidence_by_id,
            )
            for item in extracted.edges
        ]
        sources = self._reviewed_sources(
            request,
            extracted,
            review_by_id,
            node_id_by_candidate=node_id_by_candidate,
            source_hash=source_hash,
        )
        if len(sources) > 500 or len(nodes) + len(edges) + len(sources) > self._max_candidates:
            raise GraphExtractorOutputError(
                "The restored graph drafts exceed the configured candidate limit."
            )
        try:
            return GraphExtractReviewResponse(
                course_id=request.course_id,
                request_id=request.request_id,
                job_id=request.job_id,
                nodes=nodes,
                edges=edges,
                sources=sources,
                warnings=_unique_items(
                    [*document.warnings, *extracted.warnings, *reviewed.warnings]
                ),
                models=GraphIngestionModels(
                    extractor=extractor_identity,
                    reviewer=reviewer_identity,
                ),
                prompts=GraphIngestionPrompts(
                    extractor=GraphPromptIdentity(
                        version=GRAPH_EXTRACTOR_PROMPT_VERSION,
                        sha256=GRAPH_EXTRACTOR_PROMPT_SHA256,
                    ),
                    reviewer=GraphPromptIdentity(
                        version=GRAPH_REVIEWER_PROMPT_VERSION,
                        sha256=GRAPH_REVIEWER_PROMPT_SHA256,
                    ),
                ),
            )
        except ValidationError as exc:
            raise GraphReviewerOutputError(
                "The reviewed candidates cannot be represented by the frozen contract."
            ) from exc

    async def _call_extractor(
        self,
        request,
        document_lines,
        *,
        request_id: str,
        validation_feedback: str | None = None,
    ):
        provider = self._extractor_provider_factory()
        try:
            return await provider.generate(
                ModelRequest(
                    messages=build_graph_extractor_messages(
                        request,
                        document_lines,
                        validation_feedback=validation_feedback,
                    ),
                    prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION,
                    max_tokens=12_000,
                    temperature=0.0,
                    request_id=request_id,
                    capability="GRAPH_EXTRACT",
                    reasoning_mode="disabled",
                ),
                output_model=GraphExtractorProviderOutput,
            )
        finally:
            await provider.aclose()

    async def _call_reviewer(self, request, document_lines, extracted, *, request_id: str, validation_feedback=None):
        try:
            messages = build_graph_reviewer_messages(request, document_lines, extracted,
                                                      validation_feedback=validation_feedback)
        except ValueError as exc:
            raise GraphReviewerOutputError(
                "The reviewer input exceeds a safe prompt boundary."
            ) from exc
        provider = self._reviewer_provider_factory()
        try:
            return await provider.generate(
                ModelRequest(
                    messages=messages,
                    prompt_version=GRAPH_REVIEWER_PROMPT_VERSION,
                    max_tokens=8_000,
                    temperature=0.0,
                    request_id=request_id,
                    capability="GRAPH_REVIEW",
                    reasoning_mode="disabled",
                ),
                output_model=GraphReviewerOutput,
            )
        finally:
            await provider.aclose()

    @staticmethod
    def _restore_and_validate_evidence(
        generated: GraphExtractorProviderOutput,
        *,
        request: GraphExtractReviewRequest,
        document_lines: tuple[str, ...],
        source_hash: str,
        allow_line_fallback: bool,
        line_pages: tuple[int | None, ...] = (),
    ) -> tuple[list[ValidatedGraphEvidence], int]:
        result: list[ValidatedGraphEvidence] = []
        repaired_count = 0
        for item in generated.sources:
            excerpt, line_from, line_to, repaired = _resolve_evidence(
                item,
                document_lines,
                allow_line_fallback=allow_line_fallback,
            )
            repaired_count += int(repaired)
            locator = (
                f"lines {line_from}"
                if line_from == line_to
                else f"lines {line_from}-{line_to}"
            )
            evidence_data = item.model_dump(mode="python")
            evidence_data.update(
                excerpt=excerpt,
                line_from=line_from,
                line_to=line_to,
            )
            pages = [p for p in line_pages[line_from - 1:line_to] if p is not None]
            result.append(
                ValidatedGraphEvidence(
                    **evidence_data,
                    source_file_id=request.source_file_id,
                    locator=locator,
                    source_hash=source_hash,
                    page_from=min(pages) if pages else None,
                    page_to=max(pages) if pages else None,
                )
            )
        return result, repaired_count

    @staticmethod
    def _validate_review_coverage(extracted, reviewed) -> dict[str, GraphCandidateReview]:
        expected_kind = {
            **{item.candidate_id: "NODE" for item in extracted.nodes},
            **{item.candidate_id: "EDGE" for item in extracted.edges},
            **{item.evidence_id: "SOURCE" for item in extracted.sources},
        }
        result: dict[str, GraphCandidateReview] = {}
        for item in reviewed.reviews:
            if item.candidate_id in result:
                raise ValueError("Reviewer decisions must be unique.")
            if expected_kind.get(item.candidate_id) != item.candidate_kind:
                raise ValueError("Reviewer candidate kind does not match.")
            if (
                item.duplicate_of is not None
                and expected_kind.get(item.duplicate_of) != item.candidate_kind
            ):
                raise ValueError("duplicate_of must reference the same candidate kind.")
            result[item.candidate_id] = item
        if set(result) != set(expected_kind):
            raise ValueError("Reviewer decisions must cover every candidate and source.")
        if any(
            item.duplicate_of is not None and item.duplicate_of not in expected_kind
            for item in reviewed.reviews
        ):
            raise ValueError("duplicate_of must reference this extraction batch.")
        return result

    @staticmethod
    def _reviewed_node(candidate, review, *, node_id_by_candidate, evidence_by_id, rights_status):
        combined = min(candidate.extractor_confidence, review.confidence)
        evidence = [evidence_by_id[item].excerpt for item in candidate.evidence_ids]
        return GraphDraftNode(
            candidate_id=candidate.candidate_id,
            id=node_id_by_candidate[candidate.candidate_id],
            name=candidate.name,
            level=candidate.level,
            node_type=candidate.node_type,
            parent_id=node_id_by_candidate.get(candidate.parent_candidate_id),
            description=candidate.description,
            aliases=candidate.aliases,
            keywords=candidate.keywords,
            difficulty=candidate.difficulty,
            importance=candidate.importance,
            estimated_minutes=candidate.estimated_minutes,
            learning_objectives=candidate.learning_objectives,
            common_misconceptions=candidate.common_misconceptions,
            example_code=candidate.example_code,
            rights_status=rights_status,
            evidence=evidence,
            extractor_confidence=candidate.extractor_confidence,
            reviewer_confidence=review.confidence,
            review_status="PENDING",
            decision_status="PENDING",
            review_lane=_review_lane(review, combined, fast_track=0.90),
        )

    @staticmethod
    def _reviewed_edge(candidate, review, *, node_id_by_candidate, evidence_by_id):
        combined = min(candidate.extractor_confidence, review.confidence)
        return GraphDraftEdge(
            candidate_id=candidate.candidate_id,
            source_id=node_id_by_candidate[candidate.source_candidate_id],
            target_id=node_id_by_candidate[candidate.target_candidate_id],
            relation=candidate.relation,
            evidence=[evidence_by_id[item].excerpt for item in candidate.evidence_ids],
            extractor_confidence=candidate.extractor_confidence,
            reviewer_confidence=review.confidence,
            review_status="PENDING",
            decision_status="PENDING",
            review_lane=_review_lane(review, combined, fast_track=0.94),
        )

    @staticmethod
    def _reviewed_sources(
        request,
        extracted,
        review_by_id,
        *,
        node_id_by_candidate,
        source_hash: str,
    ) -> list[GraphDraftSource]:
        evidence_by_id = {item.evidence_id: item for item in extracted.sources}
        associations: set[tuple[str, str]] = set()
        for node in extracted.nodes:
            associations.update((node.candidate_id, item) for item in node.evidence_ids)
        for edge in extracted.edges:
            for evidence_id in edge.evidence_ids:
                if not any(item[1] == evidence_id for item in associations):
                    associations.add((edge.source_candidate_id, evidence_id))
        result: list[GraphDraftSource] = []
        for node_candidate_id, evidence_id in sorted(associations):
            item = evidence_by_id[evidence_id]
            review = review_by_id[evidence_id]
            combined = min(item.confidence, review.confidence)
            source_candidate_id = _stable_id(
                "src", source_hash, node_candidate_id, evidence_id, max_hash=44
            )
            result.append(
                GraphDraftSource(
                    candidate_id=source_candidate_id,
                    id=_stable_id("source", source_hash, node_candidate_id, evidence_id, max_hash=56),
                    node_id=node_id_by_candidate[node_candidate_id],
                    source_type=request.source.source_type,
                    title=request.source.title,
                    resource_id=request.source.resource_id,
                    file_id=request.source.file_id or request.source_file_id,
                    chapter=item.chapter,
                    locator=item.locator,
                    page_from=item.page_from,
                    page_to=item.page_to,
                    excerpt=item.excerpt,
                    rights_status=_rights_status(
                        request.source.copyright_status
                    ),
                    extractor_confidence=item.confidence,
                    reviewer_confidence=review.confidence,
                    review_status="PENDING",
                    decision_status="PENDING",
                    review_lane=_review_lane(review, combined, fast_track=0.95),
                )
            )
        return result

    @staticmethod
    def _validate_prompt_metadata(actual, expected, error_type) -> None:
        if actual != expected:
            raise error_type("The model response prompt version does not match.")

    @staticmethod
    def _validated_model_identity(provider, model, error_type) -> GraphModelIdentity:
        try:
            return GraphModelIdentity(provider=provider, model=model)
        except ValidationError as exc:
            raise error_type("The model response has invalid provider identity.") from exc


def _review_lane(review: GraphCandidateReview, combined: float, *, fast_track: float):
    if (
        not review.supported
        or review.recommended_action == "REJECT"
        or review.duplicate_of is not None
        or combined < 0.65
    ):
        return "REJECT_RECOMMENDED"
    if review.recommended_action == "ACCEPT" and combined >= fast_track:
        return "FAST_TRACK"
    return "STANDARD"


def _stable_id(prefix: str, *parts: str, max_hash: int) -> str:
    digest = hashlib.sha256(":".join(parts).encode("utf-8")).hexdigest()[:max_hash]
    return f"{prefix}-{digest}"


def _rights_status(copyright_status: str | None) -> str:
    if copyright_status in {"CONFIRMED", "AUTHORIZED", "PUBLIC_DOMAIN"}:
        return "ALLOWED"
    # Missing or explicitly unknown provenance must fail closed until a human
    # reviewer records a rights decision in the knowledge-service workflow.
    return "RESTRICTED"


def _unique_items(values) -> list[str]:
    result, seen = [], set()
    for value in values:
        normalized = value.strip()
        if normalized and normalized not in seen:
            seen.add(normalized)
            result.append(normalized)
    return result


def _bound_extractor_candidates(
    output: GraphExtractorProviderOutput, *, max_candidates: int
) -> GraphExtractorProviderOutput:
    """Select a disclosed batch without inventing fields or breaking dependencies.

    Input has already passed all field and cross-reference checks. Keep original
    model order, but reserve every selected node's ancestors and source evidence.
    Source cost accounts for the node/evidence associations restored for C.
    """
    node_by_id = {node.candidate_id: node for node in output.nodes}
    chosen_nodes: set[str] = set()
    evidence_ids: set[str] = set()
    source_links: set[tuple[str, str]] = set()
    for node in output.nodes:
        chain = []
        current = node
        while current.candidate_id not in chosen_nodes:
            chain.append(current)
            if current.parent_candidate_id is None:
                break
            current = node_by_id[current.parent_candidate_id]
        added_nodes = {item.candidate_id for item in chain}
        added_links = {
            (item.candidate_id, evidence_id)
            for item in chain for evidence_id in item.evidence_ids
        }
        added_evidence = {item[1] for item in added_links}
        if (
            len(chosen_nodes | added_nodes) > 32
            or len(evidence_ids | added_evidence) > 48
            or len(chosen_nodes | added_nodes) + len(source_links | added_links) > max_candidates
        ):
            continue
        chosen_nodes.update(added_nodes)
        source_links.update(added_links)
        evidence_ids.update(added_evidence)

    edges = []
    for edge in output.edges:
        if len(edges) >= 32:
            break
        if not {edge.source_candidate_id, edge.target_candidate_id} <= chosen_nodes:
            continue
        extra_sources = set(edge.evidence_ids) - evidence_ids
        if (
            len(evidence_ids | set(edge.evidence_ids)) > 48
            or len(chosen_nodes) + len(edges) + 1 + len(source_links) + len(extra_sources) > max_candidates
        ):
            continue
        edges.append(edge)
        source_links.update((edge.source_candidate_id, item) for item in extra_sources)
        evidence_ids.update(edge.evidence_ids)

    nodes = [node for node in output.nodes if node.candidate_id in chosen_nodes]
    sources = [item for item in output.sources if item.evidence_id in evidence_ids]
    warnings = list(output.warnings)
    if (len(nodes), len(edges), len(sources)) != (len(output.nodes), len(output.edges), len(output.sources)):
        notice = (
            f"模型候选超出单批容量，本批保留 {len(nodes)} 个节点、{len(edges)} 条关系、"
            f"{len(sources)} 条证据，并保留完整父链；本批未覆盖全部抽取内容，"
            "可将未覆盖章节单独上传补充。"
        )
        if len(warnings) < 50:
            warnings.append(notice)
        else:
            warnings[-1] = notice
    return GraphExtractorProviderOutput.model_validate({
        "nodes": nodes, "edges": edges, "sources": sources, "warnings": warnings,
    })


def _log_validation_failure(stage: str, attempt: int, request_id: str, error: Exception) -> None:
    # Never log model text, rejected values, exception messages or document data.
    current: BaseException | None = error
    visited: set[int] = set()
    issue_types: set[str] = set()
    while current is not None and id(current) not in visited:
        visited.add(id(current))
        if isinstance(current, ValidationError):
            issue_types.update(item["type"] for item in current.errors(include_input=False))
        current = current.__cause__
    logger.warning(
        "graph_generation_validation_failed stage=%s attempt=%s request_id=%s error_type=%s issues=%s reasons=%s",
        stage, attempt, request_id, type(error).__name__, ",".join(sorted(issue_types)) or "invalid_output",
        ','.join(_validation_reason_codes(error)),
    )


_VALIDATION_REASON_CODES = {
    'Reviewer decisions must be unique.': 'REVIEW_DUPLICATE_ID',
    'Reviewer candidate kind does not match.': 'REVIEW_ID_KIND_MISMATCH',
    'duplicate_of must reference the same candidate kind.': 'REVIEW_DUPLICATE_KIND_MISMATCH',
    'Reviewer decisions must cover every candidate and source.': 'REVIEW_INCOMPLETE_COVERAGE',
    'duplicate_of must reference this extraction batch.': 'REVIEW_DUPLICATE_OUTSIDE_BATCH',
    'Unsupported candidates must be rejected.': 'REVIEW_UNSUPPORTED_ACTION',
    'A candidate cannot duplicate itself.': 'REVIEW_SELF_DUPLICATE',
    'A duplicate candidate cannot be accepted.': 'REVIEW_DUPLICATE_ACCEPTED',
    'reviewer structured content is missing': 'REVIEW_CONTENT_MISSING',
    'Evidence line range is outside the document.': 'EVIDENCE_LINES_OUTSIDE_DOCUMENT',
    'Evidence excerpt is not present in the document.': 'EVIDENCE_EXCERPT_NOT_FOUND',
    'Evidence excerpt has no unique source location.': 'EVIDENCE_LOCATION_AMBIGUOUS',
    'Evidence line range contains no source text.': 'EVIDENCE_LINES_EMPTY',
}


def _validation_reason_codes(error: Exception) -> tuple[str, ...]:
    """Only fixed reason codes leave this boundary; never model/document values."""
    codes: set[str] = set()
    current: BaseException | None = error
    visited: set[int] = set()
    while current is not None and id(current) not in visited:
        visited.add(id(current))
        known = _VALIDATION_REASON_CODES.get(str(current))
        if known:
            codes.add(known)
        if isinstance(current, ValidationError):
            for issue in current.errors(include_input=False):
                cause = (issue.get('ctx') or {}).get('error')
                known = _VALIDATION_REASON_CODES.get(str(cause)) if cause is not None else None
                if known:
                    codes.add(known)
                else:
                    codes.add({'missing': 'SCHEMA_MISSING_FIELD', 'extra_forbidden': 'SCHEMA_EXTRA_FIELD',
                               'literal_error': 'SCHEMA_ENUM_INVALID'}.get(issue['type'], 'SCHEMA_FIELD_INVALID'))
        current = current.__cause__
    return tuple(sorted(codes)) or ('VALIDATION_UNCLASSIFIED',)


def _reviewer_retry_feedback(error: Exception | None) -> str | None:
    if error is None:
        return None
    return ('服务端上一轮校验原因码：' + ','.join(_validation_reason_codes(error))
            + '。重新逐项对照review_targets，覆盖每个ID且kind一致；'
              'duplicate_of只能引用同kind的其他ID，证据支持某节点不等于与节点重复。'
              'supported=false必须REJECT；duplicate_of非空不能ACCEPT。仅返回完整修正JSON。')


def _resolve_evidence(
    item,
    document_lines: tuple[str, ...],
    *,
    allow_line_fallback: bool,
) -> tuple[str, int, int, bool]:
    """Return exact source text and a verified locator, repairing model drift."""
    if item.line_to > len(document_lines):
        raise ValueError("Evidence line range is outside the document.")

    selected = "\n".join(document_lines[item.line_from - 1 : item.line_to])
    if item.excerpt in selected:
        return item.excerpt, item.line_from, item.line_to, False

    document = "\n".join(document_lines)
    matches: list[int] = []
    start = 0
    while True:
        position = document.find(item.excerpt, start)
        if position < 0:
            break
        matches.append(position)
        start = position + 1
    if len(matches) > 1 or not matches:
        if allow_line_fallback:
            if not selected.strip():
                raise ValueError("Evidence line range contains no source text.")
            # On the final bounded attempt, retain only literal document text;
            # the independent reviewer and human approval still assess support.
            return selected[:500], item.line_from, item.line_to, True
        if len(matches) > 1:
            raise ValueError("Evidence excerpt has no unique source location.")
        raise ValueError("Evidence excerpt is not present in the document.")

    position = matches[0]
    end = position + len(item.excerpt)
    line_starts = [0]
    for index, character in enumerate(document):
        if character == "\n":
            line_starts.append(index + 1)

    def line_for(offset: int) -> int:
        return bisect_right(line_starts, offset)

    return item.excerpt, line_for(position), line_for(max(position, end - 1)), True


def _evidence_warnings(warnings: list[str], repaired_count: int) -> list[str]:
    result = list(warnings)
    if not repaired_count:
        return result
    notice = (
        "服务端已从文档原文恢复或修正证据；相关候选仍为待审核状态，请重点核对。"
    )
    if len(result) < 50:
        result.append(notice)
    else:
        result[-1] = (result[-1][: 1000 - len(notice) - 1] + "；" + notice)[:1000]
    return result


def _retry_feedback(error: Exception | None) -> str | None:
    if error is None:
        return None
    message = str(error)
    allowed = {
        "Evidence line range is outside the document.",
        "Evidence excerpt is not present in its line range.",
        "Evidence excerpt is not present in the document.",
        "Evidence excerpt has no unique source location.",
        "Evidence line range contains no source text.",
    }
    if message in allowed:
        return (
            "上一次输出的证据校验失败："
            + message
            + " 请重新逐字复制 DOCUMENT_LINES 中的 excerpt，并重新核对 1-based line_from/line_to；"
            "不能改写、概括或补标点。"
        )
    if _has_validation_error(error):
        return (
            "上一次结构化输出未通过字段约束。请严格限制候选数量（最多 32 个节点、32 条关系、48 条来源），"
            "保留一个 level=0 的 course 根节点；每个 level=1/2/3 节点必须引用本批次中"
            "存在且恰好高一级的 parent_candidate_id；level 与 node_type 必须严格一一对应："
            "0/course、1/module、2/chapter、3/section 或 knowledge_point。"
            "无法形成完整父链的节点及其关系请直接省略，不要补造父节点。"
        )
    return "上一次结构化输出未通过服务端校验，请严格按系统约束重新生成完整 JSON。"


def _has_validation_error(error: Exception | None) -> bool:
    current: BaseException | None = error
    visited: set[int] = set()
    while current is not None and id(current) not in visited:
        if isinstance(current, ValidationError):
            return True
        visited.add(id(current))
        current = current.__cause__
    return False


def create_graph_ingestion_agent() -> GraphIngestionAgent:
    source_settings = AssessmentSourceSettings.from_env()
    ingestion_settings = GraphIngestionSettings.from_env()
    return GraphIngestionAgent(
        AssessmentSourceClient(source_settings),
        AssessmentDocumentExtractor(
            max_text_chars=source_settings.max_text_chars,
            max_pdf_pages=source_settings.max_pdf_pages,
            max_uncompressed_docx_bytes=source_settings.max_file_bytes,
        ),
        total_timeout_seconds=ingestion_settings.total_timeout_seconds,
        max_candidates=ingestion_settings.max_candidates,
    )
