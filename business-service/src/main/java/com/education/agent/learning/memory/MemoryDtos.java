package com.education.agent.learning.memory;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MemoryDtos {
  private MemoryDtos() {}
  public enum MemoryType { SESSION_SUMMARY, MISCONCEPTION, WEAK_EVIDENCE, MASTERED_EVIDENCE,
    LEARNING_PREFERENCE, LEARNING_GOAL, INTERVENTION_RESULT, TEACHER_NOTE }
  public enum MemoryStatus { ACTIVE, SUPERSEDED, DELETED, EXPIRED }
  public record MemoryView(Long id,Long studentId,Long courseId,MemoryType memoryType,String knowledgePointId,
      String knowledgePointName,String title,String content,String sourceType,Long sourceId,
      BigDecimal confidence,MemoryStatus status,int version,Long supersedesId,boolean editable,
      boolean deletable,Instant expiresAt,Instant createdAt,Instant updatedAt) {}
  public record Preferences(
      @NotNull Boolean longTermMemoryEnabled,
      @NotNull Boolean tutorPersonalizationEnabled,
      @NotNull Boolean allowWeaknessMemory,
      @NotNull Boolean allowPreferenceMemory,
      @Min(30) @Max(3650) Integer retentionDays) {
    public Preferences { retentionDays=retentionDays==null?365:retentionDays; }
  }
  public record RevisionRequest(@Size(max=200) String title,
      @NotBlank @Size(max=10000) String content,
      @NotBlank @Size(max=1000) String revisionReason) {}
  public record ClearRequest(List<MemoryType> memoryTypes,String knowledgePointId,
      Instant before,@NotNull Boolean confirm) {}
}
