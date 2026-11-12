from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


ID_PATTERN = r"^[A-Za-z0-9._:-]+$"
SHA256_PATTERN = r"^[a-f0-9]{64}$"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class GraphIngestionSourceRef(StrictModel):
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    source_id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=500)
    file_sha256: str | None = Field(default=None, pattern=SHA256_PATTERN)
    locator: str | None = Field(default=None, max_length=1000)


class GraphThresholdProfile(StrictModel):
    profile_version: str = Field(min_length=1, max_length=100)
    node_fast_track_min: float = Field(default=0.90, ge=0, le=1)
    edge_fast_track_min: float = Field(default=0.94, ge=0, le=1)
    source_fast_track_min: float = Field(default=0.95, ge=0, le=1)
    reject_below: float = Field(default=0.65, ge=0, le=1)

    @model_validator(mode="after")
    def validate_order(self) -> "GraphThresholdProfile":
        if self.reject_below >= min(
            self.node_fast_track_min,
            self.edge_fast_track_min,
            self.source_fast_track_min,
        ):
            raise ValueError("reject_below 必须低于所有 FAST_TRACK 阈值")
        return self


class GraphModelIdentity(StrictModel):
    provider: str = Field(min_length=1, max_length=40)
    model: str = Field(min_length=1, max_length=100)


class GraphIngestionModels(StrictModel):
    extractor: GraphModelIdentity
    reviewer: GraphModelIdentity


class GraphPromptIdentity(StrictModel):
    version: str = Field(min_length=1, max_length=100)
    sha256: str = Field(pattern=SHA256_PATTERN)


class GraphIngestionPrompts(StrictModel):
    extractor: GraphPromptIdentity
    reviewer: GraphPromptIdentity


DecisionStatus = Literal["PENDING", "APPROVED", "REJECTED"]
ReviewLane = Literal["FAST_TRACK", "STANDARD", "REJECT_RECOMMENDED"]


class GraphDraftNode(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=ID_PATTERN)
    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    level: int = Field(ge=0, le=3)
    node_type: Literal["course", "module", "chapter", "section", "knowledge_point"]
    parent_id: str | None = Field(default=None, max_length=64)
    description: str | None = Field(default=None, max_length=5000)
    aliases: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=20
    )
    keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=30
    )
    difficulty: Literal["BEGINNER", "BASIC", "INTERMEDIATE", "ADVANCED"] | None = None
    importance: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=1, le=10000)
    learning_objectives: list[
        Annotated[str, Field(min_length=1, max_length=1000)]
    ] = Field(default_factory=list, max_length=20)
    common_misconceptions: list[
        Annotated[str, Field(min_length=1, max_length=1000)]
    ] = Field(default_factory=list, max_length=20)
    example_code: list[Annotated[str, Field(min_length=1, max_length=10000)]] = Field(
        default_factory=list, max_length=10
    )
    rights_status: Literal["PENDING", "ALLOWED", "RESTRICTED"] = "RESTRICTED"
    evidence: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(
        min_length=1, max_length=10
    )
    extractor_confidence: float = Field(ge=0, le=1)
    reviewer_confidence: float = Field(ge=0, le=1)
    review_status: Literal["PENDING"] = "PENDING"
    decision_status: DecisionStatus = "PENDING"
    review_lane: ReviewLane = "STANDARD"
    decision_comment: str | None = Field(default=None, max_length=2000)
    decided_by: str | None = Field(default=None, max_length=128)
    decided_at: str | None = Field(default=None, max_length=40)

    @model_validator(mode="after")
    def validate_level_and_type(self) -> "GraphDraftNode":
        expected = {0: "course", 1: "module", 2: "chapter"}
        if self.level in expected and self.node_type != expected[self.level]:
            raise ValueError("level 与 node_type 不一致")
        if self.level == 3 and self.node_type not in {"section", "knowledge_point"}:
            raise ValueError("level=3 只能是 section 或 knowledge_point")
        if self.level == 0 and self.parent_id is not None:
            raise ValueError("course 根节点不能包含 parent_id")
        if self.level > 0 and not self.parent_id:
            raise ValueError("非根节点必须包含 parent_id")
        if len(set(self.aliases)) != len(self.aliases):
            raise ValueError("aliases 不能重复")
        if len(set(self.keywords)) != len(self.keywords):
            raise ValueError("keywords 不能重复")
        return self


