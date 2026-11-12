package com.education.agent.tutor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.course.CourseAccessService;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.integration.ai.AiTutorQuizGrading;
import com.education.agent.integration.ai.CodeExplainRequest;
import com.education.agent.integration.ai.CodeExplainResponse;
import com.education.agent.integration.ai.SandboxRunRequest;
import com.education.agent.integration.ai.SandboxRunResponse;
import com.education.agent.learning.service.LearningEvidenceService;
import com.education.agent.learning.service.LearningEvidenceService.MasteryChange;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.CodeRunResult;
import com.education.agent.submission.dto.SubmissionDtos.TestCaseRunResult;
import com.education.agent.assessment.model.TestVisibility;
import com.education.agent.tutor.dto.TutorActivityDtos.Activity;
import com.education.agent.tutor.dto.TutorActivityDtos.ActivityAttempt;
import com.education.agent.tutor.dto.TutorActivityDtos.AnimationScene;
import com.education.agent.tutor.dto.TutorActivityDtos.CodeLabPayload;
import com.education.agent.tutor.dto.TutorActivityDtos.CodeSubmissionRequest;
import com.education.agent.tutor.dto.TutorActivityDtos.QuestionResult;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizAttemptRequest;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizPayload;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.tutor.model.TutorActivityStatus;
import com.education.agent.tutor.model.TutorActivityType;
import com.education.agent.tutor.service.TutorActivityDraftValidator.PreparedActivity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TutorActivityService {
  private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> TEST_STATUSES = Set.of(
      "ACCEPTED", "WRONG_ANSWER", "RUNTIME_ERROR", "TIME_LIMIT_EXCEEDED",
      "MEMORY_LIMIT_EXCEEDED", "OUTPUT_LIMIT_EXCEEDED", "SYNTAX_ERROR", "SYSTEM_ERROR");
  private static final int MAX_RESULT_TEXT = 65_536;
  private static final int MAX_RESULT_JSON_BYTES = 3 * 1024 * 1024;

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AiServiceClient ai;
  private final TutorShortAnswerGradingService shortAnswers;
  private final LearningEvidenceService evidenceService;
  private final IdempotencyRecordMapper idempotencyMapper;
  private final CourseAccessService courses;
  private final TransactionTemplate tx;
  private final Set<Long> activePublicRuns = ConcurrentHashMap.newKeySet();
  private final Set<Long> activeSubmissions = ConcurrentHashMap.newKeySet();

  public TutorActivityService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      AiServiceClient ai,
      TutorShortAnswerGradingService shortAnswers,
      LearningEvidenceService evidenceService,
      IdempotencyRecordMapper idempotencyMapper,
      CourseAccessService courses,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.json = json;
    this.ai = ai;
    this.shortAnswers = shortAnswers;
    this.evidenceService = evidenceService;
    this.idempotencyMapper = idempotencyMapper;
    this.courses = courses;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Must be called inside the same transaction that inserts the assistant message. */
  public Map<String, Long> persistActivities(
      Long studentId, Long sessionId, Long messageId, List<PreparedActivity> activities) {
    Map<String, Long> ids = new LinkedHashMap<>();
    for (PreparedActivity activity : activities) {
      KeyHolder keys = new GeneratedKeyHolder();
      jdbc.update(connection -> {
        PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO edu_tutor_activity
            (session_id,message_id,student_id,activity_ref,activity_type,title,
             knowledge_point_id,knowledge_point_name,graph_version,difficulty,status,
             public_payload_json,private_payload_json,max_score)
            VALUES (?,?,?,?,?,?,?,?,?,?, 'READY',?,?,?)
            """, new String[] {"id"});
        int index = 1;
        statement.setLong(index++, sessionId);
        statement.setLong(index++, messageId);
        statement.setLong(index++, studentId);
        statement.setString(index++, activity.activityRef());
        statement.setString(index++, activity.activityType().name());
        statement.setString(index++, activity.title());
        statement.setString(index++, activity.knowledgePointId());
        statement.setString(index++, activity.knowledgePointName());
        statement.setString(index++, activity.graphVersion());
        statement.setString(index++, activity.difficulty());
        statement.setString(index++, write(activity.publicPayload()));
        statement.setString(index++, write(activity.privatePayload()));
        statement.setBigDecimal(index, activity.maxScore());
        return statement;
      }, keys);
      Number key = keys.getKey();
      if (key == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师活动保存失败");
      ids.put(activity.activityRef(), key.longValue());
    }
    return Map.copyOf(ids);
  }

  public Activity get(AuthenticatedUser principal, Long activityId) {
    requireStudent(principal);
    return toView(requireOwned(principal, activityId, false));
  }

  public ActivityAttempt submitQuiz(
      AuthenticatedUser principal,
      Long activityId,
      String idempotencyKey,
      QuizAttemptRequest request) {
    requireStudent(principal);
    ActivityRow locator = requireOwned(principal, activityId, false);
    requireWritable(principal, locator);
    requireType(locator, TutorActivityType.QUIZ);
    Map<String, List<String>> normalizedAnswers = normalizeAnswers(request.answers());
    String requestHash = sha256(write(normalizedAnswers));
    IdempotencyClaim<ActivityAttempt> claim = claim(
        principal.id(), "TUTOR_QUIZ_SUBMIT:" + activityId, idempotencyKey,
        requestHash, ActivityAttempt.class);
    if (claim.replay() != null) return claim.replay();
    if (!activeSubmissions.add(principal.id())) {
      releaseClaim(claim.recordId());
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "已有练习正在提交，请稍候");
    }
    try {
      requireReadyForSubmission(locator);
      AiTutorActivityDraft preparedDraft = read(locator.privatePayloadJson(), AiTutorActivityDraft.class);
      AiTutorQuizGrading.Response review = shortAnswers.review(
          locator.courseId(), locator.id(), preparedDraft, normalizedAnswers);
      ActivityAttempt result = tx.execute(ignored -> {
        ActivityRow activity = requireOwned(principal, activityId, true);
        requireWritable(principal, activity);
        requireType(activity, TutorActivityType.QUIZ);
        requireReadyForSubmission(activity);
        if (!Objects.equals(activity.privatePayloadJson(), locator.privatePayloadJson())) {
          throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "题目已更新，请刷新后重试");
        }
        AiTutorActivityDraft draft = read(activity.privatePayloadJson(), AiTutorActivityDraft.class);
        return persistQuizAttempt(activity, draft, normalizedAnswers, review, claim.recordId());
      });
      return Objects.requireNonNull(result);
    } catch (RuntimeException exception) {
      releaseClaim(claim.recordId());
      throw exception;
    } finally {
      activeSubmissions.remove(principal.id());
    }
  }

  public CodeRunResult runCode(
      AuthenticatedUser principal, Long activityId, CodeSubmissionRequest request) {
    requireStudent(principal);
    ActivityRow locator = requireOwned(principal, activityId, false);
    requireWritable(principal, locator);
    requireType(locator, TutorActivityType.CODE_LAB);
    if (!activePublicRuns.add(principal.id())) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_RATE_LIMITED, "该学生已有导师代码正在运行");
    }
    try {
      AiTutorActivityDraft draft = read(locator.privatePayloadJson(), AiTutorActivityDraft.class);
      String requestId = "tutor-run-" + UUID.randomUUID().toString().replace("-", "");
      SandboxRunRequest sandboxRequest = sandboxRequest(draft, request.sourceCode(), requestId, true);
      SandboxRunResponse response = ai.runSandbox(sandboxRequest);
      BigDecimal trustedScore = validateSandboxResponse(sandboxRequest, response);
      CodeRunResult result = tx.execute(ignored -> {
        ActivityRow locked = requireOwned(principal, activityId, true);
        requireWritable(principal, locked);
        requireType(locked, TutorActivityType.CODE_LAB);
        AiTutorActivityDraft currentDraft = read(locked.privatePayloadJson(), AiTutorActivityDraft.class);
        long runId = insertCodeRun(
            locked, null, "PUBLIC", requestId, request.sourceCode(), response,
            trustedScore, response.totalScore(), "{}");
        CodeRunResult view = toCodeRunResult(runId, currentDraft, response);
        requireJsonBound(view);
        jdbc.update("UPDATE edu_tutor_code_run SET result_json=? WHERE id=?", write(view), runId);
        return view;
      });
      return Objects.requireNonNull(result);
    } finally {
      activePublicRuns.remove(principal.id());
    }
  }

  public ActivityAttempt submitCode(
      AuthenticatedUser principal,
      Long activityId,
      String idempotencyKey,
      CodeSubmissionRequest request) {
    requireStudent(principal);
    ActivityRow locator = requireOwned(principal, activityId, false);
    requireWritable(principal, locator);
    requireType(locator, TutorActivityType.CODE_LAB);
    String requestHash = sha256(request.sourceCode());
    IdempotencyClaim<ActivityAttempt> claim = claim(
        principal.id(), "TUTOR_CODE_SUBMIT:" + activityId, idempotencyKey,
        requestHash, ActivityAttempt.class);
    if (claim.replay() != null) return claim.replay();
    if (!activeSubmissions.add(principal.id())) {
      releaseClaim(claim.recordId());
      throw new BusinessException(ErrorCode.CODE_SANDBOX_RATE_LIMITED, "该学生已有导师代码正在评分");
    }
    try {
      requireReadyForSubmission(locator);
      AiTutorActivityDraft draft = read(locator.privatePayloadJson(), AiTutorActivityDraft.class);
      String requestId = "tutor-code-submit-" + claim.recordId();
      SandboxRunRequest sandboxRequest = sandboxRequest(draft, request.sourceCode(), requestId, false);
      SandboxRunResponse response = ai.runSandbox(sandboxRequest);
      BigDecimal trustedScore = validateSandboxResponse(sandboxRequest, response);
      String analysis = explainCode(activityId, locator.knowledgePointId(), draft, request.sourceCode(), response);
      ActivityAttempt result = tx.execute(ignored -> {
        ActivityRow activity = requireOwned(principal, activityId, true);
        requireWritable(principal, activity);
        requireType(activity, TutorActivityType.CODE_LAB);
        requireReadyForSubmission(activity);
        AiTutorActivityDraft currentDraft = read(activity.privatePayloadJson(), AiTutorActivityDraft.class);
        return persistCodeAttempt(
            activity, currentDraft, request.sourceCode(), requestId, response,
            trustedScore, analysis, claim.recordId());
      });
      return Objects.requireNonNull(result);
    } catch (RuntimeException exception) {
      releaseClaim(claim.recordId());
      throw exception;
    } finally {
      activeSubmissions.remove(principal.id());
    }
  }

  private ActivityAttempt persistQuizAttempt(
      ActivityRow activity,
      AiTutorActivityDraft draft,
      Map<String, List<String>> answers,
      AiTutorQuizGrading.Response review,
      Long idempotencyRecordId) {
    Map<String, AiTutorActivityDraft.QuizQuestionDraft> questions = new LinkedHashMap<>();
    draft.questions().forEach(question -> questions.put(question.questionRef(), question));
    if (!questions.keySet().containsAll(answers.keySet())) throw validation("提交包含未知题目");
    List<QuestionResult> results = new ArrayList<>();
    BigDecimal score = BigDecimal.ZERO;
    for (AiTutorActivityDraft.QuizQuestionDraft question : draft.questions()) {
      List<String> submitted = answers.getOrDefault(question.questionRef(), List.of());
      List<String> selected = List.of();
      String submittedAnswer = null;
      List<String> correctOptionKeys = List.of();
      List<String> correctAnswers = List.of();
      AiTutorQuizGrading.Grade semantic = review == null ? null : review.grades().stream()
          .filter(grade -> question.questionRef().equals(grade.questionRef())).findFirst().orElse(null);
      boolean correct;
      if ("SHORT_ANSWER".equals(question.questionType())) {
        if (submitted.size() > 1) throw validation("简答题只能提交一个答案");
        submittedAnswer = submitted.isEmpty() ? null : submitted.get(0);
        correctAnswers = question.acceptedAnswers().stream().map(String::trim).toList();
        String candidateAnswer = submittedAnswer;
        correct = candidateAnswer != null && correctAnswers.stream()
            .anyMatch(answer -> TutorShortAnswerGradingService.matches(candidateAnswer, answer, question.caseSensitive()));
      } else {
        selected = submitted;
        Set<String> optionKeys = new HashSet<>();
        question.options().forEach(option -> optionKeys.add(option.key()));
        if (selected.stream().anyMatch(key -> !optionKeys.contains(key))) {
          throw validation("提交包含未知选项");
        }
        if (("SINGLE_CHOICE".equals(question.questionType()) || "TRUE_FALSE".equals(question.questionType()))
            && selected.size() > 1) {
          throw validation("单选或判断题只能选择一个选项");
        }
        correctOptionKeys = question.correctOptionKeys().stream().sorted().toList();
        correct = new HashSet<>(selected).equals(new HashSet<>(question.correctOptionKeys()));
      }
      BigDecimal earned = semantic != null ? semantic.score().setScale(2)
          : correct ? question.score().setScale(2) : BigDecimal.ZERO.setScale(2);
      if (semantic != null) correct = earned.compareTo(question.score()) == 0;
      score = score.add(earned);
      results.add(new QuestionResult(
          question.questionRef(), selected, correctOptionKeys, submittedAnswer, correctAnswers, correct,
          earned, question.score().setScale(2), semantic == null ? question.explanation().trim() : semantic.feedback().trim()));
    }
    LocalDateTime now = LocalDateTime.now();
    long attemptId = insertAttempt(
        activity, idempotencyRecordId, "QUIZ", write(review == null ? answers
            : Map.of("answers", answers, "semanticGrading", review)), score,
        activity.maxScore(), null, now);
    BigDecimal percent = percentage(score, activity.maxScore());
    MasteryChange mastery = evidenceService.recordPractice(
        activity.studentId(), activity.courseId(), activity.id(), attemptId, activity.knowledgePointId(),
        activity.knowledgePointName(), activity.graphVersion(), activity.title(),
        percent, BigDecimal.ONE, now);
    ActivityAttempt view = new ActivityAttempt(
        attemptId, activity.id(), "COMPLETED", score, activity.maxScore(), results,
        null, null, mastery.before(), mastery.after(), mastery.delta(), toInstant(now));
    completeAttempt(activity.id(), attemptId, view, mastery, idempotencyRecordId);
    return view;
  }

  private ActivityAttempt persistCodeAttempt(
      ActivityRow activity,
      AiTutorActivityDraft draft,
      String sourceCode,
      String requestId,
      SandboxRunResponse response,
      BigDecimal trustedScore,
      String analysis,
      Long idempotencyRecordId) {
    LocalDateTime now = LocalDateTime.now();
    long attemptId = insertAttempt(
        activity, idempotencyRecordId, "CODE", write(Map.of("sourceCode", sourceCode)),
        trustedScore, response.totalScore(), analysis, now);
    long runId = insertCodeRun(
        activity, attemptId, "SUBMIT", requestId, sourceCode, response,
        trustedScore, response.totalScore(), "{}");
    CodeRunResult codeResult = toCodeRunResult(runId, draft, response);
    requireJsonBound(codeResult);
    jdbc.update("UPDATE edu_tutor_code_run SET result_json=? WHERE id=?", write(codeResult), runId);
    BigDecimal percent = percentage(trustedScore, response.totalScore());
    MasteryChange mastery = evidenceService.recordPractice(
        activity.studentId(), activity.courseId(), activity.id(), attemptId, activity.knowledgePointId(),
        activity.knowledgePointName(), activity.graphVersion(), activity.title(),
        percent, BigDecimal.ONE, now);
    ActivityAttempt view = new ActivityAttempt(
        attemptId, activity.id(), "COMPLETED", trustedScore, response.totalScore(), List.of(),
        codeResult, analysis, mastery.before(), mastery.after(), mastery.delta(), toInstant(now));
    completeAttempt(activity.id(), attemptId, view, mastery, idempotencyRecordId);
    return view;
  }

  private long insertAttempt(
      ActivityRow activity,
      Long idempotencyRecordId,
      String attemptType,
      String answerJson,
      BigDecimal score,
      BigDecimal maxScore,
      String analysis,
      LocalDateTime submittedAt) {
    Integer attemptNo = jdbc.queryForObject(
        "SELECT COALESCE(MAX(attempt_no),0)+1 FROM edu_tutor_activity_attempt WHERE activity_id=?",
        Integer.class, activity.id());
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO edu_tutor_activity_attempt
          (activity_id,student_id,idempotency_record_id,attempt_no,attempt_type,status,
           answer_json,score,max_score,result_json,ai_analysis,submitted_at)
          VALUES (?,?,?,?,?,'COMPLETED',?,?,?,'{}',?,?)
          """, new String[] {"id"});
      statement.setLong(1, activity.id());
      statement.setLong(2, activity.studentId());
      statement.setLong(3, idempotencyRecordId);
      statement.setInt(4, Objects.requireNonNull(attemptNo));
      statement.setString(5, attemptType);
      statement.setString(6, answerJson);
      statement.setBigDecimal(7, score);
      statement.setBigDecimal(8, maxScore);
      statement.setString(9, analysis);
      statement.setTimestamp(10, Timestamp.valueOf(submittedAt));
      return statement;
    }, keys);
    Number key = keys.getKey();
    if (key == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师活动提交保存失败");
    return key.longValue();
  }

  private void completeAttempt(
      Long activityId,
      Long attemptId,
      ActivityAttempt view,
      MasteryChange mastery,
      Long idempotencyRecordId) {
    String resultJson = write(view);
    requireJsonBound(view);
    jdbc.update("""
        UPDATE edu_tutor_activity_attempt
        SET result_json=?,mastery_before=?,mastery_after=?,mastery_delta=?
        WHERE id=?
        """, resultJson, mastery.before(), mastery.after(), mastery.delta(), attemptId);
    jdbc.update("UPDATE edu_tutor_activity SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
        activityId);
    IdempotencyRecordEntity record = idempotencyMapper.selectById(idempotencyRecordId);
    if (record == null || record.getResponseJson() != null) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "导师活动幂等状态已改变");
    }
    record.setResponseStatus(200);
    record.setResponseJson(resultJson);
    idempotencyMapper.updateById(record);
  }

  private long insertCodeRun(
      ActivityRow activity,
      Long attemptId,
      String runType,
      String requestId,
      String sourceCode,
      SandboxRunResponse response,
      BigDecimal score,
      BigDecimal totalScore,
      String resultJson) {
    int duration = response.testResults().stream()
        .mapToInt(result -> result.timeMs() == null ? 0 : result.timeMs()).sum();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO edu_tutor_code_run
          (activity_id,attempt_id,student_id,run_type,request_id,source_hash,status,
           score,total_score,result_json,duration_ms)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """, new String[] {"id"});
      statement.setLong(1, activity.id());
      if (attemptId == null) statement.setNull(2, java.sql.Types.BIGINT);
      else statement.setLong(2, attemptId);
      statement.setLong(3, activity.studentId());
      statement.setString(4, runType);
      statement.setString(5, requestId);
      statement.setString(6, sha256(sourceCode));
      statement.setString(7, response.status());
      statement.setBigDecimal(8, score);
      statement.setBigDecimal(9, totalScore);
      statement.setString(10, resultJson);
      statement.setInt(11, duration);
      return statement;
    }, keys);
    Number key = keys.getKey();
    if (key == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师代码运行保存失败");
    return key.longValue();
  }

  private SandboxRunRequest sandboxRequest(
      AiTutorActivityDraft draft, String sourceCode, String requestId, boolean publicOnly) {
    List<SandboxRunRequest.SandboxTestCase> cases = new ArrayList<>();
    for (int index = 0; index < draft.testCases().size(); index++) {
      AiTutorActivityDraft.CodeTestCaseDraft test = draft.testCases().get(index);
      if (publicOnly && !"PUBLIC".equals(test.visibility())) continue;
      cases.add(new SandboxRunRequest.SandboxTestCase(
          (long) index + 1, test.inputData(), test.expectedOutput(), test.score().setScale(2),
          test.visibility(), test.compareMode(),
          "FLOAT_TOLERANCE".equals(test.compareMode()) ? new BigDecimal("0.000001") : null));
    }
    return new SandboxRunRequest(
        requestId, publicOnly ? "PUBLIC_RUN" : "GRADING", "PYTHON_3_11", sourceCode,
        cases, new SandboxRunRequest.SandboxLimits(2000, 128, 256));
  }

  private BigDecimal validateSandboxResponse(
      SandboxRunRequest request, SandboxRunResponse response) {
    if (response == null || !request.requestId().equals(response.requestId())
        || response.testResults() == null
        || response.testResults().size() != request.testCases().size()
        || !Objects.equals(response.totalCount(), request.testCases().size())
        || tooLong(response.compileMessage(), 4000)) {
      throw invalidSandbox();
    }
    Map<Long, SandboxRunRequest.SandboxTestCase> expected = new HashMap<>();
    request.testCases().forEach(test -> expected.put(test.testCaseId(), test));
    Set<Long> seen = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal achieved = BigDecimal.ZERO;
    int passed = 0;
    for (SandboxRunResponse.SandboxTestResult result : response.testResults()) {
      SandboxRunRequest.SandboxTestCase test = expected.get(result.testCaseId());
      if (test == null || !seen.add(result.testCaseId()) || !TEST_STATUSES.contains(result.status())
          || !test.visibility().equals(result.visibility()) || result.score() == null
          || result.maxScore() == null || result.maxScore().compareTo(test.score()) != 0
          || result.score().signum() < 0 || result.score().compareTo(test.score()) > 0
          || tooLong(result.actualOutput(), MAX_RESULT_TEXT)
          || tooLong(result.expectedOutput(), MAX_RESULT_TEXT)
          || tooLong(result.errorMessage(), MAX_RESULT_TEXT)
          || (result.timeMs() != null && result.timeMs() < 0)
          || ("PUBLIC".equals(test.visibility()) && result.expectedOutput() != null
              && !test.expectedOutput().equals(result.expectedOutput()))
          || ("HIDDEN".equals(test.visibility())
              && (hasText(result.expectedOutput()) || hasText(result.actualOutput())
                  || hasText(result.errorMessage())))) {
        throw invalidSandbox();
      }
      if (("ACCEPTED".equals(result.status()) && result.score().compareTo(result.maxScore()) != 0)
          || (!"ACCEPTED".equals(result.status()) && result.score().signum() != 0)) {
        throw invalidSandbox();
      }
      total = total.add(test.score());
      achieved = achieved.add(result.score());
      if ("ACCEPTED".equals(result.status())) passed++;
    }
    if (response.totalScore() == null || response.totalScore().compareTo(total) != 0
        || response.achievedScore() == null || response.achievedScore().compareTo(achieved) != 0
        || response.passedCount() == null || response.passedCount() != passed
        || !expectedAggregateStatus(response.status(), passed, request.testCases().size(), response.testResults())) {
      throw invalidSandbox();
    }
    return achieved.setScale(2);
  }

  private boolean expectedAggregateStatus(
      String actual, int passed, int total, List<SandboxRunResponse.SandboxTestResult> results) {
    String expected;
    if (passed == total) expected = "ACCEPTED";
    else if (passed > 0) expected = "PARTIAL";
    else {
      List<String> precedence = List.of(
          "SYNTAX_ERROR", "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED",
          "OUTPUT_LIMIT_EXCEEDED", "RUNTIME_ERROR", "SYSTEM_ERROR", "WRONG_ANSWER");
      Set<String> statuses = new HashSet<>();
      results.forEach(result -> statuses.add(result.status()));
      expected = precedence.stream().filter(statuses::contains).findFirst().orElse("WRONG_ANSWER");
    }
    return expected.equals(actual);
  }

  private CodeRunResult toCodeRunResult(
      long runId, AiTutorActivityDraft draft, SandboxRunResponse response) {
    Map<Long, SandboxRunResponse.SandboxTestResult> byId = new HashMap<>();
    response.testResults().forEach(result -> byId.put(result.testCaseId(), result));
    List<TestCaseRunResult> tests = new ArrayList<>();
    for (int index = 0; index < draft.testCases().size(); index++) {
      AiTutorActivityDraft.CodeTestCaseDraft test = draft.testCases().get(index);
      long sandboxId = (long) index + 1;
      SandboxRunResponse.SandboxTestResult result = byId.get(sandboxId);
      if (result == null) continue;
      boolean hidden = "HIDDEN".equals(test.visibility());
      tests.add(new TestCaseRunResult(
          sandboxId, index + 1, hidden ? TestVisibility.HIDDEN : TestVisibility.PUBLIC,
          result.status(), hidden ? null : test.inputData(),
          hidden ? null : test.expectedOutput(), hidden ? null : result.actualOutput(),
          hidden ? null : result.errorMessage(), result.timeMs() == null ? 0 : result.timeMs(),
          result.score()));
    }
    int duration = response.testResults().stream()
        .mapToInt(result -> result.timeMs() == null ? 0 : result.timeMs()).sum();
    return new CodeRunResult(
        runId, response.status(), response.passedCount(), response.totalCount(),
        response.achievedScore(), duration, null, response.compileMessage(), tests, null);
  }

  private String explainCode(
      Long activityId,
      String knowledgePointId,
      AiTutorActivityDraft draft,
      String sourceCode,
      SandboxRunResponse response) {
    if ("ACCEPTED".equals(response.status())) return "全部测试通过，代码实现符合本练习要求。";
    List<CodeExplainRequest.CodeFailureInput> failures = new ArrayList<>();
    for (SandboxRunResponse.SandboxTestResult result : response.testResults()) {
      int index = result.testCaseId().intValue() - 1;
      if (index < 0 || index >= draft.testCases().size()) continue;
      AiTutorActivityDraft.CodeTestCaseDraft test = draft.testCases().get(index);
      if (!"PUBLIC".equals(test.visibility()) || "ACCEPTED".equals(result.status())) continue;
      failures.add(new CodeExplainRequest.CodeFailureInput(
          index + 1, result.status(), test.inputData(), test.expectedOutput(),
          result.actualOutput(), result.errorMessage()));
    }
    if (failures.isEmpty()) return "部分隐藏测试未通过，请重点检查边界条件和异常输入。";
    try {
      CodeExplainResponse explanation = ai.explainCodeResult(new CodeExplainRequest(
          response.requestId(), activityId, List.of(knowledgePointId), sourceCode, failures));
      if (explanation == null || !response.requestId().equals(explanation.requestId())
          || explanation.feedback() == null || explanation.feedback().isBlank()
          || explanation.feedback().length() > 4000) {
        throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "代码教学解释无效");
      }
      return explanation.feedback().trim();
    } catch (RuntimeException ignored) {
      return "代码未通过全部公开测试，请根据失败用例检查输入处理、边界条件和输出格式。";
    }
  }

  private Activity toView(ActivityRow row) {
    QuizPayload quiz = null;
    CodeLabPayload code = null;
    AnimationScene animation = null;
    switch (row.activityType()) {
      case QUIZ -> quiz = read(row.publicPayloadJson(), QuizPayload.class);
      case CODE_LAB -> code = read(row.publicPayloadJson(), CodeLabPayload.class);
      case ANIMATION -> animation = read(row.publicPayloadJson(), AnimationScene.class);
    }
    return new Activity(
        row.id(), row.sessionId(), row.messageId(), row.activityType(), row.title(),
        row.knowledgePointId(), row.status(), quiz, code, animation,
        latestAttempt(row.id()), toInstant(row.createdAt()));
  }

  private ActivityAttempt latestAttempt(Long activityId) {
    List<String> values = jdbc.query("""
        SELECT result_json FROM edu_tutor_activity_attempt
        WHERE activity_id=? ORDER BY attempt_no DESC,id DESC LIMIT 1
        """, (rs, rowNum) -> rs.getString(1), activityId);
    return values.isEmpty() ? null : read(values.get(0), ActivityAttempt.class);
  }

  private ActivityRow requireOwned(AuthenticatedUser principal, Long activityId, boolean lock) {
    String sql = """
        SELECT a.id,a.session_id,a.message_id,a.student_id,s.course_id,a.activity_ref,a.activity_type,
               a.title,a.knowledge_point_id,a.knowledge_point_name,a.graph_version,a.difficulty,
               a.status,a.public_payload_json,a.private_payload_json,a.max_score,a.created_at
        FROM edu_tutor_activity a
        JOIN edu_tutor_session s ON s.id=a.session_id
        WHERE a.id=? AND a.student_id=? AND s.deleted_at IS NULL
        """ + (lock ? " FOR UPDATE" : "");
    List<ActivityRow> values = jdbc.query(sql, (rs, rowNum) -> new ActivityRow(
        rs.getLong("id"), rs.getLong("session_id"), rs.getLong("message_id"),
        rs.getLong("student_id"), rs.getLong("course_id"), rs.getString("activity_ref"),
        TutorActivityType.valueOf(rs.getString("activity_type")), rs.getString("title"),
        rs.getString("knowledge_point_id"), rs.getString("knowledge_point_name"),
        rs.getString("graph_version"), rs.getString("difficulty"),
        TutorActivityStatus.valueOf(rs.getString("status")), rs.getString("public_payload_json"),
        rs.getString("private_payload_json"), rs.getBigDecimal("max_score"),
        rs.getTimestamp("created_at").toLocalDateTime()), activityId, principal.id());
    if (values.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导师互动活动不存在");
    courses.requireView(principal, values.get(0).courseId());
    return values.get(0);
  }

  private void requireWritable(AuthenticatedUser principal, ActivityRow activity) {
    courses.requireStudy(principal, activity.courseId());
    String capability = switch (activity.activityType()) {
      case CODE_LAB -> "CODE";
      case QUIZ -> "QUIZ";
      case ANIMATION -> "VISUAL";
    };
    if (!courses.activities(activity.courseId()).contains(capability)) {
      throw validation("当前课程已停用此教学活动类型");
    }
  }

  private Map<String, List<String>> normalizeAnswers(Map<String, List<String>> input) {
    if (input == null || input.isEmpty() || input.size() > 5) throw validation("答案不能为空");
    Map<String, List<String>> result = new TreeMap<>();
    input.forEach((questionId, selected) -> {
      if (questionId == null || questionId.isBlank() || questionId.length() > 80
          || selected == null || selected.isEmpty() || selected.size() > 6
          || selected.stream().anyMatch(answer -> answer == null || answer.isBlank()
              || answer.length() > 500)) {
        throw validation("题目答案格式不合法");
      }
      List<String> trimmed = selected.stream().map(String::trim).toList();
      if (new HashSet<>(trimmed).size() != trimmed.size()) throw validation("题目答案重复");
      result.put(questionId.trim(), trimmed.stream().sorted().toList());
    });
    return Map.copyOf(result);
  }

  private void requireType(ActivityRow row, TutorActivityType expected) {
    if (row.activityType() != expected) throw validation("导师活动类型与操作不匹配");
  }

  private void requireReadyForSubmission(ActivityRow row) {
    if (row.status() != TutorActivityStatus.READY) {
      throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "导师活动已经完成，不能重复计分");
    }
  }

  private void requireStudent(AuthenticatedUser principal) {
    if (principal == null || principal.role() != com.education.agent.user.model.UserRole.STUDENT) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "导师互动活动仅对学生开放");
    }
  }

  private <T> IdempotencyClaim<T> claim(
      Long userId,
      String operationId,
      String idempotencyKey,
      String requestHash,
      Class<T> responseType) {
    try {
      return Objects.requireNonNull(tx.execute(ignored -> {
        IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
        if (existing != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
          idempotencyMapper.deleteById(existing.getId());
          existing = null;
        }
        if (existing != null) return replayOrConflict(existing, requestHash, responseType);
        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setUserId(userId);
        record.setOperationId(operationId);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setExpiresAt(LocalDateTime.now().plusHours(24));
        idempotencyMapper.insert(record);
        return new IdempotencyClaim<T>(record.getId(), null);
      }));
    } catch (DuplicateKeyException exception) {
      IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
      if (existing == null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "重复请求正在处理");
      return replayOrConflict(existing, requestHash, responseType);
    }
  }

  private <T> IdempotencyClaim<T> replayOrConflict(
      IdempotencyRecordEntity existing, String requestHash, Class<T> responseType) {
    if (!existing.getRequestHash().equals(requestHash)) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同请求");
    }
    if (existing.getResponseJson() == null) {
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同请求正在处理中");
    }
    return new IdempotencyClaim<>(existing.getId(), read(existing.getResponseJson(), responseType));
  }

  private IdempotencyRecordEntity findIdempotency(
      Long userId, String operationId, String idempotencyKey) {
    return idempotencyMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
        .eq(IdempotencyRecordEntity::getUserId, userId)
        .eq(IdempotencyRecordEntity::getOperationId, operationId)
        .eq(IdempotencyRecordEntity::getIdempotencyKey, idempotencyKey));
  }

  private void releaseClaim(Long recordId) {
    tx.executeWithoutResult(ignored -> {
      IdempotencyRecordEntity record = idempotencyMapper.selectById(recordId);
      if (record != null && record.getResponseJson() == null) idempotencyMapper.deleteById(recordId);
    });
  }

  private BigDecimal percentage(BigDecimal score, BigDecimal maximum) {
    if (maximum == null || maximum.signum() <= 0) throw validation("活动总分不合法");
    return score.multiply(BigDecimal.valueOf(100))
        .divide(maximum, 4, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
  }

  private void requireJsonBound(Object value) {
    try {
      if (json.writeValueAsBytes(value).length > MAX_RESULT_JSON_BYTES) throw invalidSandbox();
    } catch (JsonProcessingException exception) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师活动结果无法序列化");
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师活动数据无法序列化");
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师活动数据损坏");
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private java.time.Instant toInstant(LocalDateTime value) {
    return value.atZone(DATABASE_ZONE).toInstant();
  }

  private boolean tooLong(String value, int maximum) {
    return value != null && value.length() > maximum;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private BusinessException validation(String message) {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
  }

  private BusinessException invalidSandbox() {
    return new BusinessException(ErrorCode.CODE_SANDBOX_INVALID_RESPONSE, "代码沙箱返回无效或泄漏隐藏测试点的响应");
  }

  private record IdempotencyClaim<T>(Long recordId, T replay) {}

  private record ActivityRow(
      Long id,
      Long sessionId,
      Long messageId,
      Long studentId,
      Long courseId,
      String activityRef,
      TutorActivityType activityType,
      String title,
      String knowledgePointId,
      String knowledgePointName,
      String graphVersion,
      String difficulty,
      TutorActivityStatus status,
      String publicPayloadJson,
      String privatePayloadJson,
      BigDecimal maxScore,
      LocalDateTime createdAt) {}
}
