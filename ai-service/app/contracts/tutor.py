from __future__ import annotations

import re
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.contracts.common import ContentBlock


MAX_TUTOR_HISTORY_MESSAGES = 20
MAX_TUTOR_HISTORY_MESSAGE_CHARS = 20_000
MAX_TUTOR_HISTORY_TOTAL_CHARS = 40_000
MAX_TUTOR_MESSAGE_CHARS = 10_000
MAX_ROUTER_STEPS = 2
MAX_TUTOR_SPECIALISTS = 3

TutorAgentMode = Literal["FIXED", "ROUTER", "SHADOW"]
TutorEffectiveMode = Literal["FIXED", "ROUTER", "FIXED_FALLBACK", "SHADOW"]
TutorTeachingMode = Literal["AUTO", "EXPLAIN", "QUIZ", "CODE", "VISUAL", "MIXED"]
TutorSpecialistAgent = Literal["EXPLANATION", "QUIZ", "CODE_LAB", "ANIMATION"]
RouterToolName = Literal[
    "graph.rag_context",
    "graph.search",
    "graph.prerequisites",
    "graph.recommend_next",
    "memory.search",
]
RouterFallbackReason = Literal[
    "ROUTER_OUTPUT_INVALID",
    "TOOL_NOT_ALLOWED",
    "TOOL_ARGUMENT_INVALID",
    "STEP_LIMIT_REACHED",
    "TOOL_TIMEOUT",
    "TOOL_FAILED",
    "NO_ROUTER_ACTION",
]
_REASON_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")
_REF_PATTERN = r"^[A-Za-z][A-Za-z0-9_-]{0,79}$"
_ENTITY_REF_PATTERN = r"^[A-Za-z][A-Za-z0-9_-]{0,39}$"


class TutorHistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(
        min_length=1,
        max_length=MAX_TUTOR_HISTORY_MESSAGE_CHARS,
    )

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("History content must not be blank.")
        return value


class TutorStudentContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    entry_source: Literal["GRAPH", "MISTAKE", "LEARNING_PATH", "MANUAL"]
    personalization_enabled: bool


class GraphMasteryInput(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    knowledge_point_id: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)

    @field_validator("knowledge_point_id")
    @classmethod
    def strip_knowledge_point_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("knowledge_point_id must not be blank.")
        return normalized


class TutorChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=64)
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    course_name: str = Field(min_length=1, max_length=100)
    enabled_activity_types: list[
        Literal["EXPLAIN", "QUIZ", "CODE", "VISUAL"]
    ] = Field(
        min_length=1,
        max_length=4,
        json_schema_extra={"uniqueItems": True},
    )
    user_id: int
    session_id: int | None = None
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    message: str = Field(min_length=1, max_length=MAX_TUTOR_MESSAGE_CHARS)
    history: list[TutorHistoryMessage] = Field(
        default_factory=list,
        max_length=MAX_TUTOR_HISTORY_MESSAGES,
    )
    student_context: TutorStudentContext | None = None
    agent_mode: TutorAgentMode = "FIXED"
    teaching_mode: TutorTeachingMode = "AUTO"
    mastery_context: list[GraphMasteryInput] = Field(default_factory=list, max_length=100)

    @field_validator("request_id", "course_name", "knowledge_point_id", "message")
    @classmethod
    def required_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Required tutor request text must not be blank.")
        return normalized

    @model_validator(mode="after")
    def history_must_fit_prompt_budget(self) -> TutorChatRequest:
        if "EXPLAIN" not in self.enabled_activity_types or len(
            set(self.enabled_activity_types)
        ) != len(self.enabled_activity_types):
            raise ValueError("Course activities must be unique and include EXPLAIN.")
        if (
            sum(len(item.content) for item in self.history)
            > MAX_TUTOR_HISTORY_TOTAL_CHARS
        ):
            raise ValueError(
                "Tutor history exceeds the 40000-character prompt limit."
            )
        mastery_ids = [item.knowledge_point_id for item in self.mastery_context]
        if len(mastery_ids) != len(set(mastery_ids)):
            raise ValueError("mastery_context knowledge points must be unique.")
        return self


