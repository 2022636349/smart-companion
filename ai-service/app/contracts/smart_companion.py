from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Id = Annotated[str, Field(min_length=1, max_length=200)]
Text = Annotated[str, Field(max_length=12000)]
Status = Literal['NOT_ASSESSED', 'NO_EVIDENCE', 'INSUFFICIENT', 'NEEDS_PRACTICE', 'DEVELOPING', 'READY']


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True, allow_inf_nan=False)


class KnowledgePoint(StrictModel):
    id: Id
    name: Annotated[str, Field(max_length=500)]
    description: Text


class Observation(StrictModel):
    status: Status
    scored_count: int = Field(ge=0)
    available_question_count: int = Field(ge=0)
    score_percent: float | None = Field(ge=0, le=100)


class PrerequisiteObservation(StrictModel):
    indicator_id: Id
    status: Status
    scored_count: int = Field(ge=0)
    score_percent: float | None = Field(ge=0, le=100)
    evidence_ids: list[Id] = Field(max_length=100)


class Evidence(StrictModel):
    record_id: Id
    material_id: Id
    score: float | None = Field(ge=0)
    max_score: float = Field(ge=0)
    answer: Text
    process: Text
    point_scores: Text
    question: Text
    explanation: Text

    @model_validator(mode='after')
    def score_range(self):
        if self.score is not None and self.score > self.max_score:
            raise ValueError('score exceeds max_score')
        return self


class Prerequisite(StrictModel):
    knowledge_point_id: Id
    name: Annotated[str, Field(max_length=500)]
    assessment: Literal['CHECK_REQUIRED']
    observations: list[PrerequisiteObservation] = Field(max_length=200)


class Source(StrictModel):
    source_id: Id
    knowledge_point_id: Id
    title: Annotated[str, Field(max_length=1000)]
    locator: Annotated[str, Field(max_length=2000)]
    page_from: int | None = Field(ge=1)
    page_to: int | None = Field(ge=1)
    excerpt: Annotated[str, Field(max_length=6000)] | None

    @model_validator(mode='after')
    def page_range(self):
        if self.page_from and self.page_to and self.page_to < self.page_from:
            raise ValueError('invalid page range')
        return self


class Material(StrictModel):
    material_id: Id
    type: Annotated[str, Field(max_length=100)]
    body: Text
    purpose: Annotated[str, Field(max_length=1000)]


class ExplainRequest(StrictModel):
    audience: Literal['TEACHER', 'STUDENT'] = 'TEACHER'
    purpose: Literal['EXPLAIN', 'PLAN', 'HINT'] = 'EXPLAIN'
    course_id: int = Field(gt=0, strict=True)
    student_id: Id
    node_id: Id
    indicator_id: Id
    graph_version: Id
    knowledge_point: KnowledgePoint
    observation: Observation
    evidence: list[Evidence] = Field(max_length=50)
    prerequisites: list[Prerequisite] = Field(max_length=20)
    sources: list[Source] = Field(max_length=80)
    materials: list[Material] = Field(max_length=30)


class Step(StrictModel):
    title: Annotated[str, Field(min_length=1, max_length=300)]
    action: Annotated[str, Field(min_length=1, max_length=2000)]
    rationale: Annotated[str, Field(max_length=2000)]
    knowledge_point_ids: list[Id] = Field(max_length=21)
    source_ids: list[Id] = Field(max_length=80)
    evidence_ids: list[Id] = Field(max_length=100)
    material_ids: list[Id] = Field(max_length=30)


class ExplainOutput(StrictModel):
    explanation: Annotated[str, Field(min_length=1, max_length=8000)]
    steps: list[Step] = Field(min_length=1, max_length=8)
    cautions: list[Annotated[str, Field(max_length=2000)]] = Field(max_length=30)


class ExplainResponse(ExplainOutput):
    provider: Annotated[str, Field(min_length=1, max_length=200)]
    model: Annotated[str, Field(min_length=1, max_length=300)]
