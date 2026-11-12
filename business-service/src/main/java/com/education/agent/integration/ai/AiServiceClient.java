package com.education.agent.integration.ai;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AiServiceClient {

  private static final int MAX_TUTOR_RESPONSE_BYTES = 512 * 1024;
  private static final ObjectMapper STRICT_TUTOR_JSON = JsonMapper.builder()
      .findAndAddModules()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
  private static final Set<String> TRACE_FIELDS = Set.of(
      "requested_mode", "effective_mode", "fallback_reason", "tool_invocations", "total_steps");
  private static final Set<String> INVOCATION_FIELDS = Set.of(
      "step", "tool", "reason_code", "status", "duration_ms", "item_count", "error_code");

  private final RestClient tutorClient;
  private final RestClient assessmentClient;
  private final RestClient sandboxClient;
  private final String internalToken;

  @Autowired
  public AiServiceClient(
      @Qualifier("aiTutorRestClient") RestClient tutorClient,
      @Qualifier("aiAssessmentRestClient") RestClient assessmentClient,
      @Qualifier("aiSandboxRestClient") RestClient sandboxClient,
      @Value("${education.ai-service.internal-token:}") String internalToken) {
    this.tutorClient = tutorClient;
    this.assessmentClient = assessmentClient;
    this.sandboxClient = sandboxClient;
    this.internalToken = internalToken;
  }

  public AiServiceClient(RestClient client, String internalToken) {
    this(client, client, client, internalToken);
  }

  public AiTutorChatResponse tutorChat(AiTutorChatRequest request) {
    try {
      String raw = tutorClient
          .post()
          .uri("/internal/ai/tutor/chat")
          .headers(headers -> internalHeaders(headers, request.requestId()))
          .body(request)
          .retrieve()
          .body(String.class);
      if (raw == null || raw.isBlank()
          || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TUTOR_RESPONSE_BYTES) {
        throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "AI 导师响应为空或超过字节预算");
      }
      JsonNode root = STRICT_TUTOR_JSON.readTree(raw);
      rejectUnsafeTraceExtensions(root == null ? null : root.get("agent_trace"));
      return STRICT_TUTOR_JSON.readValue(raw, AiTutorChatResponse.class);
    } catch (JsonProcessingException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_INVALID_RESPONSE,
          "AI 导师返回了未声明或无法解析的字段",
          Map.of("cause", exception.getClass().getSimpleName()));
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(
          ErrorCode.AI_RATE_LIMITED,
          "AI 导师请求过于频繁，请稍后重试",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_TIMEOUT,
          "AI 导师响应超时，请使用相同问题重试",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (ResourceAccessException exception) {
      if (causedBySocketTimeout(exception)) {
        throw new BusinessException(
            ErrorCode.AI_SERVICE_TIMEOUT,
            "AI 导师响应超时，请使用相同问题重试");
      }
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 导师服务暂时不可用",
          Map.of("cause", exception.getClass().getSimpleName()));
    } catch (RestClientResponseException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 导师服务暂时不可用",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 导师服务暂时不可用",
          Map.of("cause", exception.getClass().getSimpleName()));
    }
  }

  public AiAssessmentParseResponse parseAssessment(AiAssessmentParseRequest request) {
    try {
      return assessmentClient
          .post()
          .uri("/internal/ai/assessment/parse")
          .headers(headers -> internalHeaders(headers, request.requestId()))
          .body(request)
          .retrieve()
          .body(AiAssessmentParseResponse.class);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(
          ErrorCode.AI_RATE_LIMITED,
          "AI 题目解析请求过于频繁，请稍后重试",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (HttpClientErrorException.UnprocessableEntity exception) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "AI 服务无法从该文件解析题目",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientResponseException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 题目解析服务暂时不可用",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 题目解析服务暂时不可用",
          Map.of("cause", exception.getClass().getSimpleName()));
    }
  }

  public SandboxRunResponse runSandbox(SandboxRunRequest request) {
    try {
      return sandboxClient
          .post()
          .uri("/internal/code/run")
          .headers(headers -> internalHeaders(headers, request.requestId()))
          .body(request)
          .retrieve()
          .body(SandboxRunResponse.class);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_RATE_LIMITED, "代码沙箱繁忙，请稍后重试");
    } catch (HttpClientErrorException.UnprocessableEntity exception) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "代码沙箱拒绝了运行参数");
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_TIMEOUT, "代码运行超时");
    } catch (ResourceAccessException exception) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_TIMEOUT, "代码运行超时");
    } catch (RestClientResponseException exception) {
      throw new BusinessException(
          ErrorCode.CODE_SANDBOX_UNAVAILABLE,
          "代码沙箱暂时不可用",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientException exception) {
      throw new BusinessException(ErrorCode.CODE_SANDBOX_UNAVAILABLE, "代码沙箱暂时不可用");
    }
  }

  private void rejectUnsafeTraceExtensions(JsonNode trace) {
    if (trace == null || trace.isNull()) return;
    if (!trace.isObject()) {
      throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "agent_trace 必须是对象");
    }
    trace.fieldNames().forEachRemaining(field -> {
      if (!TRACE_FIELDS.contains(field)) {
        throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,
            "agent_trace 包含未声明字段", Map.of("field", field));
      }
    });
    JsonNode invocations = trace.get("tool_invocations");
    if (invocations != null && invocations.isArray()) {
      invocations.forEach(invocation -> {
        if (!invocation.isObject()) {
          throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "工具调用摘要必须是对象");
        }
        invocation.fieldNames().forEachRemaining(field -> {
          if (!INVOCATION_FIELDS.contains(field)) {
            throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,
                "工具调用摘要包含未声明字段", Map.of("field", field));
          }
        });
      });
    }
  }

  public GraphIngestionInternalDtos.ExtractReviewResponse extractAndReviewGraph(
      GraphIngestionInternalDtos.ExtractReviewRequest request) {
    try {
      return assessmentClient.post()
          .uri("/internal/ai/graph/extract-review")
          .headers(headers -> internalHeaders(headers, request.requestId()))
          .body(request)
          .retrieve()
          .body(GraphIngestionInternalDtos.ExtractReviewResponse.class);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "知识图谱抽取服务繁忙，请稍后重试");
    } catch (HttpClientErrorException.UnprocessableEntity exception) {
      throw new BusinessException(ErrorCode.GRAPH_EXTRACTION_FAILED, "AI 无法从该来源抽取知识图谱候选");
    } catch (HttpServerErrorException.BadGateway exception) {
      String upstreamCode = graphIngestionErrorCode(exception);
      if ("GRAPH_EXTRACTOR_INVALID_RESPONSE".equals(upstreamCode)) {
        throw new BusinessException(ErrorCode.GRAPH_EXTRACTION_FAILED, "AI 无法从该来源抽取知识图谱候选");
      }
      if ("GRAPH_REVIEWER_INVALID_RESPONSE".equals(upstreamCode)) {
        throw new BusinessException(ErrorCode.GRAPH_REVIEW_FAILED, "AI 图谱独立复核未能完成");
      }
      throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "知识图谱抽取与审查服务返回了无效响应",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "知识图谱抽取与审查超时");
    } catch (ResourceAccessException exception) {
      if (causedBySocketTimeout(exception)) {
        throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "知识图谱抽取与审查超时");
      }
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "知识图谱抽取服务暂时不可用");
    } catch (RestClientResponseException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "知识图谱抽取服务暂时不可用",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "知识图谱抽取服务暂时不可用");
    }
  }

  private String graphIngestionErrorCode(HttpServerErrorException.BadGateway exception) {
    try {
      JsonNode root = STRICT_TUTOR_JSON.readTree(exception.getResponseBodyAsByteArray());
      JsonNode code = root == null ? null : root.path("detail").path("code");
      return code != null && code.isTextual() ? code.textValue() : null;
    } catch (IOException | IllegalArgumentException ignored) {
      return null;
    }
  }

  public AiLearningDtos.ProfileResponse summarizeProfile(AiLearningDtos.ProfileRequest request) {
    AiLearningDtos.ProfileResponse response = postLearning("/internal/ai/profile/summarize", request.requestId(), request,
        AiLearningDtos.ProfileResponse.class, "学习摘要");
    if (response == null || !request.requestId().equals(response.requestId())
        || !java.util.Objects.equals(request.courseId(), response.courseId())
        || response.summary() == null || response.summary().isBlank()
        || response.cognitiveLabels() == null || response.memoryCandidates() == null
        || response.resourceSuggestions() == null || response.modelVersion() == null
        || response.modelVersion().isBlank() || response.promptVersion() == null
        || response.promptVersion().isBlank()) throw invalidLearning("学习摘要");
    return response;
  }

  public AiLearningDtos.MemoryUpsertResponse upsertMemory(AiLearningDtos.MemoryUpsertRequest request) {
    AiLearningDtos.MemoryUpsertResponse response = postLearning("/internal/memory/upsert", request.requestId(), request,
        AiLearningDtos.MemoryUpsertResponse.class, "记忆索引");
    if (response == null || !request.requestId().equals(response.requestId())
        || !"learning_memory".equals(response.collection())
        || !request.memoryId().equals(response.memoryId())
        || !request.generation().equals(response.generation()) || !Boolean.TRUE.equals(response.indexed())
        || response.embeddingModel() == null || response.embeddingModel().isBlank()
        || invalidIndexSignal(response.memoryIndexEpoch(),response.memoryRebuildRequired(),false))
      throw invalidLearning("记忆索引");
    return response;
  }

  public AiLearningDtos.MemorySearchResponse searchMemory(AiLearningDtos.MemorySearchRequest request) {
    AiLearningDtos.MemorySearchResponse response = postLearning("/internal/memory/search", request.requestId(), request,
        AiLearningDtos.MemorySearchResponse.class, "记忆检索");
    if (response == null || !request.requestId().equals(response.requestId())
        || !request.studentId().equals(response.studentId()) || response.items() == null
        || !java.util.Objects.equals(request.courseId(), response.courseId())
        || !"learning_memory".equals(response.collection())
        || invalidIndexSignal(response.memoryIndexEpoch(),response.memoryRebuildRequired(),false)) throw invalidLearning("记忆检索");
    Set<Long> ids = new HashSet<>();
    for (AiLearningDtos.MemorySearchHit hit : response.items()) {
      if (hit == null || hit.memoryId() == null || !request.studentId().equals(hit.studentId())
          || !java.util.Objects.equals(request.courseId(), hit.courseId())
          || hit.memoryType() == null || hit.memoryType().isBlank() || hit.content() == null
          || hit.content().isBlank() || hit.score() == null || hit.score().signum() < 0
          || hit.score().compareTo(BigDecimal.ONE) > 0 || hit.version() == null || hit.version() < 1
          || hit.generation() == null || hit.generation() < 1 || !ids.add(hit.memoryId()))
        throw invalidLearning("记忆检索");
    }
    return response;
  }

  public AiLearningDtos.DeleteResponse deleteMemory(String requestId, Long memoryId, String mode, int generation) {
    try {
      AiLearningDtos.DeleteResponse response = tutorClient.delete().uri(builder -> builder
              .path("/internal/memory/{id}").queryParam("mode", mode)
              .queryParam("generation", generation).build(memoryId))
          .headers(headers -> internalHeaders(headers, requestId)).retrieve()
          .body(AiLearningDtos.DeleteResponse.class);
      if (response == null || !requestId.equals(response.requestId())
          || !"learning_memory".equals(response.collection()) || !memoryId.equals(response.memoryId())
          || !mode.equals(response.mode()) || !Integer.valueOf(generation).equals(response.generation())
          || response.deletedCount() == null || response.deletedCount() < 0
          || invalidIndexSignal(response.memoryIndexEpoch(),response.memoryRebuildRequired(),false))
        throw invalidLearning("记忆索引删除");
      return response;
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "记忆索引删除超时");
    } catch (HttpClientErrorException.UnprocessableEntity | HttpServerErrorException.BadGateway exception) {
      throw invalidLearning("记忆索引删除");
    } catch (ResourceAccessException exception) {
      if (causedBySocketTimeout(exception)) throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "记忆索引删除超时");
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "记忆索引服务暂时不可用");
    } catch (RestClientResponseException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "记忆索引服务暂时不可用");
    } catch (RestClientException exception) {
      throw invalidLearning("记忆索引删除");
    }
  }

  public com.fasterxml.jackson.databind.JsonNode planSmartCompanion(com.fasterxml.jackson.databind.JsonNode request) {
    try { return postLearning("/internal/ai/smart-companion/plan", com.education.agent.common.web.RequestIds.current(), request, com.fasterxml.jackson.databind.JsonNode.class, "教学方案"); }
    catch (BusinessException e) { if(e.errorCode()==ErrorCode.AI_SERVICE_TIMEOUT||e.errorCode()==ErrorCode.AI_RATE_LIMITED) throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"教学方案暂不可用，请稍后重试"); throw e; }
  }

  public com.fasterxml.jackson.databind.JsonNode explainSmartCompanion(com.fasterxml.jackson.databind.JsonNode request) {
    try {
      return postLearning("/internal/ai/smart-companion/explain", com.education.agent.common.web.RequestIds.current(), request,
          com.fasterxml.jackson.databind.JsonNode.class, "学情解释");
    } catch (BusinessException exception) {
      if (exception.errorCode() == ErrorCode.AI_SERVICE_TIMEOUT || exception.errorCode() == ErrorCode.AI_RATE_LIMITED)
        throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "学情解释暂时不可用，请稍后重试；原统计保留");
      throw exception;
    }
  }

  private <T> T postLearning(String uri, String requestId, Object body, Class<T> type, String scene) {
    try {
      return tutorClient.post().uri(uri).headers(headers -> internalHeaders(headers, requestId))
          .body(body).retrieve().body(type);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(ErrorCode.AI_RATE_LIMITED, scene + "服务繁忙，请稍后重试");
    } catch (HttpClientErrorException.UnprocessableEntity exception) {
      throw invalidLearning(scene);
    } catch (HttpServerErrorException.BadGateway exception) {
      throw invalidLearning(scene);
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, scene + "服务超时");
    } catch (ResourceAccessException exception) {
      if (causedBySocketTimeout(exception)) throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, scene + "服务超时");
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, scene + "服务暂时不可用");
    } catch (RestClientResponseException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, scene + "服务暂时不可用");
    } catch (RestClientException exception) {
      throw invalidLearning(scene);
    }
  }

  private BusinessException invalidLearning(String scene) {
    return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, scene + "服务返回无效响应");
  }

  public boolean invalidIndexSignal(String epoch,Boolean rebuildRequired,boolean allowUnused){if(rebuildRequired==null)return true;if(epoch==null)return !allowUnused||rebuildRequired;return epoch.length()<16||epoch.length()>64;}

  public CodeExplainResponse explainCodeResult(CodeExplainRequest request) {
    try {
      return assessmentClient
          .post()
          .uri("/internal/ai/code/explain-result")
          .headers(headers -> internalHeaders(headers, request.requestId()))
          .body(request)
          .retrieve()
          .body(CodeExplainResponse.class);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "代码教学解释请求过于频繁，请稍后重试");
    } catch (RestClientResponseException | ResourceAccessException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "代码教学解释服务暂时不可用",
          Map.of("cause", exception.getClass().getSimpleName()));
    } catch (RestClientException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "代码教学解释服务暂时不可用");
    }
  }

  public AiGradeResponse gradeAttachment(AiGradeRequest.Attachment request) {
    return grade("/internal/ai/attachment/grade", request.requestId(), request);
  }

  public AiTutorQuizGrading.Response suggestTutorQuizGrade(AiTutorQuizGrading.Request request) {
    return grade("/internal/ai/tutor/quiz/suggest-grade", request.requestId(), request,
        AiTutorQuizGrading.Response.class);
  }

  public AiGradeResponse suggestSubjectiveGrade(AiGradeRequest.Subjective request) {
    return grade("/internal/ai/subjective/suggest-grade", request.requestId(), request);
  }

  private AiGradeResponse grade(String uri, String requestId, Object request) {
    return grade(uri, requestId, request, AiGradeResponse.class);
  }

  private <T> T grade(String uri, String requestId, Object request, Class<T> responseType) {
    try {
      return assessmentClient
          .post()
          .uri(uri)
          .headers(headers -> internalHeaders(headers, requestId))
          .body(request)
          .retrieve()
          .body(responseType);
    } catch (HttpClientErrorException.TooManyRequests exception) {
      throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "AI 批改服务繁忙，请稍后重试");
    } catch (HttpClientErrorException.UnprocessableEntity exception) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "AI 批改请求不符合约束");
    } catch (HttpServerErrorException.BadGateway exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "AI 批改服务返回无效响应");
    } catch (HttpServerErrorException.GatewayTimeout exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "AI 批改服务超时");
    } catch (ResourceAccessException exception) {
      if (causedBySocketTimeout(exception)) {
        throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "AI 批改服务超时");
      }
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 批改服务暂时不可用");
    } catch (RestClientResponseException exception) {
      throw new BusinessException(
          ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 批改服务暂时不可用",
          Map.of("upstreamStatus", exception.getStatusCode().value()));
    } catch (RestClientException exception) {
      throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "AI 批改服务返回无效响应");
    }
  }

  private boolean causedBySocketTimeout(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException) return true;
    }
    return false;
  }

  private void internalHeaders(HttpHeaders headers, String requestId) {
    headers.set("X-Internal-Token", internalToken);
    headers.set("X-Request-Id", requestId);
  }
}
