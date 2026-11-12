package com.education.agent.graphingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.graphingestion.integration.GraphIngestionInternalDtos;
import com.education.agent.graphingestion.service.GraphIngestionBoundaryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphIngestionBoundaryValidatorTest {
  private ObjectMapper json;
  private GraphIngestionBoundaryValidator validator;

  @BeforeEach
  void setUp() {
    json = new ObjectMapper().findAndRegisterModules();
    validator = new GraphIngestionBoundaryValidator(json);
  }

  @Test
  void exactProviderCandidateAllowsOptionalNullsAndMatchesPythonCanonicalSha() throws Exception {
    JsonNode node = node();
    GraphIngestionInternalDtos.Models models = new GraphIngestionInternalDtos.Models(
        new GraphIngestionInternalDtos.ModelIdentity("deepseek", "model-e"),
        new GraphIngestionInternalDtos.ModelIdentity("deepseek", "model-r"));
    GraphIngestionInternalDtos.Prompts prompts = new GraphIngestionInternalDtos.Prompts(
        new GraphIngestionInternalDtos.PromptIdentity("extract-v1", "b".repeat(64)),
        new GraphIngestionInternalDtos.PromptIdentity("review-v1", "c".repeat(64)));
    var extraction = validator.validateExtraction(7L, "request-cycle7-0001",
        new GraphIngestionInternalDtos.ExtractReviewResponse(1L, "request-cycle7-0001", 7L, List.of(node), List.of(), List.of(), List.of(), models, prompts));
    assertThat(extraction.nodes()).containsExactly(node);

    GraphIngestionInternalDtos.BatchUpsertRequest unsigned =
        new GraphIngestionInternalDtos.BatchUpsertRequest(
            "request-cycle7-0001", "graph-ingestion-job:7", "graph-v1", "0".repeat(64),
            new GraphIngestionInternalDtos.SourceRef(
                "TEXTBOOK", "file:1", "教材", "a".repeat(64), "file:1"),
            GraphIngestionInternalDtos.ThresholdProfile.defaults(), models, prompts,
            List.of(node), List.of(), List.of());
    assertThat(validator.payloadSha256(unsigned))
        .isEqualTo("5e2ead9f79b45de1c9111b8dca22316ba24e5b0c638f0b1e3dd7d197d1099b34");
  }

  @Test
  void aiCannotSmuggleHumanDecisionOrUnknownCandidateFields() throws Exception {
    JsonNode approved = node().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) approved).put("decision_status", "APPROVED");
    assertThatThrownBy(() -> validator.validateExtraction(7L, "request-cycle7-0001",
        response(approved))).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));

    JsonNode unknown = node().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("published", true);
    assertThatThrownBy(() -> validator.validateExtraction(7L, "request-cycle7-0001",
        response(unknown))).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.GRAPH_DRAFT_VALIDATION_FAILED));
  }

  @Test
  void teachingResourceSourceAcceptsAuthoritativeFileIdentityWithoutInventingResourceId() throws Exception {
    JsonNode source = json.readTree("""
        {
          "candidate_id":"source-1","id":"source-1","node_id":"node-1",
          "source_type":"TEACHING_RESOURCE","title":"讲义","resource_id":null,
          "file_id":9,"chapter":null,"page_from":null,"page_to":null,
          "locator":"page:1","excerpt":null,
          "rights_status":"ALLOWED",
          "extractor_confidence":0.9,"reviewer_confidence":0.8,
          "review_status":"PENDING","decision_status":"PENDING",
          "review_lane":"STANDARD","decision_comment":null,
          "decided_by":null,"decided_at":null
        }
        """);
    assertThat(validator.validateExtraction(7L, "request-cycle7-0001",
        new GraphIngestionInternalDtos.ExtractReviewResponse(1L, "request-cycle7-0001", 7L, List.of(), List.of(), List.of(source), List.of(), models(), prompts())).sources()).containsExactly(source);
  }

  @Test
  void rejectsAggregateCandidatePayloadAboveTwoMiBBeforeCallingGraphService() throws Exception {
    java.util.ArrayList<JsonNode> nodes = new java.util.ArrayList<>();
    for (int index = 0; index < 45; index++) {
      var value = (com.fasterxml.jackson.databind.node.ObjectNode) node().deepCopy();
      value.put("candidate_id", "node-" + index);
      value.put("id", "node-" + index);
      value.put("name", "课程-" + index);
      value.put("description", "d".repeat(5000));
      var evidence = json.createArrayNode();
      for (int item = 0; item < 10; item++) evidence.add("e".repeat(1900) + item);
      value.set("evidence", evidence);
      var code = json.createArrayNode();
      for (int item = 0; item < 4; item++) code.add("x".repeat(9000) + item);
      value.set("example_code", code);
      nodes.add(value);
    }
    assertThatThrownBy(() -> validator.validateExtraction(7L, "request-cycle7-0001",
        new GraphIngestionInternalDtos.ExtractReviewResponse(1L, "request-cycle7-0001", 7L, nodes, List.of(), List.of(), List.of(), models(), prompts())))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.errorCode())
                .isEqualTo(ErrorCode.GRAPH_INGESTION_LIMIT_EXCEEDED));
  }

  private GraphIngestionInternalDtos.ExtractReviewResponse response(JsonNode node) {
    return new GraphIngestionInternalDtos.ExtractReviewResponse(1L, "request-cycle7-0001", 7L, List.of(node), List.of(), List.of(), List.of(), models(), prompts());
  }

  private GraphIngestionInternalDtos.Models models() {
    return new GraphIngestionInternalDtos.Models(
        new GraphIngestionInternalDtos.ModelIdentity("deepseek", "model-e"),
        new GraphIngestionInternalDtos.ModelIdentity("deepseek", "model-r"));
  }

  private GraphIngestionInternalDtos.Prompts prompts() {
    return new GraphIngestionInternalDtos.Prompts(
        new GraphIngestionInternalDtos.PromptIdentity("extract-v1", "b".repeat(64)),
        new GraphIngestionInternalDtos.PromptIdentity("review-v1", "c".repeat(64)));
  }

  private JsonNode node() throws Exception {
    return json.readTree("""
        {
          "candidate_id":"node-1","id":"node-1","name":"课程",
          "level":0,"node_type":"course","parent_id":null,"description":null,
          "aliases":[],"keywords":[],"difficulty":null,"importance":null,
          "estimated_minutes":null,"learning_objectives":[],
          "common_misconceptions":[],"example_code":[],
          "rights_status":"RESTRICTED","evidence":["教材第1章"],
          "extractor_confidence":0.9,"reviewer_confidence":0.8,
          "review_status":"PENDING","decision_status":"PENDING",
          "review_lane":"STANDARD","decision_comment":null,
          "decided_by":null,"decided_at":null
        }
        """);
  }
}
