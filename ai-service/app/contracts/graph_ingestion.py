from __future__ import annotations

import re
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator, model_validator


CandidateAction = Literal["ACCEPT", "HUMAN_REVIEW", "REJECT"]
CandidateKind = Literal["NODE", "EDGE", "SOURCE"]
_SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$")
_SHA256_PATTERN = r"^[a-f0-9]{64}$"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


def _required(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise ValueError("The value must not be blank.")
    return normalized


def _identifier(value: str) -> str:
    normalized = _required(value)
    if not _SAFE_ID.fullmatch(normalized):
        raise ValueError("Identifier contains unsupported characters.")
    return normalized


def _unique_text(values: list[str], *, max_chars: int) -> list[str]:
    normalized = [value.strip() for value in values]
    if any(not value or len(value) > max_chars for value in normalized):
        raise ValueError("List values must be bounded non-blank text.")
    if len(normalized) != len(set(normalized)):
        raise ValueError("List values must be unique.")
    return normalized


class GraphExtractSource(StrictModel):
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    title: str = Field(min_length=1, max_length=500)
    resource_id: int | None = Field(default=None, ge=1)
    file_id: int | None = Field(default=None, ge=1)
    copyright_status: (
        Literal["CONFIRMED", "AUTHORIZED", "PUBLIC_DOMAIN", "UNKNOWN"] | None
    ) = None


class GraphExtractReviewRequest(StrictModel):
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    course_name: str = Field(min_length=1, max_length=100)
    request_id: str = Field(min_length=16, max_length=64)
    job_id: int = Field(ge=1)
    source_file_id: int = Field(ge=1)
    filename: str = Field(min_length=1, max_length=255)
    download_url: HttpUrl
    source: GraphExtractSource
    preferred_language: str = Field(default="zh-CN", min_length=2, max_length=20)

    @model_validator(mode="after")
    def validate_file_identity(self) -> "GraphExtractReviewRequest":
        if self.source.file_id is not None and self.source.file_id != self.source_file_id:
            raise ValueError("source.file_id must match source_file_id when supplied.")
        # source_file_id is the authoritative uploaded-file identity even when
        # the optional nested source.file_id/resource_id descriptors are absent.
        return self


# Provider-only extraction structures. They never cross the service contract.
class GraphProviderEvidence(StrictModel):
    evidence_id: str = Field(min_length=1, max_length=64)
    excerpt: str = Field(min_length=1, max_length=500)
    line_from: int = Field(ge=1)
    line_to: int = Field(ge=1)
    chapter: str | None = Field(default=None, max_length=500)
    confidence: float = Field(ge=0, le=1)

    @field_validator("evidence_id")
    @classmethod
    def validate_id(cls, value: str) -> str:
        return _identifier(value)

    @model_validator(mode="after")
    def validate_line_range(self) -> "GraphProviderEvidence":
        if self.line_to < self.line_from:
            raise ValueError("line_to must be greater than or equal to line_from.")
        return self


class ExtractedGraphNodeCandidate(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    level: int = Field(ge=0, le=3)
    node_type: Literal["course", "module", "chapter", "section", "knowledge_point"]
    parent_candidate_id: str | None = Field(default=None, max_length=64)
    description: str | None = Field(default=None, max_length=5000)
    aliases: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=20)
    keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=30)
    difficulty: Literal["BEGINNER", "BASIC", "INTERMEDIATE", "ADVANCED"] | None = None
    importance: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=1, le=10000)
    learning_objectives: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=20)
    common_misconceptions: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=20)
    example_code: list[Annotated[str, Field(min_length=1, max_length=10000)]] = Field(default_factory=list, max_length=10)
    evidence_ids: list[str] = Field(min_length=1, max_length=10)
    extractor_confidence: float = Field(ge=0, le=1)

    @field_validator("candidate_id")
    @classmethod
    def validate_candidate_id(cls, value: str) -> str:
        return _identifier(value)

    @field_validator("parent_candidate_id")
    @classmethod
    def validate_parent_id(cls, value: str | None) -> str | None:
        return _identifier(value) if value is not None else None

    @field_validator("aliases", "keywords")
    @classmethod
    def validate_terms(cls, values: list[str]) -> list[str]:
        return _unique_text(values, max_chars=100)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, values: list[str]) -> list[str]:
        return [_identifier(value) for value in _unique_text(values, max_chars=64)]

    @model_validator(mode="after")
    def validate_level_and_type(self) -> "ExtractedGraphNodeCandidate":
        expected = {0: "course", 1: "module", 2: "chapter"}
        if self.level in expected and self.node_type != expected[self.level]:
            raise ValueError("level and node_type do not match.")
        if self.level == 3 and self.node_type not in {"section", "knowledge_point"}:
            raise ValueError("level 3 only permits section or knowledge_point.")
        if self.level == 0 and self.parent_candidate_id is not None:
            raise ValueError("A course root cannot have a parent.")
        if self.level > 0 and not self.parent_candidate_id:
            raise ValueError("A non-root node requires parent_candidate_id.")
        return self


