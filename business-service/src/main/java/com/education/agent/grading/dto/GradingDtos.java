package com.education.agent.grading.dto;

import com.education.agent.assessment.dto.AssessmentDtos.AssessmentDetail;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionDraft;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.assessment.model.TestVisibility;
import com.education.agent.grading.model.GradingSource;
import com.education.agent.submission.dto.SubmissionDtos.CodeRunResult;
import com.education.agent.submission.dto.SubmissionDtos.Submission;
import com.education.agent.submission.dto.SubmissionDtos.SubmissionAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class GradingDtos {
  private GradingDtos() {}

  public record RubricGrade(
      @NotBlank @Size(max = 200) String label,
      @NotNull @DecimalMin("0") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
          BigDecimal score,
      @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("999999.99")
          @Digits(integer = 6, fraction = 2) BigDecimal maxScore,
      @NotNull @Size(max = 20) List<@Size(max = 1000) String> evidence,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
      @Size(max = 2000) String comment) {
    public RubricGrade {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record GradingRecord(
      Long id,
      GradingSource source,
      BigDecimal suggestedScore,
      BigDecimal finalScore,
      List<RubricGrade> rubricGrades,
      BigDecimal confidence,
      String modelVersion,
      String promptVersion,
      Long gradedBy,
      Instant createdAt,
      Long acceptedAiRecordId) {}

  public record GradingAnswer(
      QuestionDraft question,
      SubmissionAnswer answer,
      List<GradingRecord> records,
      CodeRunResult codeResult) {}

  public record GradingDetail(
      Submission submission, AssessmentDetail assessment, List<GradingAnswer> gradingAnswers) {}

  public record ManualGradeRequest(
      @NotNull @DecimalMin("0") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
          BigDecimal score,
      @Size(max = 5000) String comment,
      @Valid @Size(max = 100) List<RubricGrade> rubricGrades) {
    public ManualGradeRequest {
      rubricGrades = rubricGrades == null ? List.of() : List.copyOf(rubricGrades);
    }
  }

  public record CommentRequest(@NotNull @Size(max = 5000) String comment) {}

  public record BulkCommentItem(
      @NotNull Long submissionId, @NotNull @Size(max = 5000) String comment) {}

  public record BulkCommentsRequest(
      @NotEmpty @Size(max = 100) List<@Valid BulkCommentItem> items) {
    public BulkCommentsRequest {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record ReleaseResultsRequest(Instant releasedAt) {}

  public record AsyncTask(
      Long taskId,
      String taskType,
      TaskStatus status,
      Integer progressPercent,
      String resourceType,
      Long resourceId,
      Map<String, Object> result,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {}

  public record DistributionBucket(
      String label, BigDecimal fromInclusive, BigDecimal toExclusive, long count) {}

  public record OptionDistribution(String optionKey, long selectedCount, BigDecimal selectedRate) {}

  public record TestCaseStatistics(
      Long testCaseId, TestVisibility visibility, long passCount, BigDecimal passRate) {}

  public record QuestionStatistics(
      Long questionId,
      QuestionType questionType,
      long answeredCount,
      long correctCount,
      long incorrectCount,
      long unansweredCount,
      BigDecimal correctRate,
      BigDecimal averageScore,
      List<OptionDistribution> optionDistribution,
      List<TestCaseStatistics> testCaseStatistics,
      List<String> commonErrors) {}

  public record KnowledgeMastery(
      String knowledgePointId,
      String knowledgePointName,
      BigDecimal score,
      BigDecimal confidence,
      String status,
      long evidenceCount,
      Instant updatedAt) {}

  public record AssessmentStatistics(
      Long assessmentId,
      long assignedCount,
      long inProgressCount,
      long submittedCount,
      long lateCount,
      long unsubmittedCount,
      long gradedCount,
      long releasedCount,
      BigDecimal averageScore,
      BigDecimal highestScore,
      BigDecimal lowestScore,
      BigDecimal passRate,
      List<DistributionBucket> scoreDistribution,
      List<QuestionStatistics> questionSummaries,
      List<KnowledgeMastery> weakKnowledgePoints) {}
}
