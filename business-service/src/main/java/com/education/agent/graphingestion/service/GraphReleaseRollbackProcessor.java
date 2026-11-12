package com.education.agent.graphingestion.service;

import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.entity.GraphReleaseRollbackOutboxEntity;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.graphingestion.mapper.GraphReleaseRollbackOutboxMapper;
import com.education.agent.graphingestion.model.GraphOutboxStatus;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class GraphReleaseRollbackProcessor {
    private static final Logger log = LoggerFactory.getLogger(GraphReleaseRollbackProcessor.class);
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final Set<ErrorCode> RETRYABLE = Set.of(
            ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
            ErrorCode.GRAPH_PUBLISH_FAILED,
            ErrorCode.INTERNAL_ERROR);

    private final TaskExecutor executor;
    private final GraphReleaseRollbackOutboxMapper outbox;
    private final KnowledgeServiceClient graph;
    private final GraphIngestionBoundaryValidator boundary;
    private final GraphMutationIdempotency idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String workerId;

    public GraphReleaseRollbackProcessor(
            @Qualifier("graphIngestionExecutor") TaskExecutor executor,
            GraphReleaseRollbackOutboxMapper outbox,
            KnowledgeServiceClient graph,
            GraphIngestionBoundaryValidator boundary,
            GraphMutationIdempotency idempotency,
            AuditLogService audit,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            @Value("${education.graph-rollback.instance-id:}") String configuredWorkerId) {
        this.executor = executor;
        this.outbox = outbox;
        this.graph = graph;
        this.boundary = boundary;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "graph-rollback-" + UUID.randomUUID().toString().substring(0, 12)
                : validateWorkerId(configuredWorkerId.trim());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        processAsync();
    }

    @Scheduled(fixedDelayString = "${education.graph-rollback.scan-ms:3000}",
            initialDelayString = "${education.graph-rollback.initial-delay-ms:5000}")
    public void scheduled() {
        processOnce(10);
    }

    public void processAsync() {
        try {
            executor.execute(() -> processOnce(10));
        } catch (RuntimeException exception) {
            log.warn("Graph rollback executor is saturated; durable outbox remains pending", exception);
        }
    }

    public int processOnce(int limit) {
        int processed = 0;
        for (Long id : outbox.selectClaimableIds(Math.max(1, Math.min(limit, 100)))) {
            if (processEvent(id) != null) processed++;
        }
        return processed;
    }

    /**
     * Gives the compatibility 200 endpoint one immediate attempt. The committed outbox remains
     * authoritative if this attempt fails, and startup/scheduled recovery will continue it.
     */
    public GraphIngestionDtos.RollbackResult processEventNow(Long eventId) {
        AttemptOutcome outcome = processEvent(eventId);
        if (outcome != null && outcome.response() != null) return outcome.response();
        if (outcome != null && outcome.error() != null) throw outcome.error();
        GraphReleaseRollbackOutboxEntity existing = outbox.selectById(eventId);
        if (existing != null && existing.getStatus() == GraphOutboxStatus.SUCCEEDED
                && existing.getResponseJson() != null) {
            return read(existing.getResponseJson(), GraphIngestionDtos.RollbackResult.class);
        }
        if (existing != null && existing.getStatus() == GraphOutboxStatus.FAILED) {
            throw new BusinessException(errorCode(existing.getLastErrorCode()), safeMessage(existing.getLastErrorMessage()));
        }
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "图谱回滚任务正在处理");
    }

    private AttemptOutcome processEvent(Long eventId) {
        Claimed claimed = tx.execute(status -> claim(eventId));
        if (claimed == null) return null;
        try {
            GraphIngestionInternalDtos.RollbackResponse internal = boundary.validateRollback(
                    claimed.targetGraphVersion(), claimed.expectedCurrentGraphVersion(),
                    graph.rollbackRelease(outbox.selectById(claimed.eventId()).getCourseId(), claimed.requestId(), claimed.targetGraphVersion(),
                            new GraphIngestionInternalDtos.RollbackRequest(
                                    claimed.requestId(), claimed.expectedCurrentGraphVersion())));
            String current = graph.currentGraphVersion(outbox.selectById(claimed.eventId()).getCourseId(), claimed.requestId());
            if (!claimed.targetGraphVersion().equals(current)) {
                throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                        "图谱回滚回执与当前激活版本不一致");
            }
            GraphIngestionDtos.RollbackResult response = new GraphIngestionDtos.RollbackResult(
                    internal.previousGraphVersion(), internal.graphVersion(),
                    internal.releaseSha256(), internal.activatedAt());
            complete(claimed, response);
            return new AttemptOutcome(response, null);
        } catch (BusinessException exception) {
            fail(claimed, exception.errorCode(), exception.getMessage());
            return new AttemptOutcome(null, exception);
        } catch (RuntimeException exception) {
            log.warn("Graph rollback event {} failed", claimed.eventId(), exception);
            BusinessException safe = new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "图谱回滚任务执行失败，请稍后重试");
            fail(claimed, safe.errorCode(), safe.getMessage());
            return new AttemptOutcome(null, safe);
        }
    }

    private Claimed claim(Long eventId) {
        GraphReleaseRollbackOutboxEntity event = outbox.selectForUpdate(eventId);
        LocalDateTime now = LocalDateTime.now();
        boolean claimable = event != null && (event.getStatus() == GraphOutboxStatus.PENDING
                && !event.getNextAttemptAt().isAfter(now)
                || event.getStatus() == GraphOutboxStatus.RUNNING
                && event.getLeaseUntil() != null && event.getLeaseUntil().isBefore(now));
        if (!claimable) return null;
        int attempt = number(event.getAttemptCount()) + 1;
        event.setStatus(GraphOutboxStatus.RUNNING);
        event.setAttemptCount(attempt);
        event.setLeaseOwner(workerId);
        event.setLeaseUntil(now.plus(LEASE));
        event.setLastErrorCode(null);
        event.setLastErrorMessage(null);
        outbox.updateById(event);
        return new Claimed(event.getId(), event.getIdempotencyRecordId(), event.getRequestedBy(),
                event.getTargetGraphVersion(), event.getExpectedCurrentGraphVersion(),
                event.getRequestId(), attempt, number(event.getMaxAttempts()), workerId);
    }

    private void complete(Claimed claimed, GraphIngestionDtos.RollbackResult response) {
        tx.executeWithoutResult(status -> {
            GraphReleaseRollbackOutboxEntity event = requireOwnedEvent(claimed);
            String serialized = write(response);
            idempotency.complete(claimed.idempotencyRecordId(), response);
            event.setStatus(GraphOutboxStatus.SUCCEEDED);
            event.setResponseJson(serialized);
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            event.setLastErrorCode(null);
            event.setLastErrorMessage(null);
            event.setCompletedAt(LocalDateTime.now());
            outbox.updateById(event);
            audit.recordRequired("GRAPH_RELEASE_ROLLED_BACK", "GRAPH_RELEASE",
                    claimed.targetGraphVersion(), claimed.requestedBy(), true,
                    Map.of("previousGraphVersion", claimed.expectedCurrentGraphVersion(),
                            "graphVersion", claimed.targetGraphVersion(),
                            "requestId", claimed.requestId()));
        });
    }

    private void fail(Claimed claimed, ErrorCode code, String rawMessage) {
        String message = safeMessage(rawMessage);
        tx.executeWithoutResult(status -> {
            GraphReleaseRollbackOutboxEntity event = outbox.selectForUpdate(claimed.eventId());
            if (event == null || event.getStatus() != GraphOutboxStatus.RUNNING
                    || !claimed.workerId().equals(event.getLeaseOwner())
                    || number(event.getAttemptCount()) != claimed.attempt()) return;
            event.setLastErrorCode(code.name());
            event.setLastErrorMessage(message);
            event.setLeaseOwner(null);
            event.setLeaseUntil(null);
            boolean retry = RETRYABLE.contains(code) && claimed.attempt() < claimed.maxAttempts();
            if (retry) {
                event.setStatus(GraphOutboxStatus.PENDING);
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(
                        Math.min(60, 1L << Math.min(claimed.attempt(), 6))));
            } else {
                event.setStatus(GraphOutboxStatus.FAILED);
                event.setCompletedAt(LocalDateTime.now());
            }
            outbox.updateById(event);
            if (!retry) {
                audit.recordRequired("GRAPH_RELEASE_ROLLBACK_FAILED", "GRAPH_RELEASE",
                        claimed.targetGraphVersion(), claimed.requestedBy(), false,
                        Map.of("errorCode", code.name(), "requestId", claimed.requestId()));
            }
        });
    }

    private GraphReleaseRollbackOutboxEntity requireOwnedEvent(Claimed claimed) {
        GraphReleaseRollbackOutboxEntity event = outbox.selectForUpdate(claimed.eventId());
        if (event == null || event.getStatus() != GraphOutboxStatus.RUNNING
                || !claimed.workerId().equals(event.getLeaseOwner())
                || number(event.getAttemptCount()) != claimed.attempt()) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "图谱回滚任务租约已变化");
        }
        return event;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法保存图谱回滚结果");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "图谱回滚结果损坏");
        }
    }

    private int number(Integer value) { return value == null ? 0 : value; }

    private String safeMessage(String value) {
        String message = value == null || value.isBlank() ? "图谱回滚任务执行失败" : value;
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private ErrorCode errorCode(String value) {
        if (value == null) return ErrorCode.GRAPH_PUBLISH_FAILED;
        try { return ErrorCode.valueOf(value); }
        catch (IllegalArgumentException exception) { return ErrorCode.GRAPH_PUBLISH_FAILED; }
    }

    private String validateWorkerId(String value) {
        if (value.length() > 100 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("education.graph-rollback.instance-id is invalid");
        }
        return value;
    }

    private record Claimed(Long eventId, Long idempotencyRecordId, Long requestedBy,
                           String targetGraphVersion, String expectedCurrentGraphVersion,
                           String requestId, int attempt, int maxAttempts, String workerId) {}

    private record AttemptOutcome(GraphIngestionDtos.RollbackResult response, BusinessException error) {}
}
