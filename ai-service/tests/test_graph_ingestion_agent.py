import unittest
from pydantic import ValidationError

from app.agents.graph_ingestion import (
    GraphExtractorOutputError,
    GraphIngestionAgent,
    GraphReviewerOutputError,
    _bound_extractor_candidates,
    _retry_feedback,
)
from app.contracts.graph_ingestion import (
    GraphExtractReviewRequest,
    GraphExtractorProviderOutput,
)
from app.prompts.graph_ingestion import (
    GRAPH_EXTRACTOR_PROMPT_SHA256,
    GRAPH_EXTRACTOR_PROMPT_VERSION,
    GRAPH_REVIEWER_PROMPT_SHA256,
    GRAPH_REVIEWER_PROMPT_VERSION,
)
from app.providers.base import (
    ModelCallMetadata,
    ModelRequest,
    ModelResponse,
    TokenUsage,
    StructuredOutputError,
)
from app.services.document_extraction import AssessmentDocumentExtractor


class FakeSourceClient:
    def __init__(self, content: bytes) -> None:
        self.content = content
        self.calls = []
        self.closed = False

    async def download(self, **kwargs):
        self.calls.append(kwargs)
        return type("Downloaded", (), {"content": self.content})()

    async def aclose(self) -> None:
        self.closed = True


def model_response(content, *, prompt_version: str) -> ModelResponse:
    return ModelResponse(
        text="structured-json",
        structured_content=content,
        metadata=ModelCallMetadata(
            provider="mock",
            model="mock-graph-model",
            token_usage=TokenUsage(10, 10, 20),
            finish_reason="stop",
            latency_ms=1,
            provider_request_id="mock-request",
            prompt_version=prompt_version,
            attempts=1,
        ),
    )


class FakeProvider:
    provider_name = "mock"
    model_name = "mock-graph-model"

    def __init__(self, response: ModelResponse) -> None:
        self.response = response
        self.requests: list[ModelRequest] = []
        self.output_models = []
        self.closed = False

    async def generate(self, request, *, output_model=None):
        self.requests.append(request)
        self.output_models.append(output_model)
        return self.response

    async def aclose(self):
        self.closed = True

    async def check_health(self):
        raise AssertionError


class SequenceProvider(FakeProvider):
    def __init__(self, responses: list[ModelResponse]) -> None:
        if not responses:
            raise ValueError("responses must not be empty")
        super().__init__(responses[0])
        self.responses = list(responses)

    async def generate(self, request, *, output_model=None):
        self.requests.append(request)
        self.output_models.append(output_model)
        return self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]


def request() -> GraphExtractReviewRequest:
    return GraphExtractReviewRequest(
        course_id=1,
        course_name="Python程序设计",
        request_id="graph-request-0001",
        job_id=9001,
        source_file_id=42,
        filename="chapter.txt",
        download_url="http://localhost:8080/api/files/42/content",
        source={
            "source_type": "TEXTBOOK",
            "title": "Python 程序设计",
            "file_id": 42,
            "copyright_status": "AUTHORIZED",
        },
    )