class ExtractedGraphEdgeCandidate(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64)
    source_candidate_id: str = Field(min_length=1, max_length=64)
    target_candidate_id: str = Field(min_length=1, max_length=64)
    relation: Literal["REQUIRES", "RELATED", "SUPPORTED_BY"]
    evidence_ids: list[str] = Field(min_length=1, max_length=10)
    extractor_confidence: float = Field(ge=0, le=1)

    @field_validator("candidate_id", "source_candidate_id", "target_candidate_id")
    @classmethod
    def validate_ids(cls, value: str) -> str:
        return _identifier(value)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, values: list[str]) -> list[str]:
        return [_identifier(value) for value in _unique_text(values, max_chars=64)]

    @model_validator(mode="after")
    def reject_self_edge(self) -> "ExtractedGraphEdgeCandidate":
        if self.source_candidate_id == self.target_candidate_id:
            raise ValueError("Graph candidates cannot contain self-relations.")
        return self


class GraphExtractorProviderOutput(StrictModel):
    # These are hard transport limits, matching the frozen service contract.
    # The prompt's smaller batch targets are enforced by the agent after full
    # field/reference validation, so a model's 33rd node need not lose the job.
    nodes: list[ExtractedGraphNodeCandidate] = Field(min_length=1, max_length=200)
    edges: list[ExtractedGraphEdgeCandidate] = Field(default_factory=list, max_length=500)
    sources: list[GraphProviderEvidence] = Field(min_length=1, max_length=500)
    warnings: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(
        default_factory=list, max_length=50
    )

    @model_validator(mode="after")
    def validate_references(self) -> "GraphExtractorProviderOutput":
        _validate_candidate_references(self)
        return self


class ValidatedGraphEvidence(GraphProviderEvidence):
    page_from: int | None = Field(default=None, ge=1)
    page_to: int | None = Field(default=None, ge=1)
    source_file_id: int = Field(ge=1)
    locator: str = Field(min_length=1, max_length=1000)
    source_hash: str = Field(pattern=_SHA256_PATTERN)


class GraphExtractorOutput(StrictModel):
    nodes: list[ExtractedGraphNodeCandidate] = Field(min_length=1, max_length=200)
    edges: list[ExtractedGraphEdgeCandidate] = Field(default_factory=list, max_length=500)
    sources: list[ValidatedGraphEvidence] = Field(min_length=1, max_length=500)
    warnings: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(
        default_factory=list, max_length=50
    )

    @model_validator(mode="after")
    def validate_references(self) -> "GraphExtractorOutput":
        _validate_candidate_references(self)
        return self


