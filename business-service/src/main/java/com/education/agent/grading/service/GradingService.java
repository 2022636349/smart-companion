package com.education.agent.grading.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.dto.AssessmentDtos.*;
import com.education.agent.assessment.entity.*;
import com.education.agent.assessment.mapper.*;
import com.education.agent.assessment.model.*;
import com.education.agent.assessment.service.AssessmentFileDownloadUrlFactory;
import com.education.agent.assessment.service.AssessmentService;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.grading.dto.GradingDtos.*;
import com.education.agent.grading.entity.*;
import com.education.agent.grading.mapper.*;
import com.education.agent.grading.model.GradingSource;
import com.education.agent.integration.ai.*;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.*;
import com.education.agent.submission.entity.*;
import com.education.agent.submission.mapper.*;
import com.education.agent.submission.model.*;
import com.education.agent.submission.service.SubmissionService;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.notification.service.BusinessNotificationDispatcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GradingService {
  private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final String GRADE_REQUEST_ID_PREFIX = "grading-task-request-";
  private final SubmissionMapper submissions;
  private final SubmissionAnswerMapper answers;
  private final SubmissionAttachmentMapper attachments;
  private final CodeRunRecordMapper codeRuns;
  private final AssessmentMapper assessments;
  private final AssessmentQuestionMapper questions;
  private final AssessmentVersionMapper versions;
  private final ProgrammingTestCaseMapper testCases;
  private final QuestionKnowledgePointMapper knowledgePoints;
  private final ClassStudentMapper classStudents;
  private final GradingRecordMapper records;
  private final AiTaskMapper tasks;
  private final AiTaskFileGrantMapper grants;
  private final IdempotencyRecordMapper idempotency;
  private final AssessmentService assessmentService;
  private final SubmissionService submissionService;
  private final AssessmentLifecycleService lifecycle;
  private final AssessmentFileDownloadUrlFactory urls;
  private final AiServiceClient ai;
  private final AuditLogService audit;
  private final BusinessNotificationDispatcher notificationDispatcher;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final FileStorageService files;
  private final TransactionTemplate tx;
  private final com.education.agent.course.CourseAccessService courses;
  private final Executor executor;
  private final Set<Long> activeIdempotencyClaims = ConcurrentHashMap.newKeySet();

  public GradingService(
      SubmissionMapper submissions, SubmissionAnswerMapper answers,
      SubmissionAttachmentMapper attachments, CodeRunRecordMapper codeRuns,
      AssessmentMapper assessments, AssessmentQuestionMapper questions,
      AssessmentVersionMapper versions, ProgrammingTestCaseMapper testCases,
      QuestionKnowledgePointMapper knowledgePoints, ClassStudentMapper classStudents,
      GradingRecordMapper records, AiTaskMapper tasks, AiTaskFileGrantMapper grants,
      IdempotencyRecordMapper idempotency, AssessmentService assessmentService,
      SubmissionService submissionService, AssessmentLifecycleService lifecycle,
      AssessmentFileDownloadUrlFactory urls, AiServiceClient ai, AuditLogService audit,
      BusinessNotificationDispatcher notificationDispatcher,
      ObjectMapper json, JdbcTemplate jdbc, FileStorageService files,
      @Qualifier("aiGradingExecutor") Executor executor,
      PlatformTransactionManager transactionManager, com.education.agent.course.CourseAccessService courses) {
    this.submissions=submissions; this.answers=answers; this.attachments=attachments; this.codeRuns=codeRuns;
    this.assessments=assessments; this.questions=questions; this.versions=versions; this.testCases=testCases;
    this.knowledgePoints=knowledgePoints; this.classStudents=classStudents; this.records=records; this.tasks=tasks;
    this.grants=grants; this.idempotency=idempotency; this.assessmentService=assessmentService;
    this.submissionService=submissionService; this.lifecycle=lifecycle; this.urls=urls; this.ai=ai;
    this.audit=audit; this.notificationDispatcher=notificationDispatcher; this.json=json; this.jdbc=jdbc; this.files=files; this.executor=executor;
    this.tx=new TransactionTemplate(transactionManager);
    this.courses=courses;
  }

  public GradingDetail detail(AuthenticatedUser principal, Long submissionId) {
    SubmissionEntity submission = requireReadableSubmission(principal, submissionId);
    AssessmentEntity assessment = requireReadableAssessment(principal, submission.getAssessmentId());
    List<QuestionDraft> frozen = snapshotQuestions(submission);
    AssessmentDetail frozenAssessment = frozenAssessment(submission, assessment, frozen);
    List<GradingAnswer> gradingAnswers = new ArrayList<>();
    for (QuestionDraft question : frozen) {
      SubmissionAnswerEntity answer = findAnswer(submissionId, question.id());
      SubmissionAnswer view = answer == null ? null : toAnswer(answer);
      List<GradingRecord> history = answer == null ? List.of() : records.selectList(
          new LambdaQueryWrapper<GradingRecordEntity>().eq(GradingRecordEntity::getAnswerId,answer.getId())
              .orderByAsc(GradingRecordEntity::getCreatedAt).orderByAsc(GradingRecordEntity::getId))
          .stream().map(this::toRecord).toList();
      CodeRunResult run = answer == null ? null : latestCodeResult(answer.getId());
      gradingAnswers.add(new GradingAnswer(question, view, history, run));
    }
    return new GradingDetail(submissionService.get(principal,submissionId), frozenAssessment, gradingAnswers);
  }

  public SubmissionAnswer grade(AuthenticatedUser principal, Long submissionId, Long questionId, ManualGradeRequest request) {
    return Objects.requireNonNull(tx.execute(status -> {
      SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,true);
      ensureMutable(submission);
      QuestionDraft question=requireFrozenQuestion(submission,questionId);
      validateScore(request.score(),question.score(),"score");
      validateRubricGrades(request.rubricGrades(),question,request.score());
      SubmissionAnswerEntity answer=ensureAnswer(submission,question);
      BigDecimal before=answer.getFinalScore();
      answer.setFinalScore(request.score()); answer.setTeacherComment(trim(request.comment()));
      answer.setGradingStatus(GradingStatus.COMPLETED); answers.updateById(answer);
      GradingRecordEntity record=new GradingRecordEntity();
      record.setAnswerId(answer.getId()); record.setGradingSource(GradingSource.TEACHER);
      record.setSuggestedScore(before); record.setFinalScore(request.score());
      record.setEvidenceJson(write(Map.of("rubricGrades",request.rubricGrades())));
      record.setConfidence(BigDecimal.ONE); record.setGradedBy(principal.id()); records.insert(record);
      submission.setStatus(SubmissionStatus.GRADING); submission.setGradingStatus(GradingStatus.MANUAL_GRADING);
      submissions.updateById(submission);
      audit.record("ANSWER_MANUALLY_GRADED","SUBMISSION_ANSWER",answer.getId().toString(),principal.id(),true,
          map("submissionId",submissionId,"questionId",questionId,"beforeScore",before,"finalScore",request.score()));
      return toAnswer(answer);
    }));
  }

  public SubmissionAnswer acceptAiGrade(AuthenticatedUser principal, Long submissionId, Long questionId) {
    return Objects.requireNonNull(tx.execute(status -> {
      SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,true);
      ensureMutable(submission);
      QuestionDraft question=requireFrozenQuestion(submission,questionId);
      SubmissionAnswerEntity answer=findAnswer(submissionId,questionId);
      if(answer==null)throw validation("当前题目没有可采纳的 AI 评分建议");
      GradingRecordEntity aiRecord=latestAiRecord(answer.getId());
      if(aiRecord==null)throw validation("当前题目没有可采纳的 AI 评分建议");
      if(hasAcceptedAiRecord(answer.getId(),aiRecord.getId()))return toAnswer(answer);
      applyAiGrade(principal,submission,question,answer,aiRecord);
      audit.record("AI_GRADE_SUGGESTION_ACCEPTED","SUBMISSION_ANSWER",answer.getId().toString(),principal.id(),true,
          map("submissionId",submissionId,"questionId",questionId,"aiRecordId",aiRecord.getId(),"finalScore",aiRecord.getSuggestedScore()));
      return toAnswer(answer);
    }));
  }

  public GradingDetail acceptAllAiGrades(AuthenticatedUser principal, Long submissionId) {
    tx.executeWithoutResult(status -> {
      SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,true);
      ensureMutable(submission);
      int available=0, accepted=0;
      for(QuestionDraft question:snapshotQuestions(submission)) {
        SubmissionAnswerEntity answer=findAnswer(submissionId,question.id());
        if(answer==null)continue;
        GradingRecordEntity aiRecord=latestAiRecord(answer.getId());
        if(aiRecord==null)continue;
        available++;
        if(hasAcceptedAiRecord(answer.getId(),aiRecord.getId()))continue;
        applyAiGrade(principal,submission,question,answer,aiRecord);
        accepted++;
        audit.record("AI_GRADE_SUGGESTION_ACCEPTED","SUBMISSION_ANSWER",answer.getId().toString(),principal.id(),true,
            map("submissionId",submissionId,"questionId",question.id(),"aiRecordId",aiRecord.getId(),"finalScore",aiRecord.getSuggestedScore()));
      }
      if(available==0)throw validation("当前答卷没有可采纳的 AI 评分建议");
      audit.record("AI_GRADE_SUGGESTIONS_ACCEPTED","SUBMISSION",submissionId.toString(),principal.id(),true,
          Map.of("availableCount",available,"acceptedCount",accepted));
    });
    return detail(principal,submissionId);
  }

  public void comment(AuthenticatedUser principal, Long submissionId, String comment) {
    tx.executeWithoutResult(status->{
      SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,true); ensureMutable(submission);
      submission.setOverallComment(comment); submissions.updateById(submission);
      notificationDispatcher.onAuditEvent("SUBMISSION_COMMENT_UPDATED","SUBMISSION",submissionId.toString());
      audit.record("SUBMISSION_COMMENT_UPDATED","SUBMISSION",submissionId.toString(),principal.id(),true,Map.of());
    });
  }

  public void bulkComments(AuthenticatedUser principal, Long assessmentId, BulkCommentsRequest request) {
    requireTeacherAssessment(principal,assessmentId,false);
    Set<Long> seen=new HashSet<>();
    for(BulkCommentItem item:request.items()) {
      if(!seen.add(item.submissionId())) throw validation("批量评语 submissionId 不能重复");
      SubmissionEntity submission=requireTeacherSubmission(principal,item.submissionId(),false);
      if(!submission.getAssessmentId().equals(assessmentId)) throw validation("答卷不属于该测评");
      ensureMutable(submission);
    }
    tx.executeWithoutResult(status->{
      // Lifecycle operations lock assessment first and then touch submissions. Keep that global
      // order here, and take multiple submission locks by id, so release/bulk requests cannot form
      // a submission->assessment or submission->submission deadlock cycle.
      requireTeacherAssessment(principal,assessmentId,true);
      List<BulkCommentItem> ordered=request.items().stream()
          .sorted(Comparator.comparing(BulkCommentItem::submissionId)).toList();
      for(BulkCommentItem item:ordered) {
        SubmissionEntity submission=requireTeacherSubmission(principal,item.submissionId(),true); ensureMutable(submission);
        if(!submission.getAssessmentId().equals(assessmentId))throw validation("答卷不属于该测评");
        submission.setOverallComment(item.comment()); submissions.updateById(submission);
        notificationDispatcher.onAuditEvent("SUBMISSION_COMMENT_UPDATED","SUBMISSION",submission.getId().toString());
        audit.record("SUBMISSION_COMMENT_UPDATED","SUBMISSION",submission.getId().toString(),principal.id(),true,Map.of());
      }
      audit.record("SUBMISSION_COMMENTS_BULK_UPDATED","ASSESSMENT",assessmentId.toString(),principal.id(),true,
          Map.of("count",request.items().size()));
    });
  }

  public Submission complete(AuthenticatedUser principal, Long submissionId, String key) {
    requireTeacherSubmission(principal,submissionId,false);
    String operation="COMPLETE_SUBMISSION_GRADING";
    IdempotencyClaim<Submission> claim=claimComplete(principal.id(),operation,key,sha256(operation+":"+submissionId));
    if(claim.replay()!=null) return claim.replay();
    if(claim.incomplete()) {
      SubmissionEntity persisted=requireTeacherSubmission(principal,submissionId,false);
      if(persisted.getStatus()==SubmissionStatus.GRADED&&persisted.getGradingStatus()==GradingStatus.COMPLETED&&persisted.getFinalScore()!=null) {
        Submission recovered=submissionService.get(principal,submissionId);
        completeClaim(claim.recordId(),recovered);
        return recovered;
      }
    }
    try {
      Submission response=Objects.requireNonNull(tx.execute(status->{
        SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,true); ensureMutable(submission);
        AiTaskEntity active=tasks.findActiveGradeTask(submissionId);
        if(active!=null) throw new BusinessException(ErrorCode.GRADING_INCOMPLETE,"AI 批改任务仍在运行");
        BigDecimal total=BigDecimal.ZERO;
        for(QuestionDraft question:snapshotQuestions(submission)) {
          SubmissionAnswerEntity answer=findAnswer(submissionId,question.id());
          if(answer==null||answer.getFinalScore()==null||answer.getGradingStatus()!=GradingStatus.COMPLETED)
            throw new BusinessException(ErrorCode.GRADING_INCOMPLETE,"仍有题目未完成最终评分");
          validateScore(answer.getFinalScore(),question.score(),"finalScore"); total=total.add(answer.getFinalScore());
        }
        submission.setFinalScore(total); submission.setStatus(SubmissionStatus.GRADED);
        submission.setGradingStatus(GradingStatus.COMPLETED);
        AssessmentEntity assessment=assessments.selectById(submission.getAssessmentId());
        if(assessment!=null&&Boolean.TRUE.equals(assessment.getAutoReleaseEnabled())) {
          submission.setResultReleasedAt(LocalDateTime.now());
        }
        submissions.updateById(submission);
        audit.record("SUBMISSION_GRADING_COMPLETED","SUBMISSION",submissionId.toString(),principal.id(),true,Map.of("finalScore",total));
        return submissionService.get(principal,submissionId);
      }));
      completeClaim(claim.recordId(),response); lifecycle.refreshAssessmentState(response.assessmentId()); return response;
    } catch(RuntimeException failure) { releaseClaim(claim.recordId()); throw failure; }
  }

  public AsyncTask startAiGrade(AuthenticatedUser principal, Long submissionId, String key) {
    SubmissionEntity submission=requireTeacherSubmission(principal,submissionId,false); ensureMutable(submission);
    String operation="START_AI_GRADING";
    IdempotencyClaim<AsyncTask> claim=claimTask(principal.id(),operation,key,sha256(operation+":"+submissionId));
    if(claim.replay()!=null)return claim.replay();
    if(claim.incomplete()) {
      AiTaskEntity persisted=tasks.selectOne(new LambdaQueryWrapper<AiTaskEntity>()
          .eq(AiTaskEntity::getRequestId, gradeRequestId(claim.recordId())));
      if(persisted!=null&&persisted.getOwnerId().equals(principal.id())) {
        AsyncTask recovered=toTask(persisted); completeClaim(claim.recordId(),recovered); return recovered;
      }
    }
    try {
      AiTaskEntity task=Objects.requireNonNull(tx.execute(status->{
        SubmissionEntity locked=requireTeacherSubmission(principal,submissionId,true); ensureMutable(locked);
        AiTaskEntity active=tasks.findActiveGradeTask(submissionId); if(active!=null) throw conflict("该答卷已有 AI 批改任务");
        List<AiQuestionPlan> plans=aiPlans(locked);
        if(plans.isEmpty()) throw validation("该答卷没有可进行 AI 建议评分的答案");
        AiTaskEntity entity=new AiTaskEntity(); entity.setTaskType("SUBMISSION_AI_GRADE"); entity.setStatus(TaskStatus.PENDING);
        entity.setOwnerId(principal.id()); entity.setResourceType("SUBMISSION"); entity.setResourceId(submissionId);
        entity.setRequestId(gradeRequestId(claim.recordId())); entity.setActiveKey("SUBMISSION_AI_GRADE:"+submissionId);
        entity.setProgressPercent(0); entity.setAttemptCount(0); entity.setRequestJson(write(Map.of("answerIds",plans.stream().map(p->p.answer().getId()).toList())));
        try { tasks.insert(entity); } catch(DuplicateKeyException race){ throw conflict("该答卷已有 AI 批改任务"); }
        for(AiQuestionPlan plan:plans) for(Long fileId:plan.fileIds()) grants.insert(entity.getId(),entity.getRequestId(),plan.answer().getId(),fileId);
        return entity;
      }));
      AsyncTask response=toTask(task); completeClaim(claim.recordId(),response);
      try { executor.execute(()->processAiTask(task.getId())); } catch(RejectedExecutionException ignored) {}
      return response;
    } catch(RuntimeException failure){releaseClaim(claim.recordId());throw failure;}
  }

  public AsyncTask task(AuthenticatedUser principal,Long taskId){
    AiTaskEntity task=tasks.selectById(taskId); if(task==null)throw notFound("AI 任务不存在");
    if(!"SUBMISSION_AI_GRADE".equals(task.getTaskType())||!"SUBMISSION".equals(task.getResourceType()))throw notFound("AI 任务不存在");
    if(principal==null||(principal.role()!=UserRole.ADMIN&&!Objects.equals(task.getOwnerId(),principal.id())))throw notFound("AI 任务不存在");
    SubmissionEntity submission=submissions.selectById(task.getResourceId());
    if(submission==null)throw notFound("AI 任务不存在");
    requireReadableAssessment(principal,submission.getAssessmentId());
    return toTask(task, principal.role()==UserRole.ADMIN);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedAiTasks() {
    tx.executeWithoutResult(status -> {
      tasks.requeueInterruptedGradeTasks();
      List<Long> exhausted=tasks.findExhaustedGradeTaskIds();
      tasks.failExhaustedGradeTasks();
      exhausted.forEach(grants::deleteByTaskId);
    });
    recoverAiTasks();
  }

  @Scheduled(fixedDelayString="${education.grading.ai-scan-ms:5000}")
  public void recoverAiTasks(){ for(Long id:tasks.findRecoverableGradeTaskIds(50)) try{executor.execute(()->processAiTask(id));}catch(RuntimeException ignored){} }

  private void processAiTask(Long taskId) {
    AiTaskPlan plan;
    try { plan=tx.execute(status->claimAiTask(taskId)); if(plan==null)return; }
    catch(RuntimeException failure){failTask(taskId,failure);return;}
    List<AiGradeOutcome> outcomes=new ArrayList<>();
    try {
      for(AiQuestionPlan q:plan.questions()) {
        AiGradeResponse response;
        List<AiGradeRequest.RubricItem> rubrics=q.question().rubricItems().stream().map(r->new AiGradeRequest.RubricItem(r.label(),r.description(),r.maxScore())).toList();
        if(q.question().questionType()==QuestionType.ATTACHMENT) {
          response=ai.gradeAttachment(new AiGradeRequest.Attachment(plan.task().getRequestId(),q.answer().getId(),q.fileIds().stream().map(urls::forFile).toList(),rubrics,q.question().score()));
        } else {
          Map<String,Object> payload=readMap(q.answer().getAnswerJson()); String text=(String)payload.get("textAnswer");
          String reference=q.question().referenceAnswer()==null?null:q.question().referenceAnswer().textAnswer();
          response=ai.suggestSubjectiveGrade(new AiGradeRequest.Subjective(plan.task().getRequestId(),q.answer().getId(),text,reference,rubrics,q.question().score()));
        }
        outcomes.add(validateAiResponse(plan.task(),q,response));
      }
      tx.executeWithoutResult(status->finishAiTask(taskId,outcomes));
    } catch(RuntimeException failure){failTask(taskId,failure);}
  }

  private AiTaskPlan claimAiTask(Long taskId){
    AiTaskEntity task=tasks.selectForUpdate(taskId);
    if(task==null||task.getStatus()!=TaskStatus.PENDING)return null;
    if(Optional.ofNullable(task.getAttemptCount()).orElse(0)>=2) {
      task.setStatus(TaskStatus.FAILED); task.setProgressPercent(100); task.setErrorCode("AI_TASK_RETRY_EXHAUSTED");
      task.setErrorMessage("AI 批改任务重试次数已耗尽"); task.setFinishedAt(LocalDateTime.now()); task.setActiveKey(null);
      tasks.updateById(task); grants.deleteByTaskId(taskId); return null;
    }
    task.setStatus(TaskStatus.RUNNING); task.setStartedAt(LocalDateTime.now()); task.setAttemptCount(Optional.ofNullable(task.getAttemptCount()).orElse(0)+1); task.setProgressPercent(10); tasks.updateById(task);
    SubmissionEntity submission=submissions.selectById(task.getResourceId()); if(submission==null)throw notFound("答卷不存在");
    return new AiTaskPlan(task,aiPlans(submission));
  }

  private void finishAiTask(Long taskId,List<AiGradeOutcome> outcomes){
    AiTaskEntity locator=tasks.selectById(taskId); if(locator==null)throw conflict("AI任务状态已改变");
    SubmissionEntity submissionLocator=submissions.selectById(locator.getResourceId()); if(submissionLocator==null)throw notFound("答卷不存在");
    AssessmentEntity assessment=assessments.selectForUpdate(submissionLocator.getAssessmentId()); if(assessment==null)throw notFound("测评不存在");
    AiTaskEntity task=tasks.selectForUpdate(taskId); if(task==null||task.getStatus()!=TaskStatus.RUNNING)throw conflict("AI任务状态已改变");
    SubmissionEntity submission=submissions.selectForUpdate(task.getResourceId());
    if(submission==null||!submission.getAssessmentId().equals(assessment.getId()))throw notFound("答卷不存在");
    ensureMutable(submission,assessment);
    for(AiGradeOutcome outcome:outcomes) {
      if(records.selectCount(new LambdaQueryWrapper<GradingRecordEntity>().eq(GradingRecordEntity::getRequestId,task.getRequestId()).eq(GradingRecordEntity::getAnswerId,outcome.answerId()))>0)continue;
      GradingRecordEntity record=new GradingRecordEntity(); record.setAnswerId(outcome.answerId()); record.setGradingSource(GradingSource.AI);
      record.setSuggestedScore(outcome.response().suggestedScore()); record.setEvidenceJson(write(Map.of("rubricGrades",outcome.rubricGrades(),"overallComment",outcome.response().overallComment(),"warnings",outcome.response().warnings())));
      record.setConfidence(outcome.confidence()); record.setModelVersion(outcome.response().modelVersion()); record.setPromptVersion(outcome.response().promptVersion()); record.setRequestId(task.getRequestId()); records.insert(record);
    }
    task.setStatus(TaskStatus.SUCCEEDED); task.setProgressPercent(100); task.setResultJson(write(Map.of("submissionId",submission.getId(),"answerCount",outcomes.size())));
    task.setModelVersion(outcomes.stream().map(o->o.response().modelVersion()).filter(Objects::nonNull).findFirst().orElse(null));
    task.setPromptVersion(outcomes.stream().map(o->o.response().promptVersion()).filter(Objects::nonNull).findFirst().orElse(null));
    task.setFinishedAt(LocalDateTime.now()); task.setActiveKey(null); tasks.updateById(task); grants.deleteByTaskId(taskId);
    audit.record("SUBMISSION_AI_GRADE_SUCCEEDED","SUBMISSION",submission.getId().toString(),task.getOwnerId(),true,Map.of("taskId",taskId));
  }

  private void failTask(Long taskId,RuntimeException failure){
    tx.executeWithoutResult(status->{AiTaskEntity task=tasks.selectForUpdate(taskId);if(task==null||task.getStatus()==TaskStatus.SUCCEEDED)return;
      task.setStatus(TaskStatus.FAILED);task.setProgressPercent(100);task.setErrorCode(failure instanceof BusinessException b?b.errorCode().name():"AI_SERVICE_UNAVAILABLE");
      task.setErrorMessage(failure.getMessage()==null?"AI 批改失败":failure.getMessage());task.setFinishedAt(LocalDateTime.now());task.setActiveKey(null);tasks.updateById(task);grants.deleteByTaskId(taskId);
      audit.record("SUBMISSION_AI_GRADE_FAILED","SUBMISSION",String.valueOf(task.getResourceId()),task.getOwnerId(),false,Map.of("taskId",taskId,"errorCode",task.getErrorCode()));});
  }

  public AssessmentStatistics statistics(AuthenticatedUser principal,Long assessmentId){
    AssessmentEntity assessment=requireReadableAssessment(principal,assessmentId);
    long assigned=classStudents.countByClassId(assessment.getClassId()); List<SubmissionEntity> all=latestByStudent(submissions.findByAssessmentId(assessmentId));
    long inProgress=all.stream().filter(s->s.getStatus()==SubmissionStatus.IN_PROGRESS).count();
    List<SubmissionEntity> submitted=all.stream().filter(s->s.getStatus()!=SubmissionStatus.IN_PROGRESS).toList();
    long late=assessment.getAssessmentType()==AssessmentType.HOMEWORK&&assessment.getDueAt()!=null?submitted.stream().filter(s->s.getSubmittedAt()!=null&&s.getSubmittedAt().isAfter(assessment.getDueAt())&&snapshotSettings(s).allowLateSubmission()).count():0;
    List<SubmissionEntity> graded=submitted.stream().filter(s->s.getGradingStatus()==GradingStatus.COMPLETED&&s.getFinalScore()!=null).toList();
    List<BigDecimal> scores=graded.stream().map(SubmissionEntity::getFinalScore).toList(); BigDecimal average=scores.isEmpty()?null:scores.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(scores.size()),2,RoundingMode.HALF_UP);
    BigDecimal high=scores.stream().max(BigDecimal::compareTo).orElse(null),low=scores.stream().min(BigDecimal::compareTo).orElse(null);
    BigDecimal pass=scores.isEmpty()?null:ratio(scores.stream().filter(s->s.compareTo(assessment.getTotalScore().multiply(new BigDecimal("0.60")))>=0).count(),scores.size());
    List<DistributionBucket> distribution=distribution(scores,assessment.getTotalScore());
    List<QuestionStatistics> qs=snapshotQuestionsForAssessment(assessmentId).stream().map(q->questionStatisticsInternal(assessment,submitted,q)).toList();
    return new AssessmentStatistics(assessmentId,assigned,inProgress,submitted.size(),late,Math.max(0,assigned-submitted.size()),graded.size(),
        all.stream().filter(s->s.getResultReleasedAt()!=null).count(),average,high,low,pass,distribution,qs,weakKnowledgePoints(submitted));
  }

  public QuestionStatistics questionStatistics(AuthenticatedUser principal,Long assessmentId,Long questionId){
    AssessmentEntity assessment=requireReadableAssessment(principal,assessmentId); QuestionDraft q=snapshotQuestionsForAssessment(assessmentId).stream().filter(x->x.id().equals(questionId)).findFirst().orElseThrow(()->notFound("题目不存在"));
    List<SubmissionEntity> submitted=latestByStudent(submissions.findByAssessmentId(assessmentId)).stream().filter(s->s.getStatus()!=SubmissionStatus.IN_PROGRESS).toList(); return questionStatisticsInternal(assessment,submitted,q);
  }

  private QuestionStatistics questionStatisticsInternal(AssessmentEntity assessment,List<SubmissionEntity> submitted,QuestionDraft q){
    long answered=0,scored=0,correct=0; BigDecimal sum=BigDecimal.ZERO; Map<String,Long> options=new LinkedHashMap<>(); Map<Long,Long> testPass=new LinkedHashMap<>();
    for(SubmissionEntity s:submitted){SubmissionAnswerEntity a=findAnswer(s.getId(),q.id());if(a==null||!answered(a,q.questionType()))continue;answered++;BigDecimal score=a.getFinalScore();if(score!=null&&a.getGradingStatus()==GradingStatus.COMPLETED){scored++;sum=sum.add(score);if(score.compareTo(q.score())==0)correct++;}
      if(q.questionType()==QuestionType.SINGLE_CHOICE||q.questionType()==QuestionType.MULTIPLE_CHOICE) new HashSet<>(strings(readMap(a.getAnswerJson()).get("selectedOptionKeys"))).forEach(k->options.merge(k,1L,Long::sum));
      if(q.questionType()==QuestionType.PROGRAMMING){CodeRunRecordEntity run=latestCodeRecord(a.getId());if(run!=null)try{JsonNode root=json.readTree(run.getResultJson());JsonNode nodes=root.has("test_results")?root.path("test_results"):root.path("testResults");for(JsonNode t:nodes)if("ACCEPTED".equals(t.path("status").asText()))testPass.merge(longEither(t,"test_case_id","testCaseId"),1L,Long::sum);}catch(Exception ignored){}}
    }
    List<OptionDistribution> optionDistribution=options.entrySet().stream().map(e->new OptionDistribution(e.getKey(),e.getValue(),ratio(e.getValue(),submitted.size()))).toList();
    List<TestCaseStatistics> testStats=q.testCases().stream().map(t->new TestCaseStatistics(t.id(),t.visibility(),testPass.getOrDefault(t.id(),0L),ratio(testPass.getOrDefault(t.id(),0L),submitted.size()))).toList();
    return new QuestionStatistics(q.id(),q.questionType(),answered,correct,Math.max(0,scored-correct),Math.max(0,submitted.size()-answered),scored==0?null:ratio(correct,scored),scored==0?null:sum.divide(BigDecimal.valueOf(scored),2,RoundingMode.HALF_UP),optionDistribution,testStats,List.of());
  }

  private List<KnowledgeMastery> weakKnowledgePoints(List<SubmissionEntity> submitted){
    Map<String,KpAggregate> map=new LinkedHashMap<>(); for(SubmissionEntity s:submitted)for(QuestionDraft q:snapshotQuestions(s)){SubmissionAnswerEntity a=findAnswer(s.getId(),q.id());if(a==null||a.getFinalScore()==null||a.getGradingStatus()!=GradingStatus.COMPLETED)continue;for(KnowledgePointBinding kp:q.knowledgePoints()){KpAggregate agg=map.computeIfAbsent(kp.id(),id->new KpAggregate(kp.name()));agg.score=agg.score.add(a.getFinalScore());agg.max=agg.max.add(q.score());agg.count++;}}
    return map.entrySet().stream().map(e->{BigDecimal score=e.getValue().max.signum()==0?BigDecimal.ZERO:e.getValue().score.multiply(HUNDRED).divide(e.getValue().max,2,RoundingMode.HALF_UP);return new KnowledgeMastery(e.getKey(),e.getValue().name,score,BigDecimal.ONE,"UNKNOWN",e.getValue().count,Instant.now());}).sorted(Comparator.comparing(KnowledgeMastery::score)).limit(10).toList();
  }

  // mapping/validation helpers
  private SubmissionEntity requireTeacherSubmission(AuthenticatedUser p,Long id,boolean lock){
    SubmissionEntity locator=submissions.selectById(id);if(locator==null)throw notFound("答卷不存在");
    AssessmentEntity assessment=requireTeacherAssessment(p,locator.getAssessmentId(),lock);
    if(!lock)return locator;
    SubmissionEntity locked=submissions.selectForUpdate(id);
    if(locked==null||!assessment.getId().equals(locked.getAssessmentId()))throw notFound("答卷不存在");
    return locked;
  }
  private AssessmentEntity requireTeacherAssessment(AuthenticatedUser p,Long id,boolean lock){AssessmentEntity a=lock?assessments.selectForUpdate(id):assessments.selectById(id);if(a==null||p==null||p.role()!=UserRole.TEACHER||!a.getCreatorId().equals(p.id()))throw notFound("测评不存在");courses.requireEdit(p,courses.classCourse(a.getClassId()));return a;}
  private SubmissionEntity requireReadableSubmission(AuthenticatedUser p,Long id){SubmissionEntity s=submissions.selectById(id);if(s==null)throw notFound("答卷不存在");requireReadableAssessment(p,s.getAssessmentId());return s;}
  private AssessmentEntity requireReadableAssessment(AuthenticatedUser p,Long id){AssessmentEntity a=assessments.selectById(id);if(a==null||p==null||(p.role()!=UserRole.ADMIN&&(p.role()!=UserRole.TEACHER||!a.getCreatorId().equals(p.id()))))throw notFound("测评不存在");courses.requireView(p,courses.classCourse(a.getClassId()));return a;}
  private void ensureMutable(SubmissionEntity s){ensureMutable(s,assessments.selectById(s.getAssessmentId()));}
  private void ensureMutable(SubmissionEntity s,AssessmentEntity a){if(a==null||a.getStatus()==AssessmentStatus.RELEASED||a.getStatus()==AssessmentStatus.ARCHIVED||s.getResultReleasedAt()!=null)throw conflict("成绩已公布，不能修改");if(s.getStatus()==SubmissionStatus.IN_PROGRESS)throw conflict("答卷尚未提交");}
  private SubmissionAnswerEntity ensureAnswer(SubmissionEntity s,QuestionDraft q){SubmissionAnswerEntity a=findAnswer(s.getId(),q.id());if(a!=null)return a;a=new SubmissionAnswerEntity();a.setSubmissionId(s.getId());a.setQuestionId(q.id());a.setClientSaveSequence(0L);a.setAnswerJson("{}");a.setSavedAt(LocalDateTime.now());a.setSubmittedAt(s.getSubmittedAt());a.setGradingStatus(GradingStatus.MANUAL_GRADING);answers.insert(a);return a;}
  private SubmissionAnswerEntity findAnswer(Long sid,Long qid){return answers.selectOne(new LambdaQueryWrapper<SubmissionAnswerEntity>().eq(SubmissionAnswerEntity::getSubmissionId,sid).eq(SubmissionAnswerEntity::getQuestionId,qid));}
  private QuestionDraft requireFrozenQuestion(SubmissionEntity s,Long qid){return snapshotQuestions(s).stream().filter(q->q.id().equals(qid)).findFirst().orElseThrow(()->notFound("题目不存在"));}
  private List<QuestionDraft> snapshotQuestions(SubmissionEntity s){AssessmentVersionEntity v=s.getAssessmentVersionId()!=null?versions.selectById(s.getAssessmentVersionId()):versions.selectOne(new LambdaQueryWrapper<AssessmentVersionEntity>().eq(AssessmentVersionEntity::getAssessmentId,s.getAssessmentId()).eq(AssessmentVersionEntity::getVersionNo,s.getAssessmentVersion()));if(v==null)throw conflict("答卷版本不存在");try{return parseSnapshotQuestions(json.readTree(v.getSnapshotJson()));}catch(Exception e){throw conflict("发布快照损坏");}}
  private List<QuestionDraft> snapshotQuestionsForAssessment(Long id){AssessmentEntity a=assessments.selectById(id);AssessmentVersionEntity v=versions.selectOne(new LambdaQueryWrapper<AssessmentVersionEntity>().eq(AssessmentVersionEntity::getAssessmentId,id).eq(AssessmentVersionEntity::getVersionNo,a.getCurrentVersion()));if(v==null)return List.of();try{return parseSnapshotQuestions(json.readTree(v.getSnapshotJson()));}catch(Exception e){throw conflict("发布快照损坏");}}
  private AssessmentDetail frozenAssessment(SubmissionEntity s,AssessmentEntity current,List<QuestionDraft> qs){AssessmentVersionEntity v=s.getAssessmentVersionId()!=null?versions.selectById(s.getAssessmentVersionId()):versions.selectOne(new LambdaQueryWrapper<AssessmentVersionEntity>().eq(AssessmentVersionEntity::getAssessmentId,s.getAssessmentId()).eq(AssessmentVersionEntity::getVersionNo,s.getAssessmentVersion()));try{JsonNode root=json.readTree(v.getSnapshotJson());AssessmentSettings settings=root.hasNonNull("settings")?json.treeToValue(root.get("settings"),AssessmentSettings.class):AssessmentSettings.defaults();return new AssessmentDetail(current.getId(),AssessmentType.valueOf(root.path("assessmentType").asText()),root.path("title").asText(),current.getClassId(),null,current.getCreatorId(),current.getStatus(),root.path("totalScore").decimalValue(),current.getStartAt()==null?null:toInstant(current.getStartAt()),current.getDueAt()==null?null:toInstant(current.getDueAt()),current.getDurationMinutes(),qs.size(),null,toInstant(current.getCreatedAt()),toInstant(current.getUpdatedAt()),root.hasNonNull("description")?root.get("description").asText():null,settings,s.getAssessmentVersion(),qs);}catch(Exception e){throw conflict("发布快照损坏");}}
  private List<QuestionDraft> parseSnapshotQuestions(JsonNode root){List<QuestionDraft> result=new ArrayList<>();for(JsonNode q:root.path("questions")){Long id=q.path("id").asLong();List<QuestionOptionDraft> opts=new ArrayList<>();for(JsonNode o:q.path("options"))opts.add(new QuestionOptionDraft(o.path("id").isNumber()?o.path("id").asLong():null,o.path("key").asText(),o.path("content").asText(),o.path("isCorrect").asBoolean(),o.path("position").asInt()));AnswerContent ref=q.path("referenceAnswer").isMissingNode()||q.path("referenceAnswer").isNull()?null:json.convertValue(q.path("referenceAnswer"),AnswerContent.class);List<RubricItem> rubrics=new ArrayList<>();for(JsonNode r:q.path("rubricItems"))rubrics.add(new RubricItem(r.path("id").isNumber()?r.path("id").asLong():null,r.path("clientKey").asText(),r.path("label").asText(),r.path("description").asText(),r.path("maxScore").decimalValue(),r.path("position").asInt()));ProgrammingConfig pc=q.path("programmingConfig").isMissingNode()||q.path("programmingConfig").isNull()?null:json.convertValue(q.path("programmingConfig"),ProgrammingConfig.class);List<ProgrammingTestCaseDraft> tests=new ArrayList<>();for(JsonNode t:q.path("testCases"))tests.add(new ProgrammingTestCaseDraft(t.path("id").isNumber()?t.path("id").asLong():null,t.path("clientKey").asText(),t.path("input").asText(),t.path("expectedOutput").asText(),t.path("score").decimalValue(),TestVisibility.valueOf(t.path("visibility").asText()),t.path("position").asInt()));AttachmentPolicy ap=q.path("attachmentPolicy").isMissingNode()||q.path("attachmentPolicy").isNull()?null:json.convertValue(q.path("attachmentPolicy"),AttachmentPolicy.class);List<KnowledgePointBinding> kps=new ArrayList<>();for(JsonNode k:q.path("knowledgePoints"))kps.add(new KnowledgePointBinding(k.path("id").asText(),k.path("name").asText()));result.add(new QuestionDraft(id,q.path("clientKey").asText(),QuestionType.valueOf(q.path("questionType").asText()),q.path("stem").asText(),q.path("score").decimalValue(),q.path("position").asInt(),opts,ref,rubrics,pc,tests,ap,kps,q.path("aiGenerated").asBoolean(false),null));}return result;}
  private AssessmentSettings snapshotSettings(SubmissionEntity s){AssessmentVersionEntity v=versions.selectById(s.getAssessmentVersionId());try{return json.treeToValue(json.readTree(v.getSnapshotJson()).path("settings"),AssessmentSettings.class);}catch(Exception e){return AssessmentSettings.defaults();}}
  private SubmissionAnswer toAnswer(SubmissionAnswerEntity a){Map<String,Object> p=readMap(a.getAnswerJson());return new SubmissionAnswer(a.getId(),a.getQuestionId(),strings(p.get("selectedOptionKeys")),(String)p.get("textAnswer"),a.getCodeText(),attachments.findFileIds(a.getId()).stream().map(files::findAvailable).map(com.education.agent.file.dto.FileObjectView::from).toList(),toInstant(a.getSavedAt()),toInstant(a.getSubmittedAt()),a.getAutoScore(),a.getFinalScore(),a.getGradingStatus(),a.getTeacherComment());}
  private GradingRecord toRecord(GradingRecordEntity r){Map<String,Object> e=readMap(r.getEvidenceJson());List<RubricGrade> grades=json.convertValue(e.getOrDefault("rubricGrades",List.of()),new TypeReference<List<RubricGrade>>(){});return new GradingRecord(r.getId(),r.getGradingSource(),r.getSuggestedScore(),r.getFinalScore(),grades,r.getConfidence(),r.getModelVersion(),r.getPromptVersion(),r.getGradedBy(),toInstant(r.getCreatedAt()),longValue(e.get("acceptedAiRecordId")));}
  private GradingRecordEntity latestAiRecord(Long answerId){return records.selectOne(new LambdaQueryWrapper<GradingRecordEntity>().eq(GradingRecordEntity::getAnswerId,answerId).eq(GradingRecordEntity::getGradingSource,GradingSource.AI).orderByDesc(GradingRecordEntity::getCreatedAt).orderByDesc(GradingRecordEntity::getId).last("LIMIT 1"));}
  private boolean hasAcceptedAiRecord(Long answerId,Long aiRecordId){return records.selectList(new LambdaQueryWrapper<GradingRecordEntity>().eq(GradingRecordEntity::getAnswerId,answerId).eq(GradingRecordEntity::getGradingSource,GradingSource.TEACHER)).stream().map(GradingRecordEntity::getEvidenceJson).map(this::readMap).map(e->longValue(e.get("acceptedAiRecordId"))).anyMatch(aiRecordId::equals);}
  private void applyAiGrade(AuthenticatedUser principal,SubmissionEntity submission,QuestionDraft question,SubmissionAnswerEntity answer,GradingRecordEntity aiRecord){
    Map<String,Object> aiEvidence=readMap(aiRecord.getEvidenceJson());
    List<RubricGrade> rubricGrades=json.convertValue(aiEvidence.getOrDefault("rubricGrades",List.of()),new TypeReference<List<RubricGrade>>(){});
    validateScore(aiRecord.getSuggestedScore(),question.score(),"AI suggestedScore");
    validateRubricGrades(rubricGrades,question,aiRecord.getSuggestedScore());
    answer.setFinalScore(aiRecord.getSuggestedScore()); answer.setGradingStatus(GradingStatus.COMPLETED); answers.updateById(answer);
    GradingRecordEntity teacherRecord=new GradingRecordEntity();
    teacherRecord.setAnswerId(answer.getId()); teacherRecord.setGradingSource(GradingSource.TEACHER);
    teacherRecord.setSuggestedScore(aiRecord.getSuggestedScore()); teacherRecord.setFinalScore(aiRecord.getSuggestedScore());
    teacherRecord.setEvidenceJson(write(Map.of("rubricGrades",rubricGrades,"acceptedAiRecordId",aiRecord.getId())));
    teacherRecord.setConfidence(aiRecord.getConfidence()); teacherRecord.setModelVersion(aiRecord.getModelVersion()); teacherRecord.setPromptVersion(aiRecord.getPromptVersion()); teacherRecord.setGradedBy(principal.id()); records.insert(teacherRecord);
    submission.setStatus(SubmissionStatus.GRADING); submission.setGradingStatus(GradingStatus.MANUAL_GRADING); submissions.updateById(submission);
  }
  private CodeRunRecordEntity latestCodeRecord(Long aid){return codeRuns.selectOne(new LambdaQueryWrapper<CodeRunRecordEntity>().eq(CodeRunRecordEntity::getAnswerId,aid).eq(CodeRunRecordEntity::getRunType,"SUBMIT").orderByDesc(CodeRunRecordEntity::getCreatedAt).last("LIMIT 1"));}
  private CodeRunResult latestCodeResult(Long aid){CodeRunRecordEntity r=latestCodeRecord(aid);if(r==null)return null;try{JsonNode n=json.readTree(r.getResultJson());JsonNode testNodes=n.has("test_results")?n.path("test_results"):n.path("testResults");List<TestCaseRunResult> cases=new ArrayList<>();for(JsonNode t:testNodes){String visibility=textEither(t,"visibility","visibility");boolean hidden="HIDDEN".equals(visibility);cases.add(new TestCaseRunResult(longEither(t,"test_case_id","testCaseId"),null,TestVisibility.valueOf(visibility),textEither(t,"status","status"),null,hidden?null:textEither(t,"expected_output","expectedOutput"),hidden?null:textEither(t,"actual_output","actualOutput"),hidden?null:textEither(t,"error_message","errorMessage"),intEither(t,"time_ms","timeMs"),decimalEither(t,"score","score")));}return new CodeRunResult(r.getId(),r.getStatus(),intEither(n,"passed_count","passedCount"),intEither(n,"total_count","totalCount"),r.getScore(),r.getDurationMs(),null,null,cases,null);}catch(Exception e){return null;}}
  private List<AiQuestionPlan> aiPlans(SubmissionEntity s){
    List<AiQuestionPlan> result=new ArrayList<>();
    boolean attachmentAiEnabled=snapshotSettings(s).aiAttachmentGradeEnabled();
    for(QuestionDraft q:snapshotQuestions(s)){
      if(q.questionType()!=QuestionType.SUBJECTIVE&&q.questionType()!=QuestionType.ATTACHMENT)continue;
      if(q.questionType()==QuestionType.ATTACHMENT&&!attachmentAiEnabled)continue;
      SubmissionAnswerEntity a=findAnswer(s.getId(),q.id());
      if(a==null||!answered(a,q.questionType())||q.rubricItems().isEmpty())continue;
      List<Long> ids=q.questionType()==QuestionType.ATTACHMENT?attachments.findFileIds(a.getId()):List.of();
      if(q.questionType()==QuestionType.ATTACHMENT&&ids.isEmpty())continue;
      result.add(new AiQuestionPlan(q,a,ids));
    }
    return result;
  }
  private AiGradeOutcome validateAiResponse(AiTaskEntity task,AiQuestionPlan plan,AiGradeResponse r){if(r==null||!task.getRequestId().equals(r.requestId()))throw invalidAi();validateScore(r.suggestedScore(),plan.question().score(),"suggested_score");if(r.rubricGrades()==null||r.rubricGrades().size()!=plan.question().rubricItems().size())throw invalidAi();BigDecimal sum=BigDecimal.ZERO,conf=BigDecimal.ZERO;List<RubricGrade> out=new ArrayList<>();for(int i=0;i<r.rubricGrades().size();i++){AiGradeResponse.RubricGrade g=r.rubricGrades().get(i);RubricItem expected=plan.question().rubricItems().get(i);if(!expected.label().equals(g.label())||g.maxScore()==null||g.maxScore().compareTo(expected.maxScore())!=0)throw invalidAi();validateScore(g.score(),g.maxScore(),"rubric score");if(g.evidence()==null||g.evidence().size()>20||g.evidence().stream().anyMatch(x->x==null||x.length()>1000)||g.comment()==null||g.comment().length()>2000||g.confidence()==null||g.confidence().compareTo(BigDecimal.ZERO)<0||g.confidence().compareTo(BigDecimal.ONE)>0)throw invalidAi();sum=sum.add(g.score());conf=conf.add(g.confidence());out.add(new RubricGrade(g.label(),g.score(),g.maxScore(),g.evidence(),g.confidence(),g.comment()));}if(sum.compareTo(r.suggestedScore())!=0||r.overallComment()==null||r.overallComment().length()>5000||r.warnings()==null||r.warnings().size()>20||r.warnings().stream().anyMatch(x->x==null||x.length()>1000)||blank(r.modelVersion())||blank(r.promptVersion()))throw invalidAi();return new AiGradeOutcome(plan.answer().getId(),r,List.copyOf(out),conf.divide(BigDecimal.valueOf(out.size()),4,RoundingMode.HALF_UP));}
  private void validateRubricGrades(List<RubricGrade> grades,QuestionDraft q,BigDecimal requestedScore){if(grades.isEmpty())return;if(grades.size()!=q.rubricItems().size())throw validation("评分点数量必须与发布版本一致");BigDecimal sum=BigDecimal.ZERO;for(int i=0;i<grades.size();i++){RubricGrade g=grades.get(i);RubricItem e=q.rubricItems().get(i);if(!e.label().equals(g.label())||g.maxScore().compareTo(e.maxScore())!=0)throw validation("评分点必须与发布版本一致");validateScore(g.score(),g.maxScore(),"rubric score");sum=sum.add(g.score());}if(sum.compareTo(requestedScore)!=0)throw validation("评分点分数合计必须等于本题总分");}
  private void validateScore(BigDecimal value,BigDecimal max,String name){if(value==null||value.scale()>2||value.compareTo(BigDecimal.ZERO)<0||value.compareTo(max)>0)throw validation(name+" 必须非负、最多两位小数且不超过满分");}
  private boolean answered(SubmissionAnswerEntity a,QuestionType type){Map<String,Object> p=readMap(a.getAnswerJson());return switch(type){case SINGLE_CHOICE,MULTIPLE_CHOICE->!strings(p.get("selectedOptionKeys")).isEmpty();case SUBJECTIVE->p.get("textAnswer") instanceof String s&&!s.isBlank();case PROGRAMMING->a.getCodeText()!=null&&!a.getCodeText().isBlank();case ATTACHMENT->!attachments.findFileIds(a.getId()).isEmpty();};}
  private List<DistributionBucket> distribution(List<BigDecimal> scores,BigDecimal max){if(scores.isEmpty())return List.of();List<DistributionBucket> r=new ArrayList<>();for(int i=0;i<5;i++){BigDecimal from=max.multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(5),2,RoundingMode.HALF_UP),to=i==4?max.add(new BigDecimal("0.01")):max.multiply(BigDecimal.valueOf(i+1)).divide(BigDecimal.valueOf(5),2,RoundingMode.HALF_UP);long count=scores.stream().filter(s->s.compareTo(from)>=0&&s.compareTo(to)<0).count();r.add(new DistributionBucket((i*20)+"-"+((i+1)*20),from,to,count));}return r;}
  private BigDecimal ratio(long a,long b){return b==0?BigDecimal.ZERO:BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b),4,RoundingMode.HALF_UP);}
  private String gradeRequestId(Long recordId){return GRADE_REQUEST_ID_PREFIX+recordId;}
  private AsyncTask toTask(AiTaskEntity t){return toTask(t,false);}
  private AsyncTask toTask(AiTaskEntity t,boolean auditView){
    Map<String,Object> raw=readMap(t.getResultJson());
    Map<String,Object> result=auditView
        ? map("submissionId",raw.get("submissionId"),"answerCount",raw.get("answerCount"))
        : raw;
    return new AsyncTask(t.getId(),t.getTaskType(),t.getStatus(),t.getProgressPercent(),t.getResourceType(),t.getResourceId(),result,t.getErrorCode(),auditView?null:t.getErrorMessage(),toInstant(t.getCreatedAt()),toInstant(t.getStartedAt()),toInstant(t.getFinishedAt()));
  }
  private IdempotencyClaim<Submission> claimComplete(Long uid,String op,String key,String hash){return claim(uid,op,key,hash,Submission.class);}
  private IdempotencyClaim<AsyncTask> claimTask(Long uid,String op,String key,String hash){return claim(uid,op,key,hash,AsyncTask.class);}
  private <T> IdempotencyClaim<T> claim(Long uid,String op,String key,String hash,Class<T> type){IdempotencyRecordEntity existing=findClaim(uid,op,key);if(existing!=null)return replayOrRecover(existing,hash,type);IdempotencyRecordEntity created=new IdempotencyRecordEntity();created.setUserId(uid);created.setOperationId(op);created.setIdempotencyKey(key);created.setRequestHash(hash);created.setExpiresAt(LocalDateTime.now().plusHours(24));try{idempotency.insert(created);activeIdempotencyClaims.add(created.getId());return new IdempotencyClaim<>(created.getId(),null,false);}catch(DuplicateKeyException race){existing=findClaim(uid,op,key);if(existing==null)throw conflict("相同操作正在处理");return replayOrRecover(existing,hash,type);}}
  private <T> IdempotencyClaim<T> replayOrRecover(IdempotencyRecordEntity r,String hash,Class<T> type){if(!hash.equals(r.getRequestHash()))throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"幂等键已用于不同请求");if(r.getResponseJson()!=null)try{return new IdempotencyClaim<>(r.getId(),json.readValue(r.getResponseJson(),type),false);}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"幂等响应损坏");}if(activeIdempotencyClaims.contains(r.getId()))throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"相同操作正在处理");activeIdempotencyClaims.add(r.getId());return new IdempotencyClaim<>(r.getId(),null,true);}
  private IdempotencyRecordEntity findClaim(Long uid,String op,String key){return idempotency.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>().eq(IdempotencyRecordEntity::getUserId,uid).eq(IdempotencyRecordEntity::getOperationId,op).eq(IdempotencyRecordEntity::getIdempotencyKey,key));}
  private void completeClaim(Long recordId,Object value){IdempotencyRecordEntity r=idempotency.selectById(recordId);if(r==null)throw conflict("幂等记录不存在");if(r.getResponseJson()==null){r.setResponseStatus(200);r.setResponseJson(write(value));idempotency.updateById(r);}activeIdempotencyClaims.remove(recordId);}
  private void releaseClaim(Long recordId){IdempotencyRecordEntity r=idempotency.selectById(recordId);if(r!=null&&r.getResponseJson()==null)idempotency.deleteById(r.getId());activeIdempotencyClaims.remove(recordId);}
  private Map<String,Object> readMap(String v){if(v==null||v.isBlank())return Map.of();try{return json.readValue(v,new TypeReference<Map<String,Object>>(){});}catch(Exception e){return Map.of();}}
  private Long longValue(Object value){if(value instanceof Number number)return number.longValue();if(value instanceof String text)try{return Long.valueOf(text);}catch(NumberFormatException ignored){}return null;}
  private List<String> strings(Object v){if(!(v instanceof List<?> l))return List.of();return l.stream().filter(Objects::nonNull).map(Object::toString).toList();}
  private List<SubmissionEntity> latestByStudent(List<SubmissionEntity> rows){Map<Long,SubmissionEntity> latest=new LinkedHashMap<>();for(SubmissionEntity row:rows)latest.putIfAbsent(row.getStudentId(),row);return List.copyOf(latest.values());}
  private boolean blank(String value){return value==null||value.isBlank();}
  private Map<String,Object> map(Object...v){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)if(v[i+1]!=null)r.put(v[i].toString(),v[i+1]);return r;}
  private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"结构化数据写入失败");}}
  private String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private String text(JsonNode n,String f){return n.hasNonNull(f)?n.get(f).asText():null;} private String trim(String v){return v==null?null:v.trim();}
  private String textEither(JsonNode n,String snake,String camel){return n.hasNonNull(snake)?n.get(snake).asText():text(n,camel);}
  private Long longEither(JsonNode n,String snake,String camel){JsonNode v=n.has(snake)?n.get(snake):n.get(camel);return v==null||v.isNull()?null:v.asLong();}
  private Integer intEither(JsonNode n,String snake,String camel){JsonNode v=n.has(snake)?n.get(snake):n.get(camel);return v==null||v.isNull()?null:v.asInt();}
  private BigDecimal decimalEither(JsonNode n,String snake,String camel){JsonNode v=n.has(snake)?n.get(snake):n.get(camel);return v==null||v.isNull()?null:v.decimalValue();}
  private Instant toInstant(LocalDateTime v){return v==null?null:v.atZone(DB_ZONE).toInstant();}
  private BusinessException invalidAi(){return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"AI 批改返回无效响应");}
  private BusinessException validation(String m){return new BusinessException(ErrorCode.VALIDATION_ERROR,m);}
  private BusinessException conflict(String m){return new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,m);}
  private BusinessException notFound(String m){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,m);}
  private record AiQuestionPlan(QuestionDraft question,SubmissionAnswerEntity answer,List<Long> fileIds){}
  private record AiTaskPlan(AiTaskEntity task,List<AiQuestionPlan> questions){}
  private record AiGradeOutcome(Long answerId,AiGradeResponse response,List<RubricGrade> rubricGrades,BigDecimal confidence){}
  private record IdempotencyClaim<T>(Long recordId,T replay,boolean incomplete){}
  private static final class KpAggregate{private final String name;private BigDecimal score=BigDecimal.ZERO,max=BigDecimal.ZERO;private long count;private KpAggregate(String n){name=n;}}
}