class GraphDraftEdge(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=ID_PATTERN)
    source_id: str = Field(min_length=1, max_length=64)
    target_id: str = Field(min_length=1, max_length=64)
    relation: Literal["REQUIRES", "RELATED", "SUPPORTED_BY"]
    evidence: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(
        min_length=1, max_length=10
    )
    extractor_confidence: float = Field(ge=0, le=1)
    reviewer_confidence: float = Field(ge=0, le=1)
    review_status: Literal["PENDING"] = "PENDING"
    decision_status: DecisionStatus = "PENDING"
    review_lane: ReviewLane = "STANDARD"
    decision_comment: str | None = Field(default=None, max_length=2000)
    decided_by: str | None = Field(default=None, max_length=128)
    decided_at: str | None = Field(default=None, max_length=40)


class GraphDraftSource(StrictModel):
    candidate_id: str = Field(min_length=1, max_length=64, pattern=ID_PATTERN)
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
    def validate_pages(self) -> "GraphDraftSource":
        if self.page_from is not None and self.page_to is not None:
            if self.page_to < self.page_from:
                raise ValueError("page_to 不能小于 page_from")
        if (
            self.source_type == "TEACHING_RESOURCE"
            and self.resource_id is None
            and self.file_id is None
        ):
            raise ValueError("TEACHING_RESOURCE 必须包含 resource_id 或 file_id")
        return self


class GraphIngestionBatchUpsertRequest(StrictModel):
    request_id: str = Field(min_length=16, max_length=64, pattern=ID_PATTERN)
    batch_id: str = Field(min_length=16, max_length=64, pattern=ID_PATTERN)
    base_graph_version: str = Field(min_length=1, max_length=100)
    payload_sha256: str = Field(pattern=SHA256_PATTERN)
    source: GraphIngestionSourceRef
    threshold_profile: GraphThresholdProfile
    models: GraphIngestionModels
    prompts: GraphIngestionPrompts
    nodes: list[GraphDraftNode] = Field(max_length=200)
    edges: list[GraphDraftEdge] = Field(max_length=500)
    sources: list[GraphDraftSource] = Field(max_length=500)

    @model_validator(mode="after")
    def validate_non_empty_and_pending(self) -> "GraphIngestionBatchUpsertRequest":
        if not (self.nodes or self.edges or self.sources):
            raise ValueError("建图批次至少包含一个候选")
        for item in [*self.nodes, *self.edges, *self.sources]:
            if item.decision_status != "PENDING":
                raise ValueError("新建批次的 decision_status 必须是 PENDING")
        return self


class GraphValidationIssue(StrictModel):
    code: str = Field(min_length=1, max_length=80)
    severity: Literal["INFO", "WARNING", "ERROR"]
    candidate_type: Literal["NODE", "EDGE", "SOURCE", "BATCH"]
    candidate_id: str | None = Field(default=None, max_length=128)
    message: str = Field(min_length=1, max_length=1000)


class GraphCandidateDecision(StrictModel):
    candidate_type: Literal["NODE", "EDGE", "SOURCE"]
    candidate_id: str = Field(min_length=1, max_length=128)
    decision: Literal["APPROVE", "REJECT"]
    comment: str = Field(min_length=1, max_length=2000)
    edited_node: GraphDraftNode | None = None
    edited_edge: GraphDraftEdge | None = None
    edited_source: GraphDraftSource | None = None

    @model_validator(mode="after")
    def validate_edit_shape(self) -> "GraphCandidateDecision":
        edits = {
            "NODE": self.edited_node,
            "EDGE": self.edited_edge,
            "SOURCE": self.edited_source,
        }
        supplied = [name for name, value in edits.items() if value is not None]
        if supplied and supplied != [self.candidate_type]:
            raise ValueError("edited_* 必须与 candidate_type 一致且最多一个")
        edited = edits[self.candidate_type]
        if edited is not None and edited.candidate_id != self.candidate_id:
            raise ValueError("编辑后的 candidate_id 不能改变")
        return self