def _validate_candidate_references(output) -> None:
    node_ids = [item.candidate_id for item in output.nodes]
    edge_ids = [item.candidate_id for item in output.edges]
    evidence_ids = [item.evidence_id for item in output.sources]
    identities = [*node_ids, *edge_ids, *evidence_ids]
    if len(identities) != len(set(identities)):
        raise ValueError("All candidate identities must be globally unique.")
    node_set, evidence_set = set(node_ids), set(evidence_ids)
    node_by_id = {item.candidate_id: item for item in output.nodes}
    referenced: set[str] = set()
    for node in output.nodes:
        if node.parent_candidate_id is not None:
            if node.parent_candidate_id not in node_set or node.parent_candidate_id == node.candidate_id:
                raise ValueError("parent_candidate_id is invalid.")
            if node_by_id[node.parent_candidate_id].level != node.level - 1:
                raise ValueError("A parent candidate must be exactly one level higher.")
        if not set(node.evidence_ids) <= evidence_set:
            raise ValueError("A node references unknown evidence.")
        referenced.update(node.evidence_ids)
    for edge in output.edges:
        if edge.source_candidate_id not in node_set or edge.target_candidate_id not in node_set:
            raise ValueError("An edge endpoint references an unknown node.")
        if not set(edge.evidence_ids) <= evidence_set:
            raise ValueError("An edge references unknown evidence.")
        referenced.update(edge.evidence_ids)
    if referenced != evidence_set:
        raise ValueError("Every evidence candidate must support a node or edge.")


class GraphCandidateReview(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64)
    candidate_kind: CandidateKind
    supported: bool
    confidence: float = Field(ge=0, le=1)
    duplicate_of: str | None = Field(default=None, max_length=64)
    issues: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=50)
    recommended_action: CandidateAction

    @field_validator("candidate_id")
    @classmethod
    def validate_candidate_id(cls, value: str) -> str:
        return _identifier(value)

    @model_validator(mode="after")
    def validate_decision(self) -> "GraphCandidateReview":
        if not self.supported and self.recommended_action != "REJECT":
            raise ValueError("Unsupported candidates must be rejected.")
        if self.duplicate_of == self.candidate_id:
            raise ValueError("A candidate cannot duplicate itself.")
        if self.duplicate_of is not None and self.recommended_action == "ACCEPT":
            raise ValueError("A duplicate candidate cannot be accepted.")
        return self


class GraphReviewerOutput(StrictModel):
    reviews: list[GraphCandidateReview] = Field(min_length=1, max_length=1200)
    warnings: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(
        default_factory=list, max_length=50
    )


# Exact knowledge-service ingestion candidate DTOs.
DecisionStatus = Literal["PENDING", "APPROVED", "REJECTED"]
ReviewLane = Literal["FAST_TRACK", "STANDARD", "REJECT_RECOMMENDED"]


class GraphDraftNode(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$")
    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    level: int = Field(ge=0, le=3)
    node_type: Literal["course", "module", "chapter", "section", "knowledge_point"]
    parent_id: str | None = Field(default=None, max_length=64)
    description: str | None = Field(default=None, max_length=5000)
    aliases: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=20)
    keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=30)
    difficulty: Literal["BEGINNER", "BASIC", "INTERMEDIATE", "ADVANCED"] | None = None
    importance: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=1, le=10000)
    learning_objectives: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=20)
    common_misconceptions: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=20)
    example_code: list[Annotated[str, Field(min_length=1, max_length=10000)]] = Field(default_factory=list, max_length=10)
    rights_status: Literal["PENDING", "ALLOWED", "RESTRICTED"] = "RESTRICTED"
    evidence: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(min_length=1, max_length=10)
    extractor_confidence: float = Field(ge=0, le=1)
    reviewer_confidence: float = Field(ge=0, le=1)
    review_status: Literal["PENDING"] = "PENDING"
    decision_status: DecisionStatus = "PENDING"
    review_lane: ReviewLane = "STANDARD"
    decision_comment: str | None = Field(default=None, max_length=2000)
    decided_by: str | None = Field(default=None, max_length=128)
    decided_at: str | None = Field(default=None, max_length=40)

    @model_validator(mode="after")
    def validate_level(self) -> "GraphDraftNode":
        expected = {0: "course", 1: "module", 2: "chapter"}
        if self.level in expected and self.node_type != expected[self.level]:
            raise ValueError("level and node_type do not match.")
        if self.level == 3 and self.node_type not in {"section", "knowledge_point"}:
            raise ValueError("level 3 only permits section or knowledge_point.")
        if self.level == 0 and self.parent_id is not None:
            raise ValueError("A root course cannot have a parent.")
        if self.level > 0 and not self.parent_id:
            raise ValueError("A non-root node requires parent_id.")
        if len(set(self.aliases)) != len(self.aliases):
            raise ValueError("aliases must be unique.")
        if len(set(self.keywords)) != len(self.keywords):
            raise ValueError("keywords must be unique.")
        return self


