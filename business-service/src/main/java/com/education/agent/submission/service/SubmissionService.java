package com.education.agent.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.dto.AssessmentDtos.*;
import com.education.agent.assessment.entity.*;
import com.education.agent.assessment.mapper.*;
import com.education.agent.assessment.model.*;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.course.CourseAccessService;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.CodeExplainRequest;
import com.education.agent.integration.ai.CodeExplainResponse;
import com.education.agent.integration.ai.SandboxRunRequest;
import com.education.agent.integration.ai.SandboxRunResponse;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.*;
import com.education.agent.submission.entity.*;
import com.education.agent.submission.mapper.*;
import com.education.agent.submission.model.GradingStatus;
import com.education.agent.submission.model.SubmissionStatus;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionService {
  private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> EVENT_TYPES =
      Set.of(
          "ENTER_FULLSCREEN",
          "EXIT_FULLSCREEN",
          "PAGE_HIDDEN",
          "WINDOW_BLUR",
          "COPY",
          "PASTE",
          "CONTEXT_MENU",
          "MULTI_DEVICE",
          "HEARTBEAT_MISSED",
          "RECONNECT");
  private static final Set<String> SANDBOX_TEST_STATUSES =
      Set.of(
          "ACCEPTED",
          "WRONG_ANSWER",
          "SYNTAX_ERROR",
          "RUNTIME_ERROR",
          "TIME_LIMIT_EXCEEDED",
          "MEMORY_LIMIT_EXCEEDED",
          "OUTPUT_LIMIT_EXCEEDED",
          "SYSTEM_ERROR");

  private final SubmissionMapper submissions;
  private final SubmissionAnswerMapper answers;
  private final SubmissionAttachmentMapper attachments;
  private final AntiCheatEventMapper events;
  private final CodeRunRecordMapper codeRuns;
  private final AssessmentMapper assessments;
  private final AssessmentVersionMapper versions;
  private final AssessmentQuestionMapper questions;
  private final QuestionOptionMapper options;
  private final ProgrammingTestCaseMapper testCases;
  private final ClassStudentMapper classStudents;
  private final UserMapper users;
  private final FileStorageService files;
  private final AiServiceClient ai;
  private final AuditLogService audit;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final IdempotencyRecordMapper idempotencyRecords;
  private final TransactionTemplate tx;
  private final String sessionSecret;
  private final Executor gradingExecutor;
  private final GradingFailureHook gradingFailureHook;
  private final AssessmentActivityGuard assessmentActivityGuard;
  private final CourseAccessService courses;
  private final Set<Long> activeIdempotencyClaims = ConcurrentHashMap.newKeySet();
  private final Set<Long> activeGradingSubmissions = ConcurrentHashMap.newKeySet();
  private final Set<Long> activePublicRunStudents = ConcurrentHashMap.newKeySet();

  public SubmissionService(
      SubmissionMapper submissions,
      SubmissionAnswerMapper answers,
      SubmissionAttachmentMapper attachments,
      AntiCheatEventMapper events,
      CodeRunRecordMapper codeRuns,
      AssessmentMapper assessments,
      AssessmentVersionMapper versions,
      AssessmentQuestionMapper questions,
      QuestionOptionMapper options,
      ProgrammingTestCaseMapper testCases,
      ClassStudentMapper classStudents,
      UserMapper users,
      FileStorageService files,
      AiServiceClient ai,
      AuditLogService audit,
      ObjectMapper json,
      JdbcTemplate jdbc,
      IdempotencyRecordMapper idempotencyRecords,
      @Qualifier("submissionGradingExecutor") Executor gradingExecutor,
      GradingFailureHook gradingFailureHook,
      AssessmentActivityGuard assessmentActivityGuard,
      CourseAccessService courses,
      PlatformTransactionManager transactionManager,
      @Value("${education.security.jwt-secret}") String sessionSecret) {
    this.submissions = submissions;
    this.answers = answers;
    this.attachments = attachments;
    this.events = events;
    this.codeRuns = codeRuns;
    this.assessments = assessments;
    this.versions = versions;
    this.questions = questions;
    this.options = options;
    this.testCases = testCases;
    this.classStudents = classStudents;
    this.users = users;
    this.files = files;
    this.ai = ai;
    this.audit = audit;
    this.json = json;
    this.jdbc = jdbc;
    this.idempotencyRecords = idempotencyRecords;
    this.gradingExecutor = gradingExecutor;
    this.gradingFailureHook = gradingFailureHook;
    this.assessmentActivityGuard = assessmentActivityGuard;
    this.courses = courses;
    this.tx = new TransactionTemplate(transactionManager);
    this.sessionSecret = sessionSecret;
  }

  public record StartResult(SubmissionSession session, boolean created) {}

  public StartResult start(
      AuthenticatedUser principal, Long assessmentId, String idempotencyKey, String deviceId) {
    requireStudent(principal);
    AssessmentEntity requestedAssessment = requireAssessment(assessmentId);
    requireAssignedStudent(principal, requestedAssessment);
    String operationId = "START_ASSESSMENT_SUBMISSION";
    String requestHash =
        sha256(operationId + ":" + assessmentId + ":" + deviceRequestHash(deviceId));
    IdempotencyClaim<StartResult> claim =
        claimIdempotency(
            principal.id(), operationId, idempotencyKey, requestHash, StartResult.class);
    if (claim.replay() != null) {
      SubmissionEntity persisted =
          requireOwned(principal, claim.replay().session().submission().id());
      verifyDevice(persisted, deviceId);
      return new StartResult(claim.replay().session(), false);
    }
    if (claim.incompleteReplay()) {
      SubmissionEntity existing = submissions.findLatest(assessmentId, principal.id());
      if (existing != null) {
        verifyDevice(existing, deviceId);
        StartResult recovered =
            new StartResult(
                toSession(existing, requireBoundVersion(existing).getSnapshotJson()), false);
        completeIdempotencyClaim(claim.recordId(), recovered);
        return new StartResult(recovered.session(), false);
      }
    }
    StartState state;
    try {
      state = tx.execute(status -> createOrResume(principal, assessmentId, deviceId));
    } catch (DuplicateKeyException race) {
      state = tx.execute(status -> resumeExisting(principal, assessmentId, deviceId));
    } catch (RuntimeException failure) {
      releaseIdempotencyClaim(claim.recordId());
      throw failure;
    }
    try {
      StartState result = Objects.requireNonNull(state);
      StartResult response =
          new StartResult(toSession(result.submission(), result.snapshot()), result.created());
      completeIdempotencyClaim(claim.recordId(), response);
      return response;
    } catch (RuntimeException failure) {
      releaseIdempotencyClaim(claim.recordId());
      throw failure;
    }
  }

  private StartState createOrResume(
      AuthenticatedUser principal, Long assessmentId, String deviceId) {
    AssessmentEntity assessment = requireAssessmentForUpdate(assessmentId);
    requireAssignedStudent(principal,assessment);
    SubmissionEntity existing = submissions.findLatest(assessmentId, principal.id());
    if (existing != null) return resumeState(existing, deviceId);
    if (assessment.getStatus() != AssessmentStatus.PUBLISHED) throw conflict("测评尚未开放作答");
    LocalDateTime now = LocalDateTime.now();
    if (assessment.getStartAt() != null && now.isBefore(assessment.getStartAt()))
      throw conflict("测评尚未到开始时间");
    if (assessment.getDueAt() != null
        && !now.isBefore(assessment.getDueAt())
        && (assessment.getAssessmentType() == AssessmentType.EXAM || !allowLate(assessment)))
      throw conflict("测评已过截止时间");
    AssessmentVersionEntity version = requireVersion(assessmentId, assessment.getCurrentVersion());
    SubmissionEntity entity = new SubmissionEntity();
    entity.setAssessmentId(assessmentId);
    entity.setAssessmentVersion(version.getVersionNo());
    entity.setAssessmentVersionId(version.getId());
    entity.setStudentId(principal.id());
    entity.setStatus(SubmissionStatus.IN_PROGRESS);
    entity.setAttemptNo(1);
    entity.setAcceptedSaveSequence(0L);
    entity.setGradingAttemptCount(0);
    entity.setStartedAt(now);
    entity.setGradingStatus(GradingStatus.NOT_STARTED);
    entity.setSuspicious(false);
    entity.setSubmittedByTimeout(false);
    if (assessment.getAssessmentType() == AssessmentType.EXAM) {
      int heartbeat = settings(assessment).antiCheatPolicy().heartbeatIntervalSeconds();
      LocalDateTime deadline = now.plusMinutes(assessment.getDurationMinutes());
      if (assessment.getDueAt() != null && assessment.getDueAt().isBefore(deadline))
        deadline = assessment.getDueAt();
      entity.setExamSessionStartedAt(now);
      entity.setExamDeadlineAt(deadline);
      entity.setHeartbeatIntervalSeconds(heartbeat);
      if (singleDevice(assessment)) {
        requireDeviceId(deviceId);
        entity.setDeviceIdHash(sha256(deviceId));
      }
    }
    submissions.insert(entity);
    if (assessment.getAssessmentType() == AssessmentType.EXAM)
      entity.setExamSessionTokenHash(sha256(examToken(entity)));
    submissions.updateById(entity);
    audit.record(
        "SUBMISSION_STARTED",
        "SUBMISSION",
        entity.getId().toString(),
        principal.id(),
        true,
        Map.of("assessmentId", assessmentId, "assessmentVersion", version.getVersionNo()));
    return new StartState(entity, version.getSnapshotJson(), true);
  }

  private StartState resumeExisting(
      AuthenticatedUser principal, Long assessmentId, String deviceId) {
    SubmissionEntity existing = submissions.findLatest(assessmentId, principal.id());
    if (existing == null)
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "作答创建冲突，请重试");
    return resumeState(existing, deviceId);
  }

  private StartState resumeState(SubmissionEntity existing, String deviceId) {
    verifyDevice(existing, deviceId);
    AssessmentVersionEntity version = requireBoundVersion(existing);
    if (existing.getStatus() != SubmissionStatus.IN_PROGRESS)
      return new StartState(existing, version.getSnapshotJson(), false);
    expireIfNeeded(existing);
    if (existing.getStatus() != SubmissionStatus.IN_PROGRESS) throw conflict("考试已由服务器超时交卷");
    return new StartState(existing, version.getSnapshotJson(), false);
  }

  public Submission get(AuthenticatedUser principal, Long submissionId) {
    SubmissionEntity entity = requireVisibleSubmission(principal, submissionId);
    if (principal.role() == UserRole.STUDENT) {
      expireAndPersist(entity);
      entity = requireSubmission(submissionId);
      AssessmentSettings settings =
          snapshotSettings(snapshotRoot(requireBoundVersion(entity).getSnapshotJson()));
      // Result visibility is submission-scoped. An auto-release assessment can publish one
      // completed student's result while the assessment stays open for other students.
      requireAssessment(entity.getAssessmentId());
      boolean released = entity.getResultReleasedAt() != null;
      return toSubmission(
          entity,
          released && settings.showScoreAfterRelease(),
          released && settings.showQuestionFeedbackAfterRelease(),
          released);
    }
    return toSubmission(entity, true);
  }

  public SubmissionSession review(AuthenticatedUser principal, Long submissionId) {
    requireStudent(principal);
    SubmissionEntity entity = requireOwned(principal, submissionId);
    expireAndPersist(entity);
    entity = requireOwned(principal, submissionId);
    requireAssessment(entity.getAssessmentId());
    if (entity.getStatus() == SubmissionStatus.IN_PROGRESS) {
      throw conflict("答卷尚未提交，请返回作答页面继续完成");
    }
    SubmissionSession session = toSession(entity, requireBoundVersion(entity).getSnapshotJson());
    return new SubmissionSession(session.submission(), session.paper(), null);
  }

  public PageData<Submission> list(
      AuthenticatedUser principal,
      Long assessmentId,
      long page,
      long size,
      SubmissionStatus status,
      GradingStatus gradingStatus) {
    AssessmentEntity assessment = requireTeacherAssessment(principal, assessmentId);
    Page<SubmissionEntity> result =
        submissions.selectPage(
            Page.of(page, size),
            new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getAssessmentId, assessment.getId())
                .eq(status != null, SubmissionEntity::getStatus, status)
                .eq(gradingStatus != null, SubmissionEntity::getGradingStatus, gradingStatus)
                .orderByDesc(SubmissionEntity::getCreatedAt));
    return new PageData<>(
        result.getRecords().stream().map(s -> toSubmission(s, true)).toList(),
        result.getCurrent(),
        result.getSize(),
        result.getTotal(),
        result.getPages());
  }

  public SubmissionAnswer saveAnswer(
      AuthenticatedUser principal,
      Long submissionId,
      Long questionId,
      SaveAnswerRequest request,
      String deviceId) {
    requireStudent(principal);
    return Objects.requireNonNull(
        tx.execute(
            status -> {
              SubmissionEntity submission = requireOwned(principal, submissionId);
              AssessmentEntity assessment = requireAssessmentForUpdate(submission.getAssessmentId());
              submission = requireOwnedForUpdate(principal, submissionId);
              if (!assessment.getId().equals(submission.getAssessmentId())) throw conflict("答卷关联测评已改变");
              verifyDevice(submission, deviceId);
              ensureEditable(submission);
              ensureAssessmentOpen(assessment);
              ensureHomeworkWithinDeadline(submission, assessment);
              QuestionSnapshot question = requireSnapshotQuestion(submission, questionId);
              SubmissionAnswerEntity current = findAnswer(submissionId, questionId);
              long sequence = request.clientSaveSequence();
              if (sequence <= submission.getAcceptedSaveSequence()) {
                if (current != null && sequence == current.getClientSaveSequence())
                  return toAnswer(current, false);
                throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "保存序号已过期",
                    Map.of("acceptedSaveSequence", submission.getAcceptedSaveSequence()));
              }
              validateAnswer(principal, question, request);
              SubmissionAnswerEntity answer =
                  current == null ? new SubmissionAnswerEntity() : current;
              answer.setSubmissionId(submissionId);
              answer.setQuestionId(questionId);
              answer.setClientSaveSequence(sequence);
              answer.setAnswerJson(write(answerPayload(request)));
              answer.setCodeText(trimToNull(request.sourceCode()));
              answer.setSavedAt(LocalDateTime.now());
              answer.setGradingStatus(GradingStatus.NOT_STARTED);
              if (current == null) answers.insert(answer);
              else if (answers.updateIfNewer(
                      answer.getId(), sequence, answer.getAnswerJson(), answer.getCodeText())
                  != 1) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "保存序号已过期");
              attachments.deleteByAnswer(answer.getId());
              for (Long fileId : request.attachmentFileIds())
                attachments.insert(answer.getId(), fileId);
              submission.setAcceptedSaveSequence(sequence);
              submissions.updateById(submission);
              return toAnswer(findAnswer(submissionId, questionId), false);
            }));
  }

  public Submission submit(
      AuthenticatedUser principal,
      Long submissionId,
      SubmitRequest request,
      String idempotencyKey,
      String deviceId) {
    requireStudent(principal);
    if (!Boolean.TRUE.equals(request.confirm())) throw validation("必须明确确认提交");
    String operationId = "SUBMIT_ASSESSMENT";
    String requestHash =
        sha256(
            operationId
                + ":"
                + submissionId
                + ":"
                + write(request)
                + ":"
                + deviceRequestHash(deviceId));
    IdempotencyClaim<Submission> claim =
        claimIdempotency(
            principal.id(), operationId, idempotencyKey, requestHash, Submission.class);
    if (claim.replay() != null) {
      verifyDevice(requireOwned(principal, submissionId), deviceId);
      return claim.replay();
    }
    if (claim.incompleteReplay()) {
      SubmissionEntity existing = requireOwned(principal, submissionId);
      if (isCompletedSubmission(existing)) {
        Submission recovered = toSubmission(existing, false);
        completeIdempotencyClaim(claim.recordId(), recovered);
        return recovered;
      }
      if (existing.getStatus() == SubmissionStatus.GRADING
          && existing.getGradingStatus() == GradingStatus.AUTO_GRADING) {
        markClaimFailed(existing.getId());
      }
    }
    SubmissionPlan plan = null;
    try {
      plan =
          Objects.requireNonNull(
              tx.execute(
                  status -> prepareSubmit(principal, submissionId, request, false, deviceId)));
      if (plan.alreadySubmitted()) {
        Submission response = toSubmission(plan.submission(), false);
        completeIdempotencyClaim(claim.recordId(), response);
        return response;
      }
      Submission response = toSubmission(plan.submission(), false);
      completeIdempotencyClaim(claim.recordId(), response);
      try {
        scheduleGrading(plan.submission().getId());
      } catch (RejectedExecutionException ignored) {
        // The durable AUTO_GRADING claim is recovered by the bounded periodic scanner.
      }
      return response;
    } catch (RuntimeException failure) {
      releaseIdempotencyClaim(claim.recordId());
      throw failure;
    }
  }

  private SubmissionPlan prepareSubmit(
      AuthenticatedUser principal,
      Long submissionId,
      SubmitRequest request,
      boolean timeout,
      String deviceId) {
    SubmissionEntity submission = timeout ? requireSubmission(submissionId) : requireOwned(principal, submissionId);
    AssessmentEntity assessment = requireAssessmentForUpdate(submission.getAssessmentId());
    submission = timeout ? submissions.selectForUpdate(submissionId) : requireOwnedForUpdate(principal, submissionId);
    if (!assessment.getId().equals(submission.getAssessmentId())) throw conflict("答卷关联测评已改变");
    if (!timeout) {
      verifyDevice(submission, deviceId);
      if (isExam(submission)) verifyExamToken(submission, request.examSessionToken());
    }
    if (submission.getStatus() != SubmissionStatus.IN_PROGRESS) {
      return new SubmissionPlan(
          submission, requireBoundVersion(submission).getSnapshotJson(), List.of(), true);
    }
    if (!timeout) ensureAssessmentOpen(assessment);
    if (!timeout) ensureHomeworkWithinDeadline(submission, assessment);
    boolean effectiveTimeout =
        timeout
            || (isExam(submission)
                && submission.getExamDeadlineAt() != null
                && !LocalDateTime.now().isBefore(submission.getExamDeadlineAt()));
    String snapshot = requireBoundVersion(submission).getSnapshotJson();
    List<ProgrammingTask> tasks = new ArrayList<>();
    boolean autoGrade = snapshotSettings(snapshotRoot(snapshot)).autoGradeEnabled();
    for (QuestionSnapshot q : snapshotQuestions(snapshot))
      if (autoGrade && q.type() == QuestionType.PROGRAMMING) {
        SubmissionAnswerEntity answer = findAnswer(submissionId, q.id());
        if (answer != null && answer.getCodeText() != null && !answer.getCodeText().isBlank())
          tasks.add(new ProgrammingTask(q, answer));
      }
    if (submission.getStatus() == SubmissionStatus.IN_PROGRESS) {
      submission.setStatus(
          effectiveTimeout ? SubmissionStatus.AUTO_SUBMITTED : SubmissionStatus.GRADING);
      submission.setSubmittedAt(LocalDateTime.now());
      submission.setSubmittedByTimeout(effectiveTimeout);
      submission.setGradingStatus(
          autoGrade ? GradingStatus.AUTO_GRADING : GradingStatus.MANUAL_GRADING);
      submissions.updateById(submission);
    } else {
      submission.setGradingStatus(
          autoGrade ? GradingStatus.AUTO_GRADING : GradingStatus.MANUAL_GRADING);
      submissions.updateById(submission);
    }
    return new SubmissionPlan(submission, snapshot, tasks, false);
  }

  private List<SandboxOutcome> runProgramming(SubmissionPlan plan) {
    List<SandboxOutcome> outcomes = new ArrayList<>();
    for (ProgrammingTask task : plan.tasks()) {
      String requestId =
          sandboxRequestId(
              "submit",
              plan.submission().getId(),
              task.question().id(),
              task.answer().getCodeText());
      try {
        SandboxRunRequest request =
            sandboxRequest(task.question(), task.answer().getCodeText(), requestId, false);
        SandboxRunResponse response = ai.runSandbox(request);
        BigDecimal trustedAchievedScore = validateSandboxResponse(request, response);
        outcomes.add(
            new SandboxOutcome(
                task.question().id(), requestId, response, trustedAchievedScore, null));
      } catch (RuntimeException failure) {
        // Transport errors and invalid sandbox responses are infrastructure failures. Retrying is
        // bounded by the persistent grading attempt counter and must not be mislabeled as a
        // student error; valid student code failures arrive as structured sandbox results.
        throw failure;
      }
    }
    return outcomes;
  }

  private boolean isAcceptedSandboxStatus(String status) {
    return "ACCEPTED".equals(status);
  }

  private String explainPublicFailures(
      QuestionSnapshot question,
      String sourceCode,
      String requestId,
      SandboxRunResponse response) {
    if (isAcceptedSandboxStatus(response.status())) return null;
    Map<Long, PublicTestCase> publicTests = new HashMap<>();
    question.publicTests().forEach(test -> publicTests.put(test.id(), test));
    List<CodeExplainRequest.CodeFailureInput> failures =
        response.testResults().stream()
            .filter(result -> "PUBLIC".equals(result.visibility()))
            .filter(result -> !"ACCEPTED".equals(result.status()))
            .map(
                result -> {
                  PublicTestCase test = publicTests.get(result.testCaseId());
                  if (test == null) {
                    throw new BusinessException(
                        ErrorCode.CODE_SANDBOX_INVALID_RESPONSE,
                        "Public sandbox result does not match the immutable snapshot");
                  }
                  return new CodeExplainRequest.CodeFailureInput(
                      test.position(),
                      result.status(),
                      test.input(),
                      test.expectedOutput(),
                      result.actualOutput(),
                      result.errorMessage());
                })
            .toList();
    if (failures.isEmpty()) return null;
    List<String> knowledgePointIds =
        question.raw().path("knowledgePoints").isArray()
            ? stream(question.raw().path("knowledgePoints"))
                .map(node -> node.path("id").asText())
                .filter(id -> !id.isBlank())
                .distinct()
                .toList()
            : List.of();
    CodeExplainResponse explanation =
        ai.explainCodeResult(
            new CodeExplainRequest(
                requestId, question.id(), knowledgePointIds, sourceCode, failures));
    if (explanation == null
        || !requestId.equals(explanation.requestId())
        || explanation.feedback() == null
        || explanation.feedback().isBlank()) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_INVALID_RESPONSE, "代码教学解释服务返回无效响应");
    }
    return explanation.feedback();
  }

  private java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }

  private Submission finishSubmit(
      Long studentId, SubmissionPlan plan, List<SandboxOutcome> outcomes, boolean timeout) {
    SubmissionEntity submission = submissions.selectForUpdate(plan.submission().getId());
    if (submission == null) throw notFound("作答不存在");
    // The sandbox call runs outside the database transaction. A teacher may deliberately take
    // over grading while that call is in flight. Only the exact durable AUTO_GRADING attempt that
    // produced this plan may write its result; otherwise this worker is stale and must be a no-op.
    Integer expectedAttempt = plan.submission().getGradingAttemptCount();
    boolean claimed =
        submission.getGradingStatus() == GradingStatus.AUTO_GRADING
            && Objects.equals(submission.getGradingAttemptCount(), expectedAttempt)
            && (timeout
                ? submission.getStatus() == SubmissionStatus.AUTO_SUBMITTED
                : submission.getStatus() == SubmissionStatus.GRADING);
    if (!claimed) return toSubmission(submission, false);
    Map<Long, SandboxOutcome> byQuestion = new HashMap<>();
    outcomes.forEach(o -> byQuestion.put(o.questionId(), o));
    AssessmentSettings settings = snapshotSettings(snapshotRoot(plan.snapshot()));
    boolean autoGrade = settings.autoGradeEnabled();
    BigDecimal autoTotal = BigDecimal.ZERO;
    boolean needsManual = !autoGrade;
    LocalDateTime now = LocalDateTime.now();
    for (QuestionSnapshot q : snapshotQuestions(plan.snapshot())) {
      SubmissionAnswerEntity answer = findAnswer(submission.getId(), q.id());
      if (answer == null) {
        answer = new SubmissionAnswerEntity();
        answer.setSubmissionId(submission.getId());
        answer.setQuestionId(q.id());
        answer.setClientSaveSequence(0L);
        answer.setAnswerJson("{}");
        answer.setSavedAt(now);
        answer.setGradingStatus(GradingStatus.NOT_STARTED);
        answers.insert(answer);
      }
      BigDecimal score = null;
      if (!autoGrade) {
        answer.setGradingStatus(GradingStatus.MANUAL_GRADING);
      } else if (q.type() == QuestionType.SINGLE_CHOICE
          || q.type() == QuestionType.MULTIPLE_CHOICE) {
        score = gradeChoice(q, answer);
        answer.setGradingStatus(GradingStatus.COMPLETED);
      } else if (q.type() == QuestionType.PROGRAMMING) {
        SandboxOutcome outcome = byQuestion.get(q.id());
        if (outcome != null && outcome.response() != null) {
          score = outcome.trustedAchievedScore();
          answer.setGradingStatus(GradingStatus.COMPLETED);
          persistRun(
              answer,
              studentId,
              q.id(),
              "SUBMIT",
              outcome.requestId(),
              answer.getCodeText(),
              outcome.response());
        } else if (answer.getCodeText() == null || answer.getCodeText().isBlank()) {
          score = BigDecimal.ZERO;
          answer.setGradingStatus(GradingStatus.COMPLETED);
        } else {
          answer.setGradingStatus(GradingStatus.FAILED);
          needsManual = true;
        }
      } else {
        answer.setGradingStatus(GradingStatus.MANUAL_GRADING);
        needsManual = true;
      }
      answer.setAutoScore(score);
      if (score != null) answer.setFinalScore(score);
      answer.setSubmittedAt(now);
      answers.updateById(answer);
      if (score != null) autoTotal = autoTotal.add(score);
      if (score != null) {
        jdbc.update(
            "INSERT INTO edu_grading_record(answer_id,grading_source,suggested_score,final_score,evidence_json,confidence,created_at) VALUES(?,?,?,?,?,1,CURRENT_TIMESTAMP(3))",
            answer.getId(), "AUTO", score, score, "{\"rubricGrades\":[]}");
      }
    }
    submission.setAutoScore(autoTotal);
    if (!needsManual) submission.setFinalScore(autoTotal);
    submission.setSubmittedAt(now);
    submission.setSubmittedByTimeout(timeout);
    submission.setStatus(
        timeout
            ? (needsManual ? SubmissionStatus.AUTO_SUBMITTED : SubmissionStatus.GRADED)
            : (needsManual ? SubmissionStatus.GRADING : SubmissionStatus.GRADED));
    submission.setGradingStatus(
        needsManual ? GradingStatus.MANUAL_GRADING : GradingStatus.COMPLETED);
    if (!needsManual && settings.autoReleaseEnabled()) {
      submission.setResultReleasedAt(now);
    }
    submissions.updateById(submission);
    audit.record(
        timeout ? "SUBMISSION_AUTO_SUBMITTED" : "SUBMISSION_SUBMITTED",
        "SUBMISSION",
        submission.getId().toString(),
        studentId,
        true,
        Map.of("autoScore", autoTotal));
    return toSubmission(submission, false);
  }

  public CodeRunResult runCode(
      AuthenticatedUser principal, CodeRunRequest request, String deviceId) {
    requireStudent(principal);
    if (!"ASSESSMENT".equals(request.contextType()) || request.submissionId() == null)
      throw validation("当前周期仅支持测评上下文代码运行");
    if (!"PYTHON".equals(request.language())) throw validation("仅支持 PYTHON");
    SubmissionEntity locator = requireOwned(principal, request.submissionId());
    return assessmentActivityGuard.withActivity(
        locator.getAssessmentId(), () -> runCodeGuarded(principal, request, deviceId));
  }

  private CodeRunResult runCodeGuarded(
      AuthenticatedUser principal, CodeRunRequest request, String deviceId) {
    SubmissionEntity submission = requireOwned(principal, request.submissionId());
    ensureEditableReadOnly(submission);
    ensureAssessmentOpen(submission);
    ensureHomeworkWithinDeadline(submission);
    verifyDevice(submission, deviceId);
    QuestionSnapshot question = requireSnapshotQuestion(submission, request.questionId());
    if (question.type() != QuestionType.PROGRAMMING) throw validation("该题不是编程题");
    courses.requireCode(courses.classCourse(requireAssessment(submission.getAssessmentId()).getClassId()));
    if (!activePublicRunStudents.add(principal.id())) {
      throw new BusinessException(
          ErrorCode.CODE_SANDBOX_RATE_LIMITED, "该学生已有公开测试正在运行");
    }
    try {
      String requestId =
          sandboxRequestId(
              "run", submission.getId(), question.id(), request.sourceCode() + UUID.randomUUID());
      SandboxRunRequest sandboxRequest =
          sandboxRequest(question, request.sourceCode(), requestId, true);
      SandboxRunResponse response = ai.runSandbox(sandboxRequest);
      validateSandboxResponse(sandboxRequest, response);
      CodeRunRecordEntity record =
          Objects.requireNonNull(
              tx.execute(
                  ignored -> {
                    AssessmentEntity assessment =
                        requireAssessmentForUpdate(submission.getAssessmentId());
                    SubmissionEntity fresh = requireOwnedForUpdate(principal, submission.getId());
                    if (!assessment.getId().equals(fresh.getAssessmentId())) {
                      throw conflict("答卷关联测评已改变");
                    }
                    ensureEditableReadOnly(fresh);
                    ensureAssessmentOpen(assessment);
                    ensureHomeworkWithinDeadline(fresh, assessment);
                    verifyDevice(fresh, deviceId);
                    return persistRun(
                        null,
                        principal.id(),
                        question.id(),
                        "PUBLIC",
                        requestId,
                        request.sourceCode(),
                        response);
                  }));
      String teachingFeedback = null;
      try {
        teachingFeedback =
            explainPublicFailures(question, request.sourceCode(), requestId, response);
      } catch (RuntimeException ignored) {
        // Explanations are optional enrichment. The validated sandbox result remains authoritative.
      }
      return publicRunResult(record, response, question, teachingFeedback);
    } finally {
      activePublicRunStudents.remove(principal.id());
    }
  }

  public ExamHeartbeatData heartbeat(
      AuthenticatedUser principal,
      Long assessmentId,
      ExamHeartbeatRequest request,
      String deviceId) {
    requireStudent(principal);
    SubmissionEntity submission = requireOwned(principal, request.submissionId());
    if (!submission.getAssessmentId().equals(assessmentId) || !isExam(submission))
      throw validation("考试会话不匹配");
    verifyDevice(submission, deviceId);
    verifyExamToken(submission, request.examSessionToken());
    if (submission.getStatus() != SubmissionStatus.IN_PROGRESS) throw conflict("考试已经提交");
    if (requireAssessment(assessmentId).getStatus() != AssessmentStatus.PUBLISHED) {
      autoSubmit(submission.getId());
      throw conflict("考试已截止，正在自动交卷");
    }
    LocalDateTime now = LocalDateTime.now();
    boolean expired = !now.isBefore(submission.getExamDeadlineAt());
    if (expired && submission.getStatus() == SubmissionStatus.IN_PROGRESS)
      autoSubmit(submission.getId());
    else if (submission.getStatus() == SubmissionStatus.IN_PROGRESS)
      submissions.touchHeartbeat(submission.getId(), now);
    SubmissionEntity fresh = requireSubmission(submission.getId());
    return new ExamHeartbeatData(
        toInstant(now),
        toInstant(fresh.getExamDeadlineAt()),
        remaining(fresh, now),
        expired,
        fresh.getStatus() == SubmissionStatus.IN_PROGRESS,
        fresh.getAcceptedSaveSequence());
  }

  public void reportEvents(
      AuthenticatedUser principal,
      Long assessmentId,
      AntiCheatEventsRequest request,
      String deviceId) {
    requireStudent(principal);
    SubmissionEntity submission = requireOwned(principal, request.submissionId());
    if (!submission.getAssessmentId().equals(assessmentId) || !isExam(submission))
      throw validation("考试会话不匹配");
    verifyDevice(submission, deviceId);
    verifyExamToken(submission, request.examSessionToken());
    AssessmentSettings settings =
        snapshotSettings(snapshotRoot(requireBoundVersion(submission).getSnapshotJson()));
    List<PreparedAntiCheatEvent> prepared = new ArrayList<>();
    long acceptedDetailsBytes = 0;
    for (AntiCheatEventInput input : request.events()) {
      if (!EVENT_TYPES.contains(input.eventType())) throw validation("未知防作弊事件类型");
      if (!settings.antiCheatPolicy().enabled()) continue;
      if (("COPY".equals(input.eventType())
              || "PASTE".equals(input.eventType())
              || "CONTEXT_MENU".equals(input.eventType()))
          && !settings.antiCheatPolicy().recordCopyPaste()) continue;
      if (("PAGE_HIDDEN".equals(input.eventType()) || "WINDOW_BLUR".equals(input.eventType()))
          && !settings.antiCheatPolicy().recordPageVisibility()) continue;
      String detailsJson = write(input.details() == null ? Map.of() : input.details());
      int detailsBytes = detailsJson.getBytes(StandardCharsets.UTF_8).length;
      if (detailsBytes > 4096) throw validation("Anti-cheat event details exceed 4096 bytes");
      acceptedDetailsBytes += detailsBytes;
      if (acceptedDetailsBytes > 65536) throw validation("Anti-cheat event batch exceeds 65536 bytes");
      prepared.add(new PreparedAntiCheatEvent(input, detailsJson));
    }
    tx.executeWithoutResult(
        status -> persistAntiCheatBatch(submission, settings, List.copyOf(prepared)));
  }

  private void persistAntiCheatBatch(
      SubmissionEntity submission,
      AssessmentSettings settings,
      List<PreparedAntiCheatEvent> prepared) {
    for (PreparedAntiCheatEvent item : prepared) {
      AntiCheatEventInput input = item.input();
      AntiCheatEventEntity event = new AntiCheatEventEntity();
      event.setSubmissionId(submission.getId());
      event.setClientEventId(input.clientEventId());
      event.setEventType(input.eventType());
      event.setSeverity(severity(input.eventType()));
      event.setEventAt(toLocal(input.occurredAt()));
      event.setDetailsJson(item.detailsJson());
      try {
        events.insert(event);
      } catch (DuplicateKeyException duplicate) {
        // Idempotent replay: the unique (submission_id, client_event_id) row already exists.
      }
    }
    long count =
        events.selectCount(
            new LambdaQueryWrapper<AntiCheatEventEntity>()
                .eq(AntiCheatEventEntity::getSubmissionId, submission.getId())
                .ne(AntiCheatEventEntity::getSeverity, "INFO"));
    if (count >= settings.antiCheatPolicy().suspiciousEventThreshold()
        && !Boolean.TRUE.equals(submission.getSuspicious())) {
      submissions.markSuspiciousIfInProgress(submission.getId());
    }
  }

  public AntiCheatSummary antiCheatSummary(
      AuthenticatedUser principal, Long assessmentId, Long studentId) {
    requireTeacherAssessment(principal, assessmentId);
    List<SubmissionEntity> scoped =
        submissions.selectList(
            new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getAssessmentId, assessmentId)
                .eq(studentId != null, SubmissionEntity::getStudentId, studentId));
    Set<Long> ids = new HashSet<>();
    scoped.forEach(s -> ids.add(s.getId()));
    if (ids.isEmpty())
      return new AntiCheatSummary(assessmentId, studentId, 0, 0, Map.of(), List.of());
    List<AntiCheatEventEntity> found =
        events.selectList(
            new LambdaQueryWrapper<AntiCheatEventEntity>()
                .in(AntiCheatEventEntity::getSubmissionId, ids)
                .orderByAsc(AntiCheatEventEntity::getEventAt));
    Map<String, Long> counts = new LinkedHashMap<>();
    found.forEach(e -> counts.merge(e.getEventType(), 1L, Long::sum));
    long suspicious = scoped.stream().filter(s -> Boolean.TRUE.equals(s.getSuspicious())).count();
    return new AntiCheatSummary(
        assessmentId,
        studentId,
        found.size(),
        suspicious,
        counts,
        found.stream().map(this::toEvent).toList());
  }

  public SubmissionResult result(AuthenticatedUser principal, Long submissionId) {
    requireStudent(principal);
    SubmissionEntity submission = requireOwned(principal, submissionId);
    requireAssessment(submission.getAssessmentId());
    if (submission.getResultReleasedAt() == null)
      throw new BusinessException(ErrorCode.RESULT_NOT_RELEASED, "成绩尚未公布");
    String snapshot = requireBoundVersion(submission).getSnapshotJson();
    AssessmentSettings visibility = snapshotSettings(snapshotRoot(snapshot));
    boolean showScore = visibility.showScoreAfterRelease();
    boolean showFeedback = visibility.showQuestionFeedbackAfterRelease();
    List<AnswerResult> results = new ArrayList<>();
    for (QuestionSnapshot q : snapshotQuestions(snapshot)) {
      SubmissionAnswerEntity answer = findAnswer(submissionId, q.id());
      BigDecimal score =
          answer == null
              ? BigDecimal.ZERO
              : Optional.ofNullable(answer.getFinalScore())
                  .orElse(Optional.ofNullable(answer.getAutoScore()).orElse(BigDecimal.ZERO));
      results.add(
          new AnswerResult(
              q.id(),
              showScore ? score : null,
              q.score(),
              showFeedback ? score.compareTo(q.score()) == 0 : null,
              visibility.showCorrectAnswerAfterRelease() ? q.correctKeys() : List.of(),
              visibility.showCorrectAnswerAfterRelease() ? q.referenceAnswer() : null,
              showFeedback && answer != null
                  ? answer.getTeacherComment()
                  : null,
              null,
              null,
              List.of()));
    }
    BigDecimal achieved =
        Optional.ofNullable(submission.getFinalScore())
            .orElse(Optional.ofNullable(submission.getAutoScore()).orElse(BigDecimal.ZERO));
    return new SubmissionResult(
        submissionId,
        submission.getAssessmentId(),
        snapshotRoot(snapshot).path("totalScore").decimalValue(),
        showScore ? achieved : null,
        showFeedback ? submission.getOverallComment() : null,
        results,
        List.of(),
        toInstant(submission.getResultReleasedAt()));
  }

  @Scheduled(fixedDelayString = "${education.submission.expiry-scan-ms:5000}")
  public void autoSubmitExpired() {
    for (Long id : submissions.findExpiredIds(100))
      try {
        autoSubmit(id);
      } catch (RuntimeException ignored) {
      }
    for (Long id : submissions.findRecoverableGradingIds(100))
      try {
        scheduleGrading(id);
      } catch (RuntimeException ignored) {
        // One saturated/rejected item must not stop recovery scheduling for the rest.
      }
  }

  private void autoSubmit(Long id) {
    SubmissionEntity initial = requireSubmission(id);
    UserEntity user = users.selectById(initial.getStudentId());
    if (user == null) return;
    AuthenticatedUser principal = AuthenticatedUser.from(user);
    SubmissionPlan plan =
        Objects.requireNonNull(
            tx.execute(
                s -> prepareSubmit(principal, id, new SubmitRequest(true, null), true, null)));
    if (!plan.alreadySubmitted()) {
      scheduleGrading(id);
    }
  }

  public void autoSubmitForAssessmentClose(Long id) {
    autoSubmit(id);
  }

  private void scheduleGrading(Long submissionId) {
    if (!activeGradingSubmissions.add(submissionId)) return;
    try {
      gradingExecutor.execute(() -> gradeClaimedSubmission(submissionId));
    } catch (RejectedExecutionException rejected) {
      activeGradingSubmissions.remove(submissionId);
      throw rejected;
    }
  }

  private void gradeClaimedSubmission(Long submissionId) {
    SubmissionPlan plan = null;
    try {
      plan = claimGradingWork(submissionId);
      if (plan == null) return;
      SubmissionPlan claimedPlan = plan;
      gradingFailureHook.afterClaim(submissionId);
      List<SandboxOutcome> outcomes = runProgramming(claimedPlan);
      boolean timeout = claimedPlan.submission().getStatus() == SubmissionStatus.AUTO_SUBMITTED;
      tx.execute(
          status ->
              finishSubmit(
                  claimedPlan.submission().getStudentId(), claimedPlan, outcomes, timeout));
    } catch (RuntimeException failure) {
      if (plan != null) {
        markClaimFailed(submissionId, plan.submission().getGradingAttemptCount());
      }
    } finally {
      activeGradingSubmissions.remove(submissionId);
    }
  }

  private SubmissionPlan claimGradingWork(Long submissionId) {
    return tx.execute(
        status -> {
          SubmissionEntity submission = submissions.selectForUpdate(submissionId);
          if (submission == null
              || (submission.getStatus() != SubmissionStatus.GRADING
                  && submission.getStatus() != SubmissionStatus.AUTO_SUBMITTED)
              || (submission.getGradingStatus() != GradingStatus.AUTO_GRADING
                  && submission.getGradingStatus() != GradingStatus.FAILED)) return null;
          int attempts = Optional.ofNullable(submission.getGradingAttemptCount()).orElse(0);
          if (attempts >= 2) return null;
          String snapshot = requireBoundVersion(submission).getSnapshotJson();
          List<ProgrammingTask> tasks = new ArrayList<>();
          if (snapshotSettings(snapshotRoot(snapshot)).autoGradeEnabled())
            for (QuestionSnapshot question : snapshotQuestions(snapshot))
              if (question.type() == QuestionType.PROGRAMMING) {
                SubmissionAnswerEntity answer = findAnswer(submissionId, question.id());
                if (answer != null
                    && answer.getCodeText() != null
                    && !answer.getCodeText().isBlank())
                  tasks.add(new ProgrammingTask(question, answer));
              }
          submission.setGradingStatus(GradingStatus.AUTO_GRADING);
          submission.setGradingAttemptCount(attempts + 1);
          submissions.updateById(submission);
          return new SubmissionPlan(submission, snapshot, tasks, false);
        });
  }

  private void markClaimFailed(Long submissionId) {
    markClaimFailed(submissionId, null);
  }

  private void markClaimFailed(Long submissionId, Integer expectedAttempt) {
    tx.executeWithoutResult(
        status -> {
          SubmissionEntity submission = submissions.selectForUpdate(submissionId);
          if (submission != null
              && submission.getGradingStatus() == GradingStatus.AUTO_GRADING
              && (expectedAttempt == null
                  || Objects.equals(submission.getGradingAttemptCount(), expectedAttempt))) {
            submission.setGradingStatus(GradingStatus.FAILED);
            submissions.updateById(submission);
          }
        });
  }

  private boolean isCompletedSubmission(SubmissionEntity submission) {
    return submission.getStatus() == SubmissionStatus.SUBMITTED
        || submission.getStatus() == SubmissionStatus.AUTO_SUBMITTED
        || submission.getStatus() == SubmissionStatus.GRADED
        || (submission.getStatus() == SubmissionStatus.GRADING
            && submission.getGradingStatus() != GradingStatus.AUTO_GRADING
            && submission.getGradingStatus() != GradingStatus.FAILED);
  }

  private <T> IdempotencyClaim<T> claimIdempotency(
      Long userId,
      String operationId,
      String idempotencyKey,
      String requestHash,
      Class<T> responseType) {
    try {
      IdempotencyClaim<T> claim =
          tx.execute(
              status -> {
                IdempotencyRecordEntity existing =
                    findIdempotency(userId, operationId, idempotencyKey);
                if (existing != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
                  idempotencyRecords.deleteById(existing.getId());
                  existing = null;
                }
                if (existing != null) {
                  return replayOrConflict(existing, requestHash, responseType);
                }
                IdempotencyRecordEntity record = new IdempotencyRecordEntity();
                record.setUserId(userId);
                record.setOperationId(operationId);
                record.setIdempotencyKey(idempotencyKey);
                record.setRequestHash(requestHash);
                record.setExpiresAt(LocalDateTime.now().plusHours(24));
                idempotencyRecords.insert(record);
                activeIdempotencyClaims.add(record.getId());
                return new IdempotencyClaim<>(record.getId(), null);
              });
      return Objects.requireNonNull(claim);
    } catch (DuplicateKeyException race) {
      IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
      if (existing == null) {
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同请求正在处理中");
      }
      return replayOrConflict(existing, requestHash, responseType);
    }
  }

  private <T> IdempotencyClaim<T> replayOrConflict(
      IdempotencyRecordEntity existing, String requestHash, Class<T> responseType) {
    if (!requestHash.equals(existing.getRequestHash())) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同的资源或请求体");
    }
    if (existing.getResponseJson() == null) {
      if (activeIdempotencyClaims.contains(existing.getId())) {
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同请求正在处理中");
      }
      return new IdempotencyClaim<>(existing.getId(), null, true);
    }
    try {
      return new IdempotencyClaim<>(
          existing.getId(), json.readValue(existing.getResponseJson(), responseType), false);
    } catch (Exception invalidStoredResponse) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法恢复幂等请求结果");
    }
  }

  private IdempotencyRecordEntity findIdempotency(
      Long userId, String operationId, String idempotencyKey) {
    return idempotencyRecords.selectOne(
        new LambdaQueryWrapper<IdempotencyRecordEntity>()
            .eq(IdempotencyRecordEntity::getUserId, userId)
            .eq(IdempotencyRecordEntity::getOperationId, operationId)
            .eq(IdempotencyRecordEntity::getIdempotencyKey, idempotencyKey));
  }

  private void completeIdempotencyClaim(Long recordId, Object response) {
    tx.executeWithoutResult(
        status -> {
          IdempotencyRecordEntity record = idempotencyRecords.selectById(recordId);
          if (record == null || record.getResponseJson() != null) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "幂等请求状态已经改变");
          }
          record.setResponseStatus(200);
          record.setResponseJson(write(response));
          idempotencyRecords.updateById(record);
          activeIdempotencyClaims.remove(recordId);
        });
  }

  private void releaseIdempotencyClaim(Long recordId) {
    tx.executeWithoutResult(
        status -> {
          IdempotencyRecordEntity record = idempotencyRecords.selectById(recordId);
          if (record != null && record.getResponseJson() == null) {
            idempotencyRecords.deleteById(recordId);
          }
          activeIdempotencyClaims.remove(recordId);
        });
  }

  // ---------- mapping, validation, and immutable-snapshot helpers ----------
  private record StartState(SubmissionEntity submission, String snapshot, boolean created) {}

  private record IdempotencyClaim<T>(Long recordId, T replay, boolean incompleteReplay) {
    private IdempotencyClaim(Long recordId, T replay) {
      this(recordId, replay, false);
    }
  }

  private record ProgrammingTask(QuestionSnapshot question, SubmissionAnswerEntity answer) {}

  private record PreparedAntiCheatEvent(AntiCheatEventInput input, String detailsJson) {}

  private record SandboxOutcome(
      Long questionId,
      String requestId,
      SandboxRunResponse response,
      BigDecimal trustedAchievedScore,
      RuntimeException failure) {}

  private record SubmissionPlan(
      SubmissionEntity submission,
      String snapshot,
      List<ProgrammingTask> tasks,
      boolean alreadySubmitted) {}

  private record QuestionSnapshot(
      Long id,
      QuestionType type,
      String stem,
      BigDecimal score,
      int position,
      List<QuestionOptionPaper> paperOptions,
      List<String> correctKeys,
      Object referenceAnswer,
      ProgrammingConfig programming,
      List<PublicTestCase> publicTests,
      AttachmentPolicy attachmentPolicy,
      JsonNode raw) {}

  private SubmissionSession toSession(SubmissionEntity s, String snapshot) {
    JsonNode root = snapshotRoot(snapshot);
    AssessmentSettings settings = snapshotSettings(root);
    List<QuestionSnapshot> orderedQuestions = paperQuestionOrder(s, snapshot, settings);
    List<QuestionPaper> paper = new ArrayList<>();
    for (int questionIndex = 0; questionIndex < orderedQuestions.size(); questionIndex++) {
      QuestionSnapshot q = orderedQuestions.get(questionIndex);
      List<QuestionOptionPaper> paperOptions = paperOptionOrder(s, q, settings);
      paper.add(
          new QuestionPaper(
              q.id(),
              q.type(),
              q.stem(),
              q.score(),
              questionIndex + 1,
              paperOptions,
              q.programming(),
              q.publicTests(),
              q.attachmentPolicy()));
    }
    AssessmentPaper assessmentPaper =
        new AssessmentPaper(
            s.getAssessmentId(),
            AssessmentType.valueOf(root.path("assessmentType").asText()),
            root.path("title").asText(),
            nullIfMissing(root, "description"),
            root.path("totalScore").decimalValue(),
            s.getAssessmentVersion(),
            paper);
    ExamSession exam = null;
    if (isExam(s)) {
      LocalDateTime now = LocalDateTime.now();
      boolean antiCheatEnabled = settings.antiCheatPolicy().enabled();
      exam =
          new ExamSession(
              examToken(s),
              toInstant(now),
              toInstant(s.getExamDeadlineAt()),
              remaining(s, now),
              Optional.ofNullable(s.getHeartbeatIntervalSeconds()).orElse(15),
              antiCheatEnabled && settings.antiCheatPolicy().requireFullscreen(),
              !now.isBefore(s.getExamDeadlineAt()),
              antiCheatEnabled && settings.antiCheatPolicy().recordCopyPaste(),
              antiCheatEnabled && settings.antiCheatPolicy().recordPageVisibility());
    }
    return new SubmissionSession(toSubmission(s, false), assessmentPaper, exam);
  }

  private List<QuestionSnapshot> paperQuestionOrder(
      SubmissionEntity submission, String snapshot, AssessmentSettings settings) {
    List<QuestionSnapshot> ordered = new ArrayList<>(snapshotQuestions(snapshot));
    ordered.sort(
        Comparator.comparingInt(QuestionSnapshot::position).thenComparing(QuestionSnapshot::id));
    if (settings.shuffleQuestions()) {
      stableShuffle(
          ordered, submission.getId(), "question", question -> Long.toString(question.id()));
    }
    return List.copyOf(ordered);
  }

  private List<QuestionOptionPaper> paperOptionOrder(
      SubmissionEntity submission, QuestionSnapshot question, AssessmentSettings settings) {
    List<QuestionOptionPaper> ordered = new ArrayList<>(question.paperOptions());
    ordered.sort(
        Comparator.comparingInt(QuestionOptionPaper::position)
            .thenComparing(QuestionOptionPaper::key));
    if (settings.shuffleOptions()) {
      stableShuffle(
          ordered, submission.getId(), "option:" + question.id(), QuestionOptionPaper::key);
    }
    List<QuestionOptionPaper> presented = new ArrayList<>();
    for (int optionIndex = 0; optionIndex < ordered.size(); optionIndex++) {
      QuestionOptionPaper option = ordered.get(optionIndex);
      presented.add(new QuestionOptionPaper(option.key(), option.content(), optionIndex + 1));
    }
    return List.copyOf(presented);
  }

  private <T> void stableShuffle(
      List<T> values,
      Long submissionId,
      String scope,
      java.util.function.Function<T, String> stableKey) {
    if (values.size() < 2) return;
    List<T> original = new ArrayList<>(values);
    values.sort(
        Comparator.<T, String>comparing(
                value ->
                    sha256(
                        sessionSecret
                            + ":"
                            + submissionId
                            + ":"
                            + scope
                            + ":"
                            + stableKey.apply(value)))
            .thenComparing(stableKey));
    if (values.equals(original)) {
      Collections.rotate(values, -1);
    }
  }

  private Submission toSubmission(SubmissionEntity s, boolean gradesVisible) {
    return toSubmission(s, gradesVisible, gradesVisible, gradesVisible);
  }

  private Submission toSubmission(
      SubmissionEntity s,
      boolean scoresVisible,
      boolean feedbackVisible,
      boolean releaseVisible) {
    List<SubmissionAnswer> list =
        answers
            .selectList(
                new LambdaQueryWrapper<SubmissionAnswerEntity>()
                    .eq(SubmissionAnswerEntity::getSubmissionId, s.getId())
                    .orderByAsc(SubmissionAnswerEntity::getQuestionId))
            .stream()
            .map(a -> toAnswer(a, scoresVisible, feedbackVisible))
            .toList();
    UserEntity user = users.selectById(s.getStudentId());
    return new Submission(
        s.getId(),
        s.getAssessmentId(),
        s.getAssessmentVersion(),
        s.getStudentId(),
        user == null ? null : user.getDisplayName(),
        s.getStatus(),
        s.getGradingStatus(),
        s.getAttemptNo(),
        toInstant(s.getStartedAt()),
        toInstant(s.getSubmittedAt()),
        scoresVisible ? s.getAutoScore() : null,
        scoresVisible ? s.getFinalScore() : null,
        releaseVisible ? toInstant(s.getResultReleasedAt()) : null,
        feedbackVisible ? s.getOverallComment() : null,
        s.getSuspicious(),
        list,
        toInstant(s.getCreatedAt()),
        toInstant(s.getUpdatedAt()));
  }

  private SubmissionAnswer toAnswer(SubmissionAnswerEntity a, boolean gradesVisible) {
    return toAnswer(a, gradesVisible, gradesVisible);
  }

  private SubmissionAnswer toAnswer(
      SubmissionAnswerEntity a, boolean scoresVisible, boolean feedbackVisible) {
    Map<String, Object> payload = readMap(a.getAnswerJson());
    List<String> selected = stringList(payload.get("selectedOptionKeys"));
    String text = (String) payload.get("textAnswer");
    List<FileObjectView> fileViews =
        attachments.findFileIds(a.getId()).stream()
            .map(files::findAvailable)
            .map(FileObjectView::from)
            .toList();
    return new SubmissionAnswer(
        a.getId(),
        a.getQuestionId(),
        selected,
        text,
        a.getCodeText(),
        fileViews,
        toInstant(a.getSavedAt()),
        toInstant(a.getSubmittedAt()),
        scoresVisible ? a.getAutoScore() : null,
        scoresVisible ? a.getFinalScore() : null,
        a.getGradingStatus(),
        feedbackVisible ? a.getTeacherComment() : null);
  }

  private List<QuestionSnapshot> snapshotQuestions(String snapshot) {
    List<QuestionSnapshot> result = new ArrayList<>();
    for (JsonNode q : snapshotRoot(snapshot).path("questions")) {
      QuestionType type = QuestionType.valueOf(q.path("questionType").asText());
      List<QuestionOptionPaper> paperOptions = new ArrayList<>();
      List<String> correct = new ArrayList<>();
      for (JsonNode option : q.path("options")) {
        paperOptions.add(
            new QuestionOptionPaper(
                option.path("key").asText(),
                option.path("content").asText(),
                option.path("position").asInt()));
        if (option.path("isCorrect").asBoolean(false)) correct.add(option.path("key").asText());
      }
      List<PublicTestCase> publicTests = new ArrayList<>();
      for (JsonNode t : q.path("testCases"))
        if ("PUBLIC".equals(t.path("visibility").asText()))
          publicTests.add(
              new PublicTestCase(
                  t.path("id").asLong(),
                  t.path("input").asText(),
                  t.path("expectedOutput").asText(),
                  t.path("position").asInt()));
      result.add(
          new QuestionSnapshot(
              q.path("id").asLong(),
              type,
              q.path("stem").asText(),
              q.path("score").decimalValue(),
              q.path("position").asInt(),
              List.copyOf(paperOptions),
              List.copyOf(correct),
              q.path("referenceAnswer").isMissingNode()
                  ? null
                  : json.convertValue(q.path("referenceAnswer"), Object.class),
              convertNullable(q.get("programmingConfig"), ProgrammingConfig.class),
              List.copyOf(publicTests),
              convertNullable(q.get("attachmentPolicy"), AttachmentPolicy.class),
              q));
    }
    return result;
  }

  private SandboxRunRequest sandboxRequest(
      QuestionSnapshot q, String code, String requestId, boolean publicOnly) {
    List<SandboxRunRequest.SandboxTestCase> cases = new ArrayList<>();
    for (JsonNode t : q.raw().path("testCases")) {
      if (publicOnly && !"PUBLIC".equals(t.path("visibility").asText())) continue;
      cases.add(
          new SandboxRunRequest.SandboxTestCase(
              t.path("id").asLong(),
              t.path("input").asText(),
              t.path("expectedOutput").asText(),
              t.path("score").decimalValue(),
              t.path("visibility").asText(),
              q.programming().compareMode().name(),
              q.programming().floatTolerance()));
    }
    ProgrammingConfig cfg = q.programming();
    return new SandboxRunRequest(
        requestId,
        publicOnly ? "PUBLIC_RUN" : "GRADING",
        "PYTHON_3_11",
        code,
        cases,
        new SandboxRunRequest.SandboxLimits(cfg.timeLimitMs(), cfg.memoryLimitMb(), 256));
  }

  private BigDecimal validateSandboxResponse(
      SandboxRunRequest request, SandboxRunResponse response) {
    if (response == null
        || !request.requestId().equals(response.requestId())
        || response.testResults() == null
        || response.testResults().size() != request.testCases().size()
        || !Objects.equals(response.totalCount(), request.testCases().size())) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_INVALID_RESPONSE, "代码沙箱返回无效响应");
    }
    Map<Long, SandboxRunRequest.SandboxTestCase> expected = new HashMap<>();
    request.testCases().forEach(test -> expected.put(test.testCaseId(), test));
    Set<Long> seen = new HashSet<>();
    BigDecimal computedTotal = BigDecimal.ZERO;
    BigDecimal computedAchieved = BigDecimal.ZERO;
    int computedPassed = 0;
    for (SandboxRunResponse.SandboxTestResult result : response.testResults()) {
      SandboxRunRequest.SandboxTestCase test = expected.get(result.testCaseId());
      if (test == null
          || !seen.add(result.testCaseId())
          || !SANDBOX_TEST_STATUSES.contains(result.status())
          || !test.visibility().equals(result.visibility())
          || result.score() == null
          || result.maxScore() == null
          || result.maxScore().compareTo(test.score()) != 0
          || result.score().signum() < 0
          || result.score().compareTo(test.score()) > 0
          || ("HIDDEN".equals(test.visibility())
              && (result.expectedOutput() != null
                  || (result.actualOutput() != null && !result.actualOutput().isBlank())
                  || (result.errorMessage() != null && !result.errorMessage().isBlank())))) {
        throw new BusinessException(ErrorCode.CODE_SANDBOX_INVALID_RESPONSE, "代码沙箱返回无效或泄漏隐藏测试点的响应");
      }
      computedTotal = computedTotal.add(test.score());
      computedAchieved = computedAchieved.add(result.score());
      if ("ACCEPTED".equals(result.status())) computedPassed++;
      if (("ACCEPTED".equals(result.status()) && result.score().compareTo(result.maxScore()) != 0)
          || (!"ACCEPTED".equals(result.status()) && result.score().signum() != 0)) {
        throw new BusinessException(ErrorCode.CODE_SANDBOX_INVALID_RESPONSE, "代码沙箱状态与测试点得分不一致");
      }
    }
    if (response.totalScore() == null
        || response.totalScore().compareTo(computedTotal) != 0
        || response.achievedScore() == null
        || response.achievedScore().compareTo(computedAchieved) != 0
        || response.passedCount() == null
        || response.passedCount() != computedPassed) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_INVALID_RESPONSE, "代码沙箱汇总与测试点明细不一致");
    }
    String expectedStatus;
    if (computedPassed == request.testCases().size()) {
      expectedStatus = "ACCEPTED";
    } else if (computedPassed > 0) {
      expectedStatus = "PARTIAL";
    } else {
      expectedStatus = expectedFailureStatus(response.testResults());
    }
    if (!expectedStatus.equals(response.status())) {
      throw new BusinessException(
          ErrorCode.CODE_SANDBOX_INVALID_RESPONSE,
          "Sandbox aggregate status does not match its test-case results");
    }
    return computedAchieved;
  }

  private String expectedFailureStatus(List<SandboxRunResponse.SandboxTestResult> testResults) {
    List<String> precedence =
        List.of(
            "SYNTAX_ERROR",
            "TIME_LIMIT_EXCEEDED",
            "MEMORY_LIMIT_EXCEEDED",
            "OUTPUT_LIMIT_EXCEEDED",
            "RUNTIME_ERROR",
            "SYSTEM_ERROR",
            "WRONG_ANSWER");
    Set<String> statuses = new HashSet<>();
    testResults.forEach(result -> statuses.add(result.status()));
    return precedence.stream().filter(statuses::contains).findFirst().orElse("WRONG_ANSWER");
  }

  private CodeRunRecordEntity persistRun(
      SubmissionAnswerEntity answer,
      Long studentId,
      Long questionId,
      String type,
      String requestId,
      String sourceCode,
      SandboxRunResponse response) {
    CodeRunRecordEntity record = new CodeRunRecordEntity();
    record.setRequestId(requestId);
    record.setAnswerId(answer == null ? null : answer.getId());
    record.setStudentId(studentId);
    record.setQuestionId(questionId);
    record.setRunType(type);
    record.setSourceHash(sha256(sourceCode));
    record.setStatus(response.status());
    record.setScore(response.achievedScore());
    record.setResultJson(write(response));
    record.setDurationMs(
        response.testResults() == null
            ? 0
            : response.testResults().stream()
                .mapToInt(r -> Optional.ofNullable(r.timeMs()).orElse(0))
                .sum());
    try {
      codeRuns.insert(record);
    } catch (DuplicateKeyException duplicate) {
      return codeRuns.selectOne(
          new LambdaQueryWrapper<CodeRunRecordEntity>()
              .eq(CodeRunRecordEntity::getRequestId, requestId));
    }
    return record;
  }

  private CodeRunResult publicRunResult(
      CodeRunRecordEntity record,
      SandboxRunResponse response,
      QuestionSnapshot question,
      String teachingFeedback) {
    Map<Long, Integer> positions = new HashMap<>();
    question.publicTests().forEach(t -> positions.put(t.id(), t.position()));
    List<TestCaseRunResult> cases =
        response.testResults().stream()
            .map(
                r ->
                    new TestCaseRunResult(
                        r.testCaseId(),
                        positions.get(r.testCaseId()),
                        TestVisibility.PUBLIC,
                        r.status(),
                        question.publicTests().stream()
                            .filter(t -> t.id().equals(r.testCaseId()))
                            .map(PublicTestCase::input)
                            .findFirst()
                            .orElse(null),
                        question.publicTests().stream()
                            .filter(t -> t.id().equals(r.testCaseId()))
                            .map(PublicTestCase::expectedOutput)
                            .findFirst()
                            .orElse(null),
                        r.actualOutput(),
                        r.errorMessage(),
                        r.timeMs(),
                        r.score()))
            .toList();
    return new CodeRunResult(
        record.getId(),
        response.status(),
        response.passedCount(),
        response.totalCount(),
        response.achievedScore(),
        record.getDurationMs(),
        null,
        response.compileMessage(),
        cases,
        teachingFeedback);
  }

  private BigDecimal gradeChoice(QuestionSnapshot q, SubmissionAnswerEntity answer) {
    Set<String> actual =
        new HashSet<>(stringList(readMap(answer.getAnswerJson()).get("selectedOptionKeys")));
    return actual.equals(new HashSet<>(q.correctKeys())) ? q.score() : BigDecimal.ZERO;
  }

  private void validateAnswer(
      AuthenticatedUser principal, QuestionSnapshot q, SaveAnswerRequest r) {
    Set<String> selected = new HashSet<>(r.selectedOptionKeys());
    if (selected.size() != r.selectedOptionKeys().size()) throw validation("选项不能重复");
    switch (q.type()) {
      case SINGLE_CHOICE -> {
        if (selected.size() > 1) throw validation("单选题最多选择一个选项");
        validateOptionKeys(q, selected);
        requireEmpty(r.textAnswer(), r.sourceCode(), r.attachmentFileIds());
      }
      case MULTIPLE_CHOICE -> {
        validateOptionKeys(q, selected);
        requireEmpty(r.textAnswer(), r.sourceCode(), r.attachmentFileIds());
      }
      case SUBJECTIVE -> {
        if (!selected.isEmpty() || r.sourceCode() != null || !r.attachmentFileIds().isEmpty())
          throw validation("主观题只允许文本答案");
      }
      case PROGRAMMING -> {
        if (!selected.isEmpty() || r.textAnswer() != null || !r.attachmentFileIds().isEmpty())
          throw validation("编程题只允许源代码");
        if (r.sourceCode() != null && r.sourceCode().length() > 65536)
          throw validation("源代码超过沙箱 65536 字符上限");
      }
      case ATTACHMENT -> {
        if (!selected.isEmpty() || r.sourceCode() != null) throw validation("附件题不允许选项或代码答案");
        validateAttachments(principal, q.attachmentPolicy(), r.attachmentFileIds());
      }
    }
  }

  private void validateOptionKeys(QuestionSnapshot q, Set<String> selected) {
    Set<String> allowed = new HashSet<>();
    q.paperOptions().forEach(o -> allowed.add(o.key()));
    if (!allowed.containsAll(selected)) throw validation("包含不存在的选项");
  }

  private void requireEmpty(String text, String code, List<Long> ids) {
    if (text != null || code != null || !ids.isEmpty()) throw validation("答案字段与题型不匹配");
  }

  private void validateAttachments(AuthenticatedUser p, AttachmentPolicy policy, List<Long> ids) {
    if (policy == null) throw validation("题目缺少附件策略");
    if (new HashSet<>(ids).size() != ids.size()) throw validation("附件不能重复");
    if (ids.size() > policy.maxFileCount()) throw validation("附件数量超过限制");
    for (Long id : ids) {
      FileObjectEntity f = files.requireOwnedAvailable(id, p, FilePurpose.SUBMISSION_ATTACHMENT);
      String name = f.getOriginalName().toLowerCase(Locale.ROOT);
      String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
      if (policy.allowedExtensions().stream().map(String::toLowerCase).noneMatch(ext::equals))
        throw validation("附件扩展名不符合题目要求");
      if (f.getSizeBytes() > policy.maxFileSizeMb() * 1024L * 1024L) throw validation("附件大小超过题目限制");
    }
  }

  private SubmissionEntity requireVisibleSubmission(AuthenticatedUser p, Long id) {
    SubmissionEntity s = requireSubmission(id);
    if (p.role() == UserRole.STUDENT && !s.getStudentId().equals(p.id())) throw notFound("作答不存在");
    if (p.role() == UserRole.TEACHER) requireTeacherAssessment(p, s.getAssessmentId());
    courses.requireView(p,courses.classCourse(requireAssessment(s.getAssessmentId()).getClassId()));
    return s;
  }

  private SubmissionEntity requireOwned(AuthenticatedUser p, Long id) {
    SubmissionEntity s = requireSubmission(id);
    if (p.role() != UserRole.STUDENT || !s.getStudentId().equals(p.id())) throw notFound("作答不存在");
    courses.requireView(p,courses.classCourse(requireAssessment(s.getAssessmentId()).getClassId()));
    return s;
  }

  private SubmissionEntity requireOwnedForUpdate(AuthenticatedUser p, Long id) {
    SubmissionEntity s = submissions.selectForUpdate(id);
    if (s == null || p.role() != UserRole.STUDENT || !s.getStudentId().equals(p.id()))
      throw notFound("作答不存在");
    courses.requireView(p,courses.classCourse(requireAssessment(s.getAssessmentId()).getClassId()));
    return s;
  }

  private SubmissionEntity requireSubmission(Long id) {
    SubmissionEntity s = submissions.selectById(id);
    if (s == null) throw notFound("作答不存在");
    return s;
  }

  private AssessmentEntity requireAssessment(Long id) {
    AssessmentEntity a = assessments.selectById(id);
    if (a == null) throw notFound("测评不存在");
    return a;
  }

  private AssessmentEntity requireAssessmentForUpdate(Long id) {
    AssessmentEntity a = assessments.selectForUpdate(id);
    if (a == null) throw notFound("测评不存在");
    return a;
  }

  private AssessmentEntity requireTeacherAssessment(AuthenticatedUser p, Long id) {
    AssessmentEntity a = requireAssessment(id);
    if (p.role() != UserRole.ADMIN
        && (p.role() != UserRole.TEACHER || !a.getCreatorId().equals(p.id())))
      throw notFound("测评不存在");
    courses.requireView(p,courses.classCourse(a.getClassId()));
    return a;
  }

  private void requireAssignedStudent(AuthenticatedUser p, AssessmentEntity a) {
    requireStudent(p);
    if (classStudents.countMembership(a.getClassId(), p.id()) == 0) throw notFound("测评不存在");
    courses.requireStudy(p,courses.classCourse(a.getClassId()));
    courses.requireActiveClass(a.getClassId());
  }

  private void requireStudent(AuthenticatedUser p) {
    if (p == null || p.role() != UserRole.STUDENT)
      throw new BusinessException(ErrorCode.FORBIDDEN, "只有学生可以执行此操作");
  }

  private AssessmentVersionEntity requireVersion(Long assessmentId, Integer no) {
    AssessmentVersionEntity v =
        versions.selectOne(
            new LambdaQueryWrapper<AssessmentVersionEntity>()
                .eq(AssessmentVersionEntity::getAssessmentId, assessmentId)
                .eq(AssessmentVersionEntity::getVersionNo, no));
    if (v == null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "发布版本不存在");
    return v;
  }

  private AssessmentVersionEntity requireBoundVersion(SubmissionEntity s) {
    AssessmentVersionEntity v =
        s.getAssessmentVersionId() == null
            ? requireVersion(s.getAssessmentId(), s.getAssessmentVersion())
            : versions.selectById(s.getAssessmentVersionId());
    if (v == null
        || !v.getAssessmentId().equals(s.getAssessmentId())
        || !v.getVersionNo().equals(s.getAssessmentVersion()))
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "答卷绑定版本失效");
    return v;
  }

  private QuestionSnapshot requireSnapshotQuestion(SubmissionEntity s, Long id) {
    return snapshotQuestions(requireBoundVersion(s).getSnapshotJson()).stream()
        .filter(q -> q.id().equals(id))
        .findFirst()
        .orElseThrow(() -> validation("题目不属于该答卷发布版本"));
  }

  private SubmissionAnswerEntity findAnswer(Long sid, Long qid) {
    return answers.selectOne(
        new LambdaQueryWrapper<SubmissionAnswerEntity>()
            .eq(SubmissionAnswerEntity::getSubmissionId, sid)
            .eq(SubmissionAnswerEntity::getQuestionId, qid));
  }

  private void ensureEditable(SubmissionEntity s) {
    expireIfNeeded(s);
    ensureEditableReadOnly(s);
  }

  private void ensureEditableReadOnly(SubmissionEntity s) {
    if (s.getStatus() != SubmissionStatus.IN_PROGRESS) throw conflict("答卷已经提交，不能继续修改");
    if (isExam(s) && !LocalDateTime.now().isBefore(s.getExamDeadlineAt()))
      throw conflict("考试已到服务器截止时间");
  }

  private void ensureHomeworkWithinDeadline(SubmissionEntity submission) {
    if (isExam(submission)) return;
    AssessmentEntity assessment = requireAssessment(submission.getAssessmentId());
    ensureHomeworkWithinDeadline(submission, assessment);
  }

  private void ensureHomeworkWithinDeadline(
      SubmissionEntity submission, AssessmentEntity assessment) {
    if (isExam(submission)) return;
    if (assessment.getDueAt() == null || LocalDateTime.now().isBefore(assessment.getDueAt())) {
      return;
    }
    String snapshot = requireBoundVersion(submission).getSnapshotJson();
    if (!snapshotSettings(snapshotRoot(snapshot)).allowLateSubmission()) {
      throw conflict("作业已过截止时间，且未开启迟交");
    }
  }

  private void ensureAssessmentOpen(SubmissionEntity submission) {
    ensureAssessmentOpen(requireAssessment(submission.getAssessmentId()));
  }

  private void ensureAssessmentOpen(AssessmentEntity assessment) {
    courses.requireActiveClass(assessment.getClassId());
    if (assessment.getStatus() != AssessmentStatus.PUBLISHED) {
      throw conflict("测评已经截止，不能继续作答");
    }
  }

  private void expireIfNeeded(SubmissionEntity s) {
    if (isExam(s)
        && s.getStatus() == SubmissionStatus.IN_PROGRESS
        && !LocalDateTime.now().isBefore(s.getExamDeadlineAt()))
      throw conflict("考试已到服务器截止时间，正在自动交卷");
  }

  private void expireAndPersist(SubmissionEntity s) {
    if (isExam(s)
        && s.getStatus() == SubmissionStatus.IN_PROGRESS
        && !LocalDateTime.now().isBefore(s.getExamDeadlineAt())) autoSubmit(s.getId());
  }

  private boolean isExam(SubmissionEntity s) {
    return s.getExamDeadlineAt() != null;
  }

  private boolean isReleasedState(AssessmentStatus status) {
    return status == AssessmentStatus.RELEASED || status == AssessmentStatus.ARCHIVED;
  }

  private long remaining(SubmissionEntity s, LocalDateTime now) {
    if (s.getExamDeadlineAt() == null || !now.isBefore(s.getExamDeadlineAt())) return 0;
    Duration remaining = Duration.between(now, s.getExamDeadlineAt());
    return remaining.getNano() == 0 ? remaining.getSeconds() : remaining.getSeconds() + 1;
  }

  private void verifyExamToken(SubmissionEntity s, String token) {
    if (token == null
        || !MessageDigest.isEqual(
            sha256(token).getBytes(StandardCharsets.UTF_8),
            Optional.ofNullable(s.getExamSessionTokenHash())
                .orElse("")
                .getBytes(StandardCharsets.UTF_8)))
      throw new BusinessException(ErrorCode.FORBIDDEN, "考试会话令牌无效");
  }

  private String examToken(SubmissionEntity s) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String data = s.getId() + ":" + s.getStudentId() + ":" + s.getAssessmentVersion();
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String sandboxRequestId(String prefix, Long sid, Long qid, String source) {
    return prefix.substring(0, 1) + "-" + sha256(sid + ":" + qid + ":" + source).substring(0, 48);
  }

  private String deviceRequestHash(String deviceId) {
    return deviceId == null ? "NONE" : sha256(deviceId);
  }

  private boolean allowLate(AssessmentEntity a) {
    return settings(a).allowLateSubmission();
  }

  private boolean singleDevice(AssessmentEntity a) {
    return a.getAssessmentType() == AssessmentType.EXAM
        && settings(a).antiCheatPolicy().singleDeviceOnly();
  }

  private void requireDeviceId(String id) {
    if (id == null || id.isBlank()) throw validation("此考试必须提供 X-Device-Id");
  }

  private void verifyDevice(SubmissionEntity s, String id) {
    if (s.getDeviceIdHash() == null) return;
    requireDeviceId(id);
    if (!MessageDigest.isEqual(
        s.getDeviceIdHash().getBytes(StandardCharsets.UTF_8),
        sha256(id).getBytes(StandardCharsets.UTF_8))) throw conflict("该考试已绑定另一设备会话");
  }

  private AssessmentSettings settings(AssessmentEntity a) {
    try {
      return a.getSettingsJson() == null
          ? AssessmentSettings.defaults()
          : json.readValue(a.getSettingsJson(), AssessmentSettings.class);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "测评设置损坏");
    }
  }

  private AssessmentSettings snapshotSettings(JsonNode root) {
    return root.hasNonNull("settings")
        ? json.convertValue(root.get("settings"), AssessmentSettings.class)
        : AssessmentSettings.defaults();
  }

  private JsonNode snapshotRoot(String text) {
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "发布快照损坏");
    }
  }

  private <T> T convertNullable(JsonNode n, Class<T> type) {
    return n == null || n.isNull() || n.isMissingNode() ? null : json.convertValue(n, type);
  }

  private Object answerPayload(SaveAnswerRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("selectedOptionKeys", request.selectedOptionKeys());
    payload.put("textAnswer", request.textAnswer());
    payload.put("attachmentFileIds", request.attachmentFileIds());
    return payload;
  }

  private Map<String, Object> readMap(String text) {
    if (text == null || text.isBlank()) return Map.of();
    try {
      return json.readValue(text, new TypeReference<>() {});
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "答案数据损坏");
    }
  }

  private List<String> stringList(Object value) {
    if (!(value instanceof Collection<?> c)) return List.of();
    return c.stream().filter(Objects::nonNull).map(Object::toString).toList();
  }

  private AntiCheatEvent toEvent(AntiCheatEventEntity e) {
    return new AntiCheatEvent(
        e.getId(),
        e.getClientEventId(),
        e.getEventType(),
        toInstant(e.getEventAt()),
        readMap(e.getDetailsJson()),
        e.getSeverity());
  }

  private String severity(String type) {
    return switch (type) {
      case "MULTI_DEVICE" -> "CRITICAL";
      case "EXIT_FULLSCREEN", "PAGE_HIDDEN", "WINDOW_BLUR", "HEARTBEAT_MISSED" -> "HIGH";
      case "COPY", "PASTE", "CONTEXT_MENU" -> "MEDIUM";
      default -> "INFO";
    };
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "数据序列化失败");
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String nullIfMissing(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asText() : null;
  }

  private String trimToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private LocalDateTime toLocal(Instant i) {
    return i == null ? null : LocalDateTime.ofInstant(i, DB_ZONE);
  }

  private Instant toInstant(LocalDateTime t) {
    return t == null ? null : t.atZone(DB_ZONE).toInstant();
  }

  private BusinessException validation(String m) {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, m);
  }

  private BusinessException conflict(String m) {
    return new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, m);
  }

  private BusinessException notFound(String m) {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, m);
  }
}
