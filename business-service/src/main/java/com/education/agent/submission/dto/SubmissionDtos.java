package com.education.agent.submission.dto;

import com.education.agent.assessment.dto.AssessmentDtos.AttachmentPolicy;
import com.education.agent.assessment.dto.AssessmentDtos.ProgrammingConfig;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TestVisibility;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.submission.model.GradingStatus;
import com.education.agent.submission.model.SubmissionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SubmissionDtos {
  private SubmissionDtos() {}

  public record QuestionOptionPaper(String key, String content, Integer position) {}

  public record PublicTestCase(Long id, String input, String expectedOutput, Integer position) {}

  public record QuestionPaper(
      Long id,
      QuestionType questionType,
      String stem,
      BigDecimal score,
      Integer position,
      List<QuestionOptionPaper> options,
      ProgrammingConfig programmingConfig,
      List<PublicTestCase> publicTestCases,
      AttachmentPolicy attachmentPolicy) {}

  public record AssessmentPaper(
      Long id,
      AssessmentType assessmentType,
      String title,
      String description,
      BigDecimal totalScore,
      Integer version,
      List<QuestionPaper> questions) {}

  public record ExamSession(
      String sessionToken,
      Instant serverTime,
      Instant deadlineAt,
      long remainingSeconds,
      int heartbeatIntervalSeconds,
      boolean requireFullscreen,
      boolean shouldAutoSubmit,
      boolean recordCopyPaste,
      boolean recordPageVisibility) {}

  public record SubmissionAnswer(
      Long id,
      Long questionId,
      List<String> selectedOptionKeys,
      String textAnswer,
      String sourceCode,
      List<FileObjectView> attachmentFiles,
      Instant savedAt,
      Instant submittedAt,
      BigDecimal autoScore,
      BigDecimal finalScore,
      GradingStatus gradingStatus,
      String teacherComment) {}

  public record Submission(
      Long id,
      Long assessmentId,
      Integer assessmentVersion,
      Long studentId,
      String studentName,
      SubmissionStatus status,
      GradingStatus gradingStatus,
      Integer attemptNo,
      Instant startedAt,
      Instant submittedAt,
      BigDecimal autoScore,
      BigDecimal finalScore,
      Instant resultReleasedAt,
      String overallComment,
      Boolean suspicious,
      List<SubmissionAnswer> answers,
      Instant createdAt,
      Instant updatedAt) {}

  public record SubmissionSession(
      Submission submission, AssessmentPaper paper, ExamSession examSession) {}

  public record SaveAnswerRequest(
      List<String> selectedOptionKeys,
      @Size(max = 100000) String textAnswer,
      @Size(max = 65536) String sourceCode,
      @Size(max = 5) List<Long> attachmentFileIds,
      @NotNull @Min(1) Long clientSaveSequence) {
    public SaveAnswerRequest {
      selectedOptionKeys = selectedOptionKeys == null ? List.of() : List.copyOf(selectedOptionKeys);
      attachmentFileIds = attachmentFileIds == null ? List.of() : List.copyOf(attachmentFileIds);
    }
  }

  public record SubmitRequest(Boolean confirm, String examSessionToken) {
    public SubmitRequest {
      confirm = confirm == null ? true : confirm;
    }
  }

  public record ExamHeartbeatRequest(
      @NotNull Long submissionId,
      @NotBlank String examSessionToken,
      @NotNull Instant clientTime,
      @NotNull @Min(0) Long lastSaveSequence,
      Boolean fullscreen,
      Boolean pageVisible) {}

  public record ExamHeartbeatData(
      Instant serverTime,
      Instant deadlineAt,
      long remainingSeconds,
      boolean shouldAutoSubmit,
      boolean sessionValid,
      long acceptedSaveSequence) {}

  public record AntiCheatEventInput(
      @NotBlank @Size(max = 128) String clientEventId,
      @NotBlank String eventType,
      @NotNull Instant occurredAt,
      Map<String, Object> details) {}

  public record AntiCheatEventsRequest(
      @NotNull Long submissionId,
      @NotBlank String examSessionToken,
      @NotNull @Size(min = 1, max = 100) List<@Valid AntiCheatEventInput> events) {}

  public record AntiCheatEvent(
      Long id,
      String clientEventId,
      String eventType,
      Instant occurredAt,
      Map<String, Object> details,
      String severity) {}

  public record AntiCheatSummary(
      Long assessmentId,
      Long studentId,
      long totalEventCount,
      long suspiciousSubmissionCount,
      Map<String, Long> countByType,
      List<AntiCheatEvent> events) {}

  public record CodeRunRequest(
      @NotBlank String contextType,
      @NotNull Long questionId,
      Long submissionId,
      Long practiceAttemptId,
      @NotBlank String language,
      @NotBlank @Size(max = 65536) String sourceCode) {}

  public record TestCaseRunResult(
      Long testCaseId,
      Integer position,
      TestVisibility visibility,
      String status,
      String input,
      String expectedOutput,
      String actualOutput,
      String errorMessage,
      Integer durationMs,
      BigDecimal score) {}

  public record CodeRunResult(
      Long runId,
      String status,
      Integer passedCount,
      Integer totalCount,
      BigDecimal score,
      Integer durationMs,
      String stdout,
      String stderr,
      List<TestCaseRunResult> testCases,
      String teachingFeedback) {}

  public record AnswerResult(
      Long questionId,
      BigDecimal score,
      BigDecimal maxScore,
      Boolean correct,
      List<String> correctOptionKeys,
      Object referenceAnswer,
      String teacherComment,
      String aiFeedback,
      CodeRunResult codeResult,
      List<Object> knowledgePoints) {}

  public record SubmissionResult(
      Long submissionId,
      Long assessmentId,
      BigDecimal totalScore,
      BigDecimal achievedScore,
      String overallComment,
      List<AnswerResult> answers,
      List<Object> weakKnowledgePoints,
      Instant releasedAt) {}
}
