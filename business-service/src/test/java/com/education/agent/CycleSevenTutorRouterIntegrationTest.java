package com.education.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorAgentTrace;
import com.education.agent.integration.ai.AiTutorChatRequest;
import com.education.agent.integration.ai.AiTutorChatResponse;
import com.education.agent.integration.ai.AiTutorContentBlock;
import com.education.agent.integration.ai.AiTutorToolInvocation;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.dto.TutorReply;
import com.education.agent.tutor.dto.TutorSessionCreateRequest;
import com.education.agent.tutor.model.TutorEntrySource;
import com.education.agent.tutor.service.TutorService;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "education.tutor.agent-mode=ROUTER",
    "education.graph-ingestion.scan-ms=3600000",
    "education.graph-ingestion.initial-delay-ms=3600000",
    "education.memory.outbox-scan-ms=3600000",
    "education.memory.outbox-initial-delay-ms=3600000",
    "education.profile.summary-scan-ms=3600000",
    "education.profile.summary-initial-delay-ms=3600000",
    "education.assessment.lifecycle-scan-ms=3600000"
})
class CycleSevenTutorRouterIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired TutorService tutor;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired PasswordEncoder passwords;
  @MockBean AiServiceClient ai;
  @MockBean KnowledgeServiceClient graph;

  @BeforeEach
  void setUp() {
    reset(ai, graph);
  }

  @Test
  void routerModeUsesOnlyAuthoritativeCurrentStudentMasteryAndKeepsTracePrivate() throws Exception {
    long studentId = insertStudent("router_student_" + System.nanoTime());
    long foreignStudentId = insertStudent("foreign_student_" + System.nanoTime());
    jdbc.update("""
        INSERT INTO edu_knowledge_mastery(course_id,
          student_id,knowledge_point_id,knowledge_point_name,score,confidence,
          mastery_status,evidence_count,updated_at)
        VALUES(1,?,?,?,?,?,'LEARNING',1,CURRENT_TIMESTAMP)
        """, studentId, "kp-current", "当前知识点", new BigDecimal("82"), new BigDecimal("0.9"));
    jdbc.update("""
        INSERT INTO edu_knowledge_mastery(course_id,
          student_id,knowledge_point_id,knowledge_point_name,score,confidence,
          mastery_status,evidence_count,updated_at)
        VALUES(1,?,?,?,?,?,'LEARNING',1,CURRENT_TIMESTAMP)
        """, studentId, "kp-other", "其他知识点", new BigDecimal("64"), new BigDecimal("0.7"));
    jdbc.update("""
        INSERT INTO edu_knowledge_mastery(course_id,
          student_id,knowledge_point_id,knowledge_point_name,score,confidence,
          mastery_status,evidence_count,updated_at)
        VALUES(1,?,?,?,?,?,'MASTERED',1,CURRENT_TIMESTAMP)
        """, foreignStudentId, "kp-foreign", "外部学生知识点", new BigDecimal("99"), BigDecimal.ONE);
    InternalGraphNode node = new InternalGraphNode(
        "kp-current", "当前知识点", 3, "knowledge_point", "chapter", "description",
        List.of(), List.of(), "BASIC", List.of(), List.of(), "APPROVED", 1);
    when(graph.ragContext(1L, "kp-current")).thenReturn(new InternalGraphRagContextResponse(1L, node, List.of(), List.of(), List.of(), "graph-v1", "READY", List.of()));
    when(ai.tutorChat(any())).thenAnswer(invocation -> {
      AiTutorChatRequest request = invocation.getArgument(0);
      return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "Router 已返回安全回答", List.of(new AiTutorContentBlock(
              "MARKDOWN", "**安全回答**", null, null, null, Map.of())), List.of(Map.of("title", "图谱来源", "locator", "kp-current",
              "review_status", "APPROVED", "rights_status", "ALLOWED", "reviewed", true)), List.of(), List.of(), null, false, "继续练习", "router-model", "router-prompt", new AiTutorAgentTrace("ROUTER", "ROUTER", null,
              List.of(new AiTutorToolInvocation(
                  1, "graph.rag_context", "NEED_GRAPH_CONTEXT", "SUCCEEDED", 8, 1, null)), 1));
    });
    AuthenticatedUser student = new AuthenticatedUser(
        studentId, "student", "x", UserRole.STUDENT, true, true);
    var session = tutor.create(student,
        new TutorSessionCreateRequest(1L, "kp-current", TutorEntrySource.MANUAL, null));

    TutorReply reply = tutor.sendMessage(
        student, session.id(), "router-idempotency-key-0001", "请解释当前知识点",
        "router-request-0000000000000001");

    ArgumentCaptor<AiTutorChatRequest> request = ArgumentCaptor.forClass(AiTutorChatRequest.class);
    verify(ai).tutorChat(request.capture());
    assertThat(request.getValue().agentMode()).isEqualTo("ROUTER");
    assertThat(request.getValue().masteryContext())
        .extracting(value -> value.knowledgePointId())
        .containsExactly("kp-current", "kp-other")
        .doesNotContain("kp-foreign");
    assertThat(request.getValue().masteryContext().get(0).score()).isEqualByComparingTo("82");
    assertThat(reply.assistantMessage().content()).contains("Router");
    assertThat(json.writeValueAsString(reply))
        .doesNotContain("agentTrace", "agent_trace", "toolInvocations", "tool_invocations");
    String audit = jdbc.queryForObject("""
        SELECT details_json FROM edu_audit_log
        WHERE action='TUTOR_AI_CALL' AND target_id=? ORDER BY id DESC LIMIT 1
        """, String.class, session.id().toString());
    assertThat(audit).contains(
            "ROUTER", "graph.rag_context", "NEED_GRAPH_CONTEXT", "SUCCEEDED",
            "durationMs", "itemCount")
        .doesNotContain("observation", "arguments", "chain");
    var unsafeStoredAudit = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(audit);
    var unsafeStoredAgent = (com.fasterxml.jackson.databind.node.ObjectNode) unsafeStoredAudit.get("agent");
    unsafeStoredAgent.put("reasonCode", "MODEL_REASON_MUST_NOT_LEAK");
    unsafeStoredAgent.put("errorCode", "INTERNAL_SECRET");
    unsafeStoredAgent.put("observation", "raw tool output must not leak");
    unsafeStoredAgent.put("chainOfThought", "private reasoning must not leak");
    var unsafeStoredTool = (com.fasterxml.jackson.databind.node.ObjectNode)
        unsafeStoredAgent.withArray("tools").get(0);
    unsafeStoredTool.put("observation", "nested raw tool output must not leak");
    unsafeStoredTool.put("arguments", "nested tool arguments must not leak");
    unsafeStoredTool.put("chainOfThought", "nested private reasoning must not leak");
    unsafeStoredAudit.put("rawObservation", "also not a top-level allowlisted field");
    jdbc.update("""
        UPDATE edu_audit_log SET details_json=?
        WHERE action='TUTOR_AI_CALL' AND target_id=?
        """, json.writeValueAsString(unsafeStoredAudit), session.id().toString());

    String adminUsername = "router_audit_admin_" + System.nanoTime();
    jdbc.update("""
        INSERT INTO edu_user(username,password_hash,display_name,role,status)
        VALUES(?,?, 'Router Audit Admin','ADMIN','ACTIVE')
        """, adminUsername, passwords.encode("AdminPass@123"));
    String adminLogin = mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "username", adminUsername, "password", "AdminPass@123", "expectedRole", "ADMIN"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String adminToken = json.readTree(adminLogin).at("/data/tokens/accessToken").asText();
    mvc.perform(get("/api/admin/audit-logs").param("action", "TUTOR_AI_CALL")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].details.modelVersion").value("router-model"))
        .andExpect(jsonPath("$.data.items[0].details.promptVersion").value("router-prompt"))
        .andExpect(jsonPath("$.data.items[0].details.sourceCount").value(1))
        .andExpect(jsonPath("$.data.items[0].details.agent.requestedMode").value("ROUTER"))
        .andExpect(jsonPath("$.data.items[0].details.agent.effectiveMode").value("ROUTER"))
        .andExpect(jsonPath("$.data.items[0].details.agent.steps").value(1))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].step").value(1))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].tool").value("graph.rag_context"))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].reasonCode").value("NEED_GRAPH_CONTEXT"))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].durationMs").value(8))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].itemCount").value(1))
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].errorCode").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].observation").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].arguments").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.tools[0].chainOfThought").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.reasonCode").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.errorCode").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.observation").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.agent.chainOfThought").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].details.rawObservation").doesNotExist());
  }

  private long insertStudent(String username) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO edu_user(username,password_hash,display_name,role,status)
          VALUES(?, 'x', 'Student', 'STUDENT', 'ACTIVE')
          """, new String[] {"id"});
      statement.setString(1, username);
      return statement;
    }, keys);
    long studentId=Objects.requireNonNull(keys.getKey()).longValue();
    CourseTestFixtures.student(jdbc,studentId);
    return studentId;
  }
}