class GraphDraftEdge(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$")
    source_id: str = Field(min_length=1, max_length=64)
    target_id: str = Field(min_length=1, max_length=64)
    relation: Literal["REQUIRES", "RELATED", "SUPPORTED_BY"]
    evidence: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(min_length=1, max_length=10)
    extractor_confidence: float = Field(ge=0, le=1)
    reviewer_confidence: float = Field(ge=0, le=1)
    review_status: Literal["PENDING"] = "PENDING"
    decision_status: DecisionStatus = "PENDING"
    review_lane: ReviewLane = "STANDARD"
    decision_comment: str | None = Field(default=None, max_length=2000)
    decided_by: str | None = Field(default=None, max_length=128)
    decided_at: str | None = Field(default=None, max_length=40)


class GraphDraftSource(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$")
    id: str = Field(min_length=1, max_length=128)
    node_id: str = Field(min_length=1, max_length=64)
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    title: str = Field(min_length=1, max_length=500)
    resource_id: int | None = Field(default=None, ge=1)
    file_id: int | None = Field(default=None, ge=1)
    chapter: str | None = Field(default=None, max_length=500)
    page_from: int | None = Field(default=None, ge=1)
    page_to: int | None = Field(default=None, ge=1)
    locator: str = Field(min_length=1, max_length=1000)
    excerpt: str | None = Field(default=None, max_length=500)
    rights_status: Literal["ALLOWED", "RESTRICTED"]
    extractor_confidence: float = Field(ge=0, le=1)
    reviewer_confidence: float = Field(ge=0, le=1)
    review_status: Literal["PENDING"] = "PENDING"
    decision_status: DecisionStatus = "PENDING"
    review_lane: ReviewLane = "STANDARD"
    decision_comment: str | None = Field(default=None, max_length=2000)
    decided_by: str | None = Field(default=None, max_length=128)
    decided_at: str | None = Field(default=None, max_length=40)

    @model_validator(mode="after")
    def validate_source(self) -> "GraphDraftSource":
        if self.page_from is not None and self.page_to is not None and self.page_to < self.page_from:
            raise ValueError("page_to must be greater than or equal to page_from.")
        if (
            self.source_type == "TEACHING_RESOURCE"
            and self.resource_id is None
            and self.file_id is None
        ):
            raise ValueError("TEACHING_RESOURCE requires resource_id or file_id.")
        return self


class GraphModelIdentity(StrictModel):
    provider: str = Field(min_length=1, max_length=40)
    model: str = Field(min_length=1, max_length=100)


class GraphIngestionModels(StrictModel):
    extractor: GraphModelIdentity
    reviewer: GraphModelIdentity


class GraphPromptIdentity(StrictModel):
    version: str = Field(min_length=1, max_length=100)
    sha256: str = Field(pattern=_SHA256_PATTERN)


class GraphIngestionPrompts(StrictModel):
    extractor: GraphPromptIdentity
    reviewer: GraphPromptIdentity


class GraphExtractReviewResponse(StrictModel):
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    request_id: str = Field(min_length=16, max_length=64)
    job_id: int = Field(ge=1)
    nodes: list[GraphDraftNode] = Field(min_length=1, max_length=200)
    edges: list[GraphDraftEdge] = Field(max_length=500)
    sources: list[GraphDraftSource] = Field(min_length=1, max_length=500)
    warnings: list[Annotated[str, Field(max_length=1000)]] = Field(
        max_length=100
    )
    models: GraphIngestionModels
    prompts: GraphIngestionPrompts

    @model_validator(mode="after")
    def validate_pending_batch(self) -> "GraphExtractReviewResponse":
        candidates = [*self.nodes, *self.edges, *self.sources]
        if any(item.decision_status != "PENDING" for item in candidates):
            raise ValueError("Extracted candidates must remain PENDING.")
        candidate_ids = [item.candidate_id for item in candidates]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("Candidate identities must be globally unique.")
        return self


GraphNodeCandidate = GraphDraftNode
GraphEdgeCandidate = GraphDraftEdge
