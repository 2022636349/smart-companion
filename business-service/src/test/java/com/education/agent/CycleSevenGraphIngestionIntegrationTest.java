package com.education.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.graphingestion.model.GraphIngestionStatus;
import com.education.agent.graphingestion.service.GraphIngestionProcessor;
import com.education.agent.graphingestion.service.GraphIngestionService;
import com.education.agent.graphingestion.service.GraphMutationIdempotency;
import com.education.agent.graphingestion.service.GraphReleaseRollbackProcessor;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "education.graph-ingestion.scan-ms=3600000",
    "education.graph-ingestion.initial-delay-ms=3600000",
    "education.graph-rollback.scan-ms=3600000",
    "education.graph-rollback.initial-delay-ms=3600000",
    "education.memory.outbox-scan-ms=3600000",
    "education.memory.outbox-initial-delay-ms=3600000",
    "education.profile.summary-scan-ms=3600000",
    "education.profile.summary-initial-delay-ms=3600000",
    "education.assessment.lifecycle-scan-ms=3600000"
})
class CycleSevenGraphIngestionIntegrationTest {
  private static final String SENSITIVE_EVIDENCE = "SENSITIVE_EVIDENCE_MUST_NOT_ENTER_MYSQL";
  private static final String SENSITIVE_EXCERPT = "SENSITIVE_EXCERPT_MUST_NOT_ENTER_MYSQL";
  private static final String SHA = "a".repeat(64);

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired Validator validator;
  @Autowired GraphIngestionService service;
  @Autowired GraphIngestionProcessor processor;
  @Autowired GraphReleaseRollbackProcessor rollbackProcessor;
  @Autowired GraphMutationIdempotency mutationIdempotency;
  @MockBean AiServiceClient ai;
  @MockBean KnowledgeServiceClient graph;
  @MockBean(name = "graphIngestionExecutor") TaskExecutor graphExecutor;

  private final AtomicReference<GraphIngestionInternalDtos.BatchDetail> remote =
      new AtomicReference<>();
  private long sequence;

