package com.education.agent.integration.knowledge;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.common.web.RequestIds;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.knowledge.dto.KnowledgeGraphView;
import com.education.agent.knowledge.dto.KnowledgeNodeView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Component
public class KnowledgeServiceClient {

    private final RestClient client;
    private final String internalToken;

    public KnowledgeServiceClient(
            @Qualifier("knowledgeServiceRestClient") RestClient client,
            @Value("${education.knowledge-service.internal-token:}") String internalToken
    ) {
        this.client = client;
        this.internalToken = internalToken;
    }

    public com.fasterxml.jackson.databind.JsonNode learningContent(Long courseId, String indicator) {
        try {
            var card = client.get().uri(b -> b.path("/internal/graph/learning-content/{id}")
                .queryParam("course_id", courseId).build(indicator))
                .headers(this::internalHeaders).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            if (card == null || !"APPROVED".equals(card.path("reviewStatus").asText())
                || !indicator.equals(card.path("id").asText()) || card.path("sources").isEmpty()
                || card.path("content").asText().isBlank()) throw invalidResponse();
            return card;
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "No approved learning content");
        } catch (RestClientException e) { throw unavailable(e); }
    }

    public List<KnowledgeNodeView> search(Long courseId, String query, int limit) {
        try {
            InternalGraphSearchResponse response = client.get()
                    .uri(builder -> builder
                            .path("/internal/graph/search").queryParam("course_id", courseId)
                            .queryParam("q", query)
                            .queryParam("limit", limit)
                            .build())
                    .headers(headers -> internalHeaders(headers))
                    .retrieve()
                    .body(InternalGraphSearchResponse.class);
            if (response == null || response.items() == null
                    || response.items().stream().anyMatch(node -> !validSearchNode(node))) {
                throw invalidResponse();
            }
            return response.items().stream().map(InternalGraphNode::toPublic).toList();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识点不存在");
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    public KnowledgeGraphView subgraph(Long courseId, String rootId, int depth) {
        try {
            InternalGraphSubgraphResponse response = client.get()
                    .uri(builder -> builder
                            .path("/internal/graph/subgraph").queryParam("course_id", courseId)
                            .queryParamIfPresent("center_id", java.util.Optional.ofNullable(rootId))
                            .queryParam("depth", depth)
                            .queryParam("relations", "contains,requires,related")
                            .build())
                    .headers(headers -> internalHeaders(headers))
                    .retrieve()
                    .body(InternalGraphSubgraphResponse.class);
            if (response == null || !courseId.equals(response.courseId()) || response.nodes() == null || response.edges() == null
                    || response.nodes().stream().anyMatch(node -> !validApprovedNode(node))) {
                throw invalidResponse();
            }
            Set<String> nodeIds = new HashSet<>();
            for (InternalGraphNode node : response.nodes()) {
                if (!nodeIds.add(node.id())) throw invalidResponse();
            }
            if (response.edges().stream().anyMatch(edge -> !validApprovedEdge(edge, nodeIds))) {
                throw invalidResponse();
            }
            List<KnowledgeNodeView> nodes = response.nodes().stream().map(InternalGraphNode::toPublic).toList();
            var edges = response.edges().stream().map(InternalGraphEdge::toPublic).toList();
            return new KnowledgeGraphView(nodes, edges);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识点不存在");
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * Resolves the approved Graph-RAG context for a knowledge point. The graph
     * service deliberately returns 404 for pending or rejected nodes, so callers
     * can use this method as both an existence and publication check.
     */
    public InternalGraphRagContextResponse ragContext(Long courseId, String nodeId) {
        try {
            InternalGraphRagContextResponse response = client.get()
                    .uri(builder -> builder
                            .path("/internal/graph/rag-context/{nodeId}").queryParam("course_id", courseId)
                            .queryParam("max_related", 8)
                            .build(nodeId))
                    .headers(this::internalHeaders)
                    .retrieve()
                    .body(InternalGraphRagContextResponse.class);
            requireCourse(courseId,response == null ? null : response.courseId());
            return ApprovedGraphContextValidator.require(nodeId,response);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "知识点尚未审核发布或不存在"
            );
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    public KnowledgeRecommendationDtos.Response recommendNext(
            Long courseId, Long studentId, List<KnowledgeRecommendationDtos.MasteryInput> mastery, int limit) {
        try {
            KnowledgeRecommendationDtos.Response response = client.post()
                    .uri(builder -> builder.path("/internal/graph/recommend-next").queryParam("course_id", courseId).build())
                    .headers(this::internalHeaders)
                    .body(new KnowledgeRecommendationDtos.Request(studentId, mastery, limit))
                    .retrieve()
                    .body(KnowledgeRecommendationDtos.Response.class);
            if (response == null || !courseId.equals(response.courseId()) || response.items() == null
                    || response.graphVersion() == null || response.graphVersion().isBlank()) {
                throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "知识图谱推荐响应无效");
            }
            return response;
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    public GraphIngestionInternalDtos.BatchUpsertResponse upsertIngestionBatch(
            Long courseId, String requestId, GraphIngestionInternalDtos.BatchUpsertRequest request) {
        try {
            var response = client.post().uri(builder -> builder.path("/internal/graph/ingestion-batches").queryParam("course_id", courseId).build())
                    .headers(headers -> internalHeaders(headers, requestId))
                    .body(request).retrieve().body(GraphIngestionInternalDtos.BatchUpsertResponse.class);
            requireCourse(courseId, response == null ? null : response.batch() == null ? null : response.batch().courseId());
            return response;
        } catch (HttpClientErrorException.Conflict exception) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "图谱草稿基准版本已变化");
        } catch (HttpClientErrorException.UnprocessableEntity exception) {
            throw new BusinessException(ErrorCode.GRAPH_DRAFT_VALIDATION_FAILED, "图谱候选未通过确定性校验");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱草稿服务暂时不可用");
        }
    }

    public GraphIngestionInternalDtos.BatchDetail getIngestionBatch(Long courseId, String requestId, String batchId) {
        try {
            var response = client.get().uri(builder -> builder.path("/internal/graph/ingestion-batches/{batchId}").queryParam("course_id", courseId).build(batchId))
                    .headers(headers -> internalHeaders(headers, requestId))
                    .retrieve().body(GraphIngestionInternalDtos.BatchDetail.class);
            requireCourse(courseId, response == null ? null : response.courseId());
            return response;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "知识图谱草稿批次不存在");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱草稿服务暂时不可用");
        }
    }

    public GraphIngestionInternalDtos.CandidatePage getIngestionCandidates(
            Long courseId, String requestId, String batchId, int limit, String cursor,
            String decisionStatus, String candidateType, String reviewLane) {
        try {
            var response = client.get().uri(builder -> {
                        var uri = builder.path("/internal/graph/ingestion-batches/{batchId}/candidates").queryParam("course_id", courseId)
                                .queryParam("limit", limit);
                        if (cursor != null && !cursor.isBlank()) uri.queryParam("cursor", cursor);
                        if (decisionStatus != null && !decisionStatus.isBlank()) uri.queryParam("decision_status", decisionStatus);
                        if (candidateType != null && !candidateType.isBlank()) uri.queryParam("type", candidateType);
                        if (reviewLane != null && !reviewLane.isBlank()) uri.queryParam("review_lane", reviewLane);
                        return uri.build(batchId);
                    }).headers(headers -> internalHeaders(headers, requestId))
                    .retrieve().body(GraphIngestionInternalDtos.CandidatePage.class);
            return response;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.GRAPH_INGESTION_JOB_NOT_FOUND, "知识图谱草稿批次不存在");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱候选服务暂时不可用");
        }
    }

    public GraphIngestionInternalDtos.BatchDetail decideIngestionCandidates(
            Long courseId, String requestId, String batchId, GraphIngestionInternalDtos.DecisionsRequest request) {
        try {
            var response = client.post().uri(builder -> builder.path("/internal/graph/ingestion-batches/{batchId}/decisions").queryParam("course_id", courseId).build(batchId))
                    .headers(headers -> internalHeaders(headers, requestId))
                    .body(request).retrieve().body(GraphIngestionInternalDtos.BatchDetail.class);
            requireCourse(courseId, response == null ? null : response.courseId());
            return response;
        } catch (HttpClientErrorException.Conflict exception) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "图谱草稿已被其他审核操作更新");
        } catch (HttpClientErrorException.UnprocessableEntity exception) {
            throw new BusinessException(ErrorCode.GRAPH_DRAFT_VALIDATION_FAILED, "图谱审核决策未通过校验");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱审核服务暂时不可用");
        }
    }

    public GraphIngestionInternalDtos.PublishResponse publishIngestionBatch(
            Long courseId, String requestId, String batchId, GraphIngestionInternalDtos.PublishRequest request) {
        try {
            var response = client.post().uri(builder -> builder.path("/internal/graph/ingestion-batches/{batchId}/publish").queryParam("course_id", courseId).build(batchId))
                    .headers(headers -> internalHeaders(headers, requestId))
                    .body(request).retrieve().body(GraphIngestionInternalDtos.PublishResponse.class);
            requireCourse(courseId, response == null ? null : response.courseId());
            return response;
        } catch (HttpClientErrorException.Conflict exception) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "发布时图谱或草稿版本发生变化");
        } catch (HttpClientErrorException.UnprocessableEntity exception) {
            throw new BusinessException(ErrorCode.GRAPH_HUMAN_REVIEW_REQUIRED, "全部候选必须完成人工审核后才能发布");
        } catch (HttpServerErrorException.BadGateway exception) {
            throw new BusinessException(ErrorCode.GRAPH_PUBLISH_FAILED, "图谱发布失败");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱发布服务暂时不可用");
        }
    }

    public GraphIngestionInternalDtos.RollbackResponse rollbackRelease(
            Long courseId, String requestId, String graphVersion, GraphIngestionInternalDtos.RollbackRequest request) {
        try {
            var response = client.post().uri(builder -> builder.path("/internal/graph/releases/{graphVersion}/rollback").queryParam("course_id", courseId).build(graphVersion))
                    .headers(headers -> internalHeaders(headers, requestId))
                    .body(request).retrieve().body(GraphIngestionInternalDtos.RollbackResponse.class);
            requireCourse(courseId, response == null ? null : response.courseId());
            return response;
        } catch (HttpClientErrorException.Conflict exception) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT, "当前图谱版本已变化，不能回滚");
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待回滚的图谱版本不存在");
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱回滚服务暂时不可用");
        }
    }

    public String currentGraphVersion(Long courseId, String requestId) {
        try {
            GraphIngestionInternalDtos.GraphVersion response = client.get()
                    .uri(builder -> builder.path("/internal/graph/version").queryParam("course_id", courseId).build())
                    .headers(headers -> internalHeaders(headers, requestId))
                    .retrieve().body(GraphIngestionInternalDtos.GraphVersion.class);
            if (response == null || !courseId.equals(response.courseId()) || response.graphVersion() == null
                    || response.graphVersion().isBlank() || response.graphVersion().length() > 100) {
                throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "图谱版本响应无效");
            }
            return response.graphVersion();
        } catch (RestClientException exception) {
            throw graphIngestionUnavailable(exception, "图谱版本服务暂时不可用");
        }
    }

    private void requireCourse(Long expected,Long actual){if(expected==null||!expected.equals(actual))throw invalidResponse();}

    private void internalHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set("X-Internal-Token", internalToken);
        headers.set("X-Request-Id", RequestIds.current());
    }

    private void internalHeaders(org.springframework.http.HttpHeaders headers, String requestId) {
        headers.set("X-Internal-Token", internalToken);
        headers.set("X-Request-Id", requestId);
    }

    private BusinessException graphIngestionUnavailable(RestClientException exception, String message) {
        if (exception instanceof RestClientResponseException response) {
            return new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, message,
                    java.util.Map.of("upstreamStatus", response.getStatusCode().value()));
        }
        return new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, message,
                java.util.Map.of("cause", exception.getClass().getSimpleName()));
    }

    private boolean validSearchNode(InternalGraphNode node) {
        return validApprovedNode(node);
    }

    private boolean validApprovedNode(InternalGraphNode node) {
        if (node == null || blank(node.id()) || blank(node.name()) || node.level() == null || node.level() < 0
                || blank(node.nodeType()) || !Set.of("course", "module", "chapter", "section", "knowledge_point").contains(node.nodeType())
                || !"APPROVED".equals(node.reviewStatus()) || node.contentVersion() == null || node.contentVersion() < 1) {
            return false;
        }
        return validStrings(node.aliases()) && validStrings(node.keywords())
                && validStrings(node.learningObjectives()) && validStrings(node.commonMisconceptions());
    }

    private boolean validApprovedEdge(InternalGraphEdge edge, Set<String> nodeIds) {
        return edge != null && !blank(edge.sourceId()) && !blank(edge.targetId()) && !blank(edge.relation())
                && Set.of("contains", "requires", "related").contains(edge.relation())
                && "APPROVED".equals(edge.reviewStatus())
                && !Boolean.FALSE.equals(edge.reviewed())
                && nodeIds.contains(edge.sourceId()) && nodeIds.contains(edge.targetId());
    }

    private boolean validStrings(List<String> values) {
        return values == null || values.stream().noneMatch(this::blank);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException invalidResponse() {
        return new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "知识图谱服务返回无效响应");
    }

    private BusinessException unavailable(RestClientException exception) {
        return new BusinessException(
                ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                "知识图谱服务暂不可用",
                java.util.Map.of("cause", exception.getClass().getSimpleName())
        );
    }
}