class GraphCandidateDecisionRequest(StrictModel):
    request_id: str = Field(min_length=16, max_length=64, pattern=ID_PATTERN)
    expected_revision: int = Field(ge=1)
    reviewer_id: str = Field(min_length=1, max_length=128)
    decisions: list[GraphCandidateDecision] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_unique_candidates(self) -> "GraphCandidateDecisionRequest":
        identities = [
            (item.candidate_type, item.candidate_id) for item in self.decisions
        ]
        if len(set(identities)) != len(identities):
            raise ValueError("同一候选不能在一次请求中重复决策")
        return self


class GraphPublishRequest(StrictModel):
    request_id: str = Field(min_length=16, max_length=64, pattern=ID_PATTERN)
    expected_revision: int = Field(ge=1)
    target_graph_version: str = Field(
        min_length=1,
        max_length=100,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    )


class GraphRollbackRequest(StrictModel):
    request_id: str = Field(min_length=16, max_length=64, pattern=ID_PATTERN)
    expected_current_version: str = Field(min_length=1, max_length=100)


class GraphIngestionBatchSummary(StrictModel):
    course_id: int = Field(ge=1)
    batch_id: str
    base_graph_version: str
    payload_sha256: str
    status: Literal["REVIEW_REQUIRED", "READY_TO_PUBLISH", "PUBLISHED"]
    revision: int = Field(ge=1)
    candidate_count: int = Field(ge=1)
    pending_count: int = Field(ge=0)
    approved_count: int = Field(ge=0)
    rejected_count: int = Field(ge=0)
    error_count: int = Field(ge=0)
    warning_count: int = Field(ge=0)
    created_at: str
    updated_at: str
    published_graph_version: str | None = None


class GraphIngestionBatchDetail(GraphIngestionBatchSummary):
    source: GraphIngestionSourceRef
    threshold_profile: GraphThresholdProfile
    models: GraphIngestionModels
    prompts: GraphIngestionPrompts
    nodes: list[GraphDraftNode]
    edges: list[GraphDraftEdge]
    sources: list[GraphDraftSource]
    validation_issues: list[GraphValidationIssue]


class GraphIngestionBatchUpsertResponse(StrictModel):
    duplicate: bool
    batch: GraphIngestionBatchDetail


class GraphIngestionBatchListResponse(StrictModel):
    items: list[GraphIngestionBatchSummary]
    next_cursor: str | None = None


class GraphCandidateView(StrictModel):
    candidate_type: Literal["NODE", "EDGE", "SOURCE"]
    candidate_id: str
    decision_status: DecisionStatus
    review_lane: ReviewLane
    node: GraphDraftNode | None = None
    edge: GraphDraftEdge | None = None
    source: GraphDraftSource | None = None


class GraphCandidatePageResponse(StrictModel):
    items: list[GraphCandidateView]
    next_cursor: str | None = None


class GraphPublishResponse(StrictModel):
    course_id: int = Field(ge=1)
    batch_id: str
    previous_graph_version: str
    graph_version: str
    release_sha256: str = Field(pattern=SHA256_PATTERN)
    published_at: str


class GraphRollbackResponse(StrictModel):
    course_id: int = Field(ge=1)
    previous_graph_version: str
    graph_version: str
    release_sha256: str = Field(pattern=SHA256_PATTERN)
    activated_at: str


CandidatePayload = GraphDraftNode | GraphDraftEdge | GraphDraftSource
CandidateTypeFilter = Literal["NODE", "EDGE", "SOURCE"]
DecisionStatusFilter = Literal["PENDING", "APPROVED", "REJECTED"]
ReviewLaneFilter = Literal["FAST_TRACK", "STANDARD", "REJECT_RECOMMENDED"]
BatchStatusFilter = Literal["REVIEW_REQUIRED", "READY_TO_PUBLISH", "PUBLISHED"]


def candidate_payload(decision: GraphCandidateDecision) -> CandidatePayload | None:
    value: Any = {
        "NODE": decision.edited_node,
        "EDGE": decision.edited_edge,
        "SOURCE": decision.edited_source,
    }[decision.candidate_type]
    return value
