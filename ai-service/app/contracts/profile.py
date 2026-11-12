from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.contracts.tutor import CognitiveLabel


MAX_INT64 = 9_223_372_036_854_775_807


class LearningEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source_type: Literal["ASSESSMENT", "TUTOR", "PRACTICE"]
    source_id: int = Field(ge=1, le=MAX_INT64)
    knowledge_point_id: str = Field(min_length=1)
    content: str = Field(min_length=1, max_length=4_000)
    occurred_at: datetime | None = None

    @field_validator("knowledge_point_id", "content")
    @classmethod
    def required_text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Learning evidence text must not be blank.")
        return value.strip() if len(value) < 129 else value


class ProfileSummarizeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    request_id: str = Field(min_length=16, max_length=64)
    user_id: int = Field(ge=1, le=MAX_INT64)
    previous_summary: str | None = Field(default=None, max_length=10_000)
    evidence: list[LearningEvidence] = Field(min_length=1, max_length=50)

    @field_validator("request_id")
    @classmethod
    def request_id_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("request_id must not be blank.")
        return value


MemoryCandidateType = Literal[
    "SESSION_SUMMARY",
    "MISCONCEPTION",
    "WEAK_EVIDENCE",
    "MASTERED_EVIDENCE",
    "LEARNING_PREFERENCE",
    "LEARNING_GOAL",
    "INTERVENTION_RESULT",
]


class MemoryCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    memory_type: MemoryCandidateType
    knowledge_point_id: str | None = None
    title: str | None = Field(default=None, max_length=200)
    content: str = Field(min_length=1, max_length=10_000)
    confidence: float = Field(ge=0, le=1)
    editable: bool
    deletable: bool

    @field_validator("knowledge_point_id", "title")
    @classmethod
    def optional_text_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("Optional candidate text must not be blank.")
        return normalized

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Memory candidate content must not be blank.")
        return value


ResourceType = Literal[
    "DOCUMENT", "ANIMATION", "VIDEO", "CODE", "IMAGE", "EXERCISE", "EXTERNAL_LINK"
]


class ResourceSuggestion(BaseModel):
    model_config = ConfigDict(extra="forbid")

    knowledge_point_id: str = Field(min_length=1)
    reason: str = Field(min_length=1, max_length=1_000)
    preferred_resource_types: list[ResourceType] = Field(max_length=7)

    @field_validator("knowledge_point_id", "reason")
    @classmethod
    def resource_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Resource suggestion text must not be blank.")
        return normalized

    @field_validator("preferred_resource_types")
    @classmethod
    def resource_types_must_be_unique(cls, value: list[ResourceType]) -> list[ResourceType]:
        if len(value) != len(set(value)):
            raise ValueError("preferred_resource_types must be unique.")
        return value


class ProfileProviderOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: str = Field(min_length=1, max_length=10_000)
    cognitive_labels: list[CognitiveLabel] = Field(max_length=20)
    memory_candidates: list[MemoryCandidate] = Field(max_length=20)
    resource_suggestions: list[ResourceSuggestion] = Field(max_length=20)

    @field_validator("summary")
    @classmethod
    def summary_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Profile summary must not be blank.")
        return value


class ProfileSummarizeResponse(ProfileProviderOutput):
    model_config = ConfigDict(extra="forbid", protected_namespaces=())

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    request_id: str
    model_version: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1)
