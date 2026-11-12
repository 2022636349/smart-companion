package com.education.agent.learning.dto;

import com.education.agent.knowledge.dto.KnowledgeNodeView;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentSummary;
import com.education.agent.tutor.dto.KnowledgePointBinding;
import com.education.agent.tutor.dto.CognitiveLabelView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class LearningDtos {
  private LearningDtos() {}

  public record MasteryEvidence(String sourceType, Long sourceId, BigDecimal score,
      BigDecimal weight, Instant occurredAt) {}
  public record KnowledgeMastery(String knowledgePointId, String knowledgePointName,
      BigDecimal score, BigDecimal confidence, String status, int evidenceCount,
      List<MasteryEvidence> evidence, Instant updatedAt) {}
  public record TrendPoint(LocalDate date, BigDecimal value) {}
  public record KnowledgeRecommendation(KnowledgeNodeView knowledgePoint, String reason,
      int priority, List<String> evidence, String graphVersion) {}
  public record LearningDashboard(int pendingHomeworkCount, int pendingExamCount,
      int completedAssessmentCount, BigDecimal averageScore, List<AssessmentSummary> recentAssessments,
      List<KnowledgeMastery> weakKnowledgePoints, List<KnowledgeRecommendation> recommendations) {}
  public record LearningAnalysis(Long studentId, List<TrendPoint> assessmentScoreTrend,
      List<KnowledgeMastery> mastery, List<KnowledgeMastery> strengths,
      List<KnowledgeMastery> weaknesses, List<CognitiveLabelView> recentCognitiveLabels) {}
  public record Mistake(Long assessmentId, String assessmentTitle, Long submissionId,
      Long questionId, String stem, Object myAnswer, Object correctAnswer, String feedback,
      List<KnowledgePointBinding> knowledgePoints, Instant releasedAt) {}
  public record LearningPathStep(int order, KnowledgeNodeView knowledgePoint, String reason,
      String status, List<String> evidence, String graphVersion,
      List<Long> recommendedResourceIds) {}
  public record LearningEvent(Long id, String eventType, String title, String summary,
      List<String> knowledgePointIds, String relatedType, Long relatedId,
      BigDecimal score, Instant occurredAt, Long courseId) {}
}
