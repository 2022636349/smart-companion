package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class AiGradeRequest {
  private AiGradeRequest() {}

  public record RubricItem(
      String label, String description, @JsonProperty("max_score") BigDecimal maxScore) {}

  public record Attachment(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("submission_answer_id") Long submissionAnswerId,
      @JsonProperty("attachment_download_urls") List<String> attachmentDownloadUrls,
      @JsonProperty("rubric_items") List<RubricItem> rubricItems,
      @JsonProperty("max_score") BigDecimal maxScore) {}

  public record Subjective(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("submission_answer_id") Long submissionAnswerId,
      @JsonProperty("answer_text") String answerText,
      @JsonProperty("reference_answer") String referenceAnswer,
      @JsonProperty("rubric_items") List<RubricItem> rubricItems,
      @JsonProperty("max_score") BigDecimal maxScore) {}
}
