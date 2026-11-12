package com.education.agent;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiCognitiveLabel;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorChatRequest;
import com.education.agent.integration.ai.AiTutorChatResponse;
import com.education.agent.integration.ai.AiTutorContentBlock;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.file.mapper.FileObjectMapper;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CycleOneBackendIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FileObjectMapper fileObjectMapper;

    @MockBean
    private KnowledgeServiceClient knowledgeServiceClient;
    @MockBean
    private AiServiceClient aiServiceClient;

    @BeforeEach
    void approveGraphContextByDefault() {
        when(knowledgeServiceClient.ragContext(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenAnswer(invocation ->
                approvedGraphContext(invocation.getArgument(1)));
    }

    @Test
    void managedFileAndTeachingResourceRespectReviewAndOwnership() throws Exception {
        insertUser("resource_teacher", "Teacher@123", "资源教师", UserRole.TEACHER);
        insertUser("resource_admin", "Admin@123", "审核管理员", UserRole.ADMIN);
        insertUser("resource_student", "Student@123", "资源学生", UserRole.STUDENT);
        String teacherToken = token("resource_teacher", "Teacher@123", "TEACHER");
        String adminToken = token("resource_admin", "Admin@123", "ADMIN");
        String studentToken = token("resource_student", "Student@123", "STUDENT");

        byte[] bytes = "Python 变量教学资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "variables.pdf", "application/pdf", bytes
        );
        String uploadResponse = mockMvc.perform(multipart("/api/files")
                        .file(file)
                        .param("purpose", "TEACHING_RESOURCE")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalName").value("variables.pdf"))
                .andExpect(jsonPath("$.data.sha256").isNotEmpty())
                .andExpect(jsonPath("$.data.storageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long fileId = objectMapper.readTree(uploadResponse).at("/data/id").asLong();

        String createBody = objectMapper.writeValueAsString(Map.of("courseId",1L,
                "title", "变量入门",
                "description", "用于第一周期联调",
                "resourceType", "DOCUMENT",
                "fileId", fileId,
                "difficulty", "BEGINNER",
                "knowledgePointIds", List.of("kp.variables")
        ));
        String resourceResponse = mockMvc.perform(post("/api/resources").param("courseId","1")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.knowledgePointIds[0]").value("kp.variables"))
                .andReturn().getResponse().getContentAsString();
        long resourceId = objectMapper.readTree(resourceResponse).at("/data/id").asLong();

        mockMvc.perform(get("/api/resources").param("courseId","1")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(post("/api/resources/{resourceId}/review", resourceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"APPROVED","comment":"来源与知识点均已审核"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));

        mockMvc.perform(get("/api/resources").param("courseId","1")
                        .param("knowledgePointId", "kp.variables")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].file.downloadUrl")
                        .value("/api/files/" + fileId + "/content"));

        mockMvc.perform(get("/api/files/{fileId}/content", fileId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/files/{fileId}", fileId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void activeUploadContentTypeCannotCreateSameOriginScriptExecution() throws Exception {
        insertUser("safe_file_teacher", "Teacher@123", "安全资源教师", UserRole.TEACHER);
        String teacherToken = token("safe_file_teacher", "Teacher@123", "TEACHER");

        MockMultipartFile html = new MockMultipartFile(
                "file",
                "lesson.html",
                "text/html",
                "<script>window.top.location='https://attacker.invalid'</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/files")
                        .file(html)
                        .param("purpose", "TEACHING_RESOURCE")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));

        MockMultipartFile forgedText = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/html",
                "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String forgedUpload=mockMvc.perform(multipart("/api/files")
                        .file(forgedText)
                        .param("purpose", "TEACHING_RESOURCE")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("text/plain"))
                .andReturn().getResponse().getContentAsString();
        long legacyId=objectMapper.readTree(forgedUpload).at("/data/id").asLong();
        var legacy=fileObjectMapper.selectById(legacyId);legacy.setOriginalName("legacy.html");legacy.setContentType("text/html");fileObjectMapper.updateById(legacy);
        mockMvc.perform(get("/api/files/{fileId}/content", legacyId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void resourceApprovalRejectsBindingsChangedDuringGraphValidation() throws Exception {
        insertUser("review_race_teacher", "Teacher@123", "并发资源教师", UserRole.TEACHER);
        insertUser("review_race_admin", "Admin@123", "并发审核管理员", UserRole.ADMIN);
        String teacherToken = token("review_race_teacher", "Teacher@123", "TEACHER");
        String adminToken = token("review_race_admin", "Admin@123", "ADMIN");

        MockMultipartFile file = new MockMultipartFile(
                "file", "race.txt", "text/plain", "并发审核资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String upload = mockMvc.perform(multipart("/api/files")
                        .file(file)
                        .param("purpose", "TEACHING_RESOURCE")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long fileId = objectMapper.readTree(upload).at("/data/id").asLong();

        String createBody = objectMapper.writeValueAsString(Map.of("courseId",1L,
                "title", "并发审核资源",
                "resourceType", "DOCUMENT",
                "fileId", fileId,
                "difficulty", "BEGINNER",
                "knowledgePointIds", List.of("kp.variables")
        ));
        String resource = mockMvc.perform(post("/api/resources").param("courseId","1")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long resourceId = objectMapper.readTree(resource).at("/data/id").asLong();

        when(knowledgeServiceClient.ragContext(1L, "kp.variables")).thenAnswer(invocation -> {
            jdbcTemplate.update(
                    "UPDATE edu_resource_knowledge_point SET knowledge_point_id = ? WHERE resource_id = ?",
                    "kp.changed-after-validation",
                    resourceId
            );
            return approvedGraphContext("kp.variables");
        });

        mockMvc.perform(post("/api/resources/{resourceId}/review", resourceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewStatus":"APPROVED","comment":"不应通过"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));
    }

    @Test
    void tutorConversationPersistsReviewedReplyAndReplaysIdempotently() throws Exception {
        insertUser("tutor_student", "Student@123", "导师学生", UserRole.STUDENT);
        String token = token("tutor_student", "Student@123", "STUDENT");
        when(aiServiceClient.tutorChat(any())).thenAnswer(invocation ->
                approvedAiReply(invocation.getArgument(0)));

        long sessionId = createTutorSession(token);
        String key = "1b0c9981-4de4-4d20-86c1-0b0da8fd6251";
        String first = mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .header("X-Request-Id", "cycle-one-tutor-request-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"变量和对象有什么关系？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("cycle-one-tutor-request-01"))
                .andExpect(jsonPath("$.data.assistantMessage.blocks[0].type").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.assistantMessage.sources[0].reviewed").value(true))
                .andExpect(jsonPath("$.data.cognitiveLabels[0].status").value("LEARNING"))
                .andReturn().getResponse().getContentAsString();
        long assistantMessageId = objectMapper.readTree(first).at("/data/assistantMessage/id").asLong();

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .header("X-Request-Id", "cycle-one-tutor-replay-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"变量和对象有什么关系？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("cycle-one-tutor-replay-01"))
                .andExpect(jsonPath("$.data.assistantMessage.id").value(assistantMessageId));
        verify(aiServiceClient, times(1)).tutorChat(any());

        mockMvc.perform(get("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.knowledgePoint.name").value("变量与对象"));

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/close", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tutorEventStreamEmitsOnlyValidatedCommittedReplies() throws Exception {
        insertUser("tutor_stream_student", "Student@123", "流式导师学生", UserRole.STUDENT);
        String token = token("tutor_stream_student", "Student@123", "STUDENT");
        when(aiServiceClient.tutorChat(any())).thenAnswer(invocation ->
                approvedAiReply(invocation.getArgument(0)));

        long sessionId = createTutorSession(token);
        String requestId = "cycle-one-tutor-stream-01";
        MvcResult started = mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages/stream", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "stream-key-000000000000000000000001")
                        .header("X-Request-Id", requestId)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"请流式解释变量\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Request-Id", requestId))
                .andReturn();

        String stream = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(stream)
                .contains("event:started", "event:delta", "event:complete")
                .doesNotContain("event:error");
        org.assertj.core.api.Assertions.assertThat(stream.indexOf("event:started"))
                .isLessThan(stream.indexOf("event:delta"));
        org.assertj.core.api.Assertions.assertThat(stream.indexOf("event:delta"))
                .isLessThan(stream.indexOf("event:complete"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?", Integer.class, sessionId
        )).isEqualTo(2);

        org.mockito.Mockito.doAnswer(invocation -> {
            AiTutorChatRequest aiRequest = invocation.getArgument(0);
            return new AiTutorChatResponse(1L, aiRequest.requestId(), aiRequest.knowledgePointId(), "不应发送的回答", List.of(new AiTutorContentBlock("markdown", "不应发送", null, null, null, Map.of())), List.of(Map.of(
                            "title", "未审核草稿",
                            "locator", "draft",
                            "reviewed", true,
                            "review_status", "APPROVED",
                            "rights_status", "RESTRICTED"
                    )), List.of(), null, "deepseek-test", "graph-rag-v1");
        }).when(aiServiceClient).tutorChat(any());
        long rejectedSessionId = createTutorSession(token);
        MvcResult rejected = mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages/stream", rejectedSessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "stream-key-000000000000000000000002")
                        .header("X-Request-Id", "cycle-one-tutor-stream-02")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"不要泄漏未验证回答\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String rejectedStream = mockMvc.perform(asyncDispatch(rejected))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(rejectedStream)
                .contains("event:started", "event:error", "AI_SERVICE_INVALID_RESPONSE")
                .doesNotContain("event:delta", "不应发送的回答");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",
                Integer.class,
                rejectedSessionId
        )).isZero();
    }

    @Test
    void aiFailureIsExplicitAndDoesNotPersistOrPoisonIdempotencyKey() throws Exception {
        insertUser("degraded_student", "Student@123", "降级学生", UserRole.STUDENT);
        String token = token("degraded_student", "Student@123", "STUDENT");
        when(aiServiceClient.tutorChat(any())).thenThrow(
                new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 导师服务暂时不可用")
        );
        long sessionId = createTutorSession(token);
        String key = "a594dfb5-ab34-4b21-af28-a811376fd13f";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages", sessionId)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"请解释变量\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"));
        }
        verify(aiServiceClient, times(2)).tutorChat(any());

        mockMvc.perform(get("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(0));

        doAnswer(invocation -> {
            AiTutorChatRequest request = invocation.getArgument(0);
            return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "缺少权利状态的回答", List.of(new AiTutorContentBlock("markdown", "回答", null, null, null, Map.of())), List.of(Map.of("title", "旧来源", "reviewed", true, "review_status", "APPROVED")), List.of(), null, "deepseek-test", "graph-rag-v1");
        }).when(aiServiceClient).tutorChat(any());
        long legacySessionId = createTutorSession(token);
        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages", legacySessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "source-rights-missing-key-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"旧来源不能引用\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_INVALID_RESPONSE"));
        mockMvc.perform(get("/api/tutor/sessions/{sessionId}", legacySessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }

    @Test
    void unapprovedAiSourceIsRejectedBeforeAnyMessageIsSaved() throws Exception {
        insertUser("source_guard_student", "Student@123", "来源学生", UserRole.STUDENT);
        String token = token("source_guard_student", "Student@123", "STUDENT");
        when(aiServiceClient.tutorChat(any())).thenAnswer(invocation -> {
            AiTutorChatRequest request = invocation.getArgument(0);
            return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "不应落库的回答", List.of(new AiTutorContentBlock("markdown", "回答", null, null, null, Map.of())), List.of(Map.of(
                            "title", "未审核草稿",
                            "locator", "draft",
                            "reviewed", true,
                            "review_status", "PENDING"
                    )), List.of(), null, "deepseek-test", "graph-rag-v1");
        });
        long sessionId = createTutorSession(token);

        mockMvc.perform(post("/api/tutor/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "50da62ce-a39e-4246-9331-510266f7af2d")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"引用草稿回答\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_INVALID_RESPONSE"));

        mockMvc.perform(get("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }

    @Test
    void studentCanSoftDeleteOnlyOwnTutorSession() throws Exception {
        insertUser("tutor_delete_owner", "Student@123", "删除会话学生", UserRole.STUDENT);
        insertUser("tutor_delete_other", "Student@123", "其他学生", UserRole.STUDENT);
        String ownerToken = token("tutor_delete_owner", "Student@123", "STUDENT");
        String otherToken = token("tutor_delete_other", "Student@123", "STUDENT");
        long sessionId = createTutorSession(ownerToken);

        mockMvc.perform(delete("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        mockMvc.perform(get("/api/tutor/sessions/{sessionId}", sessionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tutor/sessions").param("courseId","1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM edu_tutor_session WHERE id=?",
                Boolean.class, sessionId)).isTrue();
    }

    private long createTutorSession(String token) throws Exception {
        String response = mockMvc.perform(post("/api/tutor/sessions").param("courseId","1")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId":1,"knowledgePointId":"kp.variables","entrySource":"GRAPH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.knowledgePoint.id").value("kp.variables"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    private InternalGraphRagContextResponse approvedGraphContext(String id) {
        InternalGraphNode node = new InternalGraphNode(
                id, "变量与对象", 3, "knowledge_point", "chapter.variables", "变量保存对象引用",
                List.of("变量"), List.of("变量", "对象"), "BEGINNER",
                List.of("理解赋值"), List.of("变量等同于盒子"), "APPROVED", 1
        );
        return new InternalGraphRagContextResponse(1L, node, List.of(), List.of(), List.of(), "graph-cycle-1", "READY", List.of());
    }

    private AiTutorChatResponse approvedAiReply(AiTutorChatRequest request) {
        return new AiTutorChatResponse(1L, request.requestId(), request.knowledgePointId(), "变量是名称，对象是内存中的值；赋值让名称引用对象。", List.of(new AiTutorContentBlock(
                        "markdown", "变量是名称，对象是值。", null, "概念解释", null, Map.of()
                )), List.of(Map.of(
                        "id", "source-kp-variables-01",
                        "title", "Python程序设计基础",
                        "locator", "第2章 2.1节",
                        "excerpt", "变量通过赋值绑定对象",
                        "reviewed", true,
                        "review_status", "APPROVED",
                        "rights_status", "ALLOWED"
                )), List.of(new AiCognitiveLabel(
                        request.knowledgePointId(), "LEARNING", new BigDecimal("0.82"), "学生正在区分名称和对象"
                )), "尝试预测两次赋值后的变量引用", "deepseek-test", "graph-rag-v1");
    }

    private String token(String username, String password, String expectedRole) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "expectedRole", expectedRole
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return response.at("/data/tokens/accessToken").asText();
    }

    private UserEntity insertUser(String username, String password, String displayName, UserRole role) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setForcePasswordChange(false);
        userMapper.insert(user);
        if(role==UserRole.TEACHER)CourseTestFixtures.teacher(jdbcTemplate,user.getId());
        if(role==UserRole.STUDENT)CourseTestFixtures.student(jdbcTemplate,user.getId());
        return userMapper.selectById(user.getId());
    }
}
