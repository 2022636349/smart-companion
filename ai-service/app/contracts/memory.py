from __future__ import annotations

import hashlib
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


MemoryType = Literal[
    "SESSION_SUMMARY",
    "MISCONCEPTION",
    "WEAK_EVIDENCE",
    "MASTERED_EVIDENCE",
    "LEARNING_PREFERENCE",
    "LEARNING_GOAL",
    "INTERVENTION_RESULT",
    "TEACHER_NOTE",
]
DeleteMode = Literal["DEINDEX", "TERMINAL_DELETE"]
MAX_INT64 = 9_223_372_036_854_775_807
MAX_GENERATION = 2_147_483_647


class MemoryUpsertRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    request_id: str = Field(min_length=16, max_length=64)
    memory_id: int = Field(ge=1, le=MAX_INT64)
    student_id: int = Field(ge=1, le=MAX_INT64)
    memory_type: MemoryType
    knowledge_point_id: str | None = None
    content: str = Field(min_length=1, max_length=10_000)
    content_hash: str = Field(pattern=r"^[a-f0-9]{64}$")
    status: Literal["ACTIVE"]
    version: int = Field(ge=1, le=MAX_INT64)
    generation: int = Field(ge=1, le=MAX_GENERATION)
    source_type: str | None = None
    source_id: int | None = Field(default=None, ge=1, le=MAX_INT64)
    created_at: datetime | None = None

    @field_validator("request_id", "content")
    @classmethod
    def required_text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Required memory text must not be blank.")
        return value

    @field_validator("knowledge_point_id", "source_type")
    @classmethod
    def optional_text_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("Optional memory text must not be blank.")
        return normalized

    @model_validator(mode="after")
    def content_hash_must_match(self) -> "MemoryUpsertRequest":
        actual = hashlib.sha256(self.content.encode("utf-8")).hexdigest()
        if actual != self.content_hash:
            raise ValueError("content_hash does not match UTF-8 memory content.")
        return self


class MemoryUpsertResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str
    collection: Literal["learning_memory"]
    memory_id: int
    generation: int = Field(ge=1, le=MAX_GENERATION)
    indexed: bool
    embedding_model: str = Field(min_length=1)
    memory_index_epoch: str = Field(min_length=16, max_length=64)
    memory_rebuild_required: bool


class MemorySearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    request_id: str = Field(min_length=16, max_length=64)
    student_id: int = Field(ge=1, le=MAX_INT64)
    query: str = Field(min_length=1, max_length=10_000)
    knowledge_point_id: str | None = None
    memory_types: list[MemoryType] | None = None
    limit: int = Field(default=5, ge=1, le=20)
    min_score: float = Field(default=0.25, ge=0, le=1)

    @field_validator("request_id", "query")
    @classmethod
    def required_text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Required memory search text must not be blank.")
        return value

    @field_validator("knowledge_point_id")
    @classmethod
    def optional_kp_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("knowledge_point_id must not be blank.")
        return normalized

    @field_validator("memory_types")
    @classmethod
    def memory_types_must_be_unique(
        cls, value: list[MemoryType] | None
    ) -> list[MemoryType] | None:
        if value is not None and len(value) != len(set(value)):
            raise ValueError("memory_types must be unique.")
        return value


class MemorySearchHit(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    memory_id: int
    student_id: int
    memory_type: str
    knowledge_point_id: str | None = None
    content: str
    score: float = Field(ge=0, le=1)
    version: int = Field(ge=1)
    generation: int = Field(ge=1, le=MAX_GENERATION)
    source_type: str | None = None
    source_id: int | None = None


class MemorySearchResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    course_id: int = Field(strict=True, ge=1, le=MAX_INT64)

    request_id: str
    student_id: int
    items: list[MemorySearchHit]
    collection: Literal["learning_memory"]
    memory_index_epoch: str = Field(min_length=16, max_length=64)
    memory_rebuild_required: bool


class DeleteIndexResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str
    collection: Literal["learning_memory"]
    memory_id: int
    mode: DeleteMode
    generation: int = Field(ge=1, le=MAX_GENERATION)
    deleted_count: int = Field(ge=0)
    memory_index_epoch: str = Field(min_length=16, max_length=64)
    memory_rebuild_required: bool
