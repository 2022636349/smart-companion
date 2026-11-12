from enum import Enum
import json
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


PROGRAMMING_TEST_TEXT_MAX_CHARS = 65_536

class QuestionType(str, Enum):
    SINGLE_CHOICE = "SINGLE_CHOICE"
    MULTIPLE_CHOICE = "MULTIPLE_CHOICE"
    SUBJECTIVE = "SUBJECTIVE"
    PROGRAMMING = "PROGRAMMING"
    ATTACHMENT = "ATTACHMENT"


class ConfidenceLabel(str, Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class KnowledgePointCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    knowledge_point_id: str
    name: str
    score: float = Field(ge=0, le=1)

    @field_validator("knowledge_point_id", "name")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Knowledge point fields must not be blank.")
        return normalized


class RubricItem(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    label: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=5_000)
    max_score: float = Field(gt=0, le=999_999.99, multiple_of=0.01)

    @field_validator("label", "description")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Rubric fields must not be blank.")
        return normalized


class TestCaseDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    input_data: str = Field(max_length=PROGRAMMING_TEST_TEXT_MAX_CHARS)
    expected_output: str = Field(max_length=PROGRAMMING_TEST_TEXT_MAX_CHARS)
    score: float = Field(gt=0, le=999_999.99, multiple_of=0.01)
    visibility: Literal["PUBLIC", "HIDDEN"]
    compare_mode: Literal[
        "EXACT", "TRIM_TRAILING_WHITESPACE", "FLOAT_TOLERANCE"
    ]


class ContentBlock(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal[
        "markdown",
        "code",
        "code_lab",
        "document",
        "animation",
        "video",
        "image",
        "quiz",
    ]
    block_id: str | None = Field(
        default=None,
        max_length=80,
        pattern=r"^[A-Za-z][A-Za-z0-9_-]{0,79}$",
    )
    schema_version: int = Field(default=1, ge=1, le=10)
    activity_ref: str | None = Field(
        default=None,
        pattern=r"^[A-Za-z][A-Za-z0-9_-]{0,79}$",
    )
    content: str | None = Field(default=None, max_length=20_000)
    resource_id: int | None = Field(default=None, ge=1)
    title: str | None = Field(default=None, max_length=2_000)
    language: str | None = Field(default=None, max_length=64)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("metadata")
    @classmethod
    def metadata_must_fit_spring_boundary(cls, value: dict[str, Any]) -> dict[str, Any]:
        if len(value) > 20 or any(
            not isinstance(key, str) or not key.strip() or len(key) > 100
            for key in value
        ):
            raise ValueError("Content block metadata exceeds the bounded map shape.")
        try:
            encoded = json.dumps(
                value,
                ensure_ascii=False,
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise ValueError("Content block metadata must be finite JSON.") from exc
        if len(encoded) > 16_384:
            raise ValueError("Content block metadata exceeds the byte budget.")
        return value

