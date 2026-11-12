from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class GradeQuestion(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    question_ref: str = Field(min_length=1, max_length=80)
    stem: str = Field(min_length=1, max_length=2000)
    accepted_answers: list[str] = Field(min_length=1, max_length=10)
    explanation: str = Field(min_length=1, max_length=4000)
    submitted_answer: str = Field(min_length=1, max_length=500)
    max_score: float = Field(gt=0, le=100, multiple_of=0.01)

    @field_validator("accepted_answers")
    @classmethod
    def valid_answers(cls, values: list[str]) -> list[str]:
        if any(not value.strip() or len(value) > 500 for value in values):
            raise ValueError("Reference answers must be nonblank and at most 500 characters.")
        return values

    @field_validator("question_ref", "stem", "explanation", "submitted_answer")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Grading text must not be blank.")
        return value.strip()


class TutorQuizGradeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str = Field(min_length=16, max_length=64)
    course_id: int = Field(strict=True, gt=0, le=9_223_372_036_854_775_807)
    activity_id: int = Field(strict=True, gt=0, le=9_223_372_036_854_775_807)
    questions: list[GradeQuestion] = Field(min_length=1, max_length=5)

    @model_validator(mode="after")
    def unique_questions(self):
        refs = [question.question_ref for question in self.questions]
        if len(refs) != len(set(refs)):
            raise ValueError("Question references must be unique.")
        return self


class TutorQuizGrade(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    question_ref: str = Field(min_length=1, max_length=80)
    score: float = Field(ge=0, le=100, multiple_of=0.01)
    max_score: float = Field(gt=0, le=100, multiple_of=0.01)
    confidence: float = Field(ge=0, le=1)
    feedback: str = Field(min_length=1, max_length=1000)

    @field_validator("feedback")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Feedback must not be blank.")
        return value.strip()


class TutorQuizGradeModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    grades: list[TutorQuizGrade] = Field(min_length=1, max_length=5)


class TutorQuizGradeResponse(TutorQuizGradeModelOutput):
    request_id: str = Field(min_length=16, max_length=64)
    model_version: str = Field(min_length=1, max_length=100)
    prompt_version: str = Field(min_length=1, max_length=100)
