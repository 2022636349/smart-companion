import re
from decimal import Decimal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    HttpUrl,
    field_validator,
    model_validator,
)

from app.contracts.common import (
    ConfidenceLabel,
    KnowledgePointCandidate,
    QuestionType,
    RubricItem,
    TestCaseDraft,
)


PROGRAMMING_TEST_PAYLOAD_MAX_BYTES = 2 * 1024 * 1024
GRADING_TEXT_MAX_CHARS = 100_000
GRADING_RUBRIC_MAX_ITEMS = 100
GRADING_RUBRIC_TEXT_MAX_CHARS = 5_000
GRADING_ATTACHMENT_MAX_FILES = 5
GRADING_EVIDENCE_MAX_ITEMS = 20
GRADING_EVIDENCE_MAX_CHARS = 1_000
GRADING_EVIDENCE_DISCLOSURE_MAX_CHARS = 240
GRADING_COMMENT_MAX_CHARS = 2_000
GRADING_OVERALL_COMMENT_MAX_CHARS = 5_000
GRADING_WARNING_MAX_CHARS = 1_000


class QuestionOptionDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: str = Field(min_length=1, max_length=20)
    content: str = Field(min_length=1)
    is_correct: bool

    @field_validator("key", "content")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("The value must not be blank.")
        return normalized


class QuestionDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    client_key: str = Field(min_length=1, max_length=64)
    question_type: QuestionType
    stem: str = Field(min_length=1)
    score: float = Field(gt=0, le=999_999.99, multiple_of=0.01)
    options: list[QuestionOptionDraft] = Field(default_factory=list)
    reference_answer: str | None = None
    rubric_items: list[RubricItem] = Field(default_factory=list)
    test_cases: list[TestCaseDraft] = Field(default_factory=list, max_length=100)
    knowledge_point_candidates: list[KnowledgePointCandidate] = Field(default_factory=list)
    confidence: ConfidenceLabel
    warnings: list[str] = Field(default_factory=list)

    @field_validator("client_key", "stem")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("The value must not be blank.")
        return normalized

    @field_validator("reference_answer")
    @classmethod
    def strip_optional_answer(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @field_validator("warnings")
    @classmethod
    def validate_warnings(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            warning = value.strip()
            if not warning:
                raise ValueError("Warnings must not contain blank items.")
            if warning not in seen:
                normalized.append(warning)
                seen.add(warning)
        return normalized

    @model_validator(mode="after")
    def validate_question_shape(self) -> "QuestionDraft":
        option_keys = [option.key for option in self.options]
        if len(option_keys) != len(set(option_keys)):
            raise ValueError("Question option keys must be unique.")

        if self.question_type in {
            QuestionType.SINGLE_CHOICE,
            QuestionType.MULTIPLE_CHOICE,
        }:
            if len(self.options) < 2:
                raise ValueError("Choice questions require at least two options.")
            correct_keys = {
                option.key for option in self.options if option.is_correct
            }
            expected_count = 1 if self.question_type == QuestionType.SINGLE_CHOICE else None
            if expected_count is not None and len(correct_keys) != expected_count:
                raise ValueError("Single-choice questions require one correct option.")
            if self.question_type == QuestionType.MULTIPLE_CHOICE and not correct_keys:
                raise ValueError("Multiple-choice questions require a correct option.")
            if self.rubric_items or self.test_cases:
                raise ValueError("Choice questions cannot contain rubrics or test cases.")
            if self.reference_answer is not None:
                supplied_keys = {
                    item
                    for item in re.split(r"[,，\s]+", self.reference_answer)
                    if item
                }
                if supplied_keys != correct_keys:
                    raise ValueError(
                        "Choice reference_answer must match the correct option keys."
                    )
            return self

        if self.options:
            raise ValueError("Only choice questions can contain options.")

        if self.question_type == QuestionType.SUBJECTIVE:
            self._validate_rubric_total("Subjective")
            if self.test_cases:
                raise ValueError("Subjective questions cannot contain test cases.")
        elif self.question_type == QuestionType.PROGRAMMING:
            if not self.reference_answer:
                raise ValueError("Programming questions require reference source code.")
            if self.rubric_items:
                raise ValueError("Programming questions are scored by test cases.")
            if len(self.test_cases) < 2:
                raise ValueError("Programming questions require public and hidden tests.")
            visibilities = {test.visibility for test in self.test_cases}
            if visibilities != {"PUBLIC", "HIDDEN"}:
                raise ValueError(
                    "Programming questions require at least one public and one hidden test."
                )
            payload_bytes = sum(
                len(test.input_data.encode("utf-8"))
                + len(test.expected_output.encode("utf-8"))
                for test in self.test_cases
            )
            if payload_bytes > PROGRAMMING_TEST_PAYLOAD_MAX_BYTES:
                raise ValueError(
                    "Programming test-case input and output exceed 2 MiB."
                )
            self._validate_score_total(
                [test.score for test in self.test_cases],
                "Programming test case",
            )
        elif self.question_type == QuestionType.ATTACHMENT:
            if self.reference_answer is not None:
                raise ValueError("Attachment questions cannot contain a text answer.")
            if self.test_cases:
                raise ValueError("Attachment questions cannot contain test cases.")
            self._validate_rubric_total("Attachment")
        return self

    def _validate_rubric_total(self, label: str) -> None:
        if not self.rubric_items:
            raise ValueError(f"{label} questions require at least one rubric item.")
        self._validate_score_total(
            [item.max_score for item in self.rubric_items],
            f"{label} rubric",
        )

    def _validate_score_total(self, values: list[float], label: str) -> None:
        total = sum((Decimal(str(value)) for value in values), start=Decimal("0"))
        if total != Decimal(str(self.score)):
            raise ValueError(f"{label} scores must equal the question score.")


class AssessmentParseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=64)
    source_file_id: int = Field(gt=0)
    filename: str = Field(min_length=1, max_length=255)
    download_url: HttpUrl
    course_id: int = Field(strict=True, gt=0, le=9_223_372_036_854_775_807)
    preferred_language: str = Field(default="zh-CN", min_length=2, max_length=20)

    @field_validator("request_id", "filename", "preferred_language")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("The value must not be blank.")
        return normalized


class AssessmentModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title_suggestion: str | None = None
    questions: list[QuestionDraft] = Field(min_length=1, max_length=200)
    warnings: list[str] = Field(default_factory=list)

    @field_validator("title_suggestion")
    @classmethod
    def strip_optional_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @field_validator("warnings")
    @classmethod
    def validate_warnings(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            warning = value.strip()
            if not warning:
                raise ValueError("Warnings must not contain blank items.")
            if warning not in seen:
                normalized.append(warning)
                seen.add(warning)
        return normalized

    @model_validator(mode="after")
    def validate_unique_question_keys(self) -> "AssessmentModelOutput":
        keys = [question.client_key for question in self.questions]
        if len(keys) != len(set(keys)):
            raise ValueError("Question client keys must be unique.")
        return self


class AssessmentParseResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=(), extra="forbid")

    request_id: str
    title_suggestion: str | None = None
    questions: list[QuestionDraft]
    warnings: list[str]
    model_version: str
    prompt_version: str


class _GradeRequestBase(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    request_id: str = Field(min_length=1, max_length=64)
    submission_answer_id: int = Field(gt=0)
    rubric_items: list[RubricItem] = Field(
        min_length=1,
        max_length=GRADING_RUBRIC_MAX_ITEMS,
    )
    max_score: float = Field(
        gt=0,
        le=999_999.99,
        multiple_of=0.01,
    )

    @field_validator("request_id")
    @classmethod
    def strip_grade_request_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("request_id must not be blank.")
        return normalized

    @model_validator(mode="after")
    def validate_grade_rubric(self) -> "_GradeRequestBase":
        labels = [item.label for item in self.rubric_items]
        if len(labels) != len(set(labels)):
            raise ValueError("Rubric labels must be unique.")
        if any(
            len(item.label) > 200
            or len(item.description) > GRADING_RUBRIC_TEXT_MAX_CHARS
            for item in self.rubric_items
        ):
            raise ValueError("Rubric text exceeds the grading safety limit.")
        rubric_total = sum(
            (Decimal(str(item.max_score)) for item in self.rubric_items),
            start=Decimal("0"),
        )
        if rubric_total != Decimal(str(self.max_score)):
            raise ValueError("Rubric scores must equal max_score.")
        return self


class AttachmentGradeRequest(_GradeRequestBase):
    attachment_download_urls: list[HttpUrl] = Field(
        min_length=1,
        max_length=GRADING_ATTACHMENT_MAX_FILES,
    )

    @model_validator(mode="after")
    def validate_unique_attachment_urls(self) -> "AttachmentGradeRequest":
        normalized = [str(value) for value in self.attachment_download_urls]
        if len(normalized) != len(set(normalized)):
            raise ValueError("Attachment download URLs must be unique.")
        return self


class SubjectiveGradeRequest(_GradeRequestBase):
    answer_text: str = Field(min_length=1, max_length=GRADING_TEXT_MAX_CHARS)
    reference_answer: str | None = Field(
        default=None,
        max_length=GRADING_TEXT_MAX_CHARS,
    )

    @field_validator("answer_text")
    @classmethod
    def strip_answer_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("answer_text must not be blank.")
        return normalized

    @field_validator("reference_answer")
    @classmethod
    def strip_reference_answer(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None


class RubricGrade(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    label: str = Field(min_length=1, max_length=200)
    score: float = Field(ge=0, le=999_999.99, multiple_of=0.01)
    max_score: float = Field(
        gt=0,
        le=999_999.99,
        multiple_of=0.01,
    )
    evidence: list[str] = Field(
        max_length=GRADING_EVIDENCE_MAX_ITEMS,
    )
    confidence: float = Field(ge=0, le=1)
    comment: str = Field(min_length=1, max_length=GRADING_COMMENT_MAX_CHARS)

    @field_validator("label", "comment")
    @classmethod
    def strip_grade_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Rubric grade text must not be blank.")
        return normalized

    @field_validator("evidence")
    @classmethod
    def validate_evidence(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            item = value.strip()
            if not item or len(item) > GRADING_EVIDENCE_DISCLOSURE_MAX_CHARS:
                raise ValueError("Evidence must be a bounded non-blank excerpt.")
            if item in seen:
                raise ValueError("Evidence excerpts must be unique per rubric item.")
            normalized.append(item)
            seen.add(item)
        return normalized

    @model_validator(mode="after")
    def validate_item_score(self) -> "RubricGrade":
        if Decimal(str(self.score)) > Decimal(str(self.max_score)):
            raise ValueError("Rubric score cannot exceed max_score.")
        if Decimal(str(self.score)) > 0 and not self.evidence:
            raise ValueError("A positive rubric score requires supporting evidence.")
        return self


class GradeModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    suggested_score: float = Field(
        ge=0,
        le=999_999.99,
        multiple_of=0.01,
    )
    rubric_grades: list[RubricGrade] = Field(
        min_length=1,
        max_length=GRADING_RUBRIC_MAX_ITEMS,
    )
    overall_comment: str = Field(
        min_length=1,
        max_length=GRADING_OVERALL_COMMENT_MAX_CHARS,
    )
    warnings: list[str] = Field(max_length=20)

    @field_validator("overall_comment")
    @classmethod
    def strip_overall_comment(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("overall_comment must not be blank.")
        return normalized


class GradeProviderOutput(BaseModel):
    """Provider boundary before evidence and comments are sanitized."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    suggested_score: float = Field(
        ge=0,
        le=999_999.99,
        multiple_of=0.01,
    )
    # Keep the provider schema explicit. A free-form object here only told the
    # model that each row was "an object", so it routinely omitted confidence
    # and comment and returned evidence as a scalar string. The fully typed
    # rubric schema makes those fields required in the JSON instruction and at
    # the provider boundary.
    rubric_grades: list[RubricGrade] = Field(
        min_length=1,
        max_length=GRADING_RUBRIC_MAX_ITEMS,
    )
    overall_comment: str = Field(
        min_length=1,
        max_length=GRADING_OVERALL_COMMENT_MAX_CHARS,
        strict=True,
    )
    warnings: list[str] = Field(max_length=20, strict=True)

    @field_validator("warnings")
    @classmethod
    def validate_grade_warnings(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            if not isinstance(value, str):
                raise ValueError("Warnings must contain text values only.")
            warning = value.strip()
            if not warning or len(warning) > GRADING_WARNING_MAX_CHARS:
                raise ValueError("Warnings must be bounded non-blank text.")
            if warning not in seen:
                normalized.append(warning)
                seen.add(warning)
        return normalized


class AttachmentGradeResponse(BaseModel):
    model_config = ConfigDict(
        protected_namespaces=(),
        extra="forbid",
        allow_inf_nan=False,
    )

    request_id: str = Field(min_length=1, max_length=64)
    suggested_score: float = Field(
        ge=0,
        le=999_999.99,
        multiple_of=0.01,
    )
    rubric_grades: list[RubricGrade] = Field(
        min_length=1,
        max_length=GRADING_RUBRIC_MAX_ITEMS,
    )
    overall_comment: str = Field(
        min_length=1,
        max_length=GRADING_OVERALL_COMMENT_MAX_CHARS,
    )
    warnings: list[str] = Field(max_length=20)
    model_version: str = Field(min_length=1, max_length=200)
    prompt_version: str = Field(min_length=1, max_length=100)

    @field_validator("warnings")
    @classmethod
    def validate_response_warnings(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for value in values:
            warning = value.strip()
            if not warning or len(warning) > GRADING_WARNING_MAX_CHARS:
                raise ValueError("Warnings must be bounded non-blank text.")
            if warning not in seen:
                normalized.append(warning)
                seen.add(warning)
        return normalized
