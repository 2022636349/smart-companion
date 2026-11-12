package com.education.agent.grading.service;

import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentDetail;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.service.AssessmentService;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.grading.dto.GradingDtos.ReleaseResultsRequest;
import com.education.agent.learning.service.LearningEvidenceService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.mapper.SubmissionMapper;
import com.education.agent.submission.service.SubmissionService;
import com.education.agent.submission.service.AssessmentActivityGuard;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.notification.service.BusinessNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssessmentLifecycleService {
  private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
  private final AssessmentMapper assessments;
  private final SubmissionMapper submissions;
  private final SubmissionService submissionService;
  private final AssessmentActivityGuard assessmentActivityGuard;
  private final AssessmentService assessmentService;
  private final IdempotencyRecordMapper idempotency;
  private final ObjectMapper json;
  private final AuditLogService audit;
  private final LearningEvidenceService learningEvidence;
  private final BusinessNotificationDispatcher notificationDispatcher;
  private final TransactionTemplate tx;
  private final com.education.agent.course.CourseAccessService courses;
  private final Set<Long> activeIdempotencyClaims = ConcurrentHashMap.newKeySet();

  public AssessmentLifecycleService(
      AssessmentMapper assessments,
      SubmissionMapper submissions,
      SubmissionService submissionService,
      AssessmentActivityGuard assessmentActivityGuard,
      AssessmentService assessmentService,
      IdempotencyRecordMapper idempotency,
      ObjectMapper json,
      AuditLogService audit,
      LearningEvidenceService learningEvidence,
      BusinessNotificationDispatcher notificationDispatcher,
      PlatformTransactionManager transactionManager, com.education.agent.course.CourseAccessService courses) {
    this.assessments = assessments;
    this.submissions = submissions;
    this.submissionService = submissionService;
    this.assessmentActivityGuard = assessmentActivityGuard;
    this.assessmentService = assessmentService;
    this.idempotency = idempotency;
    this.json = json;
    this.audit = audit;
    this.learningEvidence = learningEvidence;
    this.notificationDispatcher = notificationDispatcher;
    this.tx = new TransactionTemplate(transactionManager);
    this.courses = courses;
  }

  public AssessmentDetail close(AuthenticatedUser principal, Long assessmentId, String key) {
    requireTeacher(principal, assessmentId);
    String operation = "CLOSE_ASSESSMENT";
    LifecycleClaim claim = claim(principal.id(), operation, key, sha256(operation + ":" + assessmentId));
    if (claim.replay() != null) return claim.replay();
    try {
      AssessmentEntity current = assessments.selectById(assessmentId);
      boolean alreadyAdvanced = claim.incomplete()
          && current != null
          && (current.getStatus() == AssessmentStatus.RELEASED
              || current.getStatus() == AssessmentStatus.ARCHIVED);
      if (!alreadyAdvanced) closeInternal(assessmentId, principal.id());
      AssessmentDetail result = assessmentService.get(principal, assessmentId);
      completeClaim(claim.recordId(), result);
      return result;
    } catch (RuntimeException failure) {
      releaseClaim(claim.recordId());
      throw failure;
    }
  }

  public AssessmentDetail release(
      AuthenticatedUser principal, Long assessmentId, String key, ReleaseResultsRequest request) {
    requireTeacher(principal, assessmentId);
    LocalDateTime requested = request == null || request.releasedAt() == null
        ? LocalDateTime.now()
        : dbPrecision(LocalDateTime.ofInstant(request.releasedAt(), DB_ZONE));
    String operation = "RELEASE_ASSESSMENT";
    String requestFingerprint = request == null || request.releasedAt() == null ? "IMMEDIATE" : request.releasedAt().toString();
    LifecycleClaim claim = claim(
        principal.id(), operation, key,
        sha256(operation + ":" + assessmentId + ":" + requestFingerprint));
    if (claim.replay() != null) return claim.replay();
    try {
      AssessmentEntity current = assessments.selectById(assessmentId);
      boolean persisted = claim.incomplete() && releaseEffectPersisted(current, request, requested);
      if (!persisted) {
        tx.executeWithoutResult(status -> scheduleOrRelease(assessmentId, principal.id(), requested));
      }
      AssessmentEntity released = assessments.selectById(assessmentId);
      if (released != null && (released.getStatus() == AssessmentStatus.RELEASED
          || released.getStatus() == AssessmentStatus.ARCHIVED)) learningEvidence.reconcileAssessment(assessmentId);
      AssessmentDetail result = assessmentService.get(principal, assessmentId);
      completeClaim(claim.recordId(), result);
      return result;
    } catch (RuntimeException failure) {
      releaseClaim(claim.recordId());
      throw failure;
    }
  }

  public AssessmentDetail archive(AuthenticatedUser principal, Long assessmentId) {
    requireTeacher(principal, assessmentId);
    tx.executeWithoutResult(
        status -> {
          AssessmentEntity assessment = requireLocked(assessmentId);
          if (assessment.getStatus() == AssessmentStatus.ARCHIVED) return;
          if (assessment.getStatus() != AssessmentStatus.RELEASED) throw conflict("只有已公布测评可以归档");
          assessment.setStatus(AssessmentStatus.ARCHIVED);
          assessments.updateById(assessment);
          audit.record("ASSESSMENT_ARCHIVED", "ASSESSMENT", assessmentId.toString(), principal.id(), true, Map.of());
        });
    return assessmentService.get(principal, assessmentId);
  }

  @Scheduled(fixedDelayString = "${education.assessment.lifecycle-scan-ms:5000}")
  public void scanLifecycle() {
    for (Long id : assessments.findDueForClose(100)) {
      try { closeInternal(id, null); } catch (RuntimeException ignored) {}
    }
    for (Long id : assessments.findScheduledForRelease(100)) {
      try {
        tx.executeWithoutResult(status -> scheduleOrRelease(id, null, LocalDateTime.now()));
      } catch (RuntimeException ignored) {}
    }
    for (Long id : assessments.findGradingStateIds(100)) {
      try { refreshAssessmentState(id); } catch (RuntimeException ignored) {}
    }
  }

  public void refreshAssessmentState(Long assessmentId) {
    tx.executeWithoutResult(
        status -> {
          AssessmentEntity assessment = requireLocked(assessmentId);
          refreshReadyState(assessment);
          assessments.updateById(assessment);
          if (assessment.getStatus() == AssessmentStatus.RELEASED) learningEvidence.reconcileAssessment(assessmentId);
        });
  }

  private void closeInternal(Long assessmentId, Long operatorId) {
    assessmentActivityGuard.withClose(assessmentId, () -> doCloseInternal(assessmentId, operatorId));
  }

  private void doCloseInternal(Long assessmentId, Long operatorId) {
    tx.executeWithoutResult(
        status -> {
          AssessmentEntity assessment = requireLocked(assessmentId);
          if (assessment.getStatus() != AssessmentStatus.PUBLISHED
              && assessment.getStatus() != AssessmentStatus.CLOSED
              && assessment.getStatus() != AssessmentStatus.GRADING
              && assessment.getStatus() != AssessmentStatus.READY_TO_RELEASE) {
            throw conflict("当前测评状态不能截止");
          }
          if (assessment.getStatus() == AssessmentStatus.PUBLISHED) assessment.setStatus(AssessmentStatus.CLOSED);
          assessments.updateById(assessment);
        });
    // A student submit may already hold its submission row when CLOSED is committed. Keep
    // draining the authoritative query until every open exam is terminal before returning.
    while (true) {
      var openExamIds = submissions.findOpenExamIds(assessmentId);
      if (openExamIds.isEmpty()) break;
      for (Long submissionId : openExamIds) {
        submissionService.autoSubmitForAssessmentClose(submissionId);
      }
    }
    tx.executeWithoutResult(status -> {
      AssessmentEntity assessment = requireLocked(assessmentId);
      refreshReadyState(assessment);
      assessments.updateById(assessment);
      if (assessment.getStatus() == AssessmentStatus.RELEASED) learningEvidence.reconcileAssessment(assessmentId);
      audit.record("ASSESSMENT_CLOSED", "ASSESSMENT", assessmentId.toString(), operatorId, true, Map.of());
    });
  }

  private void refreshReadyState(AssessmentEntity assessment) {
    if (assessment.getStatus() == AssessmentStatus.RELEASED || assessment.getStatus() == AssessmentStatus.ARCHIVED) return;
    if (assessment.getStatus() == AssessmentStatus.PUBLISHED
        || assessment.getStatus() == AssessmentStatus.DRAFT
        || assessment.getStatus() == AssessmentStatus.PARSING) return;
    long submitted = submissions.countSubmitted(assessment.getId());
    long incomplete = submissions.countIncompleteSubmitted(assessment.getId());
    assessment.setStatus(submitted == 0 || incomplete == 0 ? AssessmentStatus.READY_TO_RELEASE : AssessmentStatus.GRADING);
    if (assessment.getStatus() == AssessmentStatus.READY_TO_RELEASE
        && Boolean.TRUE.equals(assessment.getAutoReleaseEnabled())) {
      LocalDateTime now = LocalDateTime.now();
      assessment.setStatus(AssessmentStatus.RELEASED);
      assessment.setScheduledReleaseAt(null);
      submissions.releaseCompleted(assessment.getId(), now);
      audit.record(
          "ASSESSMENT_RESULTS_AUTO_RELEASED",
          "ASSESSMENT",
          assessment.getId().toString(),
          null,
          true,
          Map.of("releasedAt", now.toString()));
      notificationDispatcher.onAuditEvent("ASSESSMENT_RESULTS_AUTO_RELEASED","ASSESSMENT",assessment.getId().toString());
    }
  }

  private void scheduleOrRelease(Long assessmentId, Long operatorId, LocalDateTime requested) {
    AssessmentEntity assessment = requireLocked(assessmentId);
    refreshReadyState(assessment);
    if (assessment.getStatus() == AssessmentStatus.RELEASED) return;
    if (assessment.getStatus() != AssessmentStatus.READY_TO_RELEASE) {
      throw new BusinessException(ErrorCode.GRADING_INCOMPLETE, "仍有答卷未完成批改");
    }
    if (requested.isAfter(LocalDateTime.now())) {
      assessment.setScheduledReleaseAt(requested);
    } else {
      LocalDateTime now = LocalDateTime.now();
      assessment.setStatus(AssessmentStatus.RELEASED);
      assessment.setScheduledReleaseAt(null);
      submissions.releaseCompleted(assessmentId, now);
      audit.record("ASSESSMENT_RESULTS_RELEASED", "ASSESSMENT", assessmentId.toString(), operatorId, true, Map.of());
      notificationDispatcher.onAuditEvent("ASSESSMENT_RESULTS_RELEASED","ASSESSMENT",assessmentId.toString());
    }
    assessments.updateById(assessment);
    if (assessment.getStatus() == AssessmentStatus.RELEASED) learningEvidence.reconcileAssessment(assessmentId);
  }

  private void requireTeacher(AuthenticatedUser principal, Long assessmentId) {
    AssessmentEntity assessment = assessments.selectById(assessmentId);
    if (assessment == null || principal.role() != UserRole.TEACHER || !assessment.getCreatorId().equals(principal.id())) throw notFound();
    courses.requireEdit(principal,courses.classCourse(assessment.getClassId()));
  }

  private boolean releaseEffectPersisted(
      AssessmentEntity assessment, ReleaseResultsRequest request, LocalDateTime requested) {
    if (assessment == null) return false;
    if (assessment.getStatus() == AssessmentStatus.RELEASED
        || assessment.getStatus() == AssessmentStatus.ARCHIVED) return true;
    boolean scheduledRequest = request != null && request.releasedAt() != null;
    return scheduledRequest
        && assessment.getScheduledReleaseAt() != null
        && assessment.getScheduledReleaseAt().isEqual(requested);
  }

  private AssessmentEntity requireLocked(Long id) {
    AssessmentEntity assessment = assessments.selectForUpdate(id);
    if (assessment == null) throw notFound();
    return assessment;
  }

  private LifecycleClaim claim(Long userId, String operation, String key, String hash) {
    IdempotencyRecordEntity old = find(userId, operation, key);
    if (old != null) {
      if (!hash.equals(old.getRequestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键已用于不同请求");
      if (old.getResponseJson() != null) {
        try { return new LifecycleClaim(old.getId(), json.readValue(old.getResponseJson(), AssessmentDetail.class), false); }
        catch (Exception e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等响应损坏"); }
      }
      if (!activeIdempotencyClaims.add(old.getId())) {
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同操作正在处理中");
      }
      return new LifecycleClaim(old.getId(), null, true);
    }
    IdempotencyRecordEntity record = new IdempotencyRecordEntity();
    record.setUserId(userId); record.setOperationId(operation); record.setIdempotencyKey(key);
    record.setRequestHash(hash); record.setExpiresAt(LocalDateTime.now().plusHours(24));
    try {
      idempotency.insert(record);
      activeIdempotencyClaims.add(record.getId());
      return new LifecycleClaim(record.getId(), null, false);
    } catch (DuplicateKeyException race) {
      return claim(userId, operation, key, hash);
    }
  }

  private void completeClaim(Long recordId, AssessmentDetail response) {
    IdempotencyRecordEntity record = idempotency.selectById(recordId);
    if (record == null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "幂等记录不存在");
    try { record.setResponseJson(json.writeValueAsString(response)); }
    catch (Exception e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等响应序列化失败"); }
    record.setResponseStatus(200);
    idempotency.updateById(record);
    activeIdempotencyClaims.remove(recordId);
  }

  private void releaseClaim(Long recordId) {
    IdempotencyRecordEntity record = idempotency.selectById(recordId);
    if (record != null && record.getResponseJson() == null) idempotency.deleteById(record.getId());
    activeIdempotencyClaims.remove(recordId);
  }

  private IdempotencyRecordEntity find(Long userId, String operation, String key) {
    return idempotency.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IdempotencyRecordEntity>()
        .eq(IdempotencyRecordEntity::getUserId, userId).eq(IdempotencyRecordEntity::getOperationId, operation)
        .eq(IdempotencyRecordEntity::getIdempotencyKey, key));
  }

  private String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private LocalDateTime dbPrecision(LocalDateTime value) {
    return value.withNano((value.getNano() / 1_000_000) * 1_000_000);
  }
  private BusinessException conflict(String message) { return new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, message); }
  private BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在"); }
  private record LifecycleClaim(Long recordId, AssessmentDetail replay, boolean incomplete) {}
}
