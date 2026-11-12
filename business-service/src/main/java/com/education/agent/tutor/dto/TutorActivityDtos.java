package com.education.agent.tutor.dto;

import com.education.agent.submission.dto.SubmissionDtos.CodeRunResult;
import com.education.agent.tutor.model.TutorActivityStatus;
import com.education.agent.tutor.model.TutorActivityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TutorActivityDtos {
  private TutorActivityDtos() {}

  public record ChoiceOption(String key, String text) {}

  public record QuizQuestion(
      String questionId,
      String questionType,
      String stem,
      List<ChoiceOption> options,
      BigDecimal maxScore) {
    public QuizQuestion {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  public record QuizPayload(List<QuizQuestion> questions) {
    public QuizPayload {
      questions = questions == null ? List.of() : List.copyOf(questions);
    }
  }

  public record CodePublicTest(
      String testCaseId, String input, String expectedOutput, BigDecimal score) {}

  public record CodeLabPayload(
      String language,
      String stem,
      String starterCode,
      List<CodePublicTest> publicTests) {
    public CodeLabPayload {
      publicTests = publicTests == null ? List.of() : List.copyOf(publicTests);
    }
  }

  public record SceneEntity(
      String id,
      String kind,
      String label,
      List<String> values,
      String fromId,
      String toId) {
    public SceneEntity {
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  public record SceneStep(
      Integer order,
      String action,
      List<String> targetIds,
      String value,
      String caption,
      Integer durationMs,
      List<Integer> indices) {
    public SceneStep {
      targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
      indices = indices == null ? List.of() : List.copyOf(indices);
    }
  }

  public record AnimationScene(
      String sceneType, List<SceneEntity> entities, List<SceneStep> steps) {
    public AnimationScene {
      entities = entities == null ? List.of() : List.copyOf(entities);
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  public record QuestionResult(
      String questionId,
      List<String> selectedOptionKeys,
      List<String> correctOptionKeys,
      String submittedAnswer,
      List<String> correctAnswers,
      boolean correct,
      BigDecimal score,
      BigDecimal maxScore,
      String explanation) {
    public QuestionResult {
      selectedOptionKeys = selectedOptionKeys == null ? List.of() : List.copyOf(selectedOptionKeys);
      correctOptionKeys = correctOptionKeys == null ? List.of() : List.copyOf(correctOptionKeys);
      correctAnswers = correctAnswers == null ? List.of() : List.copyOf(correctAnswers);
    }
  }

  public record ActivityAttempt(
      Long id,
      Long activityId,
      String status,
      BigDecimal score,
      BigDecimal maxScore,
      List<QuestionResult> questionResults,
      CodeRunResult codeResult,
      String aiAnalysis,
      BigDecimal masteryBefore,
      BigDecimal masteryAfter,
      BigDecimal masteryDelta,
      Instant submittedAt) {
    public ActivityAttempt {
      questionResults = questionResults == null ? List.of() : List.copyOf(questionResults);
    }
  }

  public record Activity(
      Long id,
      Long sessionId,
      Long messageId,
      TutorActivityType activityType,
      String title,
      String knowledgePointId,
      TutorActivityStatus status,
      QuizPayload quiz,
      CodeLabPayload codeLab,
      AnimationScene animation,
      ActivityAttempt latestAttempt,
      Instant createdAt) {}

  public record QuizAttemptRequest(
      @NotNull @Size(min = 1, max = 5) Map<String, List<String>> answers) {}

  public record CodeSubmissionRequest(
      @NotBlank @Size(max = 65536) String sourceCode) {}
}