class TutorRouterDecision(BaseModel):
    """Provider-visible route decision. It intentionally has no rationale/CoT field."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    action: RouterToolName | Literal["FINAL"]
    arguments: dict[str, Any] = Field(default_factory=dict, max_length=8)
    reason_code: str = Field(min_length=1, max_length=80)
    confidence: float = Field(ge=0, le=1)

    @field_validator("reason_code")
    @classmethod
    def validate_reason_code(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not _REASON_CODE_PATTERN.fullmatch(normalized):
            raise ValueError("reason_code must be a bounded uppercase machine code.")
        return normalized

    @model_validator(mode="after")
    def final_has_no_arguments(self) -> "TutorRouterDecision":
        if self.action == "FINAL" and self.arguments:
            raise ValueError("FINAL cannot contain tool arguments.")
        return self


class RouterToolInvocation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    step: int = Field(ge=1, le=MAX_ROUTER_STEPS)
    tool: RouterToolName
    reason_code: str = Field(min_length=1, max_length=80)
    status: Literal["SUCCEEDED", "REJECTED", "FAILED", "TIMED_OUT"]
    duration_ms: int = Field(ge=0, le=2_147_483_647)
    item_count: int = Field(ge=0, le=1_000)
    error_code: str | None = Field(default=None, max_length=80)

    @field_validator("reason_code")
    @classmethod
    def validate_reason_code(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not _REASON_CODE_PATTERN.fullmatch(normalized):
            raise ValueError("reason_code must be a bounded uppercase machine code.")
        return normalized

    @field_validator("error_code")
    @classmethod
    def strip_error_code(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip().upper()
        return normalized or None


class TutorAgentTrace(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requested_mode: TutorAgentMode
    effective_mode: TutorEffectiveMode
    fallback_reason: RouterFallbackReason | None = None
    tool_invocations: list[RouterToolInvocation] = Field(max_length=MAX_ROUTER_STEPS)
    total_steps: int = Field(ge=0, le=MAX_ROUTER_STEPS)

    @model_validator(mode="after")
    def validate_trace(self) -> "TutorAgentTrace":
        if self.total_steps != len(self.tool_invocations):
            raise ValueError("total_steps must equal tool_invocations length.")
        if self.effective_mode == "FIXED_FALLBACK" and self.fallback_reason is None:
            raise ValueError("FIXED_FALLBACK requires fallback_reason.")
        if self.effective_mode not in {"FIXED_FALLBACK", "SHADOW"} and self.fallback_reason:
            raise ValueError("fallback_reason is only valid for fallback or shadow traces.")
        return self


class TutorTeachingPlan(BaseModel):
    """Bounded specialist plan returned by the teaching planner."""

    model_config = ConfigDict(extra="forbid")

    goal: str = Field(min_length=1, max_length=500)
    modalities: list[TutorSpecialistAgent] = Field(max_length=MAX_TUTOR_SPECIALISTS)
    specialist_agents: list[TutorSpecialistAgent] = Field(
        max_length=MAX_TUTOR_SPECIALISTS
    )

    @field_validator("goal")
    @classmethod
    def strip_goal(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Teaching goal must not be blank.")
        return normalized

    @field_validator("modalities", "specialist_agents")
    @classmethod
    def specialist_lists_must_be_unique(
        cls, value: list[TutorSpecialistAgent]
    ) -> list[TutorSpecialistAgent]:
        if len(value) != len(set(value)):
            raise ValueError("Teaching plan specialists must be unique.")
        return value

    @model_validator(mode="after")
    def modalities_must_match_specialists(self) -> "TutorTeachingPlan":
        if self.modalities != self.specialist_agents:
            raise ValueError("modalities must match specialist_agents in order.")
        return self


class TutorSpecialistInvocation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agent: TutorSpecialistAgent
    status: Literal["SUCCEEDED", "FAILED", "TIMED_OUT", "SKIPPED"]
    duration_ms: int = Field(ge=0, le=2_147_483_647)
    artifact_count: int = Field(ge=0, le=3)
    error_code: str | None = Field(default=None, max_length=80)

    @field_validator("error_code")
    @classmethod
    def normalize_error_code(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip().upper()
        if not normalized:
            return None
        if not _REASON_CODE_PATTERN.fullmatch(normalized):
            raise ValueError("error_code must be a bounded uppercase machine code.")
        return normalized

    @model_validator(mode="after")
    def validate_status_payload(self) -> "TutorSpecialistInvocation":
        if self.status == "SUCCEEDED" and self.error_code is not None:
            raise ValueError("Successful specialist traces cannot contain error_code.")
        if self.status == "SKIPPED" and self.artifact_count != 0:
            raise ValueError("Skipped specialists cannot produce artifacts.")
        if self.status in {"FAILED", "TIMED_OUT"} and self.artifact_count > 1:
            raise ValueError("Failed specialists can return at most one safe fallback.")
        if self.status in {"FAILED", "TIMED_OUT"} and self.error_code is None:
            raise ValueError("Failed specialists require a sanitized error_code.")
        return self


class TutorChoiceOptionDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: str = Field(pattern=r"^[A-F]$")
    text: str = Field(min_length=1, max_length=1_000)

    @field_validator("text")
    @classmethod
    def strip_option_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Choice option text must not be blank.")
        return normalized


class TutorQuizQuestionDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    question_ref: str = Field(pattern=_REF_PATTERN)
    question_type: Literal[
        "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER"
    ]
    stem: str = Field(min_length=1, max_length=2_000)
    options: list[TutorChoiceOptionDraft] = Field(max_length=6)
    correct_option_keys: list[str] = Field(max_length=6)
    # These fields are private answer material.  The business service must never
    # include them in the pre-attempt activity response.
    accepted_answers: list[str] = Field(max_length=10)
    case_sensitive: bool
    explanation: str = Field(min_length=1, max_length=4_000)
    score: float = Field(gt=0, le=100)

    @field_validator("stem", "explanation")
    @classmethod
    def strip_question_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Quiz text must not be blank.")
        return normalized

    @field_validator("correct_option_keys")
    @classmethod
    def validate_correct_key_shape(cls, value: list[str]) -> list[str]:
        if any(re.fullmatch(r"[A-F]", key) is None for key in value):
            raise ValueError("Correct option keys must be A through F.")
        if len(value) != len(set(value)):
            raise ValueError("Correct option keys must be unique.")
        return value

    @field_validator("accepted_answers")
    @classmethod
    def validate_accepted_answers(cls, value: list[str]) -> list[str]:
        normalized = [item.strip() for item in value]
        if any(not item for item in normalized):
            raise ValueError("Accepted answers must not be blank.")
        if any(len(item) > 500 for item in normalized):
            raise ValueError("Accepted answers must be at most 500 characters.")
        return normalized

    @model_validator(mode="after")
    def validate_answer_against_options(self) -> "TutorQuizQuestionDraft":
        if self.question_type == "SHORT_ANSWER":
            if self.options or self.correct_option_keys:
                raise ValueError(
                    "A short-answer question cannot contain choice options or keys."
                )
            if not self.accepted_answers:
                raise ValueError("A short-answer question needs accepted answers.")
            normalized_answers = [
                item if self.case_sensitive else item.casefold()
                for item in self.accepted_answers
            ]
            if len(normalized_answers) != len(set(normalized_answers)):
                raise ValueError("Accepted answers must be unique.")
            return self

        if self.accepted_answers:
            raise ValueError("Choice questions cannot contain accepted answers.")
        if self.case_sensitive:
            raise ValueError("case_sensitive only applies to short-answer questions.")
        option_keys = [option.key for option in self.options]
        if len(option_keys) != len(set(option_keys)):
            raise ValueError("Choice option keys must be unique.")
        if len(option_keys) < 2:
            raise ValueError("Choice questions need at least two options.")
        if not self.correct_option_keys:
            raise ValueError("Choice questions need correct option keys.")
        if not set(self.correct_option_keys).issubset(option_keys):
            raise ValueError("Every correct option must exist in options.")
        if self.question_type == "SINGLE_CHOICE" and len(self.correct_option_keys) != 1:
            raise ValueError("A single-choice question needs exactly one answer.")
        if self.question_type == "MULTIPLE_CHOICE" and len(self.correct_option_keys) < 2:
            raise ValueError("A multiple-choice question needs at least two answers.")
        if self.question_type == "TRUE_FALSE":
            if option_keys != ["A", "B"] or len(self.correct_option_keys) != 1:
                raise ValueError(
                    "A true/false question needs ordered A/B options and one answer."
                )
        return self


class TutorQuizActivityDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    activity_ref: str = Field(pattern=_REF_PATTERN)
    activity_type: Literal["QUIZ"]
    title: str = Field(min_length=1, max_length=200)
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    difficulty: Literal["BASIC", "MEDIUM", "ADVANCED"]
    questions: list[TutorQuizQuestionDraft] = Field(min_length=1, max_length=5)

    @field_validator("title", "knowledge_point_id")
    @classmethod
    def strip_quiz_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Quiz activity text must not be blank.")
        return normalized

    @model_validator(mode="after")
    def question_refs_must_be_unique(self) -> "TutorQuizActivityDraft":
        refs = [question.question_ref for question in self.questions]
        if len(refs) != len(set(refs)):
            raise ValueError("Quiz question references must be unique.")
        return self


class TutorCodeTestCaseDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    case_ref: str = Field(pattern=_REF_PATTERN)
    input_data: str = Field(max_length=65_536)
    expected_output: str = Field(max_length=65_536)
    score: float = Field(gt=0, le=100)
    visibility: Literal["PUBLIC", "HIDDEN"]
    compare_mode: Literal[
        "EXACT", "TRIM_TRAILING_WHITESPACE", "FLOAT_TOLERANCE"
    ]


class TutorCodeActivityDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    activity_ref: str = Field(pattern=_REF_PATTERN)
    activity_type: Literal["CODE_LAB"]
    title: str = Field(min_length=1, max_length=200)
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    difficulty: Literal["BASIC", "MEDIUM", "ADVANCED"]
    language: Literal["PYTHON"]
    stem: str = Field(min_length=1, max_length=5_000)
    starter_code: str = Field(max_length=65_536)
    reference_solution: str = Field(min_length=1, max_length=65_536)
    test_cases: list[TutorCodeTestCaseDraft] = Field(min_length=2, max_length=10)

    @field_validator("title", "knowledge_point_id", "stem", "reference_solution")
    @classmethod
    def strip_code_activity_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Code activity text must not be blank.")
        return normalized

    @model_validator(mode="after")
    def validate_test_case_boundary(self) -> "TutorCodeActivityDraft":
        refs = [case.case_ref for case in self.test_cases]
        if len(refs) != len(set(refs)):
            raise ValueError("Code test case references must be unique.")
        visibilities = {case.visibility for case in self.test_cases}
        if visibilities != {"PUBLIC", "HIDDEN"}:
            raise ValueError("Code activities need public and hidden test cases.")
        fingerprints = [
            (case.input_data, case.expected_output) for case in self.test_cases
        ]
        if len(fingerprints) != len(set(fingerprints)):
            raise ValueError("Public and hidden test cases must not be duplicated.")
        return self


class TutorSceneEntityDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=_ENTITY_REF_PATTERN)
    kind: Literal["ARRAY", "NODE", "EDGE", "POINTER", "CODE_LINE", "TEXT"]
    label: str = Field(min_length=1, max_length=200)
    values: list[str] = Field(default_factory=list, max_length=30)
    from_id: str | None = None
    to_id: str | None = None

    @field_validator("label")
    @classmethod
    def strip_entity_label(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Scene entity labels must not be blank.")
        return normalized

    @field_validator("values")
    @classmethod
    def validate_entity_values(cls, value: list[str]) -> list[str]:
        if any(len(item) > 100 for item in value):
            raise ValueError("Scene entity values must be at most 100 characters.")
        return value

    @field_validator("from_id", "to_id")
    @classmethod
    def validate_optional_entity_ref(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if re.fullmatch(_ENTITY_REF_PATTERN, value) is None:
            raise ValueError("Scene entity references have an invalid shape.")
        return value


class TutorSceneStepDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    order: int = Field(ge=1, le=30)
    action: Literal[
        "HIGHLIGHT",
        "CLEAR_HIGHLIGHT",
        "MOVE_POINTER",
        "SET_VALUE",
        "SHOW",
        "HIDE",
        "FOCUS_CODE",
        "COMPARE",
        "ANNOTATE",
    ]
    target_ids: list[str] = Field(min_length=1, max_length=10)
    indices: list[Annotated[int, Field(strict=True, ge=-30, le=29)]] | None = Field(default=None, max_length=30)
    value: str | None = Field(default=None, max_length=500)
    caption: str = Field(min_length=1, max_length=1_000)
    duration_ms: int = Field(ge=200, le=10_000)

    @field_validator("indices")
    @classmethod
    def unique_indices(cls, value: list[int] | None) -> list[int] | None:
        if value is not None and len(value) != len(set(value)):
            raise ValueError("Scene indices must be unique.")
        return value

    @field_validator("target_ids")
    @classmethod
    def target_ids_must_be_unique_and_safe(cls, value: list[str]) -> list[str]:
        if len(value) != len(set(value)):
            raise ValueError("Scene step target identifiers must be unique.")
        if any(re.fullmatch(_ENTITY_REF_PATTERN, item) is None for item in value):
            raise ValueError("Scene step target identifiers have an invalid shape.")
        return value

    @field_validator("caption")
    @classmethod
    def strip_caption(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Scene step captions must not be blank.")
        return normalized


class TutorAnimationSceneDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scene_type: Literal[
        "ARRAY", "SEARCH", "SORT", "TREE", "GRAPH", "CODE_FLOW", "CONCEPT"
    ]
    entities: list[TutorSceneEntityDraft] = Field(min_length=1, max_length=30)
    steps: list[TutorSceneStepDraft] = Field(min_length=1, max_length=30)


class TutorAnimationActivityDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    activity_ref: str = Field(pattern=_REF_PATTERN)
    activity_type: Literal["ANIMATION"]
    title: str = Field(min_length=1, max_length=200)
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    scene: TutorAnimationSceneDraft

    @field_validator("title", "knowledge_point_id")
    @classmethod
    def strip_animation_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Animation activity text must not be blank.")
        return normalized


TutorActivityDraft = Annotated[
    TutorQuizActivityDraft | TutorCodeActivityDraft | TutorAnimationActivityDraft,
    Field(discriminator="activity_type"),
]


class CognitiveLabel(BaseModel):
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    status: Literal["MASTERED", "LEARNING", "WEAK", "UNKNOWN"]
    confidence: float = Field(ge=0, le=1)
    evidence: str = Field(min_length=1, max_length=2_000)

    @field_validator("knowledge_point_id", "evidence")
    @classmethod
    def label_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Cognitive label text must not be blank.")
        return normalized


class TutorSource(BaseModel):
    id: str = Field(min_length=1, max_length=200)
    node_id: str = Field(min_length=1, max_length=64)
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    title: str = Field(min_length=1, max_length=2_000)
    resource_id: int | None = None
    file_id: int | None = None
    chapter: str | None = Field(default=None, max_length=2_000)
    page_from: int | None = Field(default=None, ge=1)
    page_to: int | None = Field(default=None, ge=1)
    locator: str = Field(min_length=1, max_length=2_000)
    excerpt: str | None = Field(default=None, max_length=2_000)
    review_status: Literal["APPROVED"] = "APPROVED"
    rights_status: Literal["ALLOWED"]
    reviewed: Literal[True] = True
    confidence: float | None = Field(default=None, ge=0, le=1)

    @field_validator("id", "node_id", "title", "locator")
    @classmethod
    def source_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Tutor source text must not be blank.")
        return normalized


class TutorModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str = Field(min_length=1, max_length=20_000)
    blocks: list[ContentBlock] = Field(min_length=1, max_length=20)
    cited_source_ids: list[str] = Field(min_length=1, max_length=20)
    cognitive_labels: list[CognitiveLabel] = Field(max_length=20)
    suggested_next_step: str | None = Field(default=None, max_length=2_000)

    @field_validator("answer")
    @classmethod
    def answer_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Tutor answer must not be blank.")
        return normalized

    @field_validator("cited_source_ids")
    @classmethod
    def citations_must_be_non_blank_and_unique(cls, value: list[str]) -> list[str]:
        normalized = [source_id.strip() for source_id in value]
        if any(not source_id for source_id in normalized):
            raise ValueError("Cited source identifiers must not be blank.")
        if len(normalized) != len(set(normalized)):
            raise ValueError("Cited source identifiers must be unique.")
        return normalized

    @field_validator("suggested_next_step")
    @classmethod
    def optional_next_step_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @model_validator(mode="after")
    def explanation_cannot_forge_activity_references(self) -> "TutorModelOutput":
        block_ids = [block.block_id for block in self.blocks if block.block_id]
        if len(block_ids) != len(set(block_ids)):
            raise ValueError("Tutor explanation block identifiers must be unique.")
        if any(block.activity_ref is not None for block in self.blocks):
            raise ValueError("Only verified activity specialists may add activity_ref.")
        if any(block.type in {"quiz", "code_lab", "animation"} for block in self.blocks):
            raise ValueError("Interactive blocks require a verified activity draft.")
        return self


class TutorChatResponse(BaseModel):
    course_id: int = Field(strict=True, ge=1, le=9_223_372_036_854_775_807)
    model_config = ConfigDict(protected_namespaces=(), extra="forbid")

    request_id: str
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    answer: str = Field(min_length=1, max_length=20_000)
    blocks: list[ContentBlock] = Field(min_length=1, max_length=20)
    activities: list[TutorActivityDraft] = Field(max_length=3)
    teaching_plan: TutorTeachingPlan
    specialist_trace: list[TutorSpecialistInvocation] = Field(max_length=3)
    sources: list[TutorSource] = Field(min_length=1)
    cognitive_labels: list[CognitiveLabel]
    memory_ids_used: list[int] = Field(max_length=10)
    memory_index_epoch: str | None = Field(min_length=16, max_length=64)
    memory_rebuild_required: bool
    agent_trace: TutorAgentTrace
    suggested_next_step: str | None = None
    model_version: str = Field(min_length=1, max_length=100)
    prompt_version: str = Field(min_length=1, max_length=100)

    @field_validator(
        "request_id",
        "knowledge_point_id",
        "answer",
        "model_version",
        "prompt_version",
    )
    @classmethod
    def response_text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Required tutor response text must not be blank.")
        return normalized

    @model_validator(mode="after")
    def validate_activity_and_trace_integrity(self) -> "TutorChatResponse":
        block_ids = [block.block_id for block in self.blocks if block.block_id]
        if len(block_ids) != len(set(block_ids)):
            raise ValueError("Tutor content block identifiers must be unique.")
        activity_refs = [activity.activity_ref for activity in self.activities]
        if len(activity_refs) != len(set(activity_refs)):
            raise ValueError("Tutor activity references must be unique.")

        reference_blocks = [block for block in self.blocks if block.activity_ref]
        block_refs = [block.activity_ref for block in reference_blocks]
        if len(block_refs) != len(set(block_refs)):
            raise ValueError("Each activity may be referenced by only one content block.")
        if set(block_refs) != set(activity_refs):
            raise ValueError("Every activity must have exactly one matching content block.")

        activity_type_by_ref = {
            activity.activity_ref: activity.activity_type for activity in self.activities
        }
        expected_block_type = {
            "QUIZ": "quiz",
            "CODE_LAB": "code_lab",
            "ANIMATION": "animation",
        }
        for block in reference_blocks:
            activity_type = activity_type_by_ref.get(block.activity_ref)
            if activity_type is None or block.type != expected_block_type[activity_type]:
                raise ValueError("Activity reference block type does not match its draft.")
            if block.block_id is None:
                raise ValueError("Activity reference blocks require stable block_id values.")

        trace_agents = [item.agent for item in self.specialist_trace]
        if len(trace_agents) != len(set(trace_agents)):
            raise ValueError("Specialist trace agents must be unique.")
        if trace_agents != self.teaching_plan.specialist_agents:
            raise ValueError("Specialist trace must follow the teaching plan exactly.")
        if sum(item.artifact_count for item in self.specialist_trace) != len(
            self.activities
        ):
            raise ValueError("Specialist artifact counts must equal activity count.")
        return self
