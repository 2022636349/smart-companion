package com.education.agent.integration.ai;

import com.education.agent.assessment.model.CompareMode;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TestVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

public record AiAssessmentParseResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("title_suggestion") String titleSuggestion,
        List<QuestionDraft> questions,
        List<String> warnings,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("prompt_version") String promptVersion
) {
    public record QuestionDraft(
            @JsonProperty("client_key") String clientKey,
            @JsonProperty("question_type") QuestionType questionType,
            String stem,
            BigDecimal score,
            List<QuestionOptionDraft> options,
            @JsonProperty("reference_answer") JsonNode referenceAnswer,
            @JsonProperty("rubric_items") List<RubricItem> rubricItems,
            @JsonProperty("test_cases") List<TestCaseDraft> testCases,
            @JsonProperty("knowledge_point_candidates") List<KnowledgePointCandidate> knowledgePointCandidates,
            String confidence,
            List<String> warnings
    ) {
    }

    public record QuestionOptionDraft(
            String key,
            String content,
            @JsonProperty("is_correct") Boolean isCorrect
    ) {
    }

    public record RubricItem(
            String label,
            String description,
            @JsonProperty("max_score") BigDecimal maxScore
    ) {
    }

    public record TestCaseDraft(
            @JsonProperty("input_data") String inputData,
            @JsonProperty("expected_output") String expectedOutput,
            BigDecimal score,
            TestVisibility visibility,
            @JsonProperty("compare_mode") CompareMode compareMode
    ) {
    }

    public record KnowledgePointCandidate(
            @JsonProperty("knowledge_point_id") String knowledgePointId,
            String name,
            BigDecimal score
    ) {
    }
}
