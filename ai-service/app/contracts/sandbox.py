from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.contracts.common import KnowledgePointCandidate


SANDBOX_TEST_TEXT_MAX_CHARS = 65_536
SANDBOX_TEST_PAYLOAD_MAX_BYTES = 2 * 1024 * 1024
SANDBOX_EXECUTION_MODES = {"PUBLIC_RUN", "GRADING"}


class SandboxStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    PARTIAL = "PARTIAL"
    WRONG_ANSWER = "WRONG_ANSWER"
    SYNTAX_ERROR = "SYNTAX_ERROR"
    RUNTIME_ERROR = "RUNTIME_ERROR"
    TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED"
    MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED"
    OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED"
    SYSTEM_ERROR = "SYSTEM_ERROR"


class SandboxTestStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    WRONG_ANSWER = "WRONG_ANSWER"
    SYNTAX_ERROR = "SYNTAX_ERROR"
    RUNTIME_ERROR = "RUNTIME_ERROR"
    TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED"
    MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED"
    OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED"
    SYSTEM_ERROR = "SYSTEM_ERROR"


class SandboxTestCase(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    test_case_id: int
    input_data: str = Field(max_length=SANDBOX_TEST_TEXT_MAX_CHARS)
    expected_output: str = Field(max_length=SANDBOX_TEST_TEXT_MAX_CHARS)
    score: float = Field(gt=0)
    visibility: str = Field(pattern="^(PUBLIC|HIDDEN)$")
    compare_mode: str = Field(
        pattern="^(EXACT|TRIM_TRAILING_WHITESPACE|FLOAT_TOLERANCE)$"
    )
    float_tolerance: float | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def validate_compare_mode(self) -> "SandboxTestCase":
        if self.compare_mode == "FLOAT_TOLERANCE" and self.float_tolerance is None:
            raise ValueError("FLOAT_TOLERANCE requires float_tolerance.")
        return self


class SandboxLimits(BaseModel):
    model_config = ConfigDict(extra="forbid")

    time_ms: int = Field(ge=100, le=10_000)
    memory_mb: int = Field(ge=16, le=512)
    output_kb: int = Field(ge=1, le=1_024)


class SandboxRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=64)
    execution_mode: str = Field(pattern="^(PUBLIC_RUN|GRADING)$")
    language: str = Field(pattern="^PYTHON_3_11$")
    source_code: str = Field(min_length=1, max_length=65_536)
    test_cases: list[SandboxTestCase] = Field(min_length=1, max_length=100)
    limits: SandboxLimits

    @field_validator("request_id")
    @classmethod
    def strip_request_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("request_id must not be blank.")
        return normalized

    @model_validator(mode="after")
    def validate_test_ids(self) -> "SandboxRunRequest":
        ids = [test.test_case_id for test in self.test_cases]
        if len(ids) != len(set(ids)):
            raise ValueError("test_case_id values must be unique.")
        payload_bytes = sum(
            len(test.input_data.encode("utf-8"))
            + len(test.expected_output.encode("utf-8"))
            for test in self.test_cases
        )
        if payload_bytes > SANDBOX_TEST_PAYLOAD_MAX_BYTES:
            raise ValueError("The combined test-case payload exceeds 2 MiB.")
        return self


class SandboxTestResult(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    test_case_id: int
    status: SandboxTestStatus
    score: float = Field(ge=0)
    max_score: float = Field(ge=0)
    time_ms: int = Field(ge=0)
    memory_kb: int = Field(ge=0)
    visibility: str = Field(pattern="^(PUBLIC|HIDDEN)$")
    actual_output: str | None = None
    expected_output: str | None = None
    error_message: str | None = None

    @model_validator(mode="after")
    def redact_hidden_result(self) -> "SandboxTestResult":
        if self.visibility == "HIDDEN":
            self.actual_output = None
            self.expected_output = None
            self.error_message = None
        return self


class SandboxRunResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    request_id: str
    status: SandboxStatus
    achieved_score: float = Field(ge=0)
    total_score: float = Field(ge=0)
    passed_count: int = Field(ge=0)
    total_count: int = Field(ge=0)
    compile_message: str | None = None
    test_results: list[SandboxTestResult]


class CodeFailureInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    position: int
    status: str
    public_input: str | None = None
    public_expected_output: str | None = None
    actual_output: str | None = None
    error_message: str | None = None


class CodeExplainRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=64)
    question_id: int
    knowledge_point_ids: list[str]
    source_code: str
    failures: list[CodeFailureInput]


class CodeExplainResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=(), extra="forbid")

    request_id: str
    feedback: str
    suspected_knowledge_points: list[KnowledgePointCandidate] = Field(default_factory=list)
    model_version: str
    prompt_version: str