def extractor_output(*, excerpt: str = "函数使用 def 定义。") -> dict:
    return {
        "nodes": [
            {
                "candidate_id": "node-course",
                "name": "Python 程序设计",
                "level": 0,
                "node_type": "course",
                "aliases": [],
                "keywords": ["Python"],
                "learning_objectives": [],
                "common_misconceptions": [],
                "example_code": [],
                "evidence_ids": ["evidence-1"],
                "extractor_confidence": 0.96,
            },
            {
                "candidate_id": "node-module",
                "name": "Python 基础",
                "level": 1,
                "node_type": "module",
                "parent_candidate_id": "node-course",
                "aliases": [],
                "keywords": ["Python"],
                "learning_objectives": [],
                "common_misconceptions": [],
                "example_code": [],
                "evidence_ids": ["evidence-1"],
                "extractor_confidence": 0.96,
            },
            {
                "candidate_id": "node-chapter",
                "name": "函数",
                "level": 2,
                "node_type": "chapter",
                "parent_candidate_id": "node-module",
                "aliases": [],
                "keywords": ["函数"],
                "learning_objectives": [],
                "common_misconceptions": [],
                "example_code": [],
                "evidence_ids": ["evidence-1"],
                "extractor_confidence": 0.96,
            },
            {
                "candidate_id": "node-function",
                "name": "函数定义",
                "level": 3,
                "node_type": "knowledge_point",
                "parent_candidate_id": "node-chapter",
                "description": "使用 def 定义函数。",
                "aliases": [],
                "keywords": ["def"],
                "learning_objectives": ["能够定义函数"],
                "common_misconceptions": [],
                "example_code": ["def f():\n    pass"],
                "evidence_ids": ["evidence-1"],
                "extractor_confidence": 0.96,
            }
        ],
        "edges": [
            {
                "candidate_id": "edge-related",
                "source_candidate_id": "node-chapter",
                "target_candidate_id": "node-function",
                "relation": "RELATED",
                "evidence_ids": ["evidence-1"],
                "extractor_confidence": 0.92,
            }
        ],
        "sources": [
            {
                "evidence_id": "evidence-1",
                "excerpt": excerpt,
                "line_from": 1,
                "line_to": 1,
                "chapter": "函数",
                "confidence": 0.99,
            }
        ],
        "warnings": [],
    }


