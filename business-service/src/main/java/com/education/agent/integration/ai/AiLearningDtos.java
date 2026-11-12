package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AiLearningDtos {
  private AiLearningDtos() {}

  public record Evidence(
      @JsonProperty("source_type") String sourceType,
      @JsonProperty("source_id") Long sourceId,
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String content,
      @JsonProperty("occurred_at") Instant occurredAt) {}

  public record ProfileRequest(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("course_id") Long courseId,
      @JsonProperty("user_id") Long userId,
      @JsonProperty("previous_summary") String previousSummary,
      List<Evidence> evidence) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemoryCandidate(
      @JsonProperty("memory_type") String memoryType,
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String title,
      String content,
      BigDecimal confidence,
      Boolean editable,
      Boolean deletable) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ResourceSuggestion(
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String reason,
      @JsonProperty("preferred_resource_types") List<String> preferredResourceTypes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProfileResponse(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("course_id") Long courseId,
      String summary,
      @JsonProperty("cognitive_labels") List<AiCognitiveLabel> cognitiveLabels,
      @JsonProperty("memory_candidates") List<MemoryCandidate> memoryCandidates,
      @JsonProperty("resource_suggestions") List<ResourceSuggestion> resourceSuggestions,
      @JsonProperty("model_version") String modelVersion,
      @JsonProperty("prompt_version") String promptVersion) {}

  public record MemoryUpsertRequest(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("memory_id") Long memoryId,
      @JsonProperty("student_id") Long studentId,
      @JsonProperty("course_id") Long courseId,
      @JsonProperty("memory_type") String memoryType,
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String content,
      @JsonProperty("content_hash") String contentHash,
      String status,
      Integer version,
      Integer generation,
      @JsonProperty("source_type") String sourceType,
      @JsonProperty("source_id") Long sourceId,
      @JsonProperty("created_at") Instant createdAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemoryUpsertResponse(
      @JsonProperty("request_id") String requestId,
      String collection,
      @JsonProperty("memory_id") Long memoryId,
      Integer generation,
      Boolean indexed,
      @JsonProperty("embedding_model") String embeddingModel,
      @JsonProperty("memory_index_epoch") String memoryIndexEpoch,
      @JsonProperty("memory_rebuild_required") Boolean memoryRebuildRequired) {}

  public record MemorySearchRequest(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("student_id") Long studentId,
      @JsonProperty("course_id") Long courseId,
      String query,
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      @JsonProperty("memory_types") List<String> memoryTypes,
      Integer limit,
      @JsonProperty("min_score") BigDecimal minScore) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemorySearchHit(
      @JsonProperty("memory_id") Long memoryId,
      @JsonProperty("student_id") Long studentId,
      @JsonProperty("course_id") Long courseId,
      @JsonProperty("memory_type") String memoryType,
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String content,
      BigDecimal score,
      Integer version,
      Integer generation,
      @JsonProperty("source_type") String sourceType,
      @JsonProperty("source_id") Long sourceId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MemorySearchResponse(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("student_id") Long studentId,
      @JsonProperty("course_id") Long courseId,
      List<MemorySearchHit> items,
      String collection,
      @JsonProperty("memory_index_epoch") String memoryIndexEpoch,
      @JsonProperty("memory_rebuild_required") Boolean memoryRebuildRequired) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DeleteResponse(
      @JsonProperty("request_id") String requestId,
      String collection,
      @JsonProperty("memory_id") Long memoryId,
      String mode,
      Integer generation,
      @JsonProperty("deleted_count") Integer deletedCount,
      @JsonProperty("memory_index_epoch") String memoryIndexEpoch,
      @JsonProperty("memory_rebuild_required") Boolean memoryRebuildRequired) {}
}
