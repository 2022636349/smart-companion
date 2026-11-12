package com.education.agent.integration.knowledge;

import com.education.agent.knowledge.dto.KnowledgeGraphView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KnowledgeServiceClientTest {

    private MockRestServiceServer server;
    private KnowledgeServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://knowledge.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KnowledgeServiceClient(builder.build(), "internal-test-token");
    }

    @Test
    void mapsApprovedChapterSearchResponseToFrozenPublicDto() {
        server.expect(once(), requestTo(URI.create("http://knowledge.test/internal/graph/search?course_id=1&q=%E5%87%BD%E6%95%B0&limit=5")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "internal-test-token"))
                .andRespond(withSuccess("""
                        {
                          "query": "函数",
                          "items": [
                            {
                              "id": "5.1.1",
                              "name": "函数定义",
                              "level": 3,
                              "node_type": "chapter",
                              "review_status": "APPROVED",
                              "content_version": 1,
                              "parent_id": "5.1",
                              "aliases": ["def语句"],
                              "keywords": ["def", "return"],
                              "difficulty": "BASIC",
                              "learning_objectives": ["能够定义函数"],
                              "common_misconceptions": ["混淆定义和调用"]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.search(1L, "函数", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).parentId()).isEqualTo("5.1");
        assertThat(result.get(0).learningObjectives()).containsExactly("能够定义函数");
        server.verify();
    }

    @Test
    void sendsOnlyStudentMasteryAndMapsVersionedApprovedRecommendations() {
        server.expect(once(), requestTo("http://knowledge.test/internal/graph/recommend-next?course_id=1"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "internal-test-token"))
                .andExpect(header("X-Request-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.student_id").value(7))
                .andExpect(jsonPath("$.mastery[0].knowledge_point_id").value("kp"))
                .andExpect(jsonPath("$.mastery[0].score").value(55))
                .andExpect(jsonPath("$.mastery[0].confidence").value(0.4))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andRespond(withSuccess("""
                    {"course_id":1,"items":[{"node":{"id":"next","name":"Next","level":1,"node_type":"knowledge_point",
                    "review_status":"APPROVED","content_version":1},"reason":"r","priority":1,"evidence":["e"]}],
                    "graph_version":"g5"}
                    """,MediaType.APPLICATION_JSON));
        var response=client.recommendNext(1L, 7L, List.of(new KnowledgeRecommendationDtos.MasteryInput("kp",new BigDecimal("55"),new BigDecimal("0.4"))), 5);
        assertThat(response.graphVersion()).isEqualTo("g5");assertThat(response.items()).singleElement().satisfies(item->assertThat(item.node().reviewStatus()).isEqualTo("APPROVED"));server.verify();
    }

    @Test
    void mapsRequiresRelationToPublicPrerequisiteRelation() {
        server.expect(once(), requestTo(URI.create(
                        "http://knowledge.test/internal/graph/subgraph?course_id=1&center_id=5.1.1&depth=2&relations=contains,requires,related")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "course_id": 1, "center_id": "5.1.1",
                          "graph_version": "v2-test",
                          "nodes": [
                            {"id": "5.1.1", "name": "函数定义", "level": 3, "node_type": "knowledge_point", "review_status": "APPROVED", "content_version": 1, "parent_id": "5.1"},
                            {"id": "4.1.1", "name": "变量", "level": 3, "node_type": "knowledge_point", "review_status": "APPROVED", "content_version": 1, "parent_id": "4.1"}
                          ],
                          "edges": [
                            {
                              "source_id": "4.1.1",
                              "target_id": "5.1.1",
                              "relation": "requires",
                              "review_status": "APPROVED"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        KnowledgeGraphView result = client.subgraph(1L, "5.1.1", 2);

        assertThat(result.nodes()).hasSize(2);
        assertThat(result.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.relation()).isEqualTo("PREREQUISITE");
            assertThat(edge.reviewed()).isTrue();
        });
        server.verify();
    }

    @Test
    void rejectsMalformedOrUnreviewedGraphPayloadsAtTheClientBoundary() {
        server.expect(once(), requestTo(URI.create("http://knowledge.test/internal/graph/search?course_id=1&q=bad&limit=5")))
                .andRespond(withSuccess("""
                        {"query":"bad","items":[{"id":"kp","name":"Bad","level":1,
                        "node_type":"knowledge_point","review_status":"PENDING","content_version":1,
                        "aliases":[null]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.search(1L, "bad", 5))
                .isInstanceOfSatisfying(com.education.agent.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                com.education.agent.common.error.ErrorCode.GRAPH_SERVICE_UNAVAILABLE));
        server.verify();
    }

    @Test
    void ingestionCandidateFilterUsesProviderTypeParameter() {
        server.expect(once(), requestTo(URI.create(
                        "http://knowledge.test/internal/graph/ingestion-batches/batch-1/candidates"
                                + "?course_id=1&limit=20&cursor=c1&decision_status=PENDING&type=NODE&review_lane=STANDARD")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Request-Id", "request-candidate-filter"))
                .andRespond(withSuccess("{\"items\":[],\"next_cursor\":null}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getIngestionCandidates(1L, "request-candidate-filter", "batch-1", 20, "c1", "PENDING", "NODE", "STANDARD").items())
                .isEmpty();
        server.verify();
    }
}
