"""Frozen internal 2.12 teaching-plan contract; no raw student work."""
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.contracts.smart_companion import Id, KnowledgePoint, Material, Source, StrictModel


class PlanConstraints(StrictModel):
    objective: Annotated[str, Field(max_length=500)]
    duration_minutes: int = Field(ge=10, le=180, strict=True)
    max_steps: int = Field(ge=1, le=12, strict=True)


class PlanSummary(StrictModel):
    student_count: int = Field(ge=0, strict=True)
    record_count: int = Field(ge=0, strict=True)
    scored_count: int = Field(ge=0, strict=True)
    earned_score: float = Field(ge=0)
    max_score: float = Field(ge=0)
    score_percent: float | None = Field(ge=0, le=100)


class StatusCounts(StrictModel):
    needs_practice: int = Field(ge=0, strict=True)
    developing: int = Field(ge=0, strict=True)
    ready: int = Field(ge=0, strict=True)
    insufficient: int = Field(ge=0, strict=True)
    no_evidence: int = Field(ge=0, strict=True)
    not_assessed: int = Field(ge=0, strict=True)


class PlanFocus(StrictModel):
    indicator_id: Id
    name: Annotated[str, Field(max_length=500)]
    knowledge_point_id: Id
    scored_count: int = Field(ge=0, strict=True)
    available_question_count: int = Field(ge=0, strict=True)
    covered_student_count: int = Field(ge=0, strict=True)
    score_percent: float | None = Field(ge=0, le=100)
    status_counts: StatusCounts
    evidence_ids: list[Id] = Field(max_length=100)


class PlanningRequest(StrictModel):
    scope_type: Literal['STUDENT', 'CLASS']
    course_id: int = Field(gt=0, strict=True)
    node_id: Id
    subject_id: Id
    graph_version: Id
    constraints: PlanConstraints
    summary: PlanSummary
    focuses: list[PlanFocus] = Field(min_length=1, max_length=9)
    knowledge_points: list[KnowledgePoint] = Field(min_length=1, max_length=30)
    sources: list[Source] = Field(max_length=80)
    materials: list[Material] = Field(max_length=60)

    @model_validator(mode='after')
    def validate_context(self):
        for ids in ([f.indicator_id for f in self.focuses],
                    [k.id for k in self.knowledge_points],
                    [s.source_id for s in self.sources], [m.material_id for m in self.materials]):
            if len(set(ids)) != len(ids):
                raise ValueError('Context identifiers must be unique within each collection.')
        if self.constraints.max_steps < len(self.focuses):
            raise ValueError('max_steps must cover every selected focus (at least one step per focus).')
        knowledge_ids = {k.id for k in self.knowledge_points}
        if any(f.knowledge_point_id not in knowledge_ids for f in self.focuses) or any(
                s.knowledge_point_id not in knowledge_ids for s in self.sources):
            raise ValueError('Focus and source knowledge points must be in the approved context.')
        return self


class PlanningStep(StrictModel):
    title: Annotated[str, Field(min_length=1, max_length=300)]
    action: Annotated[str, Field(min_length=1, max_length=4000)]
    rationale: Annotated[str, Field(max_length=2000)]
    indicator_id: Id
    duration_minutes: int = Field(ge=1, le=180, strict=True)
    knowledge_point_ids: list[Id] = Field(max_length=30)
    source_ids: list[Id] = Field(max_length=80)
    evidence_ids: list[Id] = Field(max_length=100)
    material_ids: list[Id] = Field(max_length=60)
    hints: list[Annotated[str, Field(max_length=2000)]] = Field(max_length=20)


class PlanningOutput(StrictModel):
    explanation: Annotated[str, Field(min_length=1, max_length=8000)]
    steps: list[PlanningStep] = Field(min_length=1, max_length=12)
    cautions: list[Annotated[str, Field(max_length=2000)]] = Field(max_length=30)


class PlanningResponse(PlanningOutput):
    provider: Annotated[str, Field(min_length=1, max_length=200)]
    model: Annotated[str, Field(min_length=1, max_length=300)]
