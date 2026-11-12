package com.education.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.AnimationSceneDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.ChoiceOptionDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.CodeTestCaseDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.QuizQuestionDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.SceneEntityDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.SceneStepDraft;
import com.education.agent.integration.ai.AiTutorAgentTrace;
import com.education.agent.integration.ai.AiTutorChatRequest;
import com.education.agent.integration.ai.AiTutorChatResponse;
import com.education.agent.integration.ai.AiTutorQuizGrading;
import com.education.agent.integration.ai.AiTutorContentBlock;
import com.education.agent.integration.ai.AiTutorSpecialistInvocation;
import com.education.agent.integration.ai.AiTutorTeachingPlan;
import com.education.agent.integration.ai.SandboxRunRequest;
import com.education.agent.integration.ai.SandboxRunResponse;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.dto.TutorActivityDtos.CodeSubmissionRequest;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizAttemptRequest;
import com.education.agent.tutor.dto.TutorSessionCreateRequest;
import com.education.agent.tutor.model.TutorEntrySource;
import com.education.agent.tutor.model.TutorTeachingMode;
import com.education.agent.tutor.service.TutorActivityService;
import com.education.agent.tutor.service.TutorService;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "education.graph-ingestion.scan-ms=3600000",
    "education.graph-ingestion.initial-delay-ms=3600000",
    "education.memory.outbox-scan-ms=3600000",
    "education.memory.outbox-initial-delay-ms=3600000",
    "education.profile.summary-scan-ms=3600000",
    "education.profile.summary-initial-delay-ms=3600000",
    "education.assessment.lifecycle-scan-ms=3600000"
})
class CycleEightTutorActivityIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired TutorService tutor;
  @Autowired TutorActivityService activities;
  @Autowired ObjectMapper json;
  @MockBean AiServiceClient ai;
  @MockBean KnowledgeServiceClient graph;

  @BeforeEach
  void setUp() {
    reset(ai, graph);
    when(graph.ragContext(1L, "kp-cycle8")).thenReturn(graphContext());
  }

  @Test
  void quizModePersistsOnlyPublicDraftThenRevealsDeterministicResultAndUpdatesMastery() throws Exception {
    AuthenticatedUser student = student("c8_quiz");
    var session = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    when(ai.tutorChat(any())).thenAnswer(invocation -> quizResponse(invocation.getArgument(0)));

    var reply = tutor.sendMessage(
        student, session.id(), "cycle8-quiz-message-key-0001", "请给我一道题",
        "cycle8-quiz-request-0001", TutorTeachingMode.QUIZ);

    ArgumentCaptor<AiTutorChatRequest> request = ArgumentCaptor.forClass(AiTutorChatRequest.class);
    verify(ai).tutorChat(request.capture());
    assertThat(request.getValue().teachingMode()).isEqualTo("QUIZ");
    assertThat(reply.assistantMessage().blocks()).hasSize(2);
    var activityBlock = reply.assistantMessage().blocks().get(1);
    assertThat(activityBlock.type().name()).isEqualTo("QUIZ");
    assertThat(activityBlock.activityId()).isNotNull();
    assertThat(activityBlock.blockId()).startsWith("activity-block-");

    var publicActivity = activities.get(student, activityBlock.activityId());
    String publicJson = json.writeValueAsString(publicActivity);
    assertThat(publicJson).contains("变量保存什么").contains("一个名称")
        .doesNotContain("correctOptionKeys", "explanation", "referenceSolution", "hidden");
    assertThat(jdbc.queryForObject(
        "SELECT teaching_plan_json FROM edu_tutor_message WHERE id=?",
        String.class, reply.assistantMessage().id())).contains("EXPLANATION", "QUIZ");

    var attempt = activities.submitQuiz(
        student, activityBlock.activityId(), "cycle8-quiz-attempt-key-0001",
        new QuizAttemptRequest(Map.of("q1", List.of("B"))));
    assertThat(attempt.score()).isEqualByComparingTo("0.00");
    assertThat(attempt.questionResults()).singleElement().satisfies(result -> {
      assertThat(result.correct()).isFalse();
      assertThat(result.correctOptionKeys()).containsExactly("A");
      assertThat(result.explanation()).contains("名称");
    });
    assertThat(attempt.masteryAfter()).isNotNull();
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_tutor_activity_attempt WHERE activity_id=?",
        Integer.class, activityBlock.activityId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_learning_evidence WHERE student_id=? AND source_type='PRACTICE'",
        Integer.class, student.id())).isEqualTo(1);

    var replay = activities.submitQuiz(
        student, activityBlock.activityId(), "cycle8-quiz-attempt-key-0001",
        new QuizAttemptRequest(Map.of("q1", List.of("B"))));
    assertThat(replay.id()).isEqualTo(attempt.id());
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_tutor_activity_attempt WHERE activity_id=?",
        Integer.class, activityBlock.activityId())).isEqualTo(1);
    assertThatThrownBy(() -> activities.submitQuiz(
        student, activityBlock.activityId(), "cycle8-quiz-attempt-key-0001",
        new QuizAttemptRequest(Map.of("q1", List.of("A")))))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    assertThatThrownBy(() -> activities.submitQuiz(
        student, activityBlock.activityId(), "cycle8-quiz-attempt-key-0002",
        new QuizAttemptRequest(Map.of("q1", List.of("A")))))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_RESOURCE_STATE));
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_learning_evidence WHERE student_id=? AND source_type='PRACTICE'",
        Integer.class, student.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_idempotency_record WHERE user_id=? AND idempotency_key=?",
        Integer.class, student.id(), "cycle8-quiz-attempt-key-0002")).isZero();

    AuthenticatedUser other = student("c8_quiz_other");
    assertThatThrownBy(() -> activities.get(other, activityBlock.activityId()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @Test
  void shortAnswerUsesTrimmedOptionalCaseMatchingAndKeepsAcceptedAnswersPrivate() throws Exception {
    AuthenticatedUser student = student("c8_short_answer");
    var session = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    when(ai.tutorChat(any())).thenAnswer(invocation -> shortAnswerResponse(invocation.getArgument(0)));

    var reply = tutor.sendMessage(student, session.id(), "cycle8-short-message-key-0001",
        "给我一道简答题", "cycle8-short-request-0001", TutorTeachingMode.QUIZ);
    Long activityId = reply.assistantMessage().blocks().get(1).activityId();
    assertThat(jsonValue(activities.get(student, activityId)))
        .doesNotContain("acceptedAnswers", "caseSensitive", "correctAnswers");

    var attempt = activities.submitQuiz(student, activityId, "cycle8-short-attempt-key-0001",
        new QuizAttemptRequest(Map.of("short1", List.of("  Python  "))));
    assertThat(attempt.score()).isEqualByComparingTo("10.00");
    assertThat(attempt.questionResults()).singleElement().satisfies(result -> {
      assertThat(result.correct()).isTrue();
      assertThat(result.submittedAnswer()).isEqualTo("Python");
      assertThat(result.correctAnswers()).containsExactly("Python");
    });
    verify(ai, never()).suggestTutorQuizGrade(any());
  }

  @Test
  void equivalentShortAnswerIsReviewedOutsideTransactionAndSavedOnce() {
    AuthenticatedUser student = student("c9_semantic_equivalent");
    Long activityId = readabilityActivity(student);
    when(ai.suggestTutorQuizGrade(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      AiTutorQuizGrading.Request request = invocation.getArgument(0);
      assertThat(request.courseId()).isEqualTo(1L);
      assertThat(request.activityId()).isEqualTo(activityId);
      assertThat(request.questions().get(0).submittedAnswer()).isEqualTo("高可读性");
      return reviewed(request, "25", "高可读性表达准确，与可读性意思一致。");
    });
    var input = new QuizAttemptRequest(Map.of("short1", List.of("高可读性")));
    var result = activities.submitQuiz(student, activityId, "c9-semantic-equivalent-0001", input);
    assertThat(result.score()).isEqualByComparingTo("25");
    assertThat(result.questionResults().get(0).correct()).isTrue();
    assertThat(result.questionResults().get(0).explanation()).contains("意思一致");
    var replay = activities.submitQuiz(student, activityId, "c9-semantic-equivalent-0001", input);
    assertThat(replay.id()).isEqualTo(result.id());
    verify(ai).suggestTutorQuizGrade(any());
    assertThat(jdbc.queryForObject("SELECT answer_json FROM edu_tutor_activity_attempt WHERE activity_id=?",
        String.class, activityId)).contains("semanticGrading", "test-grader", "高可读性");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_evidence WHERE student_id=? AND source_type='PRACTICE'",
        Integer.class, student.id())).isEqualTo(1);
  }

  @Test
  void semanticPartialAndWrongScoresAreNotPromotedToFullCredit() {
    for (String score : List.of("12.5", "0")) {
      AuthenticatedUser student = student("c9_semantic_score_" + score.replace('.', '_'));
      Long activityId = readabilityActivity(student);
      doAnswer(invocation -> reviewed(invocation.getArgument(0), score, "请再注意代码的可读性。"))
          .when(ai).suggestTutorQuizGrade(any());
      var result = activities.submitQuiz(student, activityId, "c9-semantic-score-key-0001",
          new QuizAttemptRequest(Map.of("short1", List.of("可读性差"))));
      assertThat(result.score()).isEqualByComparingTo(score);
      assertThat(result.questionResults().get(0).correct()).isFalse();
    }
  }

  @Test
  void unavailableSemanticReviewDoesNotSaveZeroAndSameKeyCanRetry() {
    AuthenticatedUser student = student("c9_semantic_retry");
    Long activityId = readabilityActivity(student);
    var input = new QuizAttemptRequest(Map.of("short1", List.of("代码容易阅读和理解")));
    when(ai.suggestTutorQuizGrade(any())).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "timeout"));
    assertThatThrownBy(() -> activities.submitQuiz(student, activityId, "c9-semantic-retry-key-0001", input))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_activity_attempt WHERE activity_id=?",
        Integer.class, activityId)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_evidence WHERE student_id=? AND source_type='PRACTICE'",
        Integer.class, student.id())).isZero();
    doAnswer(invocation -> reviewed(invocation.getArgument(0), "25", "意思一致。"))
        .when(ai).suggestTutorQuizGrade(any());
    assertThat(activities.submitQuiz(student, activityId, "c9-semantic-retry-key-0001", input).score()).isEqualByComparingTo("25");
  }

  private Long readabilityActivity(AuthenticatedUser student) {
    var session = tutor.create(student, new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    doAnswer(invocation -> {
      var draft = new AiTutorActivityDraft("readability_ref", "QUIZ", "可读性练习", "kp-cycle8", "BASIC",
          List.of(new QuizQuestionDraft("short1", "SHORT_ANSWER", "Python 强调用什么特点让代码更容易理解？",
              List.of(), List.of(), "Python 强调可读性。", new BigDecimal("25"), List.of("可读性", "代码可读性", "readability"), false)),
          null, null, null, null, null, null);
      return response(invocation.getArgument(0), draft, "QUIZ");
    }).when(ai).tutorChat(any());
    return tutor.sendMessage(student, session.id(), "c9-semantic-message-key-0001", "给我练习题",
        "c9-semantic-request-0001", TutorTeachingMode.QUIZ).assistantMessage().blocks().get(1).activityId();
  }

  private AiTutorQuizGrading.Response reviewed(AiTutorQuizGrading.Request request, String score, String feedback) {
    return new AiTutorQuizGrading.Response(request.requestId(), List.of(new AiTutorQuizGrading.Grade(
        "short1", new BigDecimal(score), new BigDecimal("25"), new BigDecimal("0.96"), feedback)),
        "test-grader", "tutor-short-answer-grade-v1");
  }

  @Test
  void codeLabUsesPublicTestsForRunAndHiddenTestsForScoredSubmissionWithoutLeakingThem() {
    AuthenticatedUser student = student("c8_code");
    var session = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    when(ai.tutorChat(any())).thenAnswer(invocation -> codeResponse(invocation.getArgument(0)));
    when(ai.runSandbox(any())).thenAnswer(invocation -> acceptedSandbox(invocation.getArgument(0)));

    var reply = tutor.sendMessage(
        student, session.id(), "cycle8-code-message-key-0001", "给我代码练习",
        "cycle8-code-request-0001", TutorTeachingMode.CODE);
    Long activityId = reply.assistantMessage().blocks().get(1).activityId();
    var publicActivity = activities.get(student, activityId);
    assertThat(publicActivity.codeLab().publicTests()).singleElement();
    assertThat(jsonValue(publicActivity)).doesNotContain("secret", "referenceSolution", "reference_solution");

    var run = activities.runCode(student, activityId,
        new CodeSubmissionRequest("print(input())"));
    assertThat(run.testCases()).singleElement().satisfies(result ->
        assertThat(result.visibility().name()).isEqualTo("PUBLIC"));

    var submitted = activities.submitCode(
        student, activityId, "cycle8-code-attempt-key-0001",
        new CodeSubmissionRequest("print(input())"));
    assertThat(submitted.score()).isEqualByComparingTo("100.00");
    assertThat(submitted.codeResult().testCases()).hasSize(2);
    assertThat(submitted.codeResult().testCases().get(1).visibility().name()).isEqualTo("HIDDEN");
    assertThat(submitted.codeResult().testCases().get(1).input()).isNull();
    assertThat(submitted.codeResult().testCases().get(1).expectedOutput()).isNull();
    assertThat(submitted.codeResult().testCases().get(1).actualOutput()).isNull();
    assertThat(submitted.codeResult().testCases().get(1).errorMessage()).isNull();
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_tutor_code_run WHERE activity_id=?",
        Integer.class, activityId)).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_learning_evidence WHERE student_id=? AND source_type='PRACTICE'",
        Integer.class, student.id())).isEqualTo(1);
    verify(ai, never()).explainCodeResult(any());
  }

  @Test
  void animationIsDeclarativeAndActiveContentIsRejectedBeforeAnyMessageOrActivityPersists() {
    AuthenticatedUser student = student("c8_animation");
    var session = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    when(ai.tutorChat(any())).thenAnswer(invocation ->
        animationResponse(invocation.getArgument(0), "比较中点", "检查中间元素"));

    var reply = tutor.sendMessage(
        student, session.id(), "cycle8-animation-key-0001", "动画讲解",
        "cycle8-animation-request-0001", TutorTeachingMode.VISUAL);
    var animation = activities.get(
        student, reply.assistantMessage().blocks().get(1).activityId()).animation();
    assertThat(animation.entities()).singleElement().satisfies(entity ->
        assertThat(entity.kind()).isEqualTo("ARRAY"));
    assertThat(animation.steps()).singleElement().satisfies(step ->
        assertThat(step.action()).isEqualTo("HIGHLIGHT"));

    var badSession = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    org.mockito.Mockito.doAnswer(invocation ->
        animationResponse(invocation.getArgument(0), "<script>alert(1)</script>", "危险脚本"))
        .when(ai).tutorChat(any());
    assertThatThrownBy(() -> tutor.sendMessage(
        student, badSession.id(), "cycle8-animation-key-0002", "不安全动画",
        "cycle8-animation-request-0002", TutorTeachingMode.VISUAL))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",
        Integer.class, badSession.id())).isZero();
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_tutor_activity WHERE session_id=?",
        Integer.class, badSession.id())).isZero();
  }

  @Test
  void acceptsSkippedOrUnavailableSpecialistsWithoutPersistingActivitiesAndRejectsMismatches() {
    AuthenticatedUser student = student("c8_trace_contract");

    var skippedSession = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    when(ai.tutorChat(any())).thenAnswer(invocation -> responseWithoutActivity(
        invocation.getArgument(0), "QUIZ", "SKIPPED", "CONTENT_BLOCK_LIMIT"));
    var skippedReply = tutor.sendMessage(student, skippedSession.id(),
        "cycle8-trace-skipped-key-0001", "给我一道题", "cycle8-trace-skipped-request-0001",
        TutorTeachingMode.QUIZ);
    assertThat(skippedReply.assistantMessage().blocks()).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_activity WHERE session_id=?",
        Integer.class, skippedSession.id())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",
        Integer.class, skippedSession.id())).isEqualTo(2);

    var unavailableSession = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    org.mockito.Mockito.doAnswer(invocation -> responseWithoutActivity(
        invocation.getArgument(0), "CODE_LAB", "TIMED_OUT", "CODE_LAB_UNAVAILABLE"))
        .when(ai).tutorChat(any());
    var unavailableReply = tutor.sendMessage(student, unavailableSession.id(),
        "cycle8-trace-unavailable-key-0001", "给我代码练习", "cycle8-trace-unavailable-request-0001",
        TutorTeachingMode.CODE);
    assertThat(unavailableReply.assistantMessage().blocks()).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_activity WHERE session_id=?",
        Integer.class, unavailableSession.id())).isZero();

    var maliciousSession = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    org.mockito.Mockito.doAnswer(invocation -> responseWithoutActivity(
        invocation.getArgument(0), "QUIZ", "FAILED", "CODE_LAB_UNAVAILABLE"))
        .when(ai).tutorChat(any());
    assertThatThrownBy(() -> tutor.sendMessage(student, maliciousSession.id(),
        "cycle8-trace-malicious-key-0001", "给我一道题", "cycle8-trace-malicious-request-0001",
        TutorTeachingMode.QUIZ)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",
        Integer.class, maliciousSession.id())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_activity WHERE session_id=?",
        Integer.class, maliciousSession.id())).isZero();

    var badSkippedSession = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-cycle8", TutorEntrySource.MANUAL, null));
    org.mockito.Mockito.doAnswer(invocation -> responseWithoutActivity(
        invocation.getArgument(0), "QUIZ", "SKIPPED", "QUIZ_UNAVAILABLE"))
        .when(ai).tutorChat(any());
    assertThatThrownBy(() -> tutor.sendMessage(student, badSkippedSession.id(),
        "cycle8-trace-bad-skipped-key-0001", "给我一道题", "cycle8-trace-bad-skipped-request-0001",
        TutorTeachingMode.QUIZ)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",
        Integer.class, badSkippedSession.id())).isZero();
  }

  private AiTutorChatResponse quizResponse(AiTutorChatRequest request) {
    AiTutorActivityDraft activity = new AiTutorActivityDraft(
        "quiz_ref", "QUIZ", "变量随堂自测", "kp-cycle8", "BASIC",
        List.of(new QuizQuestionDraft(
            "q1", "SINGLE_CHOICE", "变量保存什么？",
            List.of(new ChoiceOptionDraft("A", "一个名称"),
                new ChoiceOptionDraft("B", "只能保存数字")),
            List.of("A"), "变量是指向对象的名称。", new BigDecimal("10"), List.of(), false)),
        null, null, null, null, null, null);
    return response(request, activity, "QUIZ");
  }

  private AiTutorChatResponse shortAnswerResponse(AiTutorChatRequest request) {
    AiTutorActivityDraft activity = new AiTutorActivityDraft(
        "short_ref", "QUIZ", "Python 简答题", "kp-cycle8", "BASIC",
        List.of(new QuizQuestionDraft(
            "short1", "SHORT_ANSWER", "Python 是什么？", List.of(), List.of(),
            "Python 是一种编程语言。", new BigDecimal("10"), List.of("Python"),
            false)),
        null, null, null, null, null, null);
    return response(request, activity, "QUIZ");
  }

  private AiTutorChatResponse codeResponse(AiTutorChatRequest request) {
    AiTutorActivityDraft activity = new AiTutorActivityDraft(
        "code_ref", "CODE_LAB", "回显输入", "kp-cycle8", "BASIC", null,
        "PYTHON", "读取一行并原样输出。", "value = input()\nprint(value)",
        "print(input())",
        List.of(
            new CodeTestCaseDraft("public_1", "hello\n", "hello\n",
                new BigDecimal("50"), "PUBLIC", "EXACT"),
            new CodeTestCaseDraft("hidden_1", "secret\n", "secret\n",
                new BigDecimal("50"), "HIDDEN", "EXACT")),
        null);
    return response(request, activity, "CODE_LAB");
  }

  private AiTutorChatResponse animationResponse(
      AiTutorChatRequest request, String label, String caption) {
    AiTutorActivityDraft activity = new AiTutorActivityDraft(
        "animation_ref", "ANIMATION", "二分查找过程", "kp-cycle8", null,
        null, null, null, null, null, null,
        new AnimationSceneDraft(
            "SEARCH",
            List.of(new SceneEntityDraft(
                "array", "ARRAY", label, List.of("1", "3", "5"), null, null)),
            List.of(new SceneStepDraft(
                1, "HIGHLIGHT", List.of("array"), null, caption, 800))));
    return response(request, activity, "ANIMATION");
  }

  private AiTutorChatResponse response(
      AiTutorChatRequest request, AiTutorActivityDraft activity, String specialist) {
    String blockType = switch (specialist) {
      case "QUIZ" -> "QUIZ";
      case "CODE_LAB" -> "CODE_LAB";
      case "ANIMATION" -> "ANIMATION";
      default -> throw new IllegalArgumentException(specialist);
    };
    return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "## 已生成教学活动", List.of(
            new AiTutorContentBlock(
                "MARKDOWN", "explanation-block", 1, null,
                "先理解概念，再完成互动活动。", null, null, null, Map.of()),
            new AiTutorContentBlock(
                blockType, "activity-block-" + specialist.toLowerCase(), 1,
                activity.activityRef(), null, null, activity.title(), null, Map.of())), List.of(activity), new AiTutorTeachingPlan(
            "完成讲解和互动活动", List.of("EXPLANATION", specialist),
            List.of("EXPLANATION", specialist)), List.of(
            new AiTutorSpecialistInvocation("EXPLANATION", "SUCCEEDED", 3, 0, null),
            new AiTutorSpecialistInvocation(specialist, "SUCCEEDED", 4, 1, null)), List.of(Map.of(
            "title", "权威图谱", "locator", "kp-cycle8", "reviewed", true,
            "review_status", "APPROVED", "rights_status", "ALLOWED")), List.of(), List.of(), null, false, "继续练习", "cycle8-model", "cycle8-prompt", AiTutorAgentTrace.fixed());
  }

  private AiTutorChatResponse responseWithoutActivity(
      AiTutorChatRequest request, String specialist, String status, String errorCode) {
    return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "## 暂无互动活动", List.of(new AiTutorContentBlock(
            "MARKDOWN", "explanation-block", 1, null,
            "先理解概念，互动活动暂未生成。", null, null, null, Map.of())), List.of(), new AiTutorTeachingPlan(
            "完成讲解和互动活动", List.of("EXPLANATION", specialist),
            List.of("EXPLANATION", specialist)), List.of(
            new AiTutorSpecialistInvocation("EXPLANATION", "SUCCEEDED", 3, 0, null),
            new AiTutorSpecialistInvocation(specialist, status, 4, 0, errorCode)), List.of(Map.of(
            "title", "权威图谱", "locator", "kp-cycle8", "reviewed", true,
            "review_status", "APPROVED", "rights_status", "ALLOWED")), List.of(), List.of(), null, false, "请重试", "cycle8-model", "cycle8-prompt", AiTutorAgentTrace.fixed());
  }

  private SandboxRunResponse acceptedSandbox(SandboxRunRequest request) {
    List<SandboxRunResponse.SandboxTestResult> results = request.testCases().stream()
        .map(test -> new SandboxRunResponse.SandboxTestResult(
            test.testCaseId(), "ACCEPTED", test.score(), test.score(), 2, 1024,
            test.visibility(), "HIDDEN".equals(test.visibility()) ? null : test.expectedOutput(),
            "HIDDEN".equals(test.visibility()) ? null : test.expectedOutput(), null))
        .toList();
    BigDecimal total = request.testCases().stream()
        .map(SandboxRunRequest.SandboxTestCase::score)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new SandboxRunResponse(
        request.requestId(), "ACCEPTED", total, total, results.size(), results.size(),
        null, results);
  }

  private InternalGraphRagContextResponse graphContext() {
    InternalGraphNode node = new InternalGraphNode(
        "kp-cycle8", "周期八知识点", 3, "knowledge_point", null, "description",
        List.of(), List.of(), "BASIC", List.of(), List.of(), "APPROVED", 1);
    return new InternalGraphRagContextResponse(1L, node, List.of(), List.of(), List.of(), "graph-cycle8-v1", "READY", List.of());
  }

  private AuthenticatedUser student(String prefix) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO edu_user(username,password_hash,display_name,role,status)
          VALUES(?, 'x', 'Cycle 8 Student', 'STUDENT', 'ACTIVE')
          """, new String[] {"id"});
      statement.setString(1, prefix + "_" + System.nanoTime());
      return statement;
    }, keys);
    long id = Objects.requireNonNull(keys.getKey()).longValue();
    CourseTestFixtures.student(jdbc, id);
    return new AuthenticatedUser(id, prefix, "Cycle 8 Student", UserRole.STUDENT, true, true);
  }

  private String jsonValue(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