def reviewer_output() -> dict:
    return {
        "reviews": [
            {
                "candidate_id": "node-course",
                "candidate_kind": "NODE",
                "supported": True,
                "confidence": 0.97,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
            {
                "candidate_id": "node-module",
                "candidate_kind": "NODE",
                "supported": True,
                "confidence": 0.97,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
            {
                "candidate_id": "node-chapter",
                "candidate_kind": "NODE",
                "supported": True,
                "confidence": 0.97,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
            {
                "candidate_id": "node-function",
                "candidate_kind": "NODE",
                "supported": True,
                "confidence": 0.97,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
            {
                "candidate_id": "edge-related",
                "candidate_kind": "EDGE",
                "supported": True,
                "confidence": 0.95,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
            {
                "candidate_id": "evidence-1",
                "candidate_kind": "SOURCE",
                "supported": True,
                "confidence": 0.99,
                "duplicate_of": None,
                "issues": [],
                "recommended_action": "ACCEPT",
            },
        ],
        "warnings": [],
    }


class GraphIngestionAgentTest(unittest.IsolatedAsyncioTestCase):
    def make_agent(self, extractor_content, reviewer_content):
        source = FakeSourceClient(
            "函数使用 def 定义。\n调用函数时使用函数名。".encode()
        )
        extractor = FakeProvider(
            model_response(
                extractor_content,
                prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION,
            )
        )
        reviewer = FakeProvider(
            model_response(
                reviewer_content,
                prompt_version=GRAPH_REVIEWER_PROMPT_VERSION,
            )
        )
        agent = GraphIngestionAgent(
            source,
            AssessmentDocumentExtractor(
                max_text_chars=60_000,
                max_pdf_pages=200,
                max_uncompressed_docx_bytes=50 * 1024 * 1024,
            ),
            extractor_provider_factory=lambda: extractor,
            reviewer_provider_factory=lambda: reviewer,
        )
        return agent, source, extractor, reviewer

    async def test_transient_extractor_reviewer_returns_traceable_drafts(self):
        agent, source, extractor, reviewer = self.make_agent(
            extractor_output(), reviewer_output()
        )
        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )
        await agent.aclose()

        self.assertEqual(response.sources[0].file_id, 42)
        self.assertEqual(response.sources[0].locator, "lines 1")
        self.assertEqual(response.nodes[0].review_status, "PENDING")
        self.assertEqual(response.nodes[0].review_lane, "FAST_TRACK")
        self.assertEqual(response.edges[0].relation, "RELATED")
        self.assertEqual(response.edges[0].reviewer_confidence, 0.95)
        self.assertEqual(response.edges[0].decision_status, "PENDING")
        self.assertEqual(response.sources[0].reviewer_confidence, 0.99)
        self.assertEqual(response.sources[0].rights_status, "ALLOWED")
        self.assertEqual(response.models.extractor.provider, "mock")
        self.assertEqual(extractor.requests[0].reasoning_mode, "disabled")
        self.assertEqual(reviewer.requests[0].reasoning_mode, "disabled")
        self.assertEqual(
            response.prompts.extractor.sha256,
            GRAPH_EXTRACTOR_PROMPT_SHA256,
        )
        self.assertEqual(
            response.prompts.reviewer.sha256,
            GRAPH_REVIEWER_PROMPT_SHA256,
        )
        self.assertTrue(source.closed)
        self.assertTrue(extractor.closed)
        self.assertTrue(reviewer.closed)

        provider_payload = "\n".join(
            item.content for item in extractor.requests[0].messages
        )
        self.assertNotIn("graph-request-0001", provider_payload)
        self.assertNotIn("9001", provider_payload)
        self.assertNotIn("/api/files/42/content", provider_payload)
        self.assertNotIn('"source_file_id":42', provider_payload)
        self.assertIn("DOCUMENT_LINES", provider_payload)
        self.assertIn("恰好高一级", provider_payload)
        self.assertIn("完整父链", provider_payload)
        self.assertIn("必须包含至少一个", provider_payload)
        self.assertIn("禁止只把", provider_payload)

        serialized = response.model_dump_json()
        self.assertNotIn("调用函数时使用函数名", serialized)
        self.assertNotIn("source_hash", serialized)

    async def test_extractor_recovers_exact_source_text_after_bounded_retry(self):
        agent, _, _, _ = self.make_agent(
            extractor_output(excerpt="原文中不存在的证据"), reviewer_output()
        )
        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )

        self.assertEqual(response.sources[0].excerpt, "函数使用 def 定义。")
        self.assertEqual(response.nodes[0].review_status, "PENDING")
        self.assertTrue(any("从文档原文恢复" in item for item in response.warnings))

    async def test_extractor_rejects_locator_outside_document_after_retry(self):
        invalid = extractor_output()
        invalid["sources"][0]["line_from"] = 3
        invalid["sources"][0]["line_to"] = 3
        agent, _, _, _ = self.make_agent(invalid, reviewer_output())

        with self.assertRaises(GraphExtractorOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_unique_verbatim_excerpt_with_wrong_line_is_relocated(self):
        output = extractor_output()
        output["sources"][0]["line_from"] = 2
        output["sources"][0]["line_to"] = 2
        agent, _, extractor, _ = self.make_agent(output, reviewer_output())

        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )

        self.assertEqual(response.sources[0].locator, "lines 1")
        self.assertEqual(len(extractor.requests), 1)

    async def test_ambiguous_excerpt_feedback_is_sent_on_bounded_retry(self):
        source = FakeSourceClient(
            "函数使用 def 定义。\n其他内容。\n函数使用 def 定义。".encode()
        )
        invalid = extractor_output()
        invalid["sources"][0]["line_from"] = 2
        invalid["sources"][0]["line_to"] = 2
        corrected = extractor_output()
        corrected["sources"][0]["line_from"] = 1
        corrected["sources"][0]["line_to"] = 1
        extractor = SequenceProvider(
            [
                model_response(invalid, prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION),
                model_response(corrected, prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION),
            ]
        )
        reviewer = FakeProvider(
            model_response(reviewer_output(), prompt_version=GRAPH_REVIEWER_PROMPT_VERSION)
        )
        agent = GraphIngestionAgent(
            source,
            AssessmentDocumentExtractor(
                max_text_chars=60_000,
                max_pdf_pages=200,
                max_uncompressed_docx_bytes=50 * 1024 * 1024,
            ),
            extractor_provider_factory=lambda: extractor,
            reviewer_provider_factory=lambda: reviewer,
        )

        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )

        self.assertEqual(response.sources[0].locator, "lines 1")
        self.assertEqual(len(extractor.requests), 2)
        retry_payload = extractor.requests[1].messages[-1].content
        self.assertIn("previous_validation_feedback", retry_payload)
        self.assertIn("Evidence excerpt has no unique source location.", retry_payload)

    async def test_incomplete_node_hierarchy_is_corrected_on_bounded_retry(self):
        source = FakeSourceClient("函数使用 def 定义。".encode())
        invalid = extractor_output()
        invalid["nodes"][1]["parent_candidate_id"] = "missing-parent"
        extractor = SequenceProvider(
            [
                model_response(invalid, prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION),
                model_response(extractor_output(), prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION),
            ]
        )
        reviewer = FakeProvider(
            model_response(reviewer_output(), prompt_version=GRAPH_REVIEWER_PROMPT_VERSION)
        )
        agent = GraphIngestionAgent(
            source,
            AssessmentDocumentExtractor(
                max_text_chars=60_000,
                max_pdf_pages=200,
                max_uncompressed_docx_bytes=50 * 1024 * 1024,
            ),
            extractor_provider_factory=lambda: extractor,
            reviewer_provider_factory=lambda: reviewer,
        )

        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )

        self.assertTrue(response.nodes)
        self.assertEqual(len(extractor.requests), 2)
        retry_payload = extractor.requests[1].messages[-1].content
        self.assertIn("恰好高一级的 parent_candidate_id", retry_payload)
        self.assertIn("最多 32 个节点", retry_payload)

    def test_extractor_candidate_limits_bound_large_model_outputs(self):
        oversized = extractor_output()
        oversized["nodes"] = [oversized["nodes"][0]] * 201

        with self.assertRaises(ValidationError) as raised:
            GraphExtractorProviderOutput.model_validate(oversized)

        self.assertTrue(
            any(
                item["loc"] == ("nodes",) and item["type"] == "too_long"
                for item in raised.exception.errors(include_input=False)
            )
        )

    def test_model_overshoot_is_bounded_with_parent_and_evidence_integrity(self):
        oversized = extractor_output()
        template = oversized["nodes"][-1]
        for index in range(2, 61):
            node = {**template, "candidate_id": f"node-extra-{index}",
                    "name": f"函数知识点 {index}",
                    "evidence_ids": [f"evidence-{index}"]}
            oversized["nodes"].append(node)
            oversized["sources"].append({
                **oversized["sources"][0], "evidence_id": f"evidence-{index}"
            })
            oversized["edges"].append({
                **oversized["edges"][0], "candidate_id": f"edge-extra-{index}",
                "target_candidate_id": node["candidate_id"],
                "evidence_ids": node["evidence_ids"],
            })
        raw = GraphExtractorProviderOutput.model_validate(oversized)
        bounded = _bound_extractor_candidates(raw, max_candidates=300)
        self.assertEqual(len(bounded.nodes), 32)
        self.assertLessEqual(len(bounded.edges), 32)
        self.assertLessEqual(len(bounded.sources), 48)
        self.assertTrue(any("未覆盖全部" in item for item in bounded.warnings))
        node_by_id = {item.candidate_id: item for item in bounded.nodes}
        evidence_ids = {item.evidence_id for item in bounded.sources}
        for node in bounded.nodes:
            if node.parent_candidate_id:
                self.assertIn(node.parent_candidate_id, node_by_id)
                self.assertEqual(node_by_id[node.parent_candidate_id].level, node.level - 1)
            self.assertTrue(set(node.evidence_ids) <= evidence_ids)
        for edge in bounded.edges:
            self.assertIn(edge.source_candidate_id, node_by_id)
            self.assertIn(edge.target_candidate_id, node_by_id)
            self.assertTrue(set(edge.evidence_ids) <= evidence_ids)

    def test_retry_feedback_finds_validation_wrapped_by_provider_error(self):
        invalid = extractor_output()
        invalid["nodes"][1]["level"] = 3
        try:
            GraphExtractorProviderOutput.model_validate(invalid)
        except ValidationError as validation_error:
            try:
                raise StructuredOutputError("sanitized provider failure") from validation_error
            except StructuredOutputError as provider_error:
                feedback = _retry_feedback(provider_error)

        self.assertIsNotNone(feedback)
        self.assertIn("0/course、1/module、2/chapter、3/section", feedback)

    async def test_reviewer_must_cover_every_candidate_exactly_once(self):
        agent, _, _, _ = self.make_agent(
            extractor_output(), {"reviews": [], "warnings": []}
        )
        with self.assertRaises(GraphReviewerOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_invalid_structured_outputs_are_retried_once_without_reusing_candidates(self):
        source = FakeSourceClient(
            "函数使用 def 定义。\n调用函数时使用函数名。".encode()
        )
        extractor = SequenceProvider(
            [
                model_response(None, prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION),
                model_response(
                    extractor_output(), prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION
                ),
            ]
        )
        reviewer = SequenceProvider(
            [
                model_response(
                    {"reviews": [], "warnings": []},
                    prompt_version=GRAPH_REVIEWER_PROMPT_VERSION,
                ),
                model_response(
                    reviewer_output(), prompt_version=GRAPH_REVIEWER_PROMPT_VERSION
                ),
            ]
        )
        agent = GraphIngestionAgent(
            source,
            AssessmentDocumentExtractor(
                max_text_chars=60_000,
                max_pdf_pages=200,
                max_uncompressed_docx_bytes=50 * 1024 * 1024,
            ),
            extractor_provider_factory=lambda: extractor,
            reviewer_provider_factory=lambda: reviewer,
        )

        response = await agent.extract_and_review(
            request(), trace_request_id="graph-request-0001"
        )
        await agent.aclose()

        self.assertTrue(response.nodes)
        self.assertEqual(len(extractor.requests), 2)
        self.assertEqual(len(reviewer.requests), 2)
        self.assertEqual(
            extractor.output_models,
            [GraphExtractorProviderOutput, GraphExtractorProviderOutput],
        )

    async def test_invalid_structured_extractor_output_is_explicit(self):
        agent, _, extractor, _ = self.make_agent(
            extractor_output(), reviewer_output()
        )
        extractor.response = model_response(
            None, prompt_version=GRAPH_EXTRACTOR_PROMPT_VERSION
        )
        with self.assertRaises(GraphExtractorOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_confidence_and_source_contracts_are_strict(self):
        invalid = extractor_output()
        invalid["nodes"][0]["extractor_confidence"] = 1.1
        agent, _, _, _ = self.make_agent(invalid, reviewer_output())

        with self.assertRaises(GraphExtractorOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_lowercase_relation_is_rejected_before_review(self):
        invalid = extractor_output()
        invalid["edges"][0]["relation"] = "related"
        agent, _, _, _ = self.make_agent(invalid, reviewer_output())

        with self.assertRaises(GraphExtractorOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_unknown_or_missing_rights_fail_closed(self):
        for copyright_status in ("UNKNOWN", None):
            with self.subTest(copyright_status=copyright_status):
                agent, _, _, _ = self.make_agent(
                    extractor_output(), reviewer_output()
                )
                untrusted = request().model_copy(
                    update={
                        "source": request().source.model_copy(
                            update={"copyright_status": copyright_status}
                        )
                    }
                )

                response = await agent.extract_and_review(
                    untrusted, trace_request_id="graph-request-0001"
                )

                self.assertTrue(response.nodes)
                self.assertTrue(
                    all(
                        item.rights_status == "RESTRICTED"
                        for item in response.nodes
                    )
                )
                self.assertTrue(
                    all(
                        item.rights_status == "RESTRICTED"
                        for item in response.sources
                    )
                )

    async def test_model_cannot_forge_source_rights_status(self):
        forged = extractor_output()
        forged["sources"][0]["rights_status"] = "ALLOWED"
        agent, _, _, _ = self.make_agent(forged, reviewer_output())

        with self.assertRaises(GraphExtractorOutputError):
            await agent.extract_and_review(
                request(), trace_request_id="graph-request-0001"
            )

    async def test_teaching_resource_file_only_is_valid_at_request_and_response(self):
        agent, _, _, _ = self.make_agent(extractor_output(), reviewer_output())
        upload_source = request().source.model_copy(
            update={
                "source_type": "TEACHING_RESOURCE",
                "resource_id": None,
                "file_id": None,
            }
        )
        upload_request = GraphExtractReviewRequest.model_validate(
            {
                **request().model_dump(mode="json"),
                "source": upload_source.model_dump(mode="json"),
            }
        )

        response = await agent.extract_and_review(
            upload_request, trace_request_id="graph-request-0001"
        )

        self.assertTrue(response.sources)
        self.assertTrue(
            all(item.source_type == "TEACHING_RESOURCE" for item in response.sources)
        )
        self.assertTrue(all(item.resource_id is None for item in response.sources))
        self.assertTrue(all(item.file_id == 42 for item in response.sources))


if __name__ == "__main__":
    unittest.main()
