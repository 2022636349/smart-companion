package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Strict union carrier for the V2.7 Tutor activity draft contract. Spring validates the
 * discriminator-specific fields before any part of this model is persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorActivityDraft(
    @JsonProperty("activity_ref") String activityRef,
    @JsonProperty("activity_type") String activityType,
    String title,
    @JsonProperty("knowledge_point_id") String knowledgePointId,
    String difficulty,
    List<QuizQuestionDraft> questions,
    String language,
    String stem,
    @JsonProperty("starter_code") String starterCode,
    @JsonProperty("reference_solution") String referenceSolution,
    @JsonProperty("test_cases") List<CodeTestCaseDraft> testCases,
    AnimationSceneDraft scene) {

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ChoiceOptionDraft(String key, String text) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record QuizQuestionDraft(
      @JsonProperty("question_ref") String questionRef,
      @JsonProperty("question_type") String questionType,
      String stem,
      List<ChoiceOptionDraft> options,
      @JsonProperty("correct_option_keys") List<String> correctOptionKeys,
      String explanation,
      BigDecimal score,
      @JsonProperty("accepted_answers") List<String> acceptedAnswers,
      @JsonProperty("case_sensitive") Boolean caseSensitive) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record CodeTestCaseDraft(
      @JsonProperty("case_ref") String caseRef,
      @JsonProperty("input_data") String inputData,
      @JsonProperty("expected_output") String expectedOutput,
      BigDecimal score,
      String visibility,
      @JsonProperty("compare_mode") String compareMode) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record SceneEntityDraft(
      String id,
      String kind,
      String label,
      List<String> values,
      @JsonProperty("from_id") String fromId,
      @JsonProperty("to_id") String toId) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record SceneStepDraft(
      Integer order,
      String action,
      @JsonProperty("target_ids") List<String> targetIds,
      String value,
      String caption,
      @JsonProperty("duration_ms") Integer durationMs,
      List<Integer> indices) {
    public SceneStepDraft(Integer order, String action, List<String> targetIds, String value,
        String caption, Integer durationMs) {
      this(order, action, targetIds, value, caption, durationMs, null);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record AnimationSceneDraft(
      @JsonProperty("scene_type") String sceneType,
      List<SceneEntityDraft> entities,
      List<SceneStepDraft> steps) {}
}
