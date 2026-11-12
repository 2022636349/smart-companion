package com.education.agent.tutor.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.integration.ai.AiTutorQuizGrading;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TutorShortAnswerGradingService {
  private final AiServiceClient ai;

  public TutorShortAnswerGradingService(AiServiceClient ai) { this.ai = ai; }

  public static boolean matches(String submitted, String expected, Boolean caseSensitive) {
    if (submitted == null) return false;
    return Boolean.TRUE.equals(caseSensitive)
        ? submitted.trim().equals(expected.trim())
        : submitted.trim().toLowerCase(Locale.ROOT).equals(expected.trim().toLowerCase(Locale.ROOT));
  }

  /** Invoked before acquiring the activity row lock; null means no model call was needed. */
  public AiTutorQuizGrading.Response review(
      Long courseId, Long activityId, AiTutorActivityDraft draft, Map<String, List<String>> answers) {
    Set<String> known = new HashSet<>();
    List<AiTutorQuizGrading.Question> pending = new ArrayList<>();
    for (var question : draft.questions()) {
      known.add(question.questionRef());
      List<String> submitted = answers.getOrDefault(question.questionRef(), List.of());
      if ("SHORT_ANSWER".equals(question.questionType())) {
        if (submitted.size() > 1) throw validation("简答题只能提交一个答案");
        if (submitted.isEmpty() || Boolean.TRUE.equals(question.caseSensitive())) continue;
        String candidate = submitted.get(0);
        if (question.acceptedAnswers().stream().anyMatch(value -> matches(candidate, value, false))) continue;
        pending.add(new AiTutorQuizGrading.Question(question.questionRef(), question.stem(),
            question.acceptedAnswers(), question.explanation(), candidate, question.score()));
      } else {
        Set<String> optionKeys = new HashSet<>();
        question.options().forEach(option -> optionKeys.add(option.key()));
        if (!optionKeys.containsAll(submitted)) throw validation("提交包含未知选项");
        if (!"MULTIPLE_CHOICE".equals(question.questionType()) && submitted.size() > 1) {
          throw validation("单选或判断题只能选择一个选项");
        }
      }
    }
    if (!known.containsAll(answers.keySet())) throw validation("提交包含未知题目");
    if (pending.isEmpty()) return null;
    String requestId = "tutor-grade-" + UUID.randomUUID().toString().replace("-", "");
    var request = new AiTutorQuizGrading.Request(requestId, courseId, activityId, List.copyOf(pending));
    var response = ai.suggestTutorQuizGrade(request);
    validate(response, request);
    return response;
  }

  private void validate(AiTutorQuizGrading.Response response, AiTutorQuizGrading.Request request) {
    if (response == null || !request.requestId().equals(response.requestId())
        || response.grades() == null || response.grades().size() != request.questions().size()
        || blankOrLong(response.modelVersion(), 100) || blankOrLong(response.promptVersion(), 100)) {
      throw invalid();
    }
    Set<String> seen = new HashSet<>();
    for (var grade : response.grades()) {
      if (grade == null || !seen.add(grade.questionRef())) throw invalid();
      var expected = request.questions().stream()
          .filter(question -> question.questionRef().equals(grade.questionRef())).findFirst().orElseThrow(this::invalid);
      if (grade.score() == null || grade.score().signum() < 0
          || grade.score().compareTo(expected.maxScore()) > 0 || grade.score().stripTrailingZeros().scale() > 2
          || grade.maxScore() == null || grade.maxScore().compareTo(expected.maxScore()) != 0
          || grade.confidence() == null || grade.confidence().compareTo(new BigDecimal("0.75")) < 0
          || grade.confidence().compareTo(BigDecimal.ONE) > 0 || blankOrLong(grade.feedback(), 1000)) {
        throw invalid();
      }
    }
  }

  private boolean blankOrLong(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
  private BusinessException validation(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
  private BusinessException invalid() {
    return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "暂时无法可靠评阅这份答案，请稍后重试；本次未计分");
  }
}
