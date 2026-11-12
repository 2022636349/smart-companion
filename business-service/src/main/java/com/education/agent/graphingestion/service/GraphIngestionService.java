package com.education.agent.graphingestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.common.web.RequestIds;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.entity.GraphIngestionJobEntity;
import com.education.agent.graphingestion.entity.GraphIngestionOutboxEntity;
import com.education.agent.graphingestion.entity.GraphReleaseRollbackOutboxEntity;
import com.education.agent.graphingestion.entity.GraphReviewActionEntity;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.graphingestion.mapper.GraphIngestionJobMapper;
import com.education.agent.graphingestion.mapper.GraphIngestionOutboxMapper;
import com.education.agent.graphingestion.mapper.GraphReleaseRollbackOutboxMapper;
import com.education.agent.graphingestion.mapper.GraphReviewActionMapper;
import com.education.agent.graphingestion.model.GraphIngestionEventType;
import com.education.agent.graphingestion.model.GraphIngestionStatus;
import com.education.agent.graphingestion.model.GraphOutboxStatus;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class GraphIngestionService {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ATTEMPTS = 3;
    private static final int ROLLBACK_MAX_ATTEMPTS = 5;
    private static final long MAX_PUBLIC_PAGE = 1_000_000;

    private final GraphIngestionJobMapper jobs;
    private final GraphReviewActionMapper reviews;
    private final GraphIngestionOutboxMapper outbox;
    private final GraphReleaseRollbackOutboxMapper rollbackOutbox;
    private final FileStorageService files;
    private final KnowledgeServiceClient graph;
    private final GraphIngestionBoundaryValidator boundary;
    private final GraphMutationIdempotency idempotency;
    private final GraphIngestionProcessor processor;
    private final GraphReleaseRollbackProcessor rollbackProcessor;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.education.agent.course.CourseAccessService courses;

    public GraphIngestionService(
            GraphIngestionJobMapper jobs,
            GraphReviewActionMapper reviews,
            GraphIngestionOutboxMapper outbox,
            GraphReleaseRollbackOutboxMapper rollbackOutbox,
            FileStorageService files,
            KnowledgeServiceClient graph,
            GraphIngestionBoundaryValidator boundary,
            GraphMutationIdempotency idempotency,
            GraphIngestionProcessor processor,
            GraphReleaseRollbackProcessor rollbackProcessor,
            AuditLogService audit,
            ObjectMapper json,
            PlatformTransactionManager transactionManager, com.education.agent.course.CourseAccessService courses) {
        this.courses = courses;
        this.jobs = jobs;
        this.reviews = reviews;
        this.outbox = outbox;
        this.rollbackOutbox = rollbackOutbox;
        this.files = files;
        this.graph = graph;
        this.boundary = boundary;
        this.idempotency = idempotency;
        this.processor = processor;
        this.rollbackProcessor = rollbackProcessor;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public GraphIngestionDtos.JobView create(
            AuthenticatedUser principal, String idempotencyKey, GraphIngestionDtos.CreateRequest request) {
        courses.requireEdit(principal, request.courseId());
        FileObjectEntity file = files.requireOwnedAvailable(request.fileId(), principal, FilePurpose.KNOWLEDGE_SOURCE);
        String title = request.title().trim();
        String baseVersion = trim(request.baseGraphVersion());
        String requestHash = sha256(canonical(Map.of(
                "courseId", request.courseId(), "fileId", request.fileId(), "title", title, "sourceType", request.sourceType(),
                "copyrightStatus", request.copyrightStatus(),
                "baseGraphVersion", baseVersion == null ? "" : baseVersion)));
        GraphIngestionJobEntity existing = jobs.selectByIdempotency(principal.id(), idempotencyKey);
        if (existing != null) return replayJob(existing, requestHash);
        final boolean[] created = {false};
        try {
            GraphIngestionDtos.JobView response = Objects.requireNonNull(tx.execute(status -> {
                GraphIngestionJobEntity raced = jobs.selectByIdempotency(principal.id(), idempotencyKey);
                if (raced != null) return replayJob(raced, requestHash);
                GraphIngestionJobEntity job = new GraphIngestionJobEntity();
                job.setCourseId(request.courseId());
                job.setSourceFileId(file.getId());
                job.setTitle(title);
                job.setSourceType(request.sourceType());
                job.setCopyrightStatus(request.copyrightStatus());
                job.setCreatedBy(principal.id());
                job.setIdempotencyKey(idempotencyKey);
                job.setRequestHash(requestHash);
                job.setRequestId(UUID.randomUUID().toString());
                job.setStatus(GraphIngestionStatus.CREATED);
                job.setProgressPercent(0);
                job.setBaseGraphVersion(baseVersion);
                job.setAttemptCount(0);
                job.setMaxAttempts(MAX_ATTEMPTS);
                jobs.insert(job);

                GraphIngestionOutboxEntity event = new GraphIngestionOutboxEntity();
                event.setJobId(job.getId());
                event.setEventType(GraphIngestionEventType.EXTRACT_REVIEW);
                event.setDedupeKey("GRAPH_EXTRACT_REVIEW:" + job.getId());
                event.setRequestId(job.getRequestId());
                event.setPayloadJson(canonical(new ExtractionPayload(
                        GraphIngestionInternalDtos.ThresholdProfile.defaults(),
                        file.getSha256(), file.getOriginalName())));
                event.setStatus(GraphOutboxStatus.PENDING);
                event.setAttemptCount(0);
                event.setMaxAttempts(MAX_ATTEMPTS);
                event.setNextAttemptAt(LocalDateTime.now());
                outbox.insert(event);
                audit.recordRequired("GRAPH_INGESTION_CREATED", "GRAPH_INGESTION_JOB",
                        job.getId().toString(), principal.id(), true,
                        Map.of("sourceFileId", file.getId(), "sourceType", request.sourceType(),
                                "copyrightStatus", request.copyrightStatus()));
                created[0] = true;
                return view(jobs.selectById(job.getId()));
            }));
            if (created[0]) processor.processAsync();
            return response;
        } catch (DuplicateKeyException exception) {
            GraphIngestionJobEntity raced = jobs.selectByIdempotency(principal.id(), idempotencyKey);
            if (raced == null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "建图任务正在创建");
            return replayJob(raced, requestHash);
        }
    }

    public PageData<GraphIngestionDtos.JobView> list(
            AuthenticatedUser principal, Long courseId, long page, long size, GraphIngestionStatus status) {
        if(courseId==null) requireAdmin(principal); else courses.requireView(principal,courseId);
        LambdaQueryWrapper<GraphIngestionJobEntity> query = new LambdaQueryWrapper<GraphIngestionJobEntity>()
                .eq(courseId != null, GraphIngestionJobEntity::getCourseId, courseId)
                .eq(status != null, GraphIngestionJobEntity::getStatus, status)
                .orderByDesc(GraphIngestionJobEntity::getCreatedAt)
                .orderByDesc(GraphIngestionJobEntity::getId);
        Page<GraphIngestionJobEntity> result = jobs.selectPage(Page.of(page, size), query);
        return new PageData<>(result.getRecords().stream().map(this::view).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public GraphIngestionDtos.JobView get(AuthenticatedUser principal, Long jobId) {
        courses.requireView(principal,requireJob(jobId).getCourseId());
        return view(requireJob(jobId));
    }

    public GraphIngestionDtos.CandidatePage candidates(
            AuthenticatedUser principal, Long jobId, long page, long size,
            String candidateType, String decisionStatus, String reviewLane) {
        courses.requireView(principal,requireJob(jobId).getCourseId());
        if (page < 1 || page > MAX_PUBLIC_PAGE || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "候选分页参数超出边界");
        }
        GraphIngestionJobEntity job = requireJobWithBatch(jobId);
        String requestId = RequestIds.current();
        GraphIngestionInternalDtos.BatchDetail before = boundary.validateDetail(
                job.getIngestionBatchId(), job.getBaseGraphVersion(), null,
                graph.getIngestionBatch(job.getCourseId(), requestId, job.getIngestionBatchId()));
        reconcileSnapshot(jobId, before);
        List<GraphIngestionDtos.CandidateItem> all = new ArrayList<>();
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        int calls = 0;
        do {
            if (++calls > 13) throw new BusinessException(
                    ErrorCode.GRAPH_INGESTION_LIMIT_EXCEEDED, "候选分页超过总量预算");
            GraphIngestionInternalDtos.CandidatePage internal = graph.getIngestionCandidates(job.getCourseId(), requestId, job.getIngestionBatchId(), 100, cursor,
                    trim(decisionStatus), trim(candidateType), trim(reviewLane));
            all.addAll(boundary.validateCandidatePage(100, before.revision(),
                    before.validationIssues(), internal));
            cursor = trim(internal.nextCursor());
            if (cursor != null && !cursors.add(cursor)) {
                throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "候选分页 cursor 循环");
            }
        } while (cursor != null);
        GraphIngestionInternalDtos.BatchDetail after = boundary.validateDetail(
                job.getIngestionBatchId(), job.getBaseGraphVersion(), null,
                graph.getIngestionBatch(job.getCourseId(), requestId, job.getIngestionBatchId()));
        if (!before.revision().equals(after.revision())) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "查询候选期间草稿已变化，请重试");
        }
        long total = all.size();
        long from = Math.min(total, (page - 1) * size);
        long to = Math.min(total, from + size);
        List<GraphIngestionDtos.CandidateItem> items =
                List.copyOf(all.subList(Math.toIntExact(from), Math.toIntExact(to)));
        long pages = total == 0 ? 0 : (total + size - 1) / size;
        return new GraphIngestionDtos.CandidatePage(items, page, size, total, pages, before.revision());
    }

    public GraphIngestionDtos.JobView decide(
            AuthenticatedUser principal, Long jobId, String idempotencyKey,
            GraphIngestionDtos.DecisionBatchRequest request) {
        courses.requireEdit(principal,requireJob(jobId).getCourseId());
        boundary.validateDecisions(request.decisions());
        GraphIngestionJobEntity job = requireReviewable(jobId, request.expectedRevision());
        String fullJson = canonical(request.decisions());
        String requestHash = sha256(jobId + "\n" + request.expectedRevision() + "\n" + fullJson);
        String auditJson = canonical(request.decisions().stream().map(this::decisionAudit).toList());
        GraphReviewActionEntity action = claimReviewAction(
                principal.id(), jobId, idempotencyKey, requestHash,
                request.expectedRevision(), request.decisions().size(), auditJson);
        if (action.getResponseJson() != null) {
            return read(action.getResponseJson(), GraphIngestionDtos.JobView.class);
        }
        List<GraphIngestionInternalDtos.Decision> decisions =
                request.decisions().stream().map(boundary::toInternalDecision).toList();
        GraphIngestionInternalDtos.BatchDetail detail = boundary.validateDecisionResult(
                job.getIngestionBatchId(), request.expectedRevision(),
                graph.decideIngestionCandidates(job.getCourseId(), action.getRequestId(), job.getIngestionBatchId(),
                        new GraphIngestionInternalDtos.DecisionsRequest(
                                action.getRequestId(), request.expectedRevision(),
                                principal.id().toString(), decisions)));
        return completeReviewAction(action.getId(), jobId, principal.id(), detail);
    }

    public GraphIngestionDtos.JobView retry(
            AuthenticatedUser principal, Long jobId, String idempotencyKey) {
        courses.requireEdit(principal,requireJob(jobId).getCourseId());
        String hash = sha256("retry\n" + jobId);
        GraphMutationIdempotency.Claim<GraphIngestionDtos.JobView> claim = idempotency.claim(
                principal.id(), "GRAPH_INGESTION_RETRY:" + jobId, idempotencyKey, hash,
                GraphIngestionDtos.JobView.class);
        if (claim.replay() != null) return claim.replay();
        GraphIngestionDtos.JobView response = Objects.requireNonNull(tx.execute(status -> {
            GraphIngestionJobEntity job = requireJobForUpdate(jobId);
            GraphIngestionOutboxEntity event = outbox.selectLatestFailedForUpdate(jobId);
            if (event == null) {
                if (claim.incomplete() && Set.of(GraphIngestionStatus.CREATED,
                        GraphIngestionStatus.EXTRACTING, GraphIngestionStatus.PUBLISHING).contains(job.getStatus())) {
                    return view(job);
                }
                throw new BusinessException(ErrorCode.GRAPH_INGESTION_INVALID_STATE, "没有可恢复的失败步骤");
            }
            if (job.getStatus() != GraphIngestionStatus.FAILED
                    && job.getStatus() != GraphIngestionStatus.PARTIALLY_COMPLETED) {
                throw new BusinessException(ErrorCode.GRAPH_INGESTION_INVALID_STATE, "只有失败任务可以重试");
            }
            event.setStatus(GraphOutboxStatus.PENDING);
            event.setAttemptCount(0);
            event.setNextAttemptAt(LocalDateTime.now());
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            event.setLastErrorCode(null);
            event.setLastErrorMessage(null);
            event.setCompletedAt(null);
            outbox.updateById(event);
            job.setAttemptCount(0);
            job.setStatus(event.getEventType() == GraphIngestionEventType.PUBLISH
                    ? GraphIngestionStatus.PUBLISHING : GraphIngestionStatus.CREATED);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setFinishedAt(null);
            jobs.updateById(job);
            audit.recordRequired("GRAPH_INGESTION_RETRIED", "GRAPH_INGESTION_JOB",
                    jobId.toString(), principal.id(), true,
                    Map.of("eventType", event.getEventType().name()));
            return view(jobs.selectById(jobId));
        }));
        idempotency.complete(claim.recordId(), response);
        processor.processAsync();
        return response;
    }

    public GraphIngestionDtos.JobView publish(
            AuthenticatedUser principal, Long jobId, String idempotencyKey,
            GraphIngestionDtos.PublishRequest request) {
        courses.requireOwner(principal,requireJob(jobId).getCourseId());
        String target = request.targetGraphVersion().trim();
        if (!target.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "目标图谱版本格式不合法");
        }
        String hash = sha256(jobId + "\n" + request.expectedJobVersion()
                + "\n" + request.expectedDraftRevision() + "\n" + target);
        GraphMutationIdempotency.Claim<GraphIngestionDtos.JobView> claim = idempotency.claim(
                principal.id(), "GRAPH_INGESTION_PUBLISH:" + jobId, idempotencyKey, hash,
                GraphIngestionDtos.JobView.class);
        if (claim.replay() != null) return claim.replay();
        String requestId = "graph-publish:" + claim.upstreamRequestId();
        String dedupe = "GRAPH_PUBLISH:" + jobId + ":" + claim.recordId();
        GraphIngestionDtos.JobView response = tx.execute(status -> {
            GraphIngestionOutboxEntity existing = outbox.selectByDedupeKey(dedupe);
            if (existing != null) return view(requireJob(jobId));
            GraphIngestionJobEntity job = requireJobForUpdate(jobId);
            if (!request.expectedJobVersion().equals(job.getVersionNo())
                    || !request.expectedDraftRevision().equals(job.getBatchRevision())) {
                throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "任务或草稿 revision 已变化");
            }
            if (job.getStatus() != GraphIngestionStatus.READY_TO_PUBLISH
                    || number(job.getPendingCandidateCount()) != 0
                    || number(job.getAcceptedCandidateCount()) < 1) {
                throw new BusinessException(ErrorCode.GRAPH_HUMAN_REVIEW_REQUIRED,
                        "全部候选必须完成人工审核且至少批准一个候选");
            }
            GraphIngestionOutboxEntity event = new GraphIngestionOutboxEntity();
            event.setJobId(jobId);
            event.setIdempotencyRecordId(claim.recordId());
            event.setEventType(GraphIngestionEventType.PUBLISH);
            event.setDedupeKey(dedupe);
            event.setRequestId(requestId);
            event.setPayloadJson(canonical(new PublishPayload(
                    request.expectedDraftRevision(), target, principal.id(), hash)));
            event.setStatus(GraphOutboxStatus.PENDING);
            event.setAttemptCount(0);
            event.setMaxAttempts(MAX_ATTEMPTS);
            event.setNextAttemptAt(LocalDateTime.now());
            outbox.insert(event);
            job.setStatus(GraphIngestionStatus.PUBLISHING);
            job.setProgressPercent(90);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            jobs.updateById(job);
            audit.recordRequired("GRAPH_INGESTION_PUBLISH_REQUESTED", "GRAPH_INGESTION_JOB",
                    jobId.toString(), principal.id(), true,
                    Map.of("revision", request.expectedDraftRevision(), "targetGraphVersion", target));
            return view(jobs.selectById(jobId));
        });
        // The first response remains an accepted, in-progress snapshot.  The processor writes
        // the durable final response together with the terminal publish state so a replay after
        // a lost response converges to PUBLISHED instead of indefinitely replaying PUBLISHING.
        processor.processAsync();
        return response;
    }

    public GraphIngestionDtos.RollbackResult rollback(
            AuthenticatedUser principal, Long courseId, String graphVersion, String idempotencyKey,
            GraphIngestionDtos.RollbackRequest request) {
        courses.requireOwner(principal,courseId);
        String target = graphVersion.trim();
        String expected = request.expectedCurrentGraphVersion().trim();
        if (!target.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || !expected.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "图谱版本格式不合法");
        }
        String hash = sha256("rollback\n" + courseId + "\n" + target + "\n" + expected);
        RollbackSubmission submission = Objects.requireNonNull(tx.execute(status -> {
            GraphMutationIdempotency.Claim<GraphIngestionDtos.RollbackResult> claim = idempotency.claim(
                    principal.id(), "GRAPH_RELEASE_ROLLBACK:" + courseId, idempotencyKey, hash,
                    GraphIngestionDtos.RollbackResult.class);
            if (claim.replay() != null) return new RollbackSubmission(null, claim.replay());
            GraphReleaseRollbackOutboxEntity event =
                    rollbackOutbox.selectByIdempotencyRecordId(claim.recordId());
            String requestId = "graph-rollback:" + claim.upstreamRequestId();
            if (event == null) {
                event = new GraphReleaseRollbackOutboxEntity();
                event.setCourseId(courseId);
                event.setIdempotencyRecordId(claim.recordId());
                event.setRequestedBy(principal.id());
                event.setTargetGraphVersion(target);
                event.setExpectedCurrentGraphVersion(expected);
                event.setRequestHash(hash);
                event.setRequestId(requestId);
                event.setStatus(GraphOutboxStatus.PENDING);
                event.setAttemptCount(0);
                event.setMaxAttempts(ROLLBACK_MAX_ATTEMPTS);
                event.setNextAttemptAt(LocalDateTime.now());
                rollbackOutbox.insert(event);
                audit.recordRequired("GRAPH_RELEASE_ROLLBACK_REQUESTED", "GRAPH_RELEASE",
                        target, principal.id(), true,
                        Map.of("previousGraphVersion", expected, "requestId", requestId));
            } else if (!principal.id().equals(event.getRequestedBy())
                    || !target.equals(event.getTargetGraphVersion())
                    || !expected.equals(event.getExpectedCurrentGraphVersion())
                    || !hash.equals(event.getRequestHash())
                    || !requestId.equals(event.getRequestId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "同一个幂等键不能用于不同图谱回滚请求");
            }
            return new RollbackSubmission(event.getId(), null);
        }));
        if (submission.replay() != null) return submission.replay();
        return rollbackProcessor.processEventNow(submission.eventId());
    }

    private GraphReviewActionEntity claimReviewAction(
            Long reviewerId, Long jobId, String key, String hash,
            Long expectedRevision, int decisionCount, String auditJson) {
        GraphReviewActionEntity existing = reviews.selectByIdempotency(reviewerId, key);
        if (existing != null) return replayReviewAction(existing, jobId, hash);
        try {
            return Objects.requireNonNull(tx.execute(status -> {
                GraphReviewActionEntity raced = reviews.selectByIdempotency(reviewerId, key);
                if (raced != null) return replayReviewAction(raced, jobId, hash);
                GraphReviewActionEntity action = new GraphReviewActionEntity();
                action.setJobId(jobId);
                action.setReviewerId(reviewerId);
                action.setIdempotencyKey(key);
                action.setRequestHash(hash);
                action.setRequestId(UUID.randomUUID().toString());
                action.setExpectedRevision(expectedRevision);
                action.setDecisionCount(decisionCount);
                action.setDecisionsJson(auditJson);
                reviews.insert(action);
                return action;
            }));
        } catch (DuplicateKeyException exception) {
            GraphReviewActionEntity raced = reviews.selectByIdempotency(reviewerId, key);
            if (raced == null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "审核操作正在处理");
            return replayReviewAction(raced, jobId, hash);
        }
    }

    private GraphReviewActionEntity replayReviewAction(
            GraphReviewActionEntity action, Long jobId, String hash) {
        if (!jobId.equals(action.getJobId()) || !hash.equals(action.getRequestHash())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同审核请求");
        }
        return action;
    }

    private GraphIngestionDtos.JobView completeReviewAction(
            Long actionId, Long jobId, Long reviewerId, GraphIngestionInternalDtos.BatchDetail detail) {
        return Objects.requireNonNull(tx.execute(status -> {
            GraphReviewActionEntity action = reviews.selectForUpdate(actionId);
            if (action == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "审核审计记录不存在");
            if (action.getResponseJson() != null) {
                return read(action.getResponseJson(), GraphIngestionDtos.JobView.class);
            }
            GraphIngestionJobEntity job = requireJobForUpdate(jobId);
            if (job.getBatchRevision() != null && job.getBatchRevision() > detail.revision()) {
                throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "本地审核 revision 已超过服务响应");
            }
            updateSnapshot(job, detail);
            jobs.updateById(job);
            GraphIngestionDtos.JobView response = view(jobs.selectById(jobId));
            action.setResultingRevision(detail.revision());
            action.setResponseJson(canonical(response));
            reviews.updateById(action);
            audit.recordRequired("GRAPH_INGESTION_REVIEWED", "GRAPH_INGESTION_JOB",
                    jobId.toString(), reviewerId, true,
                    Map.of("decisionCount", action.getDecisionCount(), "revision", detail.revision()));
            return response;
        }));
    }

    private Map<String, Object> decisionAudit(GraphIngestionDtos.Decision item) {
        JsonNode edited = boundary.editedPayload(item);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("candidateType", item.candidateType());
        value.put("candidateId", item.candidateId());
        value.put("decision", item.decision());
        value.put("comment", item.comment().trim());
        value.put("edited", edited != null && !edited.isNull());
        if (edited != null && !edited.isNull()) value.put("editedPayloadSha256", sha256(canonical(edited)));
        return value;
    }

    private void reconcileSnapshot(Long jobId, GraphIngestionInternalDtos.BatchDetail detail) {
        tx.executeWithoutResult(status -> {
            GraphIngestionJobEntity job = requireJobForUpdate(jobId);
            if (job.getBatchRevision() == null || detail.revision() >= job.getBatchRevision()) {
                updateSnapshot(job, detail);
                jobs.updateById(job);
            }
        });
    }

    private void updateSnapshot(
            GraphIngestionJobEntity job, GraphIngestionInternalDtos.BatchDetail detail) {
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
        if (job.getStatus() != GraphIngestionStatus.PUBLISHED
                && job.getStatus() != GraphIngestionStatus.PUBLISHING) {
            job.setStatus(detail.pendingCount() > 0
                    ? GraphIngestionStatus.WAITING_HUMAN_REVIEW
                    : detail.approvedCount() > 0 && "READY_TO_PUBLISH".equals(detail.status())
                    ? GraphIngestionStatus.READY_TO_PUBLISH
                    : GraphIngestionStatus.PARTIALLY_COMPLETED);
        }
    }

    private GraphIngestionJobEntity requireReviewable(Long jobId, Long expectedRevision) {
        GraphIngestionJobEntity job = requireJobWithBatch(jobId);
        if (!Set.of(GraphIngestionStatus.WAITING_HUMAN_REVIEW,
                GraphIngestionStatus.READY_TO_PUBLISH).contains(job.getStatus())
                || !expectedRevision.equals(job.getBatchRevision())) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "任务状态或草稿 revision 已变化");
        }
        return job;
    }

    private GraphIngestionJobEntity requireJobWithBatch(Long jobId) {
        GraphIngestionJobEntity job = requireJob(jobId);
        if (job.getIngestionBatchId() == null) {
            throw new BusinessException(ErrorCode.GRAPH_INGESTION_INVALID_STATE, "建图草稿尚未生成");
        }
        return job;
    }

    private GraphIngestionJobEntity requireJob(Long jobId) {
        GraphIngestionJobEntity job = jobs.selectById(jobId);
        if (job == null) throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "建图任务不存在");
        return job;
    }

    private GraphIngestionJobEntity requireJobForUpdate(Long jobId) {
        GraphIngestionJobEntity job = jobs.selectForUpdate(jobId);
        if (job == null) throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "建图任务不存在");
        return job;
    }

    private GraphIngestionDtos.JobView replayJob(GraphIngestionJobEntity job, String hash) {
        if (!hash.equals(job.getRequestHash())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同建图请求");
        }
        return view(job);
    }

    private GraphIngestionDtos.JobView view(GraphIngestionJobEntity job) {
        return viewOf(job);
    }

    /** Shared canonical representation for public reads and durable terminal idempotency replies. */
    static GraphIngestionDtos.JobView viewOf(GraphIngestionJobEntity job) {
        return new GraphIngestionDtos.JobView(
                job.getId(), job.getCourseId(), job.getIngestionBatchId(), job.getSourceFileId(), job.getTitle(),
                job.getSourceType(), job.getCopyrightStatus(), job.getStatus(),
                job.getBaseGraphVersion(), job.getBatchRevision(),
                numberOf(job.getAttemptCount()), numberOf(job.getMaxAttempts()),
                new GraphIngestionDtos.CandidateCounts(
                        numberOf(job.getNodeCount()), numberOf(job.getEdgeCount()), numberOf(job.getSourceCount()),
                        numberOf(job.getPendingCandidateCount()), numberOf(job.getAcceptedCandidateCount()),
                        numberOf(job.getRejectedCandidateCount()), numberOf(job.getErrorCount()),
                        numberOf(job.getWarningCount())),
                Set.of(GraphIngestionStatus.FAILED, GraphIngestionStatus.PARTIALLY_COMPLETED)
                        .contains(job.getStatus()) ? job.getErrorCode() : null,
                Set.of(GraphIngestionStatus.FAILED, GraphIngestionStatus.PARTIALLY_COMPLETED)
                        .contains(job.getStatus()) ? job.getErrorMessage() : null,
                job.getPublishedGraphVersion(),
                job.getPreviousGraphVersion(), job.getReleaseSha256(), job.getCreatedBy(),
                job.getVersionNo() == null ? 0 : job.getVersionNo(),
                toInstantOf(job.getCreatedAt()), toInstantOf(job.getStartedAt()),
                toInstantOf(job.getFinishedAt()), toInstantOf(job.getUpdatedAt()));
    }

    public void requireJobCourse(Long courseId, Long jobId) {
        if(!Objects.equals(courseId,requireJob(jobId).getCourseId())) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"建图任务不属于当前课程");
    }

    private void requireAdmin(AuthenticatedUser principal) {
        if (principal == null || principal.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以管理知识图谱建图任务");
        }
    }

    private ErrorCode errorCode(String value) {
        if (value == null) return ErrorCode.GRAPH_PUBLISH_FAILED;
        try { return ErrorCode.valueOf(value); }
        catch (IllegalArgumentException exception) { return ErrorCode.GRAPH_PUBLISH_FAILED; }
    }
    private int number(Integer value) { return numberOf(value); }
    private static int numberOf(Integer value) { return value == null ? 0 : value; }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Instant toInstant(LocalDateTime value) { return toInstantOf(value); }
    private static Instant toInstantOf(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }

    private String canonical(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "建图任务数据无法序列化");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "建图任务幂等结果损坏");
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

    public record ExtractionPayload(
            GraphIngestionInternalDtos.ThresholdProfile thresholdProfile,
            String sourceSha256, String originalFilename) {}

    public record PublishPayload(
            Long expectedRevision, String targetGraphVersion,
            Long requestedBy, String requestHash) {}

    private record RollbackSubmission(
            Long eventId, GraphIngestionDtos.RollbackResult replay) {}
}
