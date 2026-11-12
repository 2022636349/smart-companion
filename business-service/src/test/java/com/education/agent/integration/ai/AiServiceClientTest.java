package com.education.agent.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiServiceClientTest {

  private MockRestServiceServer server;
  private AiServiceClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new AiServiceClient(builder.build(), "internal-test-token");
  }

  @Test
  void sendsShortAnswerGradingWithPrivateReferenceAndInternalHeaders() {
    server.expect(requestTo("http://ai.test/internal/ai/tutor/quiz/suggest-grade"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "tutor-grade-test-0001"))
        .andExpect(jsonPath("$.course_id").value(1))
        .andExpect(jsonPath("$.activity_id").value(12))
        .andExpect(jsonPath("$.questions[0].accepted_answers[0]").value("可读性"))
        .andExpect(jsonPath("$.questions[0].submitted_answer").value("高可读性"))
        .andRespond(withSuccess("""
          {"request_id":"tutor-grade-test-0001","grades":[{"question_ref":"q1","score":25,"max_score":25,
          "confidence":0.96,"feedback":"表达准确。"}],"model_version":"test","prompt_version":"tutor-short-answer-grade-v1"}
          """, MediaType.APPLICATION_JSON));
    var request = new AiTutorQuizGrading.Request("tutor-grade-test-0001", 1L, 12L, List.of(
        new AiTutorQuizGrading.Question("q1", "Python 的特点？", List.of("可读性"), "可读性便于理解。", "高可读性", new BigDecimal("25"))));
    assertThat(client.suggestTutorQuizGrade(request).grades().get(0).score()).isEqualByComparingTo("25");
    server.verify();
  }

  @Test
  void sendsFrozenContractAndMapsStructuredTutorReply() {
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/tutor/chat")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "request-1"))
        .andExpect(jsonPath("$.knowledge_point_id").value("kp-1"))
        .andExpect(jsonPath("$.student_context.entry_source").value("GRAPH"))
        .andRespond(
            withSuccess(
                """
                        {
                          "request_id": "request-1",
                          "knowledge_point_id": "kp-1",
                          "answer": "先理解变量绑定。",
                          "blocks": [
                            {"type": "MARKDOWN", "content": "变量名指向一个值。", "metadata": {}}
                          ],
                          "sources": [
                            {
                              "title": "Python 程序设计",
                              "locator": "第 2 章",
                              "review_status": "APPROVED",
                              "rights_status": "ALLOWED"
                            }
                          ],
                          "cognitive_labels": [
                            {
                              "knowledge_point_id": "kp-1",
                              "status": "LEARNING",
                              "confidence": 0.8,
                              "evidence": "学生正在询问变量绑定"
                            }
                          ],
                          "suggested_next_step": "完成一个赋值练习",
                          "model_version": "deepseek-chat",
                          "prompt_version": "tutor-v1"
                        }
                        """,
                MediaType.APPLICATION_JSON));

    AiTutorChatResponse response =
        client.tutorChat(
            new AiTutorChatRequest(1L, "Python程序设计", List.of("EXPLAIN", "QUIZ", "CODE", "VISUAL"), "request-1", 7L, 9L, "kp-1", "变量是什么？", List.of(new AiTutorHistoryMessage("user", "我刚开始学习")), Map.of("entry_source", "GRAPH")));

    assertThat(response.requestId()).isEqualTo("request-1");
    assertThat(response.blocks())
        .singleElement()
        .satisfies(block -> assertThat(block.type()).isEqualTo("MARKDOWN"));
    assertThat(response.sources())
        .singleElement()
        .satisfies(source -> {
          assertThat(source.get("review_status")).isEqualTo("APPROVED");
          assertThat(source.get("rights_status")).isEqualTo("ALLOWED");
        });
    assertThat(response.cognitiveLabels())
        .singleElement()
        .satisfies(
            label -> {
              assertThat(label.status()).isEqualTo("LEARNING");
              assertThat(label.confidence()).isEqualByComparingTo(new BigDecimal("0.8"));
            });
    server.verify();
  }

  @Test
  void mapsUpstreamRateLimitToStableBusinessError() {
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/tutor/chat")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> client.tutorChat(request()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_RATE_LIMITED));
    server.verify();
  }

  @Test
  void mapsTutorGatewayTimeoutToTimeoutInsteadOfUnavailable() {
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/tutor/chat")))
        .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

    assertThatThrownBy(() -> client.tutorChat(request()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT);
              assertThat(exception.getMessage()).contains("超时");
            });
    server.verify();
  }

  @Test
  void mapsUpstreamFailureToExplicitUnavailableError() {
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/tutor/chat")))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

    assertThatThrownBy(() -> client.tutorChat(request()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void sendsFrozenAssessmentParseContractAndMapsCandidates() {
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/assessment/parse")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "parse-request-1"))
        .andExpect(jsonPath("$.source_file_id").value(31))
        .andExpect(jsonPath("$.download_url").value("http://business.test/api/files/31/content"))
        .andExpect(jsonPath("$.course_id").value(1))
        .andRespond(
            withSuccess(
                """
                        {
                          "request_id":"parse-request-1",
                          "title_suggestion":"第一章练习",
                          "questions":[{
                            "client_key":"q-1",
                            "question_type":"SINGLE_CHOICE",
                            "stem":"Python赋值符号是？",
                            "score":10,
                            "options":[{"key":"A","content":"=","is_correct":true}],
                            "reference_answer":{"selected_option_keys":["A"]},
                            "rubric_items":[],
                            "test_cases":[],
                            "knowledge_point_candidates":[],
                            "confidence":"HIGH",
                            "warnings":[]
                          }],
                          "warnings":[],
                          "model_version":"deepseek-test",
                          "prompt_version":"assessment-v1"
                        }
                        """,
                MediaType.APPLICATION_JSON));

    AiAssessmentParseResponse response =
        client.parseAssessment(
            new AiAssessmentParseRequest(
                "parse-request-1",
                31L,
                "questions.docx",
                "http://business.test/api/files/31/content",
                1L,
                "zh-CN"));

    assertThat(response.requestId()).isEqualTo("parse-request-1");
    assertThat(response.questions())
        .singleElement()
        .satisfies(
            question -> {
              assertThat(question.clientKey()).isEqualTo("q-1");
              assertThat(question.options())
                  .singleElement()
                  .satisfies(option -> assertThat(option.isCorrect()).isTrue());
            });
    server.verify();
  }

  @Test
  void mapsGraphExtractorAndReviewerErrorsToTheirOwnStages() {
    String extractorRequestId = "graph-extract-request-1";
    server.expect(once(), requestTo("http://ai.test/internal/ai/graph/extract-review"))
        .andExpect(header("X-Request-Id", extractorRequestId))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body("""
            {"detail":{"code":"GRAPH_EXTRACTOR_INVALID_RESPONSE","owner":"D",
             "message":"extractor failed","request_id":"graph-extract-request-1"}}
            """));

    assertThatThrownBy(() -> client.extractAndReviewGraph(graphRequest(extractorRequestId)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.GRAPH_EXTRACTION_FAILED));

    setUp();
    String reviewerRequestId = "graph-review-request-1";
    server.expect(once(), requestTo("http://ai.test/internal/ai/graph/extract-review"))
        .andExpect(header("X-Request-Id", reviewerRequestId))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body("""
            {"detail":{"code":"GRAPH_REVIEWER_INVALID_RESPONSE","owner":"D",
             "message":"reviewer failed","request_id":"graph-review-request-1"}}
            """));

    assertThatThrownBy(() -> client.extractAndReviewGraph(graphRequest(reviewerRequestId)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.GRAPH_REVIEW_FAILED));
    server.verify();
  }

  @Test
  void unknownGraphBadGatewayDoesNotClaimReviewerFailureOrExposeDetails() {
    server.expect(once(), requestTo("http://ai.test/internal/ai/graph/extract-review"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body("""
            {"detail":{"code":"GRAPH_INGESTION_DEPENDENCY_REJECTED","owner":"D",
             "message":"sensitive upstream diagnostic","request_id":"graph-generic-request-1"}}
            """));

    assertThatThrownBy(() -> client.extractAndReviewGraph(graphRequest("graph-generic-request-1")))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE);
          assertThat(exception.getMessage()).doesNotContain("sensitive upstream diagnostic");
        });
    server.verify();
  }

  private GraphIngestionInternalDtos.ExtractReviewRequest graphRequest(String requestId) {
    return new GraphIngestionInternalDtos.ExtractReviewRequest(1L, "Python", requestId, 7L, 9L,
        "source.docx", "http://business.test/api/files/9/content",
        new GraphIngestionInternalDtos.ExtractSource("TEXTBOOK", "Python", null, 9L, "AUTHORIZED"), "zh-CN");
  }

  @Test
  void sendsSandboxContractAndMapsCapacityFailures() {
    SandboxRunRequest request =
        new SandboxRunRequest(
            "sandbox-request-1",
            "PUBLIC_RUN",
            "PYTHON_3_11",
            "print(input())",
            List.of(
                new SandboxRunRequest.SandboxTestCase(
                    1L, "ok", "ok", BigDecimal.ONE, "PUBLIC", "EXACT", null)),
            new SandboxRunRequest.SandboxLimits(2000, 128, 256));
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/code/run")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "sandbox-request-1"))
        .andExpect(jsonPath("$.execution_mode").value("PUBLIC_RUN"))
        .andExpect(jsonPath("$.test_cases[0].expected_output").value("ok"))
        .andRespond(
            withSuccess(
                """
                        {"request_id":"sandbox-request-1","status":"ACCEPTED","achieved_score":1,
                         "total_score":1,"passed_count":1,"total_count":1,"test_results":[
                         {"test_case_id":1,"status":"ACCEPTED","score":1,"max_score":1,
                          "time_ms":2,"memory_kb":1024,"visibility":"PUBLIC",
                          "actual_output":"ok","expected_output":"ok"}]}
                        """,
                MediaType.APPLICATION_JSON));

    assertThat(client.runSandbox(request).status()).isEqualTo("ACCEPTED");
    server.verify();

    RestClient.Builder rateBuilder = RestClient.builder().baseUrl("http://ai-rate.test");
    MockRestServiceServer rateServer = MockRestServiceServer.bindTo(rateBuilder).build();
    AiServiceClient rateClient = new AiServiceClient(rateBuilder.build(), "token");
    rateServer
        .expect(once(), requestTo(URI.create("http://ai-rate.test/internal/code/run")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThatThrownBy(() -> rateClient.runSandbox(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.CODE_SANDBOX_RATE_LIMITED));
    rateServer.verify();

    RestClient.Builder downBuilder = RestClient.builder().baseUrl("http://ai-down.test");
    MockRestServiceServer downServer = MockRestServiceServer.bindTo(downBuilder).build();
    AiServiceClient downClient = new AiServiceClient(downBuilder.build(), "token");
    downServer
        .expect(once(), requestTo(URI.create("http://ai-down.test/internal/code/run")))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> downClient.runSandbox(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.CODE_SANDBOX_UNAVAILABLE));
    downServer.verify();

    RestClient.Builder timeoutBuilder = RestClient.builder().baseUrl("http://ai-timeout.test");
    MockRestServiceServer timeoutServer = MockRestServiceServer.bindTo(timeoutBuilder).build();
    AiServiceClient timeoutClient = new AiServiceClient(timeoutBuilder.build(), "token");
    timeoutServer
        .expect(once(), requestTo(URI.create("http://ai-timeout.test/internal/code/run")))
        .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
    assertThatThrownBy(() -> timeoutClient.runSandbox(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.CODE_SANDBOX_TIMEOUT));
    timeoutServer.verify();
  }

  @Test
  void rejectsAgentTraceObservationOrToolArgumentsAtJsonBoundary() {
    server.expect(once(), requestTo("http://ai.test/internal/ai/tutor/chat"))
        .andRespond(withSuccess("""
            {"request_id":"request-1","knowledge_point_id":"kp-1","answer":"safe",
             "blocks":[],"sources":[],"cognitive_labels":[],"suggested_next_step":null,
             "model_version":"model","prompt_version":"prompt",
             "agent_trace":{"requested_mode":"FIXED","effective_mode":"FIXED",
               "fallback_reason":null,"tool_invocations":[],"observation":"hidden chain"}}
            """, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.tutorChat(request()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    server.verify();

    setUp();
    server.expect(once(), requestTo("http://ai.test/internal/ai/tutor/chat"))
        .andRespond(withSuccess("""
            {"request_id":"request-1","knowledge_point_id":"kp-1","answer":"safe",
             "blocks":[],"sources":[],"cognitive_labels":[],"suggested_next_step":null,
             "model_version":"model","prompt_version":"prompt",
             "agent_trace":{"requested_mode":"ROUTER","effective_mode":"ROUTER",
               "fallback_reason":null,"tool_invocations":[{"step":1,"tool":"GRAPH_CONTEXT",
               "reason_code":"NEED_GRAPH_CONTEXT","status":"SUCCESS","duration_ms":1,
               "item_count":1,"error_code":null,"arguments":{"query":"secret"}}]}}
            """, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.tutorChat(request()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    server.verify();
  }

  @Test
  void sendsFrozenProfileAndMemorySnakeCaseWithMatchingInternalRequestId() {
    String requestId = "profile-summary-request-7";
    server.expect(once(), requestTo(URI.create("http://ai.test/internal/ai/profile/summarize")))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", requestId)).andExpect(jsonPath("$.request_id").value(requestId))
        .andExpect(jsonPath("$.user_id").value(7)).andExpect(jsonPath("$.previous_summary").value("old"))
        .andExpect(jsonPath("$.evidence[0].source_type").value("TUTOR"))
        .andExpect(jsonPath("$.evidence[0].source_id").value(9))
        .andExpect(jsonPath("$.evidence[0].knowledge_point_id").value("kp"))
        .andRespond(withSuccess("""
            {"course_id":1,"request_id":"profile-summary-request-7","summary":"summary","cognitive_labels":[],
             "memory_candidates":[],"resource_suggestions":[],"model_version":"fake","prompt_version":"p1"}
            """, MediaType.APPLICATION_JSON));
    String memoryRequest = "memory-outbox-request-11";
    server.expect(once(), requestTo(URI.create("http://ai.test/internal/memory/upsert")))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Request-Id",memoryRequest))
        .andExpect(jsonPath("$.request_id").value(memoryRequest)).andExpect(jsonPath("$.memory_id").value(11))
        .andExpect(jsonPath("$.student_id").value(7)).andExpect(jsonPath("$.memory_type").value("LEARNING_GOAL"))
        .andExpect(jsonPath("$.knowledge_point_id").value("kp"))
        .andExpect(jsonPath("$.content_hash").value("a".repeat(64)))
        .andExpect(jsonPath("$.generation").value(3))
        .andExpect(jsonPath("$.source_type").value("SYSTEM")).andExpect(jsonPath("$.source_id").value(3))
        .andRespond(withSuccess("""
            {"request_id":"memory-outbox-request-11","collection":"learning_memory","memory_id":11,
             "generation":3,"indexed":true,"embedding_model":"fake","memory_index_epoch":"memory-index-epoch-test",
             "memory_rebuild_required":false}
            """,MediaType.APPLICATION_JSON));
    server.expect(once(),requestTo("http://ai.test/internal/memory/11?mode=DEINDEX&generation=4"))
        .andExpect(method(HttpMethod.DELETE)).andExpect(header("X-Request-Id",memoryRequest))
        .andRespond(withSuccess("""
            {"request_id":"memory-outbox-request-11","collection":"learning_memory","memory_id":11,
             "mode":"DEINDEX","generation":4,"deleted_count":1,"memory_index_epoch":"memory-index-epoch-test",
             "memory_rebuild_required":false}
            """,MediaType.APPLICATION_JSON));
    client.summarizeProfile(new AiLearningDtos.ProfileRequest(requestId, 1L, 7L, "old", List.of(
        new AiLearningDtos.Evidence("TUTOR",9L,"kp","evidence",Instant.parse("2026-08-20T00:00:00Z")))));
    client.upsertMemory(new AiLearningDtos.MemoryUpsertRequest(memoryRequest, 11L, 7L, 1L, "LEARNING_GOAL", "kp", "content", "a".repeat(64), "ACTIVE", 1, 3, "SYSTEM", 3L, Instant.parse("2026-08-20T00:00:00Z")));
    client.deleteMemory(memoryRequest,11L,"DEINDEX",4);
    server.verify();
  }

  @Test
  void profileBadGatewayAndMalformedSuccessAreInvalidResponses() {
    var request = profileRequest("profile-summary-request-8");
    server.expect(once(),requestTo("http://ai.test/internal/ai/profile/summarize"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    server.expect(once(),requestTo("http://ai.test/internal/ai/profile/summarize"))
        .andRespond(withSuccess("{\"request_id\":\"profile-summary-request-8\"}",MediaType.APPLICATION_JSON));
    assertThatThrownBy(()->client.summarizeProfile(request)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThatThrownBy(()->client.summarizeProfile(request)).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
  }

  @Test
  void profileUnavailableAndTransportTimeoutRemainDistinct() {
    var request=profileRequest("profile-summary-request-9");
    server.expect(once(),requestTo("http://ai.test/internal/ai/profile/summarize")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server.expect(once(),requestTo("http://ai.test/internal/ai/profile/summarize")).andRespond(withException(new SocketTimeoutException("read timeout")));
    assertThatThrownBy(()->client.summarizeProfile(request)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
    assertThatThrownBy(()->client.summarizeProfile(request)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
  }

  @Test
  void memoryStale422IsNeverSuccessAndDelete504IsTimeout() {
    String requestId="memory-outbox-request-12";
    server.expect(once(),requestTo("http://ai.test/internal/memory/upsert")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    server.expect(once(),requestTo("http://ai.test/internal/memory/12?mode=TERMINAL_DELETE&generation=2")).andExpect(method(HttpMethod.DELETE)).andExpect(header("X-Request-Id",requestId)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
    var request=new AiLearningDtos.MemoryUpsertRequest(requestId, 12L, 7L, 1L, "LEARNING_GOAL", null, "c", "b".repeat(64), "ACTIVE", 1, 2, "SYSTEM", null, Instant.now());
    assertThatThrownBy(()->client.upsertMemory(request)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThatThrownBy(()->client.deleteMemory(requestId,12L,"TERMINAL_DELETE",2)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
  }

  @Test
  void memoryDelete422AndMalformedEchoAreInvalidResponses() {
    String requestId="memory-outbox-request-13";
    server.expect(once(),requestTo("http://ai.test/internal/memory/13?mode=TERMINAL_DELETE&generation=2"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    server.expect(once(),requestTo("http://ai.test/internal/memory/13?mode=DEINDEX&generation=3"))
        .andRespond(withSuccess("""
            {"request_id":"wrong","collection":"learning_memory","memory_id":13,
             "mode":"DEINDEX","generation":3,"deleted_count":1,"memory_index_epoch":"memory-index-epoch-test",
             "memory_rebuild_required":false}
            """,MediaType.APPLICATION_JSON));
    assertThatThrownBy(()->client.deleteMemory(requestId,13L,"TERMINAL_DELETE",2))
        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    assertThatThrownBy(()->client.deleteMemory(requestId,13L,"DEINDEX",3))
        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
  }

  @Test
  void memorySearchUsesFrozenSnakeCaseAndReturnsIndexRecoverySignal() {
    String requestId="memory-search-request-14";
    server.expect(once(),requestTo("http://ai.test/internal/memory/search"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("X-Request-Id",requestId))
        .andExpect(jsonPath("$.request_id").value(requestId)).andExpect(jsonPath("$.student_id").value(7))
        .andExpect(jsonPath("$.knowledge_point_id").value("kp"))
        .andExpect(jsonPath("$.memory_types[0]").value("LEARNING_GOAL"))
        .andExpect(jsonPath("$.min_score").value(0.5))
        .andRespond(withSuccess("""
            {"request_id":"memory-search-request-14","student_id":7,"course_id":1,"collection":"learning_memory",
             "items":[{"memory_id":14,"student_id":7,"course_id":1,"memory_type":"LEARNING_GOAL",
             "knowledge_point_id":"kp","content":"goal","score":0.9,"version":1,"generation":2,
             "source_type":"SYSTEM","source_id":3}],"memory_index_epoch":"memory-index-epoch-search",
             "memory_rebuild_required":true}
            """,MediaType.APPLICATION_JSON));
    var response=client.searchMemory(new AiLearningDtos.MemorySearchRequest(requestId, 7L, 1L, "goal", "kp", List.of("LEARNING_GOAL"), 5, new BigDecimal("0.5")));
    assertThat(response.memoryRebuildRequired()).isTrue();
    assertThat(response.memoryIndexEpoch()).isEqualTo("memory-index-epoch-search");
    assertThat(response.items()).singleElement().satisfies(hit->assertThat(hit.generation()).isEqualTo(2));
    server.verify();
  }

  private AiLearningDtos.ProfileRequest profileRequest(String requestId) {
    return new AiLearningDtos.ProfileRequest(requestId, 1L, 7L, null, List.of(
        new AiLearningDtos.Evidence("TUTOR",1L,"kp","e",Instant.parse("2026-08-20T00:00:00Z"))));
  }

  @Test
  void sendsOnlyStructuredCodeExplainContractAndMapsFeedback() {
    CodeExplainRequest request =
        new CodeExplainRequest(
            "explain-request-1",
            41L,
            List.of("kp-1"),
            "print(input())",
            List.of(
                new CodeExplainRequest.CodeFailureInput(
                    1, "WRONG_ANSWER", "1", "2", "1", null)));
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/code/explain-result")))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "explain-request-1"))
        .andExpect(jsonPath("$.question_id").value(41))
        .andExpect(jsonPath("$.knowledge_point_ids[0]").value("kp-1"))
        .andExpect(jsonPath("$.failures[0].public_expected_output").value("2"))
        .andRespond(
            withSuccess(
                """
                    {"request_id":"explain-request-1","feedback":"检查边界条件。",
                     "suspected_knowledge_points":[],"model_version":"deterministic-v1",
                     "prompt_version":"code-explain-v1"}
                    """,
                MediaType.APPLICATION_JSON));

    assertThat(client.explainCodeResult(request).feedback()).isEqualTo("检查边界条件。");
    server.verify();
  }

  @Test
  void mapsGradingGatewayTimeoutToTypedTimeoutError() {
    AiGradeRequest.Subjective request =
        new AiGradeRequest.Subjective(
            "grading-task-request-71",
            71L,
            "answer",
            "reference",
            List.of(new AiGradeRequest.RubricItem("accuracy", "correctness", BigDecimal.TEN)),
            BigDecimal.TEN);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "grading-task-request-71"))
        .andExpect(jsonPath("$.request_id").value("grading-task-request-71"))
        .andExpect(jsonPath("$.submission_answer_id").value(71))
        .andExpect(jsonPath("$.answer_text").value("answer"))
        .andExpect(jsonPath("$.reference_answer").value("reference"))
        .andExpect(jsonPath("$.rubric_items[0].max_score").value(10))
        .andExpect(jsonPath("$.max_score").value(10))
        .andExpect(jsonPath("$.submissionAnswerId").doesNotExist())
        .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

    assertThatThrownBy(() -> client.suggestSubjectiveGrade(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
    server.verify();
  }

  @Test
  void mapsGradingBadGatewayAndMalformedSuccessToInvalidResponse() {
    AiGradeRequest.Subjective request = subjectiveGradeRequest("grading-task-request-73", 73L);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    assertThatThrownBy(() -> client.suggestSubjectiveGrade(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    server.verify();

    setUp();
    AiGradeRequest.Subjective malformedRequest = subjectiveGradeRequest("grading-task-request-74", 74L);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andRespond(withSuccess("{\"request_id\":", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.suggestSubjectiveGrade(malformedRequest))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    server.verify();
  }

  @Test
  void distinguishesGradingTransportTimeoutFromUnavailableConnection() {
    AiGradeRequest.Subjective timeoutRequest = subjectiveGradeRequest("grading-task-request-75", 75L);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andRespond(withException(new SocketTimeoutException("read timed out")));
    assertThatThrownBy(() -> client.suggestSubjectiveGrade(timeoutRequest))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
    server.verify();

    setUp();
    AiGradeRequest.Subjective unavailableRequest = subjectiveGradeRequest("grading-task-request-76", 76L);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andRespond(withException(new ConnectException("connection refused")));
    assertThatThrownBy(() -> client.suggestSubjectiveGrade(unavailableRequest))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void sendsFrozenAttachmentGradingWireContract() {
    AiGradeRequest.Attachment request =
        new AiGradeRequest.Attachment(
            "grading-task-request-77",
            77L,
            List.of("http://business.test/api/files/5/content"),
            List.of(new AiGradeRequest.RubricItem("content", "coverage", BigDecimal.TEN)),
            BigDecimal.TEN);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/attachment/grade")))
        .andExpect(header("X-Internal-Token", "internal-test-token"))
        .andExpect(header("X-Request-Id", "grading-task-request-77"))
        .andExpect(jsonPath("$.request_id").value("grading-task-request-77"))
        .andExpect(jsonPath("$.submission_answer_id").value(77))
        .andExpect(jsonPath("$.attachment_download_urls[0]").value("http://business.test/api/files/5/content"))
        .andExpect(jsonPath("$.rubric_items[0].max_score").value(10))
        .andExpect(jsonPath("$.max_score").value(10))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    assertThatThrownBy(() -> client.gradeAttachment(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
    server.verify();
  }

  @Test
  void mapsGradingServiceUnavailableToTypedUnavailableError() {
    AiGradeRequest.Subjective request =
        new AiGradeRequest.Subjective(
            "grading-task-request-72",
            72L,
            "answer",
            "reference",
            List.of(new AiGradeRequest.RubricItem("accuracy", "correctness", BigDecimal.TEN)),
            BigDecimal.TEN);
    server
        .expect(once(), requestTo(URI.create("http://ai.test/internal/ai/subjective/suggest-grade")))
        .andExpect(header("X-Request-Id", "grading-task-request-72"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> client.suggestSubjectiveGrade(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void rejectsSandboxPayloadBeyondAggregateBudget() {
    String field = "x".repeat(65_536);
    List<SandboxRunRequest.SandboxTestCase> cases =
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(
                index ->
                    new SandboxRunRequest.SandboxTestCase(
                        (long) index,
                        field,
                        field,
                        BigDecimal.ONE,
                        "PUBLIC",
                        "EXACT",
                        null))
            .toList();

    assertThatThrownBy(
            () ->
                new SandboxRunRequest(
                    "too-large", "PUBLIC_RUN", "PYTHON_3_11", "print(1)", cases,
                    new SandboxRunRequest.SandboxLimits(1000, 128, 256)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2 MiB");
  }

  @Test
  void sandboxClientTimeoutCoversMaximumFrozenContractBudget() {
    com.education.agent.config.InternalServiceClientConfig config =
        new com.education.agent.config.InternalServiceClientConfig();
    RestClient sandbox = config.aiSandboxRestClient("http://ai-timeout.test", 1020);
    Object requestFactory = ReflectionTestUtils.getField(sandbox, "clientRequestFactory");
    assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
    Integer readTimeout = (Integer) ReflectionTestUtils.getField(requestFactory, "readTimeout");
    assertThat(readTimeout).isGreaterThanOrEqualTo(1_005_000);
  }

  private AiTutorChatRequest request() {
    return new AiTutorChatRequest(1L, "Python程序设计", List.of("EXPLAIN", "QUIZ", "CODE", "VISUAL"), "request-1", 7L, 9L, "kp-1", "变量是什么？", List.of(), Map.of());
  }

  private AiGradeRequest.Subjective subjectiveGradeRequest(String requestId, Long answerId) {
    return new AiGradeRequest.Subjective(
        requestId,
        answerId,
        "answer",
        "reference",
        List.of(new AiGradeRequest.RubricItem("accuracy", "correctness", BigDecimal.TEN)),
        BigDecimal.TEN);
  }
  @Test void teachingPlanningUsesInternalHeadersAndFrozenPath() throws Exception {
    org.slf4j.MDC.put("requestId","teaching-plan-test");
    try {
      server.expect(requestTo("http://ai.test/internal/ai/smart-companion/plan"))
          .andExpect(method(HttpMethod.POST)).andExpect(header("X-Internal-Token","internal-test-token"))
          .andExpect(header("X-Request-Id","teaching-plan-test")).andExpect(jsonPath("$.scope_type").value("CLASS"))
          .andRespond(withSuccess("{\"provider\":\"fixture\",\"steps\":[]}",MediaType.APPLICATION_JSON));
      var request=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"scope_type\":\"CLASS\"}");
      assertThat(client.planSmartCompanion(request).path("provider").asText()).isEqualTo("fixture");server.verify();
    } finally { org.slf4j.MDC.remove("requestId"); }
  }
  @Test void teachingPlanningUnavailableNeverReturnsFallback() throws Exception {
    server.expect(requestTo("http://ai.test/internal/ai/smart-companion/plan")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(()->client.planSmartCompanion(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
      .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));server.verify();
  }
}
