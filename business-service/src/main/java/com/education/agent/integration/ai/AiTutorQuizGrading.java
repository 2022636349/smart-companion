package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class AiTutorQuizGrading {
  private AiTutorQuizGrading() {}

  public record Question(
      @JsonProperty("question_ref") String questionRef,
      String stem,
      @JsonProperty("accepted_answers") List<String> acceptedAnswers,
      String explanation,
      @JsonProperty("submitted_answer") String submittedAnswer,
      @JsonProperty("max_score") BigDecimal maxScore) {}

  public record Request(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("course_id") Long courseId,
      @JsonProperty("activity_id") Long activityId,
      List<Question> questions) {}

  public record Grade(
      @JsonProperty("question_ref") String questionRef,
      BigDecimal score,
      @JsonProperty("max_score") BigDecimal maxScore,
      BigDecimal confidence,
      String feedback) {}

  public record Response(
      @JsonProperty("request_id") String requestId,
      List<Grade> grades,
      @JsonProperty("model_version") String modelVersion,
      @JsonProperty("prompt_version") String promptVersion) {}
}
