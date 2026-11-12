from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


ReviewStatus = Literal["PENDING", "APPROVED", "REJECTED"]


class GraphNode(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    level: int
    node_type: Literal[
        "course", "module", "chapter", "section", "knowledge_point"
    ]
    parent_id: str | None = None
    description: str | None = None
    aliases: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)
    difficulty: str | None = None
    importance: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=1)
    learning_objectives: list[str] = Field(default_factory=list)
    common_misconceptions: list[str] = Field(default_factory=list)
    example_code: list[str] = Field(default_factory=list)
    review_status: ReviewStatus
    content_version: int = Field(ge=1)

    @field_validator("id", "name")
    @classmethod
    def node_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Graph node identifiers and names must not be blank.")
        return normalized


class GraphSource(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(min_length=1)
    node_id: str = Field(min_length=1, max_length=64)
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    title: str = Field(min_length=1)
    resource_id: int | None = None
    file_id: int | None = None
    chapter: str | None = None
    page_from: int | None = Field(default=None, ge=1)
    page_to: int | None = Field(default=None, ge=1)
    locator: str = Field(min_length=1)
    excerpt: str | None = None
    review_status: ReviewStatus
    rights_status: Literal["ALLOWED", "RESTRICTED"]
    confidence: float | None = Field(default=None, ge=0, le=1)

    @field_validator("id", "node_id", "title", "locator")
    @classmethod
    def source_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Graph source trace fields must not be blank.")
        return normalized


class GraphRagContext(BaseModel):
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    model_config = ConfigDict(extra="ignore")

    knowledge_point: GraphNode
    prerequisites: list[GraphNode]
    successors: list[GraphNode]
    related_nodes: list[GraphNode]
    sources: list[GraphSource]
    graph_version: str = Field(min_length=1)
    context_status: Literal["READY", "INSUFFICIENT"]
    warnings: list[str]

    @field_validator("graph_version")
    @classmethod
    def graph_version_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Graph version must not be blank.")
        return normalized

    def ensure_approved_and_traceable(self, expected_node_id: str) -> None:
        if self.knowledge_point.id != expected_node_id:
            raise ValueError("The graph context knowledge point does not match the request.")

        nodes = (
            self.knowledge_point,
            *self.prerequisites,
            *self.successors,
            *self.related_nodes,
        )
        if any(node.review_status != "APPROVED" for node in nodes):
            raise ValueError("The graph context contains a non-approved node.")

        known_node_ids = {node.id for node in nodes}
        source_ids: set[str] = set()
        for source in self.sources:
            if source.review_status != "APPROVED":
                raise ValueError("The graph context contains a non-approved source.")
            if source.rights_status != "ALLOWED":
                raise ValueError("The graph context contains a source without approved rights.")
            if source.node_id not in known_node_ids:
                raise ValueError("A graph source cannot be traced to a context node.")
            if source.id in source_ids:
                raise ValueError("The graph context contains duplicate source identifiers.")
            source_ids.add(source.id)

        if self.context_status == "READY" and not any(
            source.node_id == self.knowledge_point.id for source in self.sources
        ):
            raise ValueError(
                "A ready graph context must contain a source for the knowledge point."
            )


class GraphMatchCandidate(BaseModel):
    model_config = ConfigDict(extra="ignore")

    node: GraphNode
    score: float = Field(ge=0, le=1)
    matched_by: list[
        Literal["NAME", "ALIAS", "KEYWORD", "FULLTEXT", "GRAPH_CONTEXT"]
    ]
    evidence: list[str] = Field(default_factory=list)
    requires_review: bool


class GraphMatchResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[GraphMatchCandidate]


class GraphSearchResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[GraphNode]
    query: str = Field(min_length=1)

    @field_validator("query")
    @classmethod
    def strip_query(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Graph search query must not be blank.")
        return normalized

    def ensure_approved_knowledge_points(self) -> None:
        if any(
            item.review_status != "APPROVED" or item.node_type != "knowledge_point"
            for item in self.items
        ):
            raise ValueError("Graph search returned a non-approved knowledge point.")
        identifiers = [item.id for item in self.items]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("Graph search returned duplicate knowledge points.")


class GraphNodeListResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[GraphNode]

    def ensure_approved_knowledge_points(self) -> None:
        if any(
            item.review_status != "APPROVED" or item.node_type != "knowledge_point"
            for item in self.items
        ):
            raise ValueError("Graph traversal returned a non-approved knowledge point.")
        identifiers = [item.id for item in self.items]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("Graph traversal returned duplicate knowledge points.")


class GraphRecommendation(BaseModel):
    model_config = ConfigDict(extra="ignore")

    node: GraphNode
    reason: str = Field(min_length=1, max_length=2_000)
    priority: int
    evidence: list[str] = Field(default_factory=list, max_length=20)

    @field_validator("reason")
    @classmethod
    def strip_reason(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Recommendation reason must not be blank.")
        return normalized

    @field_validator("evidence")
    @classmethod
    def validate_evidence(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value or len(value) > 2_000 for value in normalized):
            raise ValueError("Recommendation evidence must be bounded non-blank text.")
        return normalized


class GraphRecommendationResponse(BaseModel):
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    model_config = ConfigDict(extra="ignore")

    items: list[GraphRecommendation]
    graph_version: str = Field(min_length=1)

    @field_validator("graph_version")
    @classmethod
    def strip_graph_version(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Graph version must not be blank.")
        return normalized

    @model_validator(mode="after")
    def ensure_approved_unique_nodes(self) -> "GraphRecommendationResponse":
        nodes = [item.node for item in self.items]
        if any(
            node.review_status != "APPROVED" or node.node_type != "knowledge_point"
            for node in nodes
        ):
            raise ValueError("Recommendations contain a non-approved knowledge point.")
        identifiers = [node.id for node in nodes]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("Recommendations contain duplicate knowledge points.")
        return self
