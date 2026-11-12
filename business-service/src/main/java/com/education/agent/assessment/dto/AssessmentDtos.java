package com.education.agent.assessment.dto;

import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.assessment.model.CompareMode;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.assessment.model.TestVisibility;
import com.education.agent.file.dto.FileObjectView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AssessmentDtos {

    private AssessmentDtos() {
    }

    public record AntiCheatPolicy(
            Boolean enabled,
            Boolean requireFullscreen,
            Boolean singleDeviceOnly,
            @Min(5) @Max(120) Integer heartbeatIntervalSeconds,
            @Min(1) Integer suspiciousEventThreshold,
            Boolean recordCopyPaste,
            Boolean recordPageVisibility
    ) {
        public AntiCheatPolicy {
            enabled = enabled == null ? false : enabled;
            requireFullscreen = requireFullscreen == null ? false : requireFullscreen;
            singleDeviceOnly = singleDeviceOnly == null ? true : singleDeviceOnly;
            heartbeatIntervalSeconds = heartbeatIntervalSeconds == null ? 15 : heartbeatIntervalSeconds;
            suspiciousEventThreshold = suspiciousEventThreshold == null ? 5 : suspiciousEventThreshold;
            recordCopyPaste = recordCopyPaste == null ? true : recordCopyPaste;
            recordPageVisibility = recordPageVisibility == null ? true : recordPageVisibility;
        }

        public static AntiCheatPolicy defaults() {
            return new AntiCheatPolicy(null, null, null, null, null, null, null);
        }
    }

    public record AssessmentSettings(
            Boolean autoGradeEnabled,
            Boolean autoReleaseEnabled,
            Boolean aiAttachmentGradeEnabled,
            Boolean allowLateSubmission,
            Boolean showScoreAfterRelease,
            Boolean showCorrectAnswerAfterRelease,
            Boolean showQuestionFeedbackAfterRelease,
            Boolean shuffleQuestions,
            Boolean shuffleOptions,
            @Valid AntiCheatPolicy antiCheatPolicy
    ) {
        public AssessmentSettings {
            autoGradeEnabled = autoGradeEnabled == null ? true : autoGradeEnabled;
            autoReleaseEnabled = autoReleaseEnabled == null ? false : autoReleaseEnabled;
            aiAttachmentGradeEnabled = aiAttachmentGradeEnabled == null ? false : aiAttachmentGradeEnabled;
            allowLateSubmission = allowLateSubmission == null ? false : allowLateSubmission;
            showScoreAfterRelease = showScoreAfterRelease == null ? true : showScoreAfterRelease;
            showCorrectAnswerAfterRelease = showCorrectAnswerAfterRelease == null ? true : showCorrectAnswerAfterRelease;
            showQuestionFeedbackAfterRelease = showQuestionFeedbackAfterRelease == null ? true : showQuestionFeedbackAfterRelease;
            shuffleQuestions = shuffleQuestions == null ? false : shuffleQuestions;
            shuffleOptions = shuffleOptions == null ? false : shuffleOptions;
            antiCheatPolicy = antiCheatPolicy == null ? AntiCheatPolicy.defaults() : antiCheatPolicy;
        }

        public static AssessmentSettings defaults() {
            return new AssessmentSettings(null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record QuestionOptionDraft(
            Long id,
            @NotBlank @Size(max = 20) String key,
            @NotBlank String content,
            @NotNull Boolean isCorrect,
            @NotNull @Min(1) Integer position
    ) {
    }

    public record AnswerContent(
            List<@NotBlank String> selectedOptionKeys,
            String textAnswer,
            @Size(max = 65536) String sourceCode,
            List<@NotNull Long> attachmentFileIds
    ) {
    }

    public record RubricItem(
            Long id,
            @NotBlank @Size(max = 64) String clientKey,
            @NotBlank @Size(max = 200) String label,
            @NotBlank @Size(max = 5000) String description,
            @NotNull
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("999999.99")
            @Digits(integer = 6, fraction = 2)
            BigDecimal maxScore,
            @NotNull @Min(1) Integer position
    ) {
    }

    public record ProgrammingConfig(
            @NotBlank String language,
            @NotNull String starterCode,
            @NotNull @Min(100) @Max(10000) Integer timeLimitMs,
            @NotNull @Min(16) @Max(512) Integer memoryLimitMb,
            @NotNull CompareMode compareMode,
            @DecimalMin("0") BigDecimal floatTolerance
    ) {
    }

    public record ProgrammingTestCaseDraft(
            Long id,
            @NotBlank @Size(max = 64) String clientKey,
            @NotNull @Size(max = 65536) String input,
            @NotNull @Size(max = 65536) String expectedOutput,
            @NotNull
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("999999.99")
            @Digits(integer = 6, fraction = 2)
            BigDecimal score,
            @NotNull TestVisibility visibility,
            @NotNull @Min(1) Integer position
    ) {
    }

    public record AttachmentPolicy(
            @NotEmpty List<@NotBlank String> allowedExtensions,
            @NotNull @Min(1) @Max(5) Integer maxFileCount,
            @NotNull @Min(1) @Max(50) Integer maxFileSizeMb
    ) {
    }

    public record KnowledgePointBinding(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 200) String name
    ) {
    }

    public record QuestionUpsertRequest(
            Long id,
            @NotBlank @Size(max = 64) String clientKey,
            @NotNull QuestionType questionType,
            @NotBlank String stem,
            @NotNull
            @DecimalMin(value = "0", inclusive = false)
            @DecimalMax("999999.99")
            @Digits(integer = 6, fraction = 2)
            BigDecimal score,
            @NotNull @Min(1) Integer position,
            @Valid List<QuestionOptionDraft> options,
            @Valid AnswerContent referenceAnswer,
            @Valid List<RubricItem> rubricItems,
            @Valid ProgrammingConfig programmingConfig,
            @Valid @Size(max = 100) List<ProgrammingTestCaseDraft> testCases,
            @Valid AttachmentPolicy attachmentPolicy,
            @Valid List<KnowledgePointBinding> knowledgePoints,
            Boolean aiGenerated
    ) {
        public QuestionUpsertRequest {
            options = options == null ? List.of() : List.copyOf(options);
            rubricItems = rubricItems == null ? List.of() : List.copyOf(rubricItems);
            testCases = testCases == null ? List.of() : List.copyOf(testCases);
            knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
            aiGenerated = aiGenerated == null ? false : aiGenerated;
        }
    }

    public record QuestionDraft(
            Long id,
            String clientKey,
            QuestionType questionType,
            String stem,
            BigDecimal score,
            Integer position,
            List<QuestionOptionDraft> options,
            AnswerContent referenceAnswer,
            List<RubricItem> rubricItems,
            ProgrammingConfig programmingConfig,
            List<ProgrammingTestCaseDraft> testCases,
            AttachmentPolicy attachmentPolicy,
            List<KnowledgePointBinding> knowledgePoints,
            Boolean aiGenerated,
            Instant updatedAt
    ) {
    }

    public record AssessmentUpsertRequest(
            @NotNull AssessmentType assessmentType,
            @NotNull Long classId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            Instant startAt,
            Instant dueAt,
            @Min(1) @Max(1440) Integer durationMinutes,
            @NotNull @Valid AssessmentSettings settings,
            @NotNull @Valid List<QuestionUpsertRequest> questions
    ) {
        public AssessmentUpsertRequest {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public record AssessmentDeadlineUpdateRequest(
            @NotNull Instant dueAt
    ) {
    }

    public record ReorderQuestionsRequest(
            @NotEmpty List<@NotNull Long> questionIds
    ) {
        public ReorderQuestionsRequest {
            questionIds = questionIds == null ? List.of() : List.copyOf(questionIds);
        }
    }

    public record AssessmentSummary(
            Long id,
            AssessmentType assessmentType,
            String title,
            Long classId,
            String className,
            Long creatorId,
            AssessmentStatus status,
            BigDecimal totalScore,
            Instant startAt,
            Instant dueAt,
            Integer durationMinutes,
            Integer questionCount,
            String mySubmissionStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AssessmentDetail(
            Long id,
            AssessmentType assessmentType,
            String title,
            Long classId,
            String className,
            Long creatorId,
            AssessmentStatus status,
            BigDecimal totalScore,
            Instant startAt,
            Instant dueAt,
            Integer durationMinutes,
            Integer questionCount,
            String mySubmissionStatus,
            Instant createdAt,
            Instant updatedAt,
            String description,
            AssessmentSettings settings,
            Integer currentVersion,
            List<QuestionDraft> questions
    ) {
    }

    public record ImportCandidateQuestion(
            Long id,
            String clientKey,
            QuestionType questionType,
            String stem,
            BigDecimal score,
            Integer position,
            List<QuestionOptionDraft> options,
            AnswerContent referenceAnswer,
            List<RubricItem> rubricItems,
            ProgrammingConfig programmingConfig,
            List<ProgrammingTestCaseDraft> testCases,
            AttachmentPolicy attachmentPolicy,
            List<KnowledgePointBinding> knowledgePoints,
            Boolean aiGenerated,
            String confidence,
            @DecimalMin("0") @DecimalMax("1") BigDecimal confidenceScore,
            List<String> warnings
    ) {
    }

    public record ImportTask(
            Long id,
            Long assessmentId,
            FileObjectView sourceFile,
            TaskStatus status,
            Integer progressPercent,
            String titleSuggestion,
            List<ImportCandidateQuestion> candidateQuestions,
            List<String> warnings,
            String errorCode,
            String errorMessage,
            String modelVersion,
            String promptVersion,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    /** Internal MySQL payload for an AI import task; it is never accepted from the browser. */
    public record ImportTaskPayload(
            String titleSuggestion,
            List<ImportCandidateQuestion> candidateQuestions,
            List<String> warnings
    ) {
        public ImportTaskPayload {
            candidateQuestions = candidateQuestions == null ? List.of() : List.copyOf(candidateQuestions);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
