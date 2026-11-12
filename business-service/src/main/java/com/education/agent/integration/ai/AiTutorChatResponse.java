package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorChatResponse(
        @JsonProperty("course_id") Long courseId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("knowledge_point_id") String knowledgePointId,
        String answer,
        List<AiTutorContentBlock> blocks,
        List<AiTutorActivityDraft> activities,
        @JsonProperty("teaching_plan") AiTutorTeachingPlan teachingPlan,
        @JsonProperty("specialist_trace") List<AiTutorSpecialistInvocation> specialistTrace,
        List<Map<String, Object>> sources,
        @JsonProperty("cognitive_labels") List<AiCognitiveLabel> cognitiveLabels,
        @JsonProperty("memory_ids_used") List<Long> memoryIdsUsed,
        @JsonProperty("memory_index_epoch") String memoryIndexEpoch,
        @JsonProperty("memory_rebuild_required") Boolean memoryRebuildRequired,
        @JsonProperty("suggested_next_step") String suggestedNextStep,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("agent_trace") AiTutorAgentTrace agentTrace
) {
    public AiTutorChatResponse(
            Long courseId,
            String requestId, String knowledgePointId, String answer,
            List<AiTutorContentBlock> blocks, List<Map<String, Object>> sources,
            List<AiCognitiveLabel> cognitiveLabels, String suggestedNextStep,
            String modelVersion, String promptVersion
    ) {
        this(courseId, requestId, knowledgePointId, answer, blocks, List.of(),
                new AiTutorTeachingPlan("Legacy compatible explanation", List.of("EXPLANATION"), List.of("EXPLANATION")),
                List.of(new AiTutorSpecialistInvocation("EXPLANATION", "SUCCEEDED", 0, 0, null)),
                sources, cognitiveLabels,
                List.of(), "memory-index-epoch-test", false, suggestedNextStep, modelVersion, promptVersion,
                AiTutorAgentTrace.fixed());
    }

    public AiTutorChatResponse(
            Long courseId,
            String requestId, String knowledgePointId, String answer,
            List<AiTutorContentBlock> blocks, List<Map<String, Object>> sources,
            List<AiCognitiveLabel> cognitiveLabels, List<Long> memoryIdsUsed,
            String memoryIndexEpoch, Boolean memoryRebuildRequired, String suggestedNextStep,
            String modelVersion, String promptVersion) {
        this(courseId, requestId, knowledgePointId, answer, blocks, List.of(),
                new AiTutorTeachingPlan("Legacy compatible explanation", List.of("EXPLANATION"), List.of("EXPLANATION")),
                List.of(new AiTutorSpecialistInvocation("EXPLANATION", "SUCCEEDED", 0, 0, null)),
                sources, cognitiveLabels, memoryIdsUsed,
                memoryIndexEpoch, memoryRebuildRequired, suggestedNextStep, modelVersion, promptVersion,
                AiTutorAgentTrace.fixed());
    }

    public AiTutorChatResponse(
            Long courseId,
            String requestId, String knowledgePointId, String answer,
            List<AiTutorContentBlock> blocks, List<Map<String, Object>> sources,
            List<AiCognitiveLabel> cognitiveLabels, List<Long> memoryIdsUsed,
            String memoryIndexEpoch, Boolean memoryRebuildRequired, String suggestedNextStep,
            String modelVersion, String promptVersion, AiTutorAgentTrace agentTrace) {
        this(courseId, requestId, knowledgePointId, answer, blocks, List.of(),
                new AiTutorTeachingPlan("Legacy compatible explanation", List.of("EXPLANATION"), List.of("EXPLANATION")),
                List.of(new AiTutorSpecialistInvocation("EXPLANATION", "SUCCEEDED", 0, 0, null)),
                sources, cognitiveLabels, memoryIdsUsed, memoryIndexEpoch, memoryRebuildRequired,
                suggestedNextStep, modelVersion, promptVersion, agentTrace);
    }

    public AiTutorChatResponse(
            Long courseId,
            String requestId, String knowledgePointId, String answer,
            List<AiTutorContentBlock> blocks, List<AiTutorActivityDraft> activities,
            AiTutorTeachingPlan teachingPlan, List<AiTutorSpecialistInvocation> specialistTrace,
            List<Map<String, Object>> sources, List<AiCognitiveLabel> cognitiveLabels,
            List<Long> memoryIdsUsed, String memoryIndexEpoch, Boolean memoryRebuildRequired,
            String suggestedNextStep, String modelVersion, String promptVersion) {
        this(courseId, requestId, knowledgePointId, answer, blocks, activities, teachingPlan, specialistTrace,
                sources, cognitiveLabels, memoryIdsUsed, memoryIndexEpoch, memoryRebuildRequired,
                suggestedNextStep, modelVersion, promptVersion, AiTutorAgentTrace.fixed());
    }
}
