package com.education.agent.graphingestion.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public class GraphIngestionBoundaryValidator {
    static final int MAX_NODES = 200;
    static final int MAX_EDGES = 500;
    static final int MAX_SOURCES = 500;
    private static final int MAX_BATCH = MAX_NODES + MAX_EDGES + MAX_SOURCES;
    private static final int MAX_ITEM_BYTES = 65_536;
    private static final int MAX_BATCH_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FIELDS = 300;
    private static final Set<String> TYPES = Set.of("NODE", "EDGE", "SOURCE");
    private static final Set<String> DECISIONS = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> LANES = Set.of("FAST_TRACK", "STANDARD", "REJECT_RECOMMENDED");
    private static final Set<String> BATCH_STATES =
            Set.of("REVIEW_REQUIRED", "READY_TO_PUBLISH", "PUBLISHED");
    private static final Set<String> MANAGED =
            Set.of("decision_status", "review_lane", "decision_comment", "decided_by", "decided_at");
    private static final Set<String> NODE_FIELDS = Set.of(
            "candidate_id", "id", "name", "level", "node_type", "parent_id", "description",
            "aliases", "keywords", "difficulty", "importance", "estimated_minutes",
            "learning_objectives", "common_misconceptions", "example_code", "rights_status",
            "evidence", "extractor_confidence", "reviewer_confidence", "review_status",
            "decision_status", "review_lane", "decision_comment", "decided_by", "decided_at");
    private static final Set<String> EDGE_FIELDS = Set.of(
            "candidate_id", "source_id", "target_id", "relation", "evidence",
            "extractor_confidence", "reviewer_confidence", "review_status", "decision_status",
            "review_lane", "decision_comment", "decided_by", "decided_at");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "candidate_id", "id", "node_id", "source_type", "title", "resource_id", "file_id",
            "chapter", "page_from", "page_to", "locator", "excerpt", "rights_status", "extractor_confidence",
            "reviewer_confidence", "review_status", "decision_status", "review_lane",
            "decision_comment", "decided_by", "decided_at");

    private final ObjectMapper json;

    public GraphIngestionBoundaryValidator(ObjectMapper json) {
        this.json = json;
    }

    public ValidatedExtraction validateExtraction(
            Long jobId, String requestId, GraphIngestionInternalDtos.ExtractReviewResponse response) {
        if (response == null || !requestId.equals(response.requestId()) || !jobId.equals(response.jobId())) {
            throw invalidAi("request_id 或 job_id 不匹配");
        }
        validateModels(response.models(), true);
        validatePrompts(response.prompts(), true);
        List<JsonNode> nodes = copy(response.nodes(), MAX_NODES, "nodes", true);
        List<JsonNode> edges = copy(response.edges(), MAX_EDGES, "edges", true);
        List<JsonNode> sources = copy(response.sources(), MAX_SOURCES, "sources", true);
        if (nodes.isEmpty() && edges.isEmpty() && sources.isEmpty()) throw invalidAi("没有候选");
        Set<String> identities = new HashSet<>();
        for (JsonNode item : nodes) validateCandidate(item, "NODE", true, identities);
        for (JsonNode item : edges) validateCandidate(item, "EDGE", true, identities);
        for (JsonNode item : sources) validateCandidate(item, "SOURCE", true, identities);
        validateWarnings(response.warnings());
        requireAggregateBudget(Map.of("nodes", nodes, "edges", edges, "sources", sources));
        return new ValidatedExtraction(nodes, edges, sources, response.models(), response.prompts());
    }

    /** Replaces the untrusted AI copyright classification with the administrator declaration. */
    public ValidatedExtraction enforceAuthoritativeRights(
            ValidatedExtraction extraction, String copyrightStatus) {
        String rights = switch (copyrightStatus == null ? "" : copyrightStatus) {
            case "CONFIRMED", "AUTHORIZED", "PUBLIC_DOMAIN" -> "ALLOWED";
            case "UNKNOWN" -> "RESTRICTED";
            default -> throw new BusinessException(
                    ErrorCode.GRAPH_DRAFT_VALIDATION_FAILED, "建图任务版权声明不合法");
        };
        List<JsonNode> nodes = extraction.nodes().stream().map(value -> {
            ObjectNode copy = ((ObjectNode) value).deepCopy();
            copy.put("rights_status", rights);
            return (JsonNode) copy;
        }).toList();
        List<JsonNode> sources = extraction.sources().stream().map(value -> {
            ObjectNode copy = ((ObjectNode) value).deepCopy();
            copy.put("rights_status", rights);
            return (JsonNode) copy;
        }).toList();
        return new ValidatedExtraction(nodes, extraction.edges(), sources,
                extraction.models(), extraction.prompts());
    }

    public void validateAuthoritativeRights(
            GraphIngestionInternalDtos.BatchDetail detail, String copyrightStatus) {
        String expected = Set.of("CONFIRMED", "AUTHORIZED", "PUBLIC_DOMAIN").contains(copyrightStatus)
                ? "ALLOWED" : "UNKNOWN".equals(copyrightStatus) ? "RESTRICTED" : null;
        if (expected == null
                || detail.nodes().stream().anyMatch(node -> !expected.equals(text(node, "rights_status")))
                || detail.sources().stream().anyMatch(source -> !expected.equals(text(source, "rights_status")))) {
            throw new BusinessException(ErrorCode.GRAPH_VERSION_CONFLICT,
                    "已存在图谱批次的版权状态与当前权威声明不一致");
        }
    }

    public GraphIngestionInternalDtos.BatchDetail validateUpsert(
            String batchId, String baseVersion, String payloadHash,
            GraphIngestionInternalDtos.BatchUpsertResponse response) {
        if (response == null || response.duplicate() == null || response.batch() == null) {
            throw invalidGraph("批次响应缺少 duplicate 或 batch");
        }
        return validateDetail(batchId, baseVersion, payloadHash, response.batch());
    }

    public GraphIngestionInternalDtos.BatchDetail validateDetail(
            String batchId, String baseVersion, String payloadHash,
            GraphIngestionInternalDtos.BatchDetail detail) {
        if (detail == null || !batchId.equals(detail.batchId())
                || baseVersion != null && !baseVersion.equals(detail.baseGraphVersion())
                || payloadHash != null && !payloadHash.equals(detail.payloadSha256())) {
            throw invalidGraph("批次身份、基准版本或摘要不匹配");
        }
        if (blank(detail.baseGraphVersion()) || detail.baseGraphVersion().length() > 100
                || detail.payloadSha256() == null || !detail.payloadSha256().matches("[a-f0-9]{64}")
                || detail.revision() == null || detail.revision() < 1
                || !BATCH_STATES.contains(detail.status())) {
            throw invalidGraph("批次状态字段不合法");
        }
        List<JsonNode> nodes = copyGraph(detail.nodes(), MAX_NODES, "nodes");
        List<JsonNode> edges = copyGraph(detail.edges(), MAX_EDGES, "edges");
        List<JsonNode> sources = copyGraph(detail.sources(), MAX_SOURCES, "sources");
        Set<String> identities = new HashSet<>();
        for (JsonNode item : nodes) validateCandidate(item, "NODE", false, identities);
        for (JsonNode item : edges) validateCandidate(item, "EDGE", false, identities);
        for (JsonNode item : sources) validateCandidate(item, "SOURCE", false, identities);
        int total = nodes.size() + edges.size() + sources.size();
        if (detail.candidateCount() == null || detail.candidateCount() != total || total < 1
                || nonNegative(detail.pendingCount()) + nonNegative(detail.approvedCount())
                + nonNegative(detail.rejectedCount()) != total
                || nonNegative(detail.errorCount()) > MAX_BATCH || nonNegative(detail.warningCount()) > MAX_BATCH) {
            throw invalidGraph("批次候选计数不一致");
        }
        validateSourceRef(detail.source());
        validateThreshold(detail.thresholdProfile());
        validateModels(detail.models(), false);
        validatePrompts(detail.prompts(), false);
        validateIssues(detail.validationIssues());
        validateTime(detail.createdAt(), detail.updatedAt());
        return detail;
    }

    public List<GraphIngestionDtos.CandidateItem> validateCandidatePage(
            int requestedLimit, long revision, List<GraphIngestionInternalDtos.ValidationIssue> allIssues,
            GraphIngestionInternalDtos.CandidatePage page) {
        if (page == null || page.items() == null || page.items().size() > requestedLimit
                || page.nextCursor() != null && page.nextCursor().length() > 512) {
            throw invalidGraph("候选分页响应不合法");
        }
        Set<String> identities = new HashSet<>();
        List<GraphIngestionDtos.CandidateItem> result = new ArrayList<>();
        for (GraphIngestionInternalDtos.CandidateItem item : page.items()) {
            if (item == null || !TYPES.contains(item.candidateType())
                    || blank(item.candidateId()) || item.candidateId().length() > 128
                    || !DECISIONS.contains(item.decisionStatus()) || !LANES.contains(item.reviewLane())
                    || !identities.add(item.candidateType() + ":" + item.candidateId())) {
                throw invalidGraph("候选分页身份或状态不合法");
            }
            JsonNode payload = switch (item.candidateType()) {
                case "NODE" -> exclusive(item.node(), item.edge(), item.source());
                case "EDGE" -> exclusive(item.edge(), item.node(), item.source());
                case "SOURCE" -> exclusive(item.source(), item.node(), item.edge());
                default -> null;
            };
            validateCandidate(payload, item.candidateType(), false, new HashSet<>());
            if (!item.candidateId().equals(text(payload, "candidate_id"))) {
                throw invalidGraph("候选包裹身份与 payload 不一致");
            }
            List<GraphIngestionDtos.ValidationIssue> issues = (allIssues == null ? List
                    .<GraphIngestionInternalDtos.ValidationIssue>of() : allIssues).stream()
                    .filter(issue -> item.candidateType().equals(issue.candidateType())
                            && item.candidateId().equals(issue.candidateId()))
                    .map(issue -> new GraphIngestionDtos.ValidationIssue(
                            issue.code(), issue.severity(), issue.message())).toList();
            result.add(toPublic(item, payload, issues, revision));
        }
        return List.copyOf(result);
    }

    public void validateDecisions(List<GraphIngestionDtos.Decision> decisions) {
        Set<String> identities = new HashSet<>();
        for (GraphIngestionDtos.Decision decision : decisions) {
            if (decision == null || !identities.add(decision.candidateType() + ":" + decision.candidateId())
                    || blank(decision.comment()) || decision.comment().trim().length() > 2000) {
                throw validation("同一候选不能重复，且 comment 必须为 1..2000 字符");
            }
            JsonNode edited = switch (decision.candidateType()) {
                case "NODE" -> exclusiveEdit(
                        toInternal(decision.editedNode()), decision.editedEdge(), decision.editedSource());
                case "EDGE" -> exclusiveEdit(
                        toInternal(decision.editedEdge()), decision.editedNode(), decision.editedSource());
                case "SOURCE" -> exclusiveEdit(
                        toInternal(decision.editedSource()), decision.editedNode(), decision.editedEdge());
                default -> throw validation("候选类型不合法");
            };
            if (edited != null) {
                validateCandidate(edited, decision.candidateType(), false, new HashSet<>());
                if (!decision.candidateId().equals(text(edited, "candidate_id"))) {
                    throw validation("编辑后的 candidateId 不能改变");
                }
            }
        }
    }

    public GraphIngestionInternalDtos.Decision toInternalDecision(GraphIngestionDtos.Decision value) {
        return new GraphIngestionInternalDtos.Decision(
                value.candidateType(), value.candidateId(), value.decision(), value.comment().trim(),
                toInternal(value.editedNode()), toInternal(value.editedEdge()), toInternal(value.editedSource()));
    }

    public JsonNode editedPayload(GraphIngestionDtos.Decision value) {
        if (value.editedNode() != null) return toInternal(value.editedNode());
        if (value.editedEdge() != null) return toInternal(value.editedEdge());
        if (value.editedSource() != null) return toInternal(value.editedSource());
        return null;
    }

    public GraphIngestionInternalDtos.BatchDetail validateDecisionResult(
            String batchId, long previousRevision, GraphIngestionInternalDtos.BatchDetail detail) {
        validateDetail(batchId, null, null, detail);
        if (detail.revision() <= previousRevision) throw invalidGraph("审核未推进批次 revision");
        return detail;
    }

    public GraphIngestionInternalDtos.PublishResponse validatePublish(
            String batchId, String targetVersion, GraphIngestionInternalDtos.PublishResponse response) {
        if (response == null || !batchId.equals(response.batchId())
                || !targetVersion.equals(response.graphVersion()) || blank(response.previousGraphVersion())
                || !validHash(response.releaseSha256()) || invalidFuture(response.publishedAt())) {
            throw new BusinessException(ErrorCode.GRAPH_PUBLISH_FAILED, "图谱发布响应未通过业务校验");
        }
        return response;
    }

    public GraphIngestionInternalDtos.RollbackResponse validateRollback(
            String targetVersion, String expectedCurrent,
            GraphIngestionInternalDtos.RollbackResponse response) {
        if (response == null || !targetVersion.equals(response.graphVersion())
                || !expectedCurrent.equals(response.previousGraphVersion())
                || !validHash(response.releaseSha256()) || invalidFuture(response.activatedAt())) {
            throw new BusinessException(ErrorCode.GRAPH_PUBLISH_FAILED, "图谱回滚响应未通过业务校验");
        }
        return response;
    }

    /** Mirrors knowledge-service canonical_payload_sha256 byte-for-byte. */
    public String payloadSha256(GraphIngestionInternalDtos.BatchUpsertRequest request) {
        JsonNode value = json.valueToTree(request);
        ObjectNode payload = json.createObjectNode();
        for (String field : List.of("base_graph_version", "source", "threshold_profile",
                "models", "prompts", "nodes", "edges", "sources")) {
            JsonNode child = value.get(field);
            if (Set.of("nodes", "edges", "sources").contains(field)) {
                ArrayNode candidates = json.createArrayNode();
                child.forEach(candidate -> {
                    ObjectNode copy = ((ObjectNode) candidate).deepCopy();
                    MANAGED.forEach(copy::remove);
                    candidates.add(copy);
                });
                child = candidates;
            }
            payload.set(field, child);
        }
        try {
            byte[] canonical = json.writeValueAsBytes(sort(payload));
            if (canonical.length > MAX_BATCH_BYTES) {
                throw new BusinessException(ErrorCode.GRAPH_INGESTION_LIMIT_EXCEEDED,
                        "知识图谱候选批次超过 2 MiB 规范载荷预算");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算图谱候选摘要", exception);
        }
    }

    private GraphIngestionDtos.CandidateItem toPublic(
            GraphIngestionInternalDtos.CandidateItem item, JsonNode payload,
            List<GraphIngestionDtos.ValidationIssue> issues, long revision) {
        String comment = nullableText(payload, "decision_comment");
        String reviewer = nullableText(payload, "decided_by");
        Instant reviewedAt = parseInstant(nullableText(payload, "decided_at"));
        return new GraphIngestionDtos.CandidateItem(item.candidateId(), item.candidateType(),
                item.decisionStatus(), item.reviewLane(),
                "NODE".equals(item.candidateType()) ? toPublicNode(payload) : null,
                "EDGE".equals(item.candidateType()) ? toPublicEdge(payload) : null,
                "SOURCE".equals(item.candidateType()) ? toPublicSource(payload) : null,
                issues, comment, reviewer, reviewedAt, revision);
    }

    private GraphIngestionDtos.CandidateNode toPublicNode(JsonNode value) {
        return new GraphIngestionDtos.CandidateNode(
                text(value, "candidate_id"), text(value, "id"), text(value, "name"),
                integer(value, "level"), text(value, "node_type"), nullableText(value, "parent_id"),
                nullableText(value, "description"), strings(value, "aliases"), strings(value, "keywords"),
                nullableText(value, "difficulty"), integer(value, "importance"),
                integer(value, "estimated_minutes"), strings(value, "learning_objectives"),
                strings(value, "common_misconceptions"), strings(value, "example_code"),
                text(value, "rights_status"), strings(value, "evidence"),
                decimal(value, "extractor_confidence"), decimal(value, "reviewer_confidence"),
                text(value, "review_status"), text(value, "decision_status"), text(value, "review_lane"),
                nullableText(value, "decision_comment"), nullableText(value, "decided_by"),
                parseInstant(nullableText(value, "decided_at")));
    }

    private GraphIngestionDtos.CandidateEdge toPublicEdge(JsonNode value) {
        return new GraphIngestionDtos.CandidateEdge(
                text(value, "candidate_id"), text(value, "source_id"), text(value, "target_id"),
                text(value, "relation"), strings(value, "evidence"),
                decimal(value, "extractor_confidence"), decimal(value, "reviewer_confidence"),
                text(value, "review_status"), text(value, "decision_status"), text(value, "review_lane"),
                nullableText(value, "decision_comment"), nullableText(value, "decided_by"),
                parseInstant(nullableText(value, "decided_at")));
    }

    private GraphIngestionDtos.CandidateSource toPublicSource(JsonNode value) {
        return new GraphIngestionDtos.CandidateSource(
                text(value, "candidate_id"), text(value, "id"), text(value, "node_id"),
                text(value, "source_type"), text(value, "title"), longValue(value, "resource_id"),
                longValue(value, "file_id"), nullableText(value, "chapter"), integer(value, "page_from"),
                integer(value, "page_to"), text(value, "locator"), nullableText(value, "excerpt"),
                text(value, "rights_status"),
                decimal(value, "extractor_confidence"), decimal(value, "reviewer_confidence"),
                text(value, "review_status"), text(value, "decision_status"), text(value, "review_lane"),
                nullableText(value, "decision_comment"), nullableText(value, "decided_by"),
                parseInstant(nullableText(value, "decided_at")));
    }

    private JsonNode toInternal(GraphIngestionDtos.CandidateNode value) {
        if (value == null) return null;
        ObjectNode node = json.createObjectNode();
        put(node, "candidate_id", value.candidateId());
        put(node, "id", value.id());
        put(node, "name", value.name());
        put(node, "level", value.level());
        put(node, "node_type", value.nodeType());
        put(node, "parent_id", value.parentId());
        put(node, "description", value.description());
        put(node, "aliases", value.aliases());
        put(node, "keywords", value.keywords());
        put(node, "difficulty", value.difficulty());
        put(node, "importance", value.importance());
        put(node, "estimated_minutes", value.estimatedMinutes());
        put(node, "learning_objectives", value.learningObjectives());
        put(node, "common_misconceptions", value.commonMisconceptions());
        put(node, "example_code", value.exampleCode());
        put(node, "rights_status", value.rightsStatus());
        put(node, "evidence", value.evidence());
        put(node, "extractor_confidence", value.extractorConfidence());
        put(node, "reviewer_confidence", value.reviewerConfidence());
        put(node, "review_status", value.reviewStatus());
        put(node, "decision_status", value.decisionStatus());
        put(node, "review_lane", value.reviewLane());
        put(node, "decision_comment", value.decisionComment());
        put(node, "decided_by", value.decidedBy());
        put(node, "decided_at", value.decidedAt() == null ? null : value.decidedAt().toString());
        return node;
    }

    private JsonNode toInternal(GraphIngestionDtos.CandidateEdge value) {
        if (value == null) return null;
        ObjectNode node = json.createObjectNode();
        put(node, "candidate_id", value.candidateId());
        put(node, "source_id", value.sourceId());
        put(node, "target_id", value.targetId());
        put(node, "relation", value.relation());
        put(node, "evidence", value.evidence());
        put(node, "extractor_confidence", value.extractorConfidence());
        put(node, "reviewer_confidence", value.reviewerConfidence());
        put(node, "review_status", value.reviewStatus());
        put(node, "decision_status", value.decisionStatus());
        put(node, "review_lane", value.reviewLane());
        put(node, "decision_comment", value.decisionComment());
        put(node, "decided_by", value.decidedBy());
        put(node, "decided_at", value.decidedAt() == null ? null : value.decidedAt().toString());
        return node;
    }

    private JsonNode toInternal(GraphIngestionDtos.CandidateSource value) {
        if (value == null) return null;
        ObjectNode node = json.createObjectNode();
        put(node, "candidate_id", value.candidateId());
        put(node, "id", value.id());
        put(node, "node_id", value.nodeId());
        put(node, "source_type", value.sourceType());
        put(node, "title", value.title());
        put(node, "resource_id", value.resourceId());
        put(node, "file_id", value.fileId());
        put(node, "chapter", value.chapter());
        put(node, "page_from", value.pageFrom());
        put(node, "page_to", value.pageTo());
        put(node, "locator", value.locator());
        put(node, "excerpt", value.excerpt());
        put(node, "rights_status", value.rightsStatus());
        put(node, "extractor_confidence", value.extractorConfidence());
        put(node, "reviewer_confidence", value.reviewerConfidence());
        put(node, "review_status", value.reviewStatus());
        put(node, "decision_status", value.decisionStatus());
        put(node, "review_lane", value.reviewLane());
        put(node, "decision_comment", value.decisionComment());
        put(node, "decided_by", value.decidedBy());
        put(node, "decided_at", value.decidedAt() == null ? null : value.decidedAt().toString());
        return node;
    }

    private void validateCandidate(JsonNode item, String type, boolean mustBePending, Set<String> identities) {
        if (item == null || !item.isObject()) throw boundary(type + " 候选必须是对象");
        validateJson(item, 0, new Counter(), type);
        Set<String> allowed = switch (type) {
            case "NODE" -> NODE_FIELDS;
            case "EDGE" -> EDGE_FIELDS;
            case "SOURCE" -> SOURCE_FIELDS;
            default -> throw boundary("候选类型不合法");
        };
        item.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw boundary(type + " 包含未允许字段 " + field);
        });
        String candidateId = requiredText(item, "candidate_id", 64);
        if (!candidateId.matches("[A-Za-z0-9._:-]+")
                || !identities.add(type + ":" + candidateId)) throw boundary("candidate_id 重复或格式不合法");
        requireConfidence(item.get("extractor_confidence"), "extractor_confidence");
        requireConfidence(item.get("reviewer_confidence"), "reviewer_confidence");
        requireEnum(item, "review_status", Set.of("PENDING"));
        requireEnum(item, "decision_status", DECISIONS);
        requireEnum(item, "review_lane", LANES);
        if (mustBePending && (!"PENDING".equals(text(item, "decision_status"))
                || present(item, "decision_comment") || present(item, "decided_by") || present(item, "decided_at"))) {
            throw invalidAi("AI 不得作出人工审核决策");
        }
        if ("NODE".equals(type)) validateNode(item);
        else if ("EDGE".equals(type)) validateEdge(item);
        else validateSource(item);
    }

    private void validateNode(JsonNode item) {
        requiredText(item, "id", 64);
        requiredText(item, "name", 200);
        int level = requiredInt(item, "level", 0, 3);
        String nodeType = requiredEnum(item, "node_type",
                Set.of("course", "module", "chapter", "section", "knowledge_point"));
        Map<Integer, String> fixed = Map.of(0, "course", 1, "module", 2, "chapter");
        if (fixed.containsKey(level) && !fixed.get(level).equals(nodeType)
                || level == 3 && !Set.of("section", "knowledge_point").contains(nodeType)
                || level == 0 && present(item, "parent_id")
                || level > 0 && blank(nullableText(item, "parent_id"))) throw boundary("节点层级或 parent_id 不合法");
        optionalText(item, "parent_id", 64);
        optionalText(item, "description", 5000);
        stringArray(item, "aliases", 20, 100, false);
        stringArray(item, "keywords", 30, 100, false);
        optionalEnum(item, "difficulty", Set.of("BEGINNER", "BASIC", "INTERMEDIATE", "ADVANCED"));
        optionalInt(item, "importance", 1, 5);
        optionalInt(item, "estimated_minutes", 1, 10_000);
        stringArray(item, "learning_objectives", 20, 1000, false);
        stringArray(item, "common_misconceptions", 20, 1000, false);
        stringArray(item, "example_code", 10, 10_000, false);
        requireEnum(item, "rights_status", Set.of("PENDING", "ALLOWED", "RESTRICTED"));
        stringArray(item, "evidence", 10, 2000, true);
    }

    private void validateEdge(JsonNode item) {
        String source = requiredText(item, "source_id", 64);
        String target = requiredText(item, "target_id", 64);
        if (source.equals(target)) throw boundary("边不能自环");
        requireEnum(item, "relation", Set.of("REQUIRES", "RELATED", "SUPPORTED_BY"));
        stringArray(item, "evidence", 10, 2000, true);
    }

    private void validateSource(JsonNode item) {
        requiredText(item, "id", 128);
        requiredText(item, "node_id", 64);
        String type = requiredEnum(item, "source_type", Set.of("TEXTBOOK", "TEACHING_RESOURCE"));
        requiredText(item, "title", 500);
        optionalLong(item, "resource_id");
        optionalLong(item, "file_id");
        if ("TEACHING_RESOURCE".equals(type)
                && !present(item, "resource_id") && !present(item, "file_id")) {
            throw boundary("TEACHING_RESOURCE 候选必须包含 resource_id 或 file_id");
        }
        optionalText(item, "chapter", 500);
        Integer from = optionalInt(item, "page_from", 1, Integer.MAX_VALUE);
        Integer to = optionalInt(item, "page_to", 1, Integer.MAX_VALUE);
        if (from != null && to != null && to < from) throw boundary("页码范围不合法");
        requiredText(item, "locator", 1000);
        optionalText(item, "excerpt", 500);
        requireEnum(item, "rights_status", Set.of("ALLOWED", "RESTRICTED"));
    }

    private void validateSourceRef(GraphIngestionInternalDtos.SourceRef source) {
        if (source == null || !Set.of("TEXTBOOK", "TEACHING_RESOURCE").contains(source.sourceType())
                || blank(source.sourceId()) || source.sourceId().length() > 128
                || blank(source.title()) || source.title().length() > 500
                || source.fileSha256() != null && !validHash(source.fileSha256())
                || source.locator() != null && source.locator().length() > 1000) throw invalidGraph("来源描述不合法");
    }

    private void validateThreshold(GraphIngestionInternalDtos.ThresholdProfile value) {
        if (value == null || blank(value.profileVersion()) || value.profileVersion().length() > 100
                || !confidence(value.nodeFastTrackMin()) || !confidence(value.edgeFastTrackMin())
                || !confidence(value.sourceFastTrackMin()) || !confidence(value.rejectBelow())
                || value.rejectBelow().compareTo(value.nodeFastTrackMin()) >= 0
                || value.rejectBelow().compareTo(value.edgeFastTrackMin()) >= 0
                || value.rejectBelow().compareTo(value.sourceFastTrackMin()) >= 0) throw invalidGraph("阈值配置不合法");
    }

    private void validateModels(GraphIngestionInternalDtos.Models value, boolean aiBoundary) {
        boolean invalid = value == null || invalidModel(value.extractor()) || invalidModel(value.reviewer());
        if (invalid) throw aiBoundary ? invalidAi("模型身份不合法") : invalidGraph("模型身份不合法");
    }

    private boolean invalidModel(GraphIngestionInternalDtos.ModelIdentity value) {
        return value == null || blank(value.provider()) || value.provider().length() > 40
                || blank(value.model()) || value.model().length() > 100;
    }

    private void validatePrompts(GraphIngestionInternalDtos.Prompts value, boolean aiBoundary) {
        boolean invalid = value == null || invalidPrompt(value.extractor()) || invalidPrompt(value.reviewer());
        if (invalid) throw aiBoundary ? invalidAi("Prompt 身份不合法") : invalidGraph("Prompt 身份不合法");
    }

    private boolean invalidPrompt(GraphIngestionInternalDtos.PromptIdentity value) {
        return value == null || blank(value.version()) || value.version().length() > 100 || !validHash(value.sha256());
    }

    private void validateIssues(List<GraphIngestionInternalDtos.ValidationIssue> issues) {
        if (issues == null || issues.size() > MAX_BATCH) throw invalidGraph("validation_issues 不合法");
        for (GraphIngestionInternalDtos.ValidationIssue issue : issues) {
            if (issue == null || blank(issue.code()) || issue.code().length() > 80
                    || !Set.of("INFO", "WARNING", "ERROR").contains(issue.severity())
                    || !Set.of("NODE", "EDGE", "SOURCE", "BATCH").contains(issue.candidateType())
                    || issue.candidateId() != null && issue.candidateId().length() > 128
                    || blank(issue.message()) || issue.message().length() > 1000) throw invalidGraph("校验问题字段不合法");
        }
    }

    private void validateWarnings(List<String> warnings) {
        if (warnings == null || warnings.size() > 100
                || warnings.stream().anyMatch(value -> blank(value) || value.length() > 1000)) {
            throw invalidAi("warnings 不合法");
        }
    }

    private void requireAggregateBudget(Object value) {
        try {
            if (json.writeValueAsBytes(value).length > MAX_BATCH_BYTES) {
                throw new BusinessException(ErrorCode.GRAPH_INGESTION_LIMIT_EXCEEDED,
                        "AI 图谱候选超过 2 MiB 聚合字节预算");
            }
        } catch (JsonProcessingException exception) {
            throw invalidAi("候选聚合载荷无法序列化");
        }
    }

    private void validateTime(Instant created, Instant updated) {
        if (created == null || updated == null || updated.isBefore(created) || invalidFuture(updated)) {
            throw invalidGraph("批次时间戳不合法");
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, sort(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(value -> result.add(sort(value)));
            return result;
        }
        return node;
    }

    private JsonNode exclusive(JsonNode selected, JsonNode other1, JsonNode other2) {
        if (selected == null || selected.isNull() || present(other1) || present(other2)) throw invalidGraph("候选 union 不合法");
        return selected;
    }

    private JsonNode exclusiveEdit(JsonNode selected, Object other1, Object other2) {
        if (other1 != null || other2 != null) throw validation("editedNode/editedEdge/editedSource 必须与候选类型一致");
        return present(selected) ? selected : null;
    }

    private boolean present(JsonNode node) { return node != null && !node.isNull(); }
    private boolean present(JsonNode object, String field) { return present(object.get(field)); }

    private <T> List<T> copy(List<T> value, int limit, String field, boolean ai) {
        if (value == null || value.size() > limit) throw ai ? invalidAi(field + " 超出边界") : invalidGraph(field + " 超出边界");
        return List.copyOf(value);
    }

    private List<JsonNode> copyGraph(List<JsonNode> value, int limit, String field) {
        return copy(value, limit, field, false);
    }

    private int nonNegative(Integer value) {
        if (value == null || value < 0 || value > MAX_BATCH) throw invalidGraph("计数不合法");
        return value;
    }

    private void validateJson(JsonNode node, int depth, Counter counter, String field) {
        if (node == null || depth > MAX_DEPTH || ++counter.fields > MAX_FIELDS) {
            throw boundary(field + " 为空、过深或字段过多");
        }
        if (node.isNull()) {
            if (depth == 0) throw boundary(field + " 不能为空");
            return;
        }
        if (node.isTextual() && node.textValue().length() > 20_000) throw boundary(field + " 文本过长");
        if (node.isObject()) node.fields().forEachRemaining(entry -> validateJson(entry.getValue(), depth + 1, counter, field));
        if (node.isArray()) {
            if (node.size() > 500) throw boundary(field + " 数组过长");
            node.forEach(child -> validateJson(child, depth + 1, counter, field));
        }
        if (depth == 0) {
            try {
                if (json.writeValueAsBytes(node).length > MAX_ITEM_BYTES) throw boundary(field + " 超过字节预算");
            } catch (JsonProcessingException exception) {
                throw boundary(field + " 无法序列化");
            }
        }
    }

    private String requiredText(JsonNode object, String field, int max) {
        String value = nullableText(object, field);
        if (blank(value) || value.length() > max) throw boundary(field + " 不合法");
        return value;
    }

    private String nullableText(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isTextual() ? value.textValue().trim() : null;
    }

    private String text(JsonNode object, String field) { return nullableText(object, field); }

    private Integer integer(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value.intValue();
    }

    private Long longValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value.longValue();
    }

    private java.math.BigDecimal decimal(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private List<String> strings(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(item.textValue()));
        return List.copyOf(result);
    }

    private void put(ObjectNode object, String field, Object value) {
        if (value == null) object.putNull(field);
        else object.set(field, json.valueToTree(value));
    }

    private void optionalText(JsonNode object, String field, int max) {
        if (!present(object, field)) return;
        String value = nullableText(object, field);
        if (blank(value) || value.length() > max) throw boundary(field + " 不合法");
    }

    private int requiredInt(JsonNode object, String field, int min, int max) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) throw boundary(field + " 不合法");
        return value.intValue();
    }

    private Integer optionalInt(JsonNode object, String field, int min, int max) {
        if (!present(object, field)) return null;
        return requiredInt(object, field, min, max);
    }

    private void optionalLong(JsonNode object, String field) {
        if (!present(object, field)) return;
        JsonNode value = object.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw boundary(field + " 不合法");
    }

    private String requiredEnum(JsonNode object, String field, Set<String> values) {
        String value = requiredText(object, field, 100);
        if (!values.contains(value)) throw boundary(field + " 不合法");
        return value;
    }

    private void requireEnum(JsonNode object, String field, Set<String> values) {
        requiredEnum(object, field, values);
    }

    private void optionalEnum(JsonNode object, String field, Set<String> values) {
        if (present(object, field)) requireEnum(object, field, values);
    }

    private void stringArray(JsonNode object, String field, int maxItems, int maxLength, boolean nonEmpty) {
        JsonNode values = object.get(field);
        if (values == null || !values.isArray() || values.size() > maxItems || nonEmpty && values.isEmpty()) {
            throw boundary(field + " 不合法");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || blank(value.textValue()) || value.textValue().length() > maxLength
                    || !unique.add(value.textValue())) throw boundary(field + " 含非法或重复值");
        }
    }

    private void requireConfidence(JsonNode value, String field) {
        if (value == null || !value.isNumber() || value.decimalValue().scale() > 10
                || value.decimalValue().signum() < 0 || value.decimalValue().compareTo(java.math.BigDecimal.ONE) > 0) {
            throw boundary(field + " 不在 0..1");
        }
    }

    private boolean confidence(java.math.BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(java.math.BigDecimal.ONE) <= 0;
    }

    private boolean validHash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private boolean invalidFuture(Instant value) {
        return value == null || value.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private Instant parseInstant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException exception) { throw invalidGraph("decided_at 不合法"); }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
    private BusinessException invalidAi(String reason) {
        return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,
                "AI 图谱抽取与审查结果未通过业务校验", Map.of("reason", reason));
    }
    private BusinessException invalidGraph(String reason) {
        return new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                "图谱草稿服务返回无效响应", Map.of("reason", reason));
    }
    private BusinessException boundary(String reason) {
        return new BusinessException(ErrorCode.GRAPH_DRAFT_VALIDATION_FAILED,
                "知识图谱候选未通过边界校验", Map.of("reason", reason));
    }

    private static final class Counter { private int fields; }

    public record ValidatedExtraction(
            List<JsonNode> nodes, List<JsonNode> edges, List<JsonNode> sources,
            GraphIngestionInternalDtos.Models models,
            GraphIngestionInternalDtos.Prompts prompts) {}
}
