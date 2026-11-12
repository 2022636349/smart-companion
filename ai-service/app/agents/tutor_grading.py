import asyncio

from pydantic import ValidationError

from app.contracts.tutor_grading import TutorQuizGradeModelOutput, TutorQuizGradeRequest, TutorQuizGradeResponse
from app.prompts.tutor_grading import TUTOR_QUIZ_GRADE_PROMPT_VERSION, build_grade_messages
from app.providers.base import ModelRequest, ModelResponseError, ModelTimeoutError
from app.providers.factory import create_model_provider


class TutorShortAnswerGrader:
    def __init__(self, *, provider_factory=create_model_provider):
        self.provider_factory = provider_factory

    async def grade(self, request: TutorQuizGradeRequest) -> TutorQuizGradeResponse:
        provider = self.provider_factory()
        try:
            async with asyncio.timeout(60):
                response = await provider.generate(ModelRequest(
                    messages=build_grade_messages(request),
                    prompt_version=TUTOR_QUIZ_GRADE_PROMPT_VERSION,
                    max_tokens=4000, temperature=0,
                    request_id=request.request_id, capability="TUTOR_SHORT_ANSWER_GRADE",
                ), output_model=TutorQuizGradeModelOutput)
                output = TutorQuizGradeModelOutput.model_validate(response.structured_content)
                expected = {question.question_ref: question for question in request.questions}
                refs = [grade.question_ref for grade in output.grades]
                if len(refs) != len(set(refs)) or set(refs) != set(expected):
                    raise ModelResponseError("Grading must return each requested question exactly once.")
                for grade in output.grades:
                    if (grade.max_score != expected[grade.question_ref].max_score
                            or grade.score > grade.max_score or grade.confidence < 0.75):
                        raise ModelResponseError("The grading suggestion is inconsistent or uncertain.")
                if response.metadata.prompt_version != TUTOR_QUIZ_GRADE_PROMPT_VERSION:
                    raise ModelResponseError("The grading prompt version does not match.")
                return TutorQuizGradeResponse(
                    request_id=request.request_id, grades=output.grades,
                    model_version=response.metadata.model,
                    prompt_version=TUTOR_QUIZ_GRADE_PROMPT_VERSION,
                )
        except TimeoutError as exc:
            raise ModelTimeoutError("Short-answer grading timed out.") from exc
        except ValidationError as exc:
            raise ModelResponseError("Short-answer grading returned invalid data.") from exc
        finally:
            await provider.aclose()