  @BeforeEach
  void setUp() {
    reset(ai, graph, graphExecutor);
    remote.set(null);
    sequence = System.nanoTime();
    jdbc.update("DELETE FROM edu_graph_release_rollback_outbox");
    jdbc.update("DELETE FROM edu_graph_ingestion_outbox");
    jdbc.update("DELETE FROM edu_graph_review_action");
    jdbc.update("DELETE FROM edu_graph_ingestion_job");
    jdbc.update("DELETE FROM edu_idempotency_record");
    jdbc.execute("ALTER TABLE edu_idempotency_record ALTER COLUMN id RESTART WITH 1");
    when(graph.currentGraphVersion(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn("graph-v1");
    when(graph.getIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString())).thenAnswer(invocation -> {
      GraphIngestionInternalDtos.BatchDetail value = remote.get();
      if (value == null) {
        throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "missing");
      }
      return value;
    });
    when(graph.upsertIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), any())).thenAnswer(invocation -> {
      GraphIngestionInternalDtos.BatchUpsertRequest request = invocation.getArgument(2);
      GraphIngestionInternalDtos.BatchDetail detail = detail(
          request.batchId(), request.baseGraphVersion(), request.payloadSha256(), 1L,
          "REVIEW_REQUIRED", request.source(), request.thresholdProfile(), request.models(),
          request.prompts(), request.nodes(), request.edges(), request.sources(), 2, 0, 0);
      remote.set(detail);
      return new GraphIngestionInternalDtos.BatchUpsertResponse(false, detail);
    });
    when(graph.getIngestionCandidates(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString(), anyInt(), any(), any(), any(), any()))
        .thenAnswer(invocation -> candidatePage(remote.get()));
  }

  @Test
  void adminFlowIsReviewedAuditedAndPublishedOnlyThroughRecoverableOutbox() {
    long adminId = insertAdmin("flow");
    long fileId = insertFile(adminId, "flow-source.pdf");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.CreateRequest create = new GraphIngestionDtos.CreateRequest(1L, fileId, "受版权约束的教材", "TEACHING_RESOURCE", "UNKNOWN", null);
    when(ai.extractAndReviewGraph(any())).thenAnswer(invocation -> extraction(
        invocation.getArgument(0), fileId));

    GraphIngestionDtos.JobView created = service.create(admin, "create-flow-key", create);
    assertThat(service.create(admin, "create-flow-key", create).id()).isEqualTo(created.id());
    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView waiting = service.get(admin, created.id());
    assertThat(waiting.status()).isEqualTo(GraphIngestionStatus.WAITING_HUMAN_REVIEW);
    assertThat(waiting.copyrightStatus()).isEqualTo("UNKNOWN");
    ArgumentCaptor<GraphIngestionInternalDtos.ExtractReviewRequest> extractionRequest =
        ArgumentCaptor.forClass(GraphIngestionInternalDtos.ExtractReviewRequest.class);
    verify(ai).extractAndReviewGraph(extractionRequest.capture());
    assertThat(extractionRequest.getValue().source().copyrightStatus()).isEqualTo("UNKNOWN");
    ArgumentCaptor<GraphIngestionInternalDtos.BatchUpsertRequest> batchRequest =
        ArgumentCaptor.forClass(GraphIngestionInternalDtos.BatchUpsertRequest.class);
    verify(graph).upsertIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), batchRequest.capture());
    assertThat(batchRequest.getValue().nodes().get(0).get("rights_status").asText())
        .isEqualTo("RESTRICTED");
    assertThat(batchRequest.getValue().sources().get(0).get("rights_status").asText())
        .isEqualTo("RESTRICTED");
    assertThat(jdbc.queryForObject("""
        SELECT details_json FROM edu_audit_log
        WHERE action='GRAPH_INGESTION_CREATED' AND target_id=? ORDER BY id DESC LIMIT 1
        """, String.class, created.id().toString())).contains("UNKNOWN").doesNotContain(SENSITIVE_EVIDENCE);

    GraphIngestionDtos.JobView beforePublish = service.get(admin, created.id());
    assertThatThrownBy(() -> service.publish(admin, created.id(), "publish-before-review",
        new GraphIngestionDtos.PublishRequest(beforePublish.version(), 1L, "graph-v2")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.GRAPH_HUMAN_REVIEW_REQUIRED));
    verify(graph, never()).publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.candidates(
        admin, created.id(), Long.MAX_VALUE, 100, null, null, null))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    GraphIngestionDtos.CandidatePage candidates = service.candidates(
        admin, created.id(), 1, 100, null, null, null);
    assertThat(candidates.items()).hasSize(2);
    GraphIngestionDtos.CandidateItem node = candidates.items().stream()
        .filter(item -> "NODE".equals(item.candidateType())).findFirst().orElseThrow();
    GraphIngestionDtos.CandidateItem source = candidates.items().stream()
        .filter(item -> "SOURCE".equals(item.candidateType())).findFirst().orElseThrow();
    assertThat(source.source().rightsStatus()).isEqualTo("RESTRICTED");
    when(graph.decideIngestionCandidates(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq(waiting.batchId()), any()))
        .thenAnswer(invocation -> {
          GraphIngestionInternalDtos.DecisionsRequest request = invocation.getArgument(3);
          assertThat(request.requestId()).isEqualTo(invocation.getArgument(1));
          assertThat(request.decisions().get(0).editedNode().get("evidence").get(0).asText())
              .isEqualTo(SENSITIVE_EVIDENCE);
          assertThat(request.decisions().get(0).editedNode().get("rights_status").asText())
              .isEqualTo("ALLOWED");
          assertThat(request.decisions().get(1).editedSource().get("rights_status").asText())
              .isEqualTo("ALLOWED");
          GraphIngestionInternalDtos.BatchDetail previous = remote.get();
          GraphIngestionInternalDtos.BatchDetail approved = detail(
              previous.batchId(), previous.baseGraphVersion(), previous.payloadSha256(), 2L,
              "READY_TO_PUBLISH", previous.source(), previous.thresholdProfile(), previous.models(),
              previous.prompts(), approve(withRights(previous.nodes(), "ALLOWED"), adminId), previous.edges(),
              approve(withRights(previous.sources(), "ALLOWED"), adminId), 0, 2, 0);
          remote.set(approved);
          return approved;
        });
    GraphIngestionDtos.JobView ready = service.decide(admin, created.id(), "review-flow-key",
        new GraphIngestionDtos.DecisionBatchRequest(candidates.draftRevision(), List.of(
            new GraphIngestionDtos.Decision("NODE", node.candidateId(), "APPROVE", "批准节点",
                withRights(node.node(), "ALLOWED"), null, null),
            new GraphIngestionDtos.Decision("SOURCE", source.candidateId(), "APPROVE", "批准来源",
                null, null, withRights(source.source(), "ALLOWED")))));
    assertThat(ready.status()).isEqualTo(GraphIngestionStatus.READY_TO_PUBLISH);
    String auditJson = jdbc.queryForObject(
        "SELECT decisions_json FROM edu_graph_review_action WHERE job_id=?", String.class, created.id());
    assertThat(auditJson).contains("editedPayloadSha256", "candidateId", "批准节点")
        .doesNotContain(SENSITIVE_EVIDENCE, SENSITIVE_EXCERPT, "受版权约束的教材", "example_code");

    when(graph.publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq(ready.batchId()), any()))
        .thenAnswer(invocation -> new GraphIngestionInternalDtos.PublishResponse(1L, ready.batchId(), "graph-v1", "graph-v2", "b".repeat(64), Instant.now()));
    GraphIngestionDtos.JobView publishing = service.publish(admin, created.id(), "publish-flow-key",
        new GraphIngestionDtos.PublishRequest(ready.version(), ready.draftRevision(), "graph-v2"));
    assertThat(publishing.status()).isEqualTo(GraphIngestionStatus.PUBLISHING);
    when(graph.currentGraphVersion(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn("graph-v2");
    assertThat(processor.processOnce(10)).isEqualTo(1);
    ArgumentCaptor<GraphIngestionInternalDtos.PublishRequest> publishRequest =
        ArgumentCaptor.forClass(GraphIngestionInternalDtos.PublishRequest.class);
    ArgumentCaptor<String> publishRequestId = ArgumentCaptor.forClass(String.class);
    verify(graph).publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), publishRequestId.capture(), eq(ready.batchId()), publishRequest.capture());
    assertThat(publishRequestId.getValue()).startsWith("graph-publish:").hasSizeGreaterThanOrEqualTo(16);
    assertThat(publishRequest.getValue().requestId()).isEqualTo(publishRequestId.getValue());
    GraphIngestionDtos.JobView published = service.get(admin, created.id());
    assertThat(published.status()).isEqualTo(GraphIngestionStatus.PUBLISHED);
    assertThat(published.publishedGraphVersion()).isEqualTo("graph-v2");
    assertThat(published.releaseSha256()).isEqualTo("b".repeat(64));
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='edu_graph_candidate'",
        Integer.class)).isZero();
  }

  @Test
  void existingProviderBatchCompletesAfterLostResponseWithoutCallingAiAgain() {
    long adminId = insertAdmin("recover");
    long fileId = insertFile(adminId, "recover.pdf");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.JobView created = service.create(admin, "create-recover-key",
        new GraphIngestionDtos.CreateRequest(1L, fileId, "恢复任务", "TEACHING_RESOURCE", "AUTHORIZED", "graph-v1"));
    GraphIngestionInternalDtos.ExtractReviewResponse extracted = extraction(
        new GraphIngestionInternalDtos.ExtractReviewRequest(1L, "Python程序设计", "ignored", created.id(), fileId, "recover.pdf", "http://unused", new GraphIngestionInternalDtos.ExtractSource(
                "TEACHING_RESOURCE", "恢复任务", null, fileId, "AUTHORIZED"), "zh-CN"), fileId);
    remote.set(detail(batchId(created.id()), "graph-v1", SHA, 1L,
        "REVIEW_REQUIRED", sourceRef(fileId, "恢复任务"),
        GraphIngestionInternalDtos.ThresholdProfile.defaults(), extracted.models(), extracted.prompts(),
        extracted.nodes(), extracted.edges(), extracted.sources(), 2, 0, 0));

    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView recovered = service.get(admin, created.id());
    assertThat(recovered.status()).isEqualTo(GraphIngestionStatus.WAITING_HUMAN_REVIEW);
    assertThat(recovered.batchId()).isEqualTo(batchId(created.id()));
    verify(ai, never()).extractAndReviewGraph(any());
    verify(graph, never()).upsertIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), any());
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM edu_graph_ingestion_job WHERE id=? AND ingestion_batch_id IS NOT NULL",
        Integer.class, created.id())).isOne();
  }

  @Test
  void deterministicFailureCanBeRetriedAndInvalidCopyrightIsRejected() {
    assertThat(validator.validate(new GraphIngestionDtos.CreateRequest(1L, 1L, "bad", "TEXTBOOK", "STOLEN", null))).isNotEmpty();
    long adminId = insertAdmin("retry");
    long fileId = insertFile(adminId, "retry.pdf");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.CreateRequest create = new GraphIngestionDtos.CreateRequest(1L, fileId, "可重试任务", "TEACHING_RESOURCE", "CONFIRMED", "graph-v1");
    when(ai.extractAndReviewGraph(any())).thenThrow(
        new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "invalid candidate"));
    GraphIngestionDtos.JobView created = service.create(admin, "create-retry-key", create);
    processor.processOnce(10);
    assertThat(service.get(admin, created.id()).status()).isEqualTo(GraphIngestionStatus.FAILED);

    GraphIngestionDtos.JobView retried = service.retry(admin, created.id(), "retry-key");
    assertThat(retried.status()).isEqualTo(GraphIngestionStatus.CREATED);
    assertThat(service.get(admin, created.id()).failureCode()).isNull();
    assertThat(jdbc.queryForObject(
        "SELECT error_code IS NULL AND error_message IS NULL FROM edu_graph_ingestion_job WHERE id=?",
        Boolean.class, created.id())).isTrue();
    Mockito.doAnswer(invocation -> {
      GraphIngestionInternalDtos.ExtractReviewResponse value = extraction(
          invocation.getArgument(0), fileId);
      ((ObjectNode) value.nodes().get(0)).put("rights_status", "RESTRICTED");
      return value;
    })
        .when(ai).extractAndReviewGraph(any());
    assertThat(processor.processOnce(10)).isEqualTo(1);
    assertThat(service.get(admin, created.id()).status())
        .isEqualTo(GraphIngestionStatus.WAITING_HUMAN_REVIEW);
    assertThat(service.get(admin, created.id()).failureCode()).isNull();
    assertThat(jdbc.queryForObject(
        "SELECT error_code IS NULL AND error_message IS NULL FROM edu_graph_ingestion_job WHERE id=?",
        Boolean.class, created.id())).isTrue();
    ArgumentCaptor<GraphIngestionInternalDtos.BatchUpsertRequest> authorizedBatch =
        ArgumentCaptor.forClass(GraphIngestionInternalDtos.BatchUpsertRequest.class);
    verify(graph).upsertIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), authorizedBatch.capture());
    assertThat(authorizedBatch.getValue().nodes().get(0).get("rights_status").asText())
        .isEqualTo("ALLOWED");
    assertThat(authorizedBatch.getValue().sources().get(0).get("rights_status").asText())
        .isEqualTo("ALLOWED");
    assertThat(service.retry(admin, created.id(), "retry-key").id()).isEqualTo(created.id());
    assertThat(service.create(admin, "create-retry-key", create).id()).isEqualTo(created.id());
  }

  @Test
  void staleProviderBatchWithReusedIdentityCannotBindToANewFile() {
    long adminId = insertAdmin("stale");
    long fileId = insertFile(adminId, "new-source.pdf");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.JobView created = service.create(admin, "create-stale-key",
        new GraphIngestionDtos.CreateRequest(1L, fileId, "新来源", "TEACHING_RESOURCE", "CONFIRMED", "graph-v1"));
    GraphIngestionInternalDtos.ExtractReviewResponse extracted = extraction(
        new GraphIngestionInternalDtos.ExtractReviewRequest(1L, "Python程序设计", "ignored", created.id(), fileId, "new-source.pdf", "http://unused", new GraphIngestionInternalDtos.ExtractSource(
                "TEACHING_RESOURCE", "新来源", null, fileId, "CONFIRMED"), "zh-CN"), fileId);
    GraphIngestionInternalDtos.SourceRef stale = new GraphIngestionInternalDtos.SourceRef(
        "TEACHING_RESOURCE", "file:999999", "旧来源", "f".repeat(64), "file:999999");
    remote.set(detail(batchId(created.id()), "graph-v1", SHA, 1L,
        "REVIEW_REQUIRED", stale, GraphIngestionInternalDtos.ThresholdProfile.defaults(),
        extracted.models(), extracted.prompts(), extracted.nodes(), extracted.edges(),
        extracted.sources(), 2, 0, 0));

    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView failed = service.get(admin, created.id());
    assertThat(failed.status()).isEqualTo(GraphIngestionStatus.FAILED);
    assertThat(failed.failureCode()).isEqualTo(ErrorCode.GRAPH_VERSION_CONFLICT.name());
    verify(ai, never()).extractAndReviewGraph(any());
    verify(graph, never()).upsertIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), any());
  }

  @Test
  void publishedProviderBatchConvergesAfterActivationResponseWasLost() {
    long adminId = insertAdmin("publish-recovery");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.JobView ready = createReadyJob(adminId, "publish-recovery");
    GraphIngestionDtos.JobView publishing = service.publish(admin, ready.id(),
        "publish-recovery-key", new GraphIngestionDtos.PublishRequest(
            ready.version(), ready.draftRevision(), "graph-v2"));
    assertThat(publishing.status()).isEqualTo(GraphIngestionStatus.PUBLISHING);

    GraphIngestionInternalDtos.PublishResponse receipt =
        new GraphIngestionInternalDtos.PublishResponse(1L, ready.batchId(), "graph-v1", "graph-v2", "e".repeat(64), Instant.now());
    remote.set(published(remote.get(), "graph-v2"));
    when(graph.publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq(ready.batchId()), any())).thenReturn(receipt);
    when(graph.currentGraphVersion(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn("graph-v2");

    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView recovered = service.get(admin, ready.id());
    assertThat(recovered.status()).isEqualTo(GraphIngestionStatus.PUBLISHED);
    assertThat(recovered.previousGraphVersion()).isEqualTo("graph-v1");
    assertThat(recovered.publishedGraphVersion()).isEqualTo("graph-v2");
    assertThat(recovered.releaseSha256()).isEqualTo("e".repeat(64));
    assertThat(jdbc.queryForObject(
        "SELECT status FROM edu_graph_ingestion_outbox WHERE event_type='PUBLISH' AND job_id=?",
        String.class, ready.id())).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject("""
        SELECT response_json IS NOT NULL FROM edu_idempotency_record
        WHERE operation_id=? AND idempotency_key=?
        """, Boolean.class, "GRAPH_INGESTION_PUBLISH:" + ready.id(), "publish-recovery-key"))
        .isTrue();
    GraphIngestionDtos.JobView replay = service.publish(admin, ready.id(), "publish-recovery-key",
        new GraphIngestionDtos.PublishRequest(ready.version(), ready.draftRevision(), "graph-v2"));
    assertThat(replay.status()).isEqualTo(GraphIngestionStatus.PUBLISHED);
    assertThat(replay.id()).isEqualTo(ready.id());
    assertThat(replay.publishedGraphVersion()).isEqualTo("graph-v2");
    assertThatThrownBy(() -> service.publish(admin, ready.id(), "publish-recovery-key",
        new GraphIngestionDtos.PublishRequest(ready.version(), ready.draftRevision(), "graph-v3")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<GraphIngestionInternalDtos.PublishRequest> request =
        ArgumentCaptor.forClass(GraphIngestionInternalDtos.PublishRequest.class);
    verify(graph).publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), requestId.capture(), eq(ready.batchId()), request.capture());
    assertThat(request.getValue().requestId()).isEqualTo(requestId.getValue());
    assertThat(request.getValue().targetGraphVersion()).isEqualTo("graph-v2");
  }

  @Test
  void publishedProviderBatchForAnotherTargetIsAConflict() {
    long adminId = insertAdmin("publish-conflict");
    AuthenticatedUser admin = admin(adminId);
    GraphIngestionDtos.JobView ready = createReadyJob(adminId, "publish-conflict");
    service.publish(admin, ready.id(), "publish-conflict-key",
        new GraphIngestionDtos.PublishRequest(ready.version(), ready.draftRevision(), "graph-v2"));
    remote.set(published(remote.get(), "graph-v3"));

    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView failed = service.get(admin, ready.id());
    assertThat(failed.status()).isEqualTo(GraphIngestionStatus.PARTIALLY_COMPLETED);
    assertThat(failed.failureCode()).isEqualTo(ErrorCode.GRAPH_VERSION_CONFLICT.name());
    assertThat(jdbc.queryForObject(
        "SELECT status FROM edu_graph_ingestion_outbox WHERE event_type='PUBLISH' AND job_id=?",
        String.class, ready.id())).isEqualTo("FAILED");
    verify(graph, never()).publishIngestionBatch(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString(), any());
  }

  @Test
  void rollbackOutboxRecoversACompletedActivationWithoutCallerRetry() {
    long adminId = insertAdmin("rollback-recovery");
    AuthenticatedUser admin = admin(adminId);
    int requestedBefore = jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLBACK_REQUESTED' AND target_id='graph-v1'
        """, Integer.class);
    int completedBefore = jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLED_BACK' AND target_id='graph-v1'
        """, Integer.class);
    GraphIngestionInternalDtos.RollbackResponse receipt =
        new GraphIngestionInternalDtos.RollbackResponse(1L, "graph-v2", "graph-v1", "f".repeat(64), Instant.now());
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> stableRequestId = new AtomicReference<>();
    when(graph.rollbackRelease(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq("graph-v1"), any())).thenAnswer(invocation -> {
      String requestId = invocation.getArgument(1);
      GraphIngestionInternalDtos.RollbackRequest body = invocation.getArgument(3);
      if (stableRequestId.get() == null) stableRequestId.set(requestId);
      assertThat(requestId).isEqualTo(stableRequestId.get());
      assertThat(body.requestId()).isEqualTo(requestId);
      if (calls.getAndIncrement() == 0) {
        throw new BusinessException(
            ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "activation completed; response lost");
      }
      return receipt;
    });
    when(graph.currentGraphVersion(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn("graph-v1");

    assertThatThrownBy(() -> service.rollback(admin, 1L, "graph-v1", "rollback-recovery-key", new GraphIngestionDtos.RollbackRequest("graph-v2")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode())
                .isEqualTo(ErrorCode.GRAPH_SERVICE_UNAVAILABLE));
    assertThat(jdbc.queryForObject(
        "SELECT status FROM edu_graph_release_rollback_outbox", String.class)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject(
        "SELECT response_json IS NULL FROM edu_idempotency_record", Boolean.class)).isTrue();
    jdbc.update("UPDATE edu_graph_release_rollback_outbox SET next_attempt_at=?",
        LocalDateTime.now().minusSeconds(1));

    assertThat(rollbackProcessor.processOnce(10)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT status FROM edu_graph_release_rollback_outbox", String.class)).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject(
        "SELECT response_json IS NOT NULL FROM edu_graph_release_rollback_outbox", Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject(
        "SELECT response_json IS NOT NULL FROM edu_idempotency_record", Boolean.class)).isTrue();
    GraphIngestionDtos.RollbackResult replay = service.rollback(admin, 1L, "graph-v1", "rollback-recovery-key", new GraphIngestionDtos.RollbackRequest("graph-v2"));
    assertThat(replay.previousGraphVersion()).isEqualTo("graph-v2");
    assertThat(replay.graphVersion()).isEqualTo("graph-v1");
    assertThat(replay.releaseSha256()).isEqualTo("f".repeat(64));
    assertThat(calls).hasValue(2);
    assertThat(stableRequestId.get()).startsWith("graph-rollback:");
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLBACK_REQUESTED' AND target_id='graph-v1'
        """, Integer.class)).isEqualTo(requestedBefore + 1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLED_BACK' AND target_id='graph-v1'
        """, Integer.class)).isEqualTo(completedBefore + 1);

    assertThatThrownBy(() -> service.rollback(admin, 1L, "graph-v1", "rollback-recovery-key", new GraphIngestionDtos.RollbackRequest("graph-v3")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    assertThatThrownBy(() -> service.rollback(admin, 1L, "graph-v0", "rollback-recovery-key", new GraphIngestionDtos.RollbackRequest("graph-v1")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void rollbackRetryableFailuresExhaustTheirDurableAttemptBudget() {
    long adminId = insertAdmin("rollback-exhausted");
    AuthenticatedUser admin = admin(adminId);
    int failedBefore = jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLBACK_FAILED' AND target_id='graph-v1'
        """, Integer.class);
    when(graph.rollbackRelease(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq("graph-v1"), any())).thenThrow(
        new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "graph temporarily unavailable"));

    assertThatThrownBy(() -> service.rollback(admin, 1L, "graph-v1", "rollback-exhausted-key", new GraphIngestionDtos.RollbackRequest("graph-v2")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode())
                .isEqualTo(ErrorCode.GRAPH_SERVICE_UNAVAILABLE));
    assertThat(jdbc.queryForObject("SELECT attempt_count FROM edu_graph_release_rollback_outbox",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT max_attempts FROM edu_graph_release_rollback_outbox",
        Integer.class)).isEqualTo(5);
    for (int attempt = 2; attempt <= 5; attempt++) {
      jdbc.update("UPDATE edu_graph_release_rollback_outbox SET next_attempt_at=?",
          LocalDateTime.now().minusSeconds(1));
      assertThat(rollbackProcessor.processOnce(10)).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT status FROM edu_graph_release_rollback_outbox",
        String.class)).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT attempt_count FROM edu_graph_release_rollback_outbox",
        Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_audit_log
        WHERE action='GRAPH_RELEASE_ROLLBACK_FAILED' AND target_id='graph-v1'
        """, Integer.class)).isEqualTo(failedBefore + 1);
  }

  @Test
  void upstreamMutationRequestIdIsStableOnReplayAndUniqueAcrossClaims() {
    long adminId = insertAdmin("mutation-id");
    GraphMutationIdempotency.Claim<GraphIngestionDtos.RollbackResult> first =
        mutationIdempotency.claim(adminId, "GRAPH_RELEASE_ROLLBACK:v1", "stable-key", SHA,
            GraphIngestionDtos.RollbackResult.class);
    GraphMutationIdempotency.Claim<GraphIngestionDtos.RollbackResult> replay =
        mutationIdempotency.claim(adminId, "GRAPH_RELEASE_ROLLBACK:v1", "stable-key", SHA,
            GraphIngestionDtos.RollbackResult.class);
    GraphMutationIdempotency.Claim<GraphIngestionDtos.RollbackResult> second =
        mutationIdempotency.claim(adminId, "GRAPH_RELEASE_ROLLBACK:v2", "second-key", SHA,
            GraphIngestionDtos.RollbackResult.class);
    assertThat(replay.recordId()).isEqualTo(first.recordId());
    assertThat(replay.upstreamRequestId()).isEqualTo(first.upstreamRequestId());
    assertThat(first.upstreamRequestId()).hasSize(32).isNotEqualTo(second.upstreamRequestId());
  }

  private GraphIngestionDtos.JobView createReadyJob(long adminId, String suffix) {
    long fileId = insertFile(adminId, suffix + ".pdf");
    String title = "待发布任务-" + suffix;
    GraphIngestionDtos.JobView created = service.create(admin(adminId), "create-" + suffix,
        new GraphIngestionDtos.CreateRequest(1L, fileId, title, "TEACHING_RESOURCE", "AUTHORIZED", "graph-v1"));
    GraphIngestionInternalDtos.ExtractReviewResponse extracted = extraction(
        new GraphIngestionInternalDtos.ExtractReviewRequest(1L, "Python程序设计", "ignored", created.id(), fileId, suffix + ".pdf", "http://unused", new GraphIngestionInternalDtos.ExtractSource(
                "TEACHING_RESOURCE", title, null, fileId, "AUTHORIZED"), "zh-CN"), fileId);
    remote.set(detail(batchId(created.id()), "graph-v1", SHA, 2L,
        "READY_TO_PUBLISH", sourceRef(fileId, title),
        GraphIngestionInternalDtos.ThresholdProfile.defaults(), extracted.models(), extracted.prompts(),
        approve(extracted.nodes(), adminId), extracted.edges(), approve(extracted.sources(), adminId),
        0, 2, 0));
    assertThat(processor.processOnce(10)).isEqualTo(1);
    GraphIngestionDtos.JobView ready = service.get(admin(adminId), created.id());
    assertThat(ready.status()).isEqualTo(GraphIngestionStatus.READY_TO_PUBLISH);
    return ready;
  }

  private GraphIngestionInternalDtos.BatchDetail published(
      GraphIngestionInternalDtos.BatchDetail value, String graphVersion) {
    return new GraphIngestionInternalDtos.BatchDetail(1L, value.batchId(), value.baseGraphVersion(), value.payloadSha256(), "PUBLISHED", value.revision(), value.candidateCount(), value.pendingCount(), value.approvedCount(), value.rejectedCount(), value.errorCount(), value.warningCount(), value.createdAt(), Instant.now(), graphVersion, value.source(), value.thresholdProfile(), value.models(), value.prompts(), value.nodes(), value.edges(), value.sources(), value.validationIssues());
  }

  private GraphIngestionInternalDtos.ExtractReviewResponse extraction(
      GraphIngestionInternalDtos.ExtractReviewRequest request, long fileId) {
    return new GraphIngestionInternalDtos.ExtractReviewResponse(1L, request.requestId(), request.jobId(), List.of(node()), List.of(), List.of(source(fileId)), List.of(), models(), prompts());
  }

  private ObjectNode node() {
    ObjectNode value = json.createObjectNode();
    value.put("candidate_id", "node-1");
    value.put("id", "course-python");
    value.put("name", "敏感候选节点正文");
    value.put("level", 0);
    value.put("node_type", "course");
    value.putNull("parent_id");
    value.putNull("description");
    value.putArray("aliases");
    value.putArray("keywords");
    value.putNull("difficulty");
    value.putNull("importance");
    value.putNull("estimated_minutes");
    value.putArray("learning_objectives");
    value.putArray("common_misconceptions");
    value.putArray("example_code");
    value.put("rights_status", "ALLOWED");
    value.putArray("evidence").add(SENSITIVE_EVIDENCE);
    pending(value);
    return value;
  }

  private ObjectNode source(long fileId) {
    ObjectNode value = json.createObjectNode();
    value.put("candidate_id", "source-1");
    value.put("id", "source-file-" + fileId);
    value.put("node_id", "course-python");
    value.put("source_type", "TEACHING_RESOURCE");
    value.put("title", "敏感候选来源正文");
    value.putNull("resource_id");
    value.put("file_id", fileId);
    value.putNull("chapter");
    value.putNull("page_from");
    value.putNull("page_to");
    value.put("locator", "file:" + fileId);
    value.put("excerpt", SENSITIVE_EXCERPT);
    value.put("rights_status", "ALLOWED");
    pending(value);
    return value;
  }

  private void pending(ObjectNode value) {
    value.put("extractor_confidence", new BigDecimal("0.93"));
    value.put("reviewer_confidence", new BigDecimal("0.91"));
    value.put("review_status", "PENDING");
    value.put("decision_status", "PENDING");
    value.put("review_lane", "STANDARD");
    value.putNull("decision_comment");
    value.putNull("decided_by");
    value.putNull("decided_at");
  }

  private GraphIngestionDtos.CandidateNode withRights(
      GraphIngestionDtos.CandidateNode value, String rightsStatus) {
    ObjectNode copy = json.valueToTree(value);
    copy.put("rightsStatus", rightsStatus);
    return json.convertValue(copy, GraphIngestionDtos.CandidateNode.class);
  }

  private GraphIngestionDtos.CandidateSource withRights(
      GraphIngestionDtos.CandidateSource value, String rightsStatus) {
    ObjectNode copy = json.valueToTree(value);
    copy.put("rightsStatus", rightsStatus);
    return json.convertValue(copy, GraphIngestionDtos.CandidateSource.class);
  }

  private List<JsonNode> withRights(List<JsonNode> values, String rightsStatus) {
    return values.stream().map(value -> {
      ObjectNode copy = ((ObjectNode) value).deepCopy();
      copy.put("rights_status", rightsStatus);
      return (JsonNode) copy;
    }).toList();
  }

  private List<JsonNode> approve(List<JsonNode> values, long reviewerId) {
    return values.stream().map(value -> {
      ObjectNode approved = ((ObjectNode) value).deepCopy();
      approved.put("decision_status", "APPROVED");
      approved.put("decision_comment", "human approved");
      approved.put("decided_by", Long.toString(reviewerId));
      approved.put("decided_at", Instant.now().toString());
      return (JsonNode) approved;
    }).toList();
  }

  private GraphIngestionInternalDtos.CandidatePage candidatePage(
      GraphIngestionInternalDtos.BatchDetail detail) {
    List<GraphIngestionInternalDtos.CandidateItem> items = java.util.stream.Stream.concat(
        detail.nodes().stream().map(node -> new GraphIngestionInternalDtos.CandidateItem(
            "NODE", node.get("candidate_id").asText(), node.get("decision_status").asText(),
            node.get("review_lane").asText(), node, null, null)),
        detail.sources().stream().map(source -> new GraphIngestionInternalDtos.CandidateItem(
            "SOURCE", source.get("candidate_id").asText(), source.get("decision_status").asText(),
            source.get("review_lane").asText(), null, null, source))).toList();
    return new GraphIngestionInternalDtos.CandidatePage(items, null);
  }

  private GraphIngestionInternalDtos.BatchDetail detail(
      String batchId, String baseVersion, String payloadSha, long revision, String status,
      GraphIngestionInternalDtos.SourceRef source,
      GraphIngestionInternalDtos.ThresholdProfile profile,
      GraphIngestionInternalDtos.Models models,
      GraphIngestionInternalDtos.Prompts prompts,
      List<JsonNode> nodes, List<JsonNode> edges, List<JsonNode> sources,
      int pending, int approved, int rejected) {
    Instant now = Instant.now();
    return new GraphIngestionInternalDtos.BatchDetail(1L, batchId, baseVersion, payloadSha, status, revision, nodes.size() + edges.size() + sources.size(), pending, approved, rejected, 0, 0, now.minusSeconds(1), now, null, source, profile, models, prompts, nodes, edges, sources, List.of());
  }

  private GraphIngestionInternalDtos.SourceRef sourceRef(long fileId, String title) {
    return new GraphIngestionInternalDtos.SourceRef(
        "TEACHING_RESOURCE", "file:" + fileId, title, SHA, "file:" + fileId);
  }

  private GraphIngestionInternalDtos.Models models() {
    return new GraphIngestionInternalDtos.Models(
        new GraphIngestionInternalDtos.ModelIdentity("provider", "extractor-v1"),
        new GraphIngestionInternalDtos.ModelIdentity("provider", "reviewer-v1"));
  }

  private GraphIngestionInternalDtos.Prompts prompts() {
    return new GraphIngestionInternalDtos.Prompts(
        new GraphIngestionInternalDtos.PromptIdentity("extract-v1", "c".repeat(64)),
        new GraphIngestionInternalDtos.PromptIdentity("review-v1", "d".repeat(64)));
  }

  private long insertAdmin(String suffix) {
    return generated("""
        INSERT INTO edu_user(username,password_hash,display_name,role,status)
        VALUES(?, 'x', 'Graph Admin', 'ADMIN', 'ACTIVE')
        """, "cycle7_" + suffix + "_" + sequence++);
  }

  private String batchId(long jobId) {
    return jdbc.queryForObject(
        "SELECT CONCAT('graph-ingestion:',request_id) FROM edu_graph_ingestion_job WHERE id=?",
        String.class, jobId);
  }

  private long insertFile(long ownerId, String name) {
    return generated("""
        INSERT INTO edu_file_object(
          original_name,storage_key,content_type,size_bytes,sha256,owner_id,purpose,status)
        VALUES(?,?,'application/pdf',128,?,?, 'KNOWLEDGE_SOURCE','AVAILABLE')
        """, name, "cycle7/" + sequence++ + "/" + name, SHA, ownerId);
  }

  private long generated(String sql, Object... values) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"});
      for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
      return statement;
    }, keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  private AuthenticatedUser admin(long id) {
    return new AuthenticatedUser(id, "admin", "x", UserRole.ADMIN, true, true);
  }
}
