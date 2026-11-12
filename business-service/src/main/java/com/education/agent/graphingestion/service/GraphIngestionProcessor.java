package com.education.agent.graphingestion.service;

import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.service.AssessmentFileDownloadUrlFactory;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.graphingestion.entity.GraphIngestionJobEntity;
import com.education.agent.graphingestion.entity.GraphIngestionOutboxEntity;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.graphingestion.mapper.GraphIngestionJobMapper;
import com.education.agent.graphingestion.mapper.GraphIngestionOutboxMapper;
import com.education.agent.graphingestion.model.GraphIngestionEventType;
import com.education.agent.graphingestion.model.GraphIngestionStatus;
import com.education.agent.graphingestion.model.GraphOutboxStatus;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GraphIngestionProcessor {
    private static final Logger log = LoggerFactory.getLogger(GraphIngestionProcessor.class);
    private static final Duration LEASE = Duration.ofMinutes(10);

    private final TaskExecutor executor;
    private final GraphIngestionJobMapper jobs;
    private final GraphIngestionOutboxMapper outbox;
    private final FileStorageService files;
    private final AssessmentFileDownloadUrlFactory downloadUrls;
    private final AiServiceClient ai;
    private final KnowledgeServiceClient graph;
    private final GraphIngestionBoundaryValidator boundary;
    private final GraphMutationIdempotency idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.education.agent.course.CourseAccessService courses;
    private final String workerId;

    public GraphIngestionProcessor(
            @Qualifier("graphIngestionExecutor") TaskExecutor executor,
            GraphIngestionJobMapper jobs,
            GraphIngestionOutboxMapper outbox,
            FileStorageService files,
            AssessmentFileDownloadUrlFactory downloadUrls,
            AiServiceClient ai,
            KnowledgeServiceClient graph,
            GraphIngestionBoundaryValidator boundary,
            GraphMutationIdempotency idempotency,
            AuditLogService audit,
            ObjectMapper json,
            com.education.agent.course.CourseAccessService courses, PlatformTransactionManager transactionManager,
            @Value("${education.graph-ingestion.instance-id:}") String configuredWorkerId) {
        this.executor = executor;
        this.jobs = jobs;
        this.outbox = outbox;
        this.files = files;
        this.downloadUrls = downloadUrls;
        this.ai = ai;
        this.graph = graph;
        this.boundary = boundary;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.courses=courses;
        this.tx = new TransactionTemplate(transactionManager);
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "graph-worker-" + UUID.randomUUID().toString().substring(0, 12)
                : validateWorkerId(configuredWorkerId.trim());
    }

    public void processAsync() {
        try {
            executor.execute(() -> processOnce(10));
        } catch (RuntimeException exception) {
            log.warn("Graph ingestion executor is saturated; durable outbox remains pending", exception);
        }
    }

    @Scheduled(fixedDelayString = "${education.graph-ingestion.scan-ms:3000}",
            initialDelayString = "${education.graph-ingestion.initial-delay-ms:5000}")
    public void scheduled() {
        processOnce(10);
    }

    public int processOnce(int limit) {
        int processed = 0;
        for (Long id : outbox.selectClaimableIds(Math.max(1, Math.min(limit, 100)))) {
            if (processEvent(id)) processed++;
        }
        return processed;
    }

    /** Used by synchronous publish while preserving an already-committed recoverable outbox. */
    public boolean processEventNow(Long eventId) {
        return processEvent(eventId);
    }

    private boolean processEvent(Long eventId) {
        Claimed claimed = tx.execute(status -> claim(eventId));
        if (claimed == null) return false;
        try {
            if (claimed.eventType() == GraphIngestionEventType.EXTRACT_REVIEW) processExtraction(claimed);
            else if (claimed.eventType() == GraphIngestionEventType.PUBLISH) processPublish(claimed);
            else fail(claimed, ErrorCode.INTERNAL_ERROR, "不支持的建图事件");
        } catch (BusinessException exception) {
            fail(claimed, exception.errorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Graph ingestion event {} failed", claimed.eventId(), exception);
            fail(claimed, ErrorCode.INTERNAL_ERROR, "建图任务执行失败，请稍后重试");
        }
        return true;
    }

    private Claimed claim(Long eventId) {
        GraphIngestionOutboxEntity event = outbox.selectForUpdate(eventId);
        LocalDateTime now = LocalDateTime.now();
        boolean claimable = event != null && (event.getStatus() == GraphOutboxStatus.PENDING
                && !event.getNextAttemptAt().isAfter(now)
                || event.getStatus() == GraphOutboxStatus.RUNNING
                && event.getLeaseUntil() != null && event.getLeaseUntil().isBefore(now));
        if (!claimable) return null;
        GraphIngestionJobEntity job = jobs.selectForUpdate(event.getJobId());
        if (job == null || job.getStatus() == GraphIngestionStatus.CANCELLED
                || job.getStatus() == GraphIngestionStatus.PUBLISHED) {
            event.setStatus(GraphOutboxStatus.FAILED);
            event.setLastErrorCode(ErrorCode.GRAPH_INGESTION_INVALID_STATE.name());
            event.setLastErrorMessage("任务已不存在或进入终态");
            event.setCompletedAt(now);
            outbox.updateById(event);
            return null;
        }
        int attempt = number(event.getAttemptCount()) + 1;
        event.setStatus(GraphOutboxStatus.RUNNING);
        event.setAttemptCount(attempt);
        event.setLeaseOwner(workerId);
        event.setLeaseUntil(now.plus(LEASE));
        event.setLastErrorCode(null);
        event.setLastErrorMessage(null);
        outbox.updateById(event);
        job.setAttemptCount(attempt);
        job.setStartedAt(job.getStartedAt() == null ? now : job.getStartedAt());
        job.setFinishedAt(null);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        if (event.getEventType() == GraphIngestionEventType.EXTRACT_REVIEW) {
            job.setStatus(GraphIngestionStatus.EXTRACTING);
            job.setProgressPercent(10);
        } else {
            job.setStatus(GraphIngestionStatus.PUBLISHING);
            job.setProgressPercent(90);
        }
        jobs.updateById(job);
        return new Claimed(event.getId(), job.getId(), event.getEventType(), event.getRequestId(),
                event.getPayloadJson(), attempt, number(event.getMaxAttempts()), workerId);
    }

    private void processExtraction(Claimed claimed) {
        GraphIngestionService.ExtractionPayload payload =
                read(claimed.payloadJson(), GraphIngestionService.ExtractionPayload.class);
        GraphIngestionJobEntity job = requireJob(claimed.jobId());
        FileObjectEntity file = files.findAvailable(job.getSourceFileId());
        String baseVersion = job.getBaseGraphVersion();
        if (baseVersion == null || baseVersion.isBlank()) {
            baseVersion = graph.currentGraphVersion(job.getCourseId(), claimed.requestId());
            String resolved = baseVersion;
            tx.executeWithoutResult(status -> {
                GraphIngestionJobEntity locked = jobs.selectForUpdate(claimed.jobId());
                if (locked.getBaseGraphVersion() == null || locked.getBaseGraphVersion().isBlank()) {
                    locked.setBaseGraphVersion(resolved);
                    jobs.updateById(locked);
                } else if (!locked.getBaseGraphVersion().equals(resolved)) {
                    throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "图谱基准版本解析发生并发冲突");
                }
            });
        }
        String batchId = batchId(job);
        GraphIngestionInternalDtos.SourceRef sourceRef =
                new GraphIngestionInternalDtos.SourceRef(job.getSourceType(),
                        "file:" + file.getId(), job.getTitle(), file.getSha256(),
                        "file:" + file.getId());
        GraphIngestionInternalDtos.BatchDetail recovered = findExistingBatch(
                job.getCourseId(), claimed.requestId(), batchId, baseVersion, sourceRef, job.getCopyrightStatus());
        if (recovered != null) {
            completeExtraction(claimed, recovered);
            return;
        }
        GraphIngestionInternalDtos.ExtractReviewRequest request =
                new GraphIngestionInternalDtos.ExtractReviewRequest(
                        job.getCourseId(), (String)courses.requireCourse(job.getCourseId()).get("name"),
                        claimed.requestId(), job.getId(), file.getId(), file.getOriginalName(),
                        downloadUrls.forFile(file.getId()),
                        new GraphIngestionInternalDtos.ExtractSource(job.getSourceType(), job.getTitle(),
                                null, file.getId(), job.getCopyrightStatus()), "zh-CN");
        GraphIngestionBoundaryValidator.ValidatedExtraction extraction =
                boundary.enforceAuthoritativeRights(boundary.validateExtraction(
                        job.getId(), claimed.requestId(), requireExtractCourse(job.getCourseId(), ai.extractAndReviewGraph(request))),
                        job.getCopyrightStatus());
        GraphIngestionInternalDtos.BatchUpsertRequest unsigned =
                new GraphIngestionInternalDtos.BatchUpsertRequest(
                        claimed.requestId(), batchId, baseVersion, "0".repeat(64),
                        sourceRef,
                        payload.thresholdProfile(), extraction.models(), extraction.prompts(),
                        extraction.nodes(), extraction.edges(), extraction.sources());
        String digest = boundary.payloadSha256(unsigned);
        GraphIngestionInternalDtos.BatchUpsertRequest batch =
                new GraphIngestionInternalDtos.BatchUpsertRequest(
                        unsigned.requestId(), unsigned.batchId(), unsigned.baseGraphVersion(), digest,
                        unsigned.source(), unsigned.thresholdProfile(), unsigned.models(), unsigned.prompts(),
                        unsigned.nodes(), unsigned.edges(), unsigned.sources());
        GraphIngestionInternalDtos.BatchDetail detail = boundary.validateUpsert(
                batchId, baseVersion, digest, graph.upsertIngestionBatch(job.getCourseId(), claimed.requestId(), batch));
        completeExtraction(claimed, detail);
    }

    private GraphIngestionInternalDtos.ExtractReviewResponse requireExtractCourse(Long courseId,GraphIngestionInternalDtos.ExtractReviewResponse response){
        if(response==null||!courseId.equals(response.courseId()))throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"AI 建图响应课程不一致");return response;
    }

    private void completeExtraction(Claimed claimed, GraphIngestionInternalDtos.BatchDetail detail) {
        tx.executeWithoutResult(status -> {
            GraphIngestionOutboxEntity event = requireOwnedEvent(claimed);
            GraphIngestionJobEntity job = jobs.selectForUpdate(claimed.jobId());
            if (job == null) throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "建图任务不存在");
            job.setIngestionBatchId(detail.batchId());
            job.setBatchRevision(detail.revision());
            job.setTotalCandidateCount(detail.candidateCount());
            job.setNodeCount(detail.nodes().size());
            job.setEdgeCount(detail.edges().size());
            job.setSourceCount(detail.sources().size());
            job.setPendingCandidateCount(detail.pendingCount());
            job.setAcceptedCandidateCount(detail.approvedCount());
            job.setRejectedCandidateCount(detail.rejectedCount());
            job.setErrorCount(detail.errorCount());
            job.setWarningCount(detail.warningCount());
            job.setExtractorModelProvider(detail.models().extractor().provider());
            job.setExtractorModelVersion(detail.models().extractor().model());
            job.setReviewerModelProvider(detail.models().reviewer().provider());
            job.setReviewerModelVersion(detail.models().reviewer().model());
            job.setExtractorPromptVersion(detail.prompts().extractor().version());
            job.setExtractorPromptSha256(detail.prompts().extractor().sha256());
            job.setReviewerPromptVersion(detail.prompts().reviewer().version());
            job.setReviewerPromptSha256(detail.prompts().reviewer().sha256());
            job.setStatus(statusFor(detail));
            job.setProgressPercent(job.getStatus() == GraphIngestionStatus.READY_TO_PUBLISH ? 85 : 70);
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorCode(null);
            job.setErrorMessage(null);
            jobs.updateById(job);
            completeEvent(event);
            audit.recordRequired("GRAPH_INGESTION_DRAFT_CREATED", "GRAPH_INGESTION_JOB",
                    job.getId().toString(), job.getCreatedBy(), true,
                    Map.of("batchId", detail.batchId(), "revision", detail.revision(),
                            "candidateCount", detail.candidateCount(), "warningCount", detail.warningCount()));
        });
    }

    private GraphIngestionInternalDtos.BatchDetail findExistingBatch(
            Long courseId, String requestId, String batchId, String baseVersion,
            GraphIngestionInternalDtos.SourceRef expectedSource, String copyrightStatus) {
        try {
            GraphIngestionInternalDtos.BatchDetail detail = boundary.validateDetail(batchId, baseVersion, null,
                    graph.getIngestionBatch(courseId, requestId, batchId));
            if (!expectedSource.equals(detail.source())) {
                throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                        "已存在图谱批次的来源身份与当前文件不一致");
            }
            boundary.validateAuthoritativeRights(detail, copyrightStatus);
            return detail;
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND) return null;
            throw exception;
        }
    }

    private String batchId(GraphIngestionJobEntity job) {
        String requestId = job.getRequestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 48
                || !requestId.matches("[A-Za-z0-9._:-]+")) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "建图任务稳定标识不合法");
        }
        return "graph-ingestion:" + requestId;
    }

    private void processPublish(Claimed claimed) {
        GraphIngestionService.PublishPayload payload =
                read(claimed.payloadJson(), GraphIngestionService.PublishPayload.class);
        GraphIngestionJobEntity job = requireJob(claimed.jobId());
        GraphIngestionInternalDtos.BatchDetail snapshot = boundary.validateDetail(
                job.getIngestionBatchId(), job.getBaseGraphVersion(), null,
                graph.getIngestionBatch(job.getCourseId(), claimed.requestId(), job.getIngestionBatchId()));
        if (!payload.expectedRevision().equals(snapshot.revision())) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                    "发布草稿 revision 已变化");
        }
        boolean publishedRecovery = "PUBLISHED".equals(snapshot.status());
        if (publishedRecovery
                && !payload.targetGraphVersion().equals(snapshot.publishedGraphVersion())) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                    "图谱草稿已发布为其他目标版本");
        }
        if (snapshot.pendingCount() != 0 || snapshot.approvedCount() < 1
                || !publishedRecovery && !"READY_TO_PUBLISH".equals(snapshot.status())) {
            throw new BusinessException(ErrorCode.GRAPH_HUMAN_REVIEW_REQUIRED,
                    "发布前必须完成全部人工审核且至少批准一个候选");
        }
        GraphIngestionInternalDtos.PublishResponse response = boundary.validatePublish(
                job.getIngestionBatchId(), payload.targetGraphVersion(),
                graph.publishIngestionBatch(job.getCourseId(), claimed.requestId(), job.getIngestionBatchId(),
                        new GraphIngestionInternalDtos.PublishRequest(claimed.requestId(),
                                payload.expectedRevision(), payload.targetGraphVersion())));
        if (!job.getBaseGraphVersion().equals(response.previousGraphVersion())) {
            throw new BusinessException(ErrorCode.GRAPH_PUBLISH_FAILED,
                    "图谱发布回执的原版本不匹配");
        }
        String current = graph.currentGraphVersion(job.getCourseId(), claimed.requestId());
        if (!payload.targetGraphVersion().equals(current)) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                    "图谱发布回执与当前激活版本不一致");
        }
        tx.executeWithoutResult(status -> completePublish(claimed, response));
    }

    private void completePublish(Claimed claimed, GraphIngestionInternalDtos.PublishResponse response) {
        GraphIngestionOutboxEntity event = requireOwnedEvent(claimed);
        GraphIngestionJobEntity job = jobs.selectForUpdate(claimed.jobId());
        if (job == null) throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "建图任务不存在");
        job.setStatus(GraphIngestionStatus.PUBLISHED);
        job.setProgressPercent(100);
        job.setPreviousGraphVersion(response.previousGraphVersion());
        job.setPublishedGraphVersion(response.graphVersion());
        job.setReleaseSha256(response.releaseSha256());
        LocalDateTime publishedAt = LocalDateTime.ofInstant(
                response.publishedAt(), java.time.ZoneId.of("Asia/Shanghai"));
        job.setPublishedAt(publishedAt);
        job.setFinishedAt(publishedAt);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        jobs.updateById(job);
        if (event.getIdempotencyRecordId() != null) {
            idempotency.complete(event.getIdempotencyRecordId(), GraphIngestionService.viewOf(job));
        }
        completeEvent(event);
        audit.recordRequired("GRAPH_INGESTION_PUBLISHED", "GRAPH_INGESTION_JOB",
                job.getId().toString(), job.getCreatedBy(), true,
                Map.of("batchId", response.batchId(), "graphVersion", response.graphVersion(),
                        "previousGraphVersion", response.previousGraphVersion()));
    }

    private GraphIngestionOutboxEntity requireOwnedEvent(Claimed claimed) {
        GraphIngestionOutboxEntity event = outbox.selectForUpdate(claimed.eventId());
        if (event == null || event.getStatus() != GraphOutboxStatus.RUNNING
                || !claimed.workerId().equals(event.getLeaseOwner())
                || number(event.getAttemptCount()) != claimed.attempt()) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "建图任务租约已变化");
        }
        return event;
    }

    private void completeEvent(GraphIngestionOutboxEntity event) {
        event.setStatus(GraphOutboxStatus.SUCCEEDED);
        event.setLeaseOwner(null);
        event.setLeaseUntil(null);
        event.setCompletedAt(LocalDateTime.now());
        event.setLastErrorCode(null);
        event.setLastErrorMessage(null);
        outbox.updateById(event);
    }

    private void fail(Claimed claimed, ErrorCode code, String rawMessage) {
        String message = safeMessage(rawMessage);
        tx.executeWithoutResult(status -> {
            GraphIngestionOutboxEntity event = outbox.selectForUpdate(claimed.eventId());
            if (event == null || event.getStatus() != GraphOutboxStatus.RUNNING
                    || !claimed.workerId().equals(event.getLeaseOwner())) return;
            GraphIngestionJobEntity job = jobs.selectForUpdate(claimed.jobId());
            boolean retry = retryable(code) && claimed.attempt() < claimed.maxAttempts();
            event.setLastErrorCode(code.name());
            event.setLastErrorMessage(message);
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            if (retry) {
                event.setStatus(GraphOutboxStatus.PENDING);
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(60, 1L << claimed.attempt())));
            } else {
                event.setStatus(GraphOutboxStatus.FAILED);
                event.setCompletedAt(LocalDateTime.now());
            }
            outbox.updateById(event);
            if (job != null) {
                job.setErrorCode(code.name());
                job.setErrorMessage(message);
                job.setFinishedAt(retry ? null : LocalDateTime.now());
                if (retry) {
                    job.setStatus(claimed.eventType() == GraphIngestionEventType.PUBLISH
                            ? GraphIngestionStatus.PUBLISHING : GraphIngestionStatus.CREATED);
                } else {
                    job.setStatus(job.getIngestionBatchId() == null
                            ? GraphIngestionStatus.FAILED : GraphIngestionStatus.PARTIALLY_COMPLETED);
                }
                jobs.updateById(job);
                if (!retry) {
                    audit.recordRequired("GRAPH_INGESTION_FAILED", "GRAPH_INGESTION_JOB",
                            job.getId().toString(), job.getCreatedBy(), false,
                            Map.of("errorCode", code.name(), "eventType", claimed.eventType().name()));
                }
            }
        });
    }

    private boolean retryable(ErrorCode code) {
        return SetHolder.RETRYABLE.contains(code);
    }

    private GraphIngestionStatus statusFor(GraphIngestionInternalDtos.BatchDetail detail) {
        if (detail.pendingCount() > 0) return GraphIngestionStatus.WAITING_HUMAN_REVIEW;
        if (detail.approvedCount() > 0 && "READY_TO_PUBLISH".equals(detail.status())) {
            return GraphIngestionStatus.READY_TO_PUBLISH;
        }
        return GraphIngestionStatus.PARTIALLY_COMPLETED;
    }

    private GraphIngestionJobEntity requireJob(Long id) {
        GraphIngestionJobEntity job = jobs.selectById(id);
        if (job == null) throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "建图任务不存在");
        return job;
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "建图任务 Outbox 数据损坏");
        }
    }

    private int number(Integer value) { return value == null ? 0 : value; }

    private String safeMessage(String value) {
        String message = value == null || value.isBlank() ? "建图任务执行失败" : value;
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String validateWorkerId(String value) {
        if (value.length() > 100 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("education.graph-ingestion.instance-id is invalid");
        }
        return value;
    }

    private record Claimed(Long eventId, Long jobId, GraphIngestionEventType eventType,
                           String requestId, String payloadJson, int attempt,
                           int maxAttempts, String workerId) {}

    private static final class SetHolder {
        private static final java.util.Set<ErrorCode> RETRYABLE = java.util.Set.of(
                ErrorCode.AI_SERVICE_UNAVAILABLE, ErrorCode.AI_SERVICE_TIMEOUT,
                ErrorCode.AI_RATE_LIMITED, ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                ErrorCode.GRAPH_PUBLISH_FAILED, ErrorCode.INTERNAL_ERROR);
    }
}
