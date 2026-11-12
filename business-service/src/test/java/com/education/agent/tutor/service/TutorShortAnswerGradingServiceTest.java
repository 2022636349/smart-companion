package com.education.agent.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.QuizQuestionDraft;
import com.education.agent.integration.ai.AiTutorQuizGrading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TutorShortAnswerGradingServiceTest {
  final AiServiceClient ai = mock(AiServiceClient.class);
  final TutorShortAnswerGradingService service = new TutorShortAnswerGradingService(ai);

  AiTutorActivityDraft draft(boolean strict) {
    return new AiTutorActivityDraft("ref", "QUIZ", "练习", "kp", "BASIC", List.of(
        new QuizQuestionDraft("q1", "SHORT_ANSWER", "Python 的特点？", List.of(), List.of(), "可读性便于理解。",
            new BigDecimal("25"), List.of("可读性", "readability"), strict)), null, null, null, null, null, null);
  }

  @Test void exactAlternativesAndStrictIdentifiersDoNotRequireModel() {
    assertThat(service.review(1L, 2L, draft(false), Map.of("q1", List.of("  READABILITY  ")))).isNull();
    assertThat(service.review(1L, 2L, draft(true), Map.of("q1", List.of("READABILITY")))).isNull();
    assertThat(TutorShortAnswerGradingService.matches("READABILITY", "readability", true)).isFalse();
    verifyNoInteractions(ai);
  }

  @Test void unknownQuestionAndMultipleShortAnswersFailBeforeModel() {
    for (var answers : List.of(Map.of("unknown", List.of("高可读性")), Map.of("q1", List.of("可读性", "readability")))) {
      assertThatThrownBy(() -> service.review(1L, 2L, draft(false), answers)).isInstanceOf(BusinessException.class);
    }
    verifyNoInteractions(ai);
  }

  @Test void validatesEachResponseBoundaryBeforeAcceptingSuggestion() {
    for (String invalid : List.of("null", "request", "duplicate", "unknown", "score", "precision", "max", "confidence", "feedback", "model")) {
      doAnswer(invocation -> {
        AiTutorQuizGrading.Request request = invocation.getArgument(0);
        if (invalid.equals("null")) return null;
        var grade = new AiTutorQuizGrading.Grade(invalid.equals("unknown") ? "x" : "q1",
            new BigDecimal(invalid.equals("score") ? "26" : invalid.equals("precision") ? "0.001" : "25"),
            new BigDecimal(invalid.equals("max") ? "30" : "25"),
            new BigDecimal(invalid.equals("confidence") ? "0.74" : "0.96"), invalid.equals("feedback") ? " " : "表达正确。");
        return new AiTutorQuizGrading.Response(invalid.equals("request") ? "wrong" : request.requestId(),
            invalid.equals("duplicate") ? List.of(grade, grade) : List.of(grade),
            invalid.equals("model") ? "m".repeat(101) : "test-model", "tutor-short-answer-grade-v1");
      }).when(ai).suggestTutorQuizGrade(any());
      assertThatThrownBy(() -> service.review(1L, 2L, draft(false), Map.of("q1", List.of("高可读性"))))
          .as(invalid).isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    }
  }
}
