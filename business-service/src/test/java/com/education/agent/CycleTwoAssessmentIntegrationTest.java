package com.education.agent;

import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import com.education.agent.assessment.mapper.AssessmentImportTaskMapper;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.assessment.service.AssessmentImportRecovery;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiAssessmentParseRequest;
import com.education.agent.integration.ai.AiAssessmentParseResponse;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CycleTwoAssessmentIntegrationTest {

    private static final Path TEST_STORAGE = Path.of("D:/CodexEnvs/EducationAgent/test-storage");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ClassMapper classMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AssessmentImportTaskMapper importTaskMapper;
    @Autowired private AssessmentImportRecovery importRecovery;

    @MockBean private KnowledgeServiceClient knowledgeServiceClient;
    @MockBean private AiServiceClient aiServiceClient;

    @BeforeEach
    void resetDependencies() {
        Mockito.reset(knowledgeServiceClient, aiServiceClient);
        when(knowledgeServiceClient.ragContext(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenAnswer(invocation ->
                approvedGraphContext(invocation.getArgument(1)));
    }

    @Test
    @Transactional
    void teacherAuthorsPublishesImmutableSnapshotAndCopiesAllFiveQuestionTypes() throws Exception {
        UserEntity teacher = insertUser("c2_author_teacher", UserRole.TEACHER);
        UserEntity student = insertUser("c2_author_student", UserRole.STUDENT);
        UserEntity outsider = insertUser("c2_outside_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 五题型班", teacher);
        jdbcTemplate.update("INSERT INTO edu_class_student(class_id, student_id) VALUES (?, ?)", classroom.getId(), student.getId());
        String teacherToken = token(teacher, "TEACHER");
        String studentToken = token(student, "STUDENT");
        String outsiderToken = token(outsider, "TEACHER");

        JsonNode created = createAssessment(teacherToken, classroom.getId(), completeAssessmentBody(classroom.getId(), "五题型作业", "kp.variables"));
        long assessmentId = created.at("/data/id").asLong();
        long sourceQuestionId = created.at("/data/questions/0/id").asLong();
        assertThat(created.at("/data/totalScore").decimalValue()).isEqualByComparingTo("60");
        assertThat(created.at("/data/questions").size()).isEqualTo(5);

        mockMvc.perform(get("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(outsiderToken)))
                .andExpect(status().isNotFound());

        String publishKey = "4fe84c77-7cf2-45dd-a660-62557ce9acb7";
        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .header("Idempotency-Key", publishKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.questions[0].knowledgePoints[0].name").value("变量与对象"));

        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .header("Idempotency-Key", publishKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_version WHERE assessment_id = ?", Long.class, assessmentId
        )).isEqualTo(1L);
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT snapshot_json FROM edu_assessment_version WHERE assessment_id = ?", String.class, assessmentId
        );
        assertThat(snapshot)
                .contains("\"status\":\"PUBLISHED\"")
                .contains("\"graphVersion\":\"graph-cycle-2\"")
                .contains("\"knowledgePointBindings\"")
                .contains("\"contentVersion\":1")
                .contains("变量与对象");

        mockMvc.perform(put("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeAssessmentBody(classroom.getId(), "不应修改", "kp.variables")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_STATE"));

        mockMvc.perform(get("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.questions.length()").value(0));

        String copiedText = mockMvc.perform(post("/api/assessments/{id}/copy", assessmentId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.questions.length()").value(5))
                .andExpect(jsonPath("$.data.questions[3].testCases.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode copied = objectMapper.readTree(copiedText);
        assertThat(copied.at("/data/id").asLong()).isNotEqualTo(assessmentId);
        assertThat(copied.at("/data/questions/0/id").asLong()).isNotEqualTo(sourceQuestionId);
        assertThat(copied.at("/data/questions/0/options/0/id").asLong())
                .isNotEqualTo(created.at("/data/questions/0/options/0/id").asLong());
        assertThat(copied.at("/data/questions/3/testCases/0/id").asLong())
                .isNotEqualTo(created.at("/data/questions/3/testCases/0/id").asLong());
        assertThat(copied.at("/data/currentVersion").asInt()).isEqualTo(1);
    }

    @Test
    @Transactional
    void teacherUpdatesOnlyTheDeadlineOfAnOpenPublishedAssessment() throws Exception {
        UserEntity teacher = insertUser("c2_deadline_teacher", UserRole.TEACHER);
        UserEntity outsider = insertUser("c2_deadline_outsider", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 截止时间班", teacher);
        String teacherToken = token(teacher, "TEACHER");
        String outsiderToken = token(outsider, "TEACHER");

        JsonNode created = createAssessment(
                teacherToken,
                classroom.getId(),
                completeAssessmentBody(classroom.getId(), "允许调整截止时间", "kp.variables")
        );
        long assessmentId = created.at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .header("Idempotency-Key", "c2-update-deadline-publish-key"))
                .andExpect(status().isOk());

        Instant revisedDueAt = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.SECONDS);
        String request = objectMapper.writeValueAsString(Map.of("dueAt", revisedDueAt));
        mockMvc.perform(put("/api/assessments/{id}/deadline", assessmentId)
                        .header("Authorization", bearer(outsiderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNotFound());

        String updatedText = mockMvc.perform(put("/api/assessments/{id}/deadline", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(objectMapper.readTree(updatedText).at("/data/dueAt").asText()))
                .isEqualTo(revisedDueAt);

        String snapshot = jdbcTemplate.queryForObject(
                "SELECT snapshot_json FROM edu_assessment_version WHERE assessment_id = ?", String.class, assessmentId
        );
        assertThat(Instant.parse(objectMapper.readTree(snapshot).path("dueAt").asText()))
                .isEqualTo(revisedDueAt);

        Instant readyToReleaseDueAt = revisedDueAt.plusSeconds(3600);
        jdbcTemplate.update(
                "UPDATE edu_assessment SET status = 'READY_TO_RELEASE' WHERE id = ?", assessmentId
        );
        mockMvc.perform(put("/api/assessments/{id}/deadline", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dueAt", readyToReleaseDueAt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));

        Instant releasedDueAt = readyToReleaseDueAt.plusSeconds(3600);
        jdbcTemplate.update(
                "UPDATE edu_assessment SET status = 'RELEASED' WHERE id = ?", assessmentId
        );
        mockMvc.perform(put("/api/assessments/{id}/deadline", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dueAt", releasedDueAt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));

        mockMvc.perform(put("/api/assessments/{id}/deadline", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dueAt", Instant.now().minusSeconds(60)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        JsonNode draft = createAssessment(
                teacherToken,
                classroom.getId(),
                emptyAssessmentBody(classroom.getId(), "未发布草稿")
        );
        mockMvc.perform(put("/api/assessments/{id}/deadline", draft.at("/data/id").asLong())
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_STATE"));
    }

    @Test
    @Transactional
    void publishFailsClosedForPendingKnowledgePointAndContradictoryChoiceAnswer() throws Exception {
        UserEntity teacher = insertUser("c2_validation_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 校验班", teacher);
        String token = token(teacher, "TEACHER");

        when(knowledgeServiceClient.ragContext(1L, "kp.pending")).thenReturn(graphContext(
                "kp.pending", "knowledge_point", "PENDING", 1, "READY", "graph-cycle-2"
        ));
        long pendingId = createAssessment(
                token, classroom.getId(), completeAssessmentBody(classroom.getId(), "待审核知识点", "kp.pending")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", pendingId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "2f48ce22-c7a3-4c71-b1c4-d125e32dc162"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM edu_assessment WHERE id = ?", String.class, pendingId
        )).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_version WHERE assessment_id = ?", Long.class, pendingId
        )).isZero();

        when(knowledgeServiceClient.ragContext(1L, "kp.forged")).thenThrow(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识点尚未审核发布或不存在")
        );
        long forgedId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "伪造知识点", "kp.forged", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", forgedId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "f4d2a56f-4e92-4532-813b-d8664617b0ec"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String contradictory = singleChoiceBody(classroom.getId(), "矛盾答案", "kp.variables", "B");
        long contradictoryId = createAssessment(token, classroom.getId(), contradictory).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", contradictoryId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "93dfb3c8-4dbd-48c1-a5a4-3c8ed7c2846c"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("正确选项完全一致")));

        when(knowledgeServiceClient.ragContext(1L, "kp.unavailable")).thenThrow(
                new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "知识图谱服务暂不可用")
        );
        long unavailableId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "图谱不可用", "kp.unavailable", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", unavailableId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "8ea77f89-9a1d-411d-97ee-c586c7a8cf50"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GRAPH_SERVICE_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM edu_assessment WHERE id = ?", String.class, unavailableId
        )).isEqualTo("DRAFT");

        when(knowledgeServiceClient.ragContext(1L, "0")).thenReturn(graphContext(
                "0", "course", "APPROVED", 1, "READY", "graph-cycle-2"
        ));
        long structuralId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "结构祖先", "0", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", structuralId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "5473fb5a-c079-4dd0-8fb7-07d28b6f4abd"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("知识点节点")));

        when(knowledgeServiceClient.ragContext(1L, "kp.rejected")).thenReturn(graphContext(
                "kp.rejected", "knowledge_point", "REJECTED", 1, "READY", "graph-cycle-2"
        ));
        long rejectedId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "拒绝节点", "kp.rejected", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", rejectedId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "acbed621-74a3-41a7-b50b-5d584a86e797"))
                .andExpect(status().isUnprocessableEntity());

        when(knowledgeServiceClient.ragContext(1L, "kp.missing-meta")).thenReturn(graphContext(
                "kp.missing-meta", null, null, null, "READY", "graph-cycle-2"
        ));
        long missingMetadataId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "元数据缺失", "kp.missing-meta", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", missingMetadataId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "ee7b8bdc-26f1-49c2-b483-ad90e7a8198b"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GRAPH_SERVICE_UNAVAILABLE"));

        when(knowledgeServiceClient.ragContext(1L, "kp.invalid-content-version")).thenReturn(graphContext(
                "kp.invalid-content-version", "knowledge_point", "APPROVED", 0, "READY", "graph-cycle-2"
        ));
        long invalidContentVersionId = createAssessment(
                token, classroom.getId(),
                singleChoiceBody(classroom.getId(), "内容版本无效", "kp.invalid-content-version", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", invalidContentVersionId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "abfefc33-7a08-47a5-a8d8-e4f86d94acd6"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GRAPH_SERVICE_UNAVAILABLE"));

        when(knowledgeServiceClient.ragContext(1L, "kp.insufficient")).thenReturn(graphContext(
                "kp.insufficient", "knowledge_point", "APPROVED", 1, "INSUFFICIENT", "graph-cycle-2"
        ));
        long insufficientId = createAssessment(
                token, classroom.getId(), singleChoiceBody(classroom.getId(), "上下文不足", "kp.insufficient", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", insufficientId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "66f079da-0086-4a5e-812b-c63e874b95e0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("图谱上下文")));

        when(knowledgeServiceClient.ragContext(1L, "kp.unknown-version")).thenReturn(graphContext(
                "kp.unknown-version", "knowledge_point", "APPROVED", 1, "READY", " unknown "
        ));
        long unknownVersionId = createAssessment(
                token, classroom.getId(), singleChoiceBody(
                        classroom.getId(), "未知图谱版本", "kp.unknown-version", "A"
                )
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", unknownVersionId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "7b08660e-cd7b-46d7-98a3-ad19ab7c806a"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GRAPH_SERVICE_UNAVAILABLE"));

        when(knowledgeServiceClient.ragContext(1L, "kp.graph-v1")).thenReturn(graphContext(
                "kp.graph-v1", "knowledge_point", "APPROVED", 2, "READY", "graph-v1"
        ));
        when(knowledgeServiceClient.ragContext(1L, "kp.graph-v2")).thenReturn(graphContext(
                "kp.graph-v2", "knowledge_point", "APPROVED", 3, "READY", "graph-v2"
        ));
        long mixedVersionId = createAssessment(
                token, classroom.getId(), twoKnowledgePointBody(classroom.getId())
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", mixedVersionId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "99356c3c-16d6-48ab-8c0e-7d34d52ad94d"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不同的知识图谱版本")));
    }

    @Test
    @Transactional
    void publishRequiresCompleteExamScheduleAndTypeSpecificQuestionRules() throws Exception {
        UserEntity teacher = insertUser("c2_schedule_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 考试与题型校验班", teacher);
        String token = token(teacher, "TEACHER");

        long missingStart = createAssessment(token, classroom.getId(), examBody(
                classroom.getId(), "缺开始时间", null, "2026-09-01T10:00:00Z", 60
        )).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", missingStart)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "30f82067-0a03-44bc-b3ea-e280f6000039"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("开始时间")));

        long missingDue = createAssessment(token, classroom.getId(), examBody(
                classroom.getId(), "缺截止时间", "2026-09-01T08:00:00Z", null, 60
        )).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", missingDue)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "a2edebbe-3b01-434a-9ac1-851c04d42398"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("截止时间")));

        long missingDuration = createAssessment(token, classroom.getId(), examBody(
                classroom.getId(), "缺答题时长", "2026-09-01T08:00:00Z", "2026-09-01T10:00:00Z", null
        )).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", missingDuration)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "2b938cbc-5398-4b7e-975a-1c02025ab87c"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("答题时长")));

        long invalidRange = createAssessment(token, classroom.getId(), examBody(
                classroom.getId(), "发布时范围复核", "2026-09-01T08:00:00Z", "2026-09-01T10:00:00Z", 60
        )).at("/data/id").asLong();
        jdbcTemplate.update(
                "UPDATE edu_assessment SET due_at = start_at WHERE id = ?",
                invalidRange
        );
        mockMvc.perform(post("/api/assessments/{id}/publish", invalidRange)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "5b659ded-6751-44fc-b49e-b3cfab1c1316"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("开始时间必须早于截止时间")));

        long subjectiveWithoutAnswer = createAssessment(
                token, classroom.getId(), subjectiveWithoutAnswerBody(classroom.getId())
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", subjectiveWithoutAnswer)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "8642539c-ae2a-4cd0-be9c-65114d577b4e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        long publicOnlyProgramming = createAssessment(
                token, classroom.getId(), publicOnlyProgrammingBody(classroom.getId())
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", publicOnlyProgramming)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "fdbcc6dd-7bfa-4546-9328-96507b264fcc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("公开测试点和一个隐藏测试点")));
    }

    @Test
    @Transactional
    void scoreContractIsEnforcedAtDtoDraftTotalAndPublicationBoundaries() throws Exception {
        UserEntity teacher = insertUser("c2_score_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 分值校验班", teacher);
        String token = token(teacher, "TEACHER");

        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScoreBody(classroom.getId(), "question", "0.001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScoreBody(classroom.getId(), "rubric", "0.001")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScoreBody(classroom.getId(), "test", "0.001")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overflowTotalBody(classroom.getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("测评总分")));

        long assessmentId = createAssessment(
                token, classroom.getId(), completeAssessmentBody(classroom.getId(), "发布时分值复核", "kp.variables")
        ).at("/data/id").asLong();
        long subjectiveQuestionId = jdbcTemplate.queryForObject(
                "SELECT id FROM edu_assessment_question WHERE assessment_id = ? AND question_type = 'SUBJECTIVE'",
                Long.class, assessmentId
        );
        long programmingQuestionId = jdbcTemplate.queryForObject(
                "SELECT id FROM edu_assessment_question WHERE assessment_id = ? AND question_type = 'PROGRAMMING'",
                Long.class, assessmentId
        );

        jdbcTemplate.update("UPDATE edu_assessment_question SET score = 0 WHERE id = ?", subjectiveQuestionId);
        assertPublishValidationFailure(token, assessmentId, "4a26ae68-e07a-4138-8470-da9a06d37f4b", "题目分值");
        jdbcTemplate.update("UPDATE edu_assessment_question SET score = 10 WHERE id = ?", subjectiveQuestionId);

        jdbcTemplate.update("UPDATE edu_question_rubric_item SET max_score = 0 WHERE question_id = ?", subjectiveQuestionId);
        assertPublishValidationFailure(token, assessmentId, "50ba05ef-f858-4663-b11b-f1859d1ec0f2", "评分点分值");
        jdbcTemplate.update("UPDATE edu_question_rubric_item SET max_score = 10 WHERE question_id = ?", subjectiveQuestionId);

        jdbcTemplate.update("UPDATE edu_programming_test_case SET score = 0 WHERE question_id = ? AND visibility = 'HIDDEN'", programmingQuestionId);
        assertPublishValidationFailure(token, assessmentId, "556f7300-67cc-4180-9f43-066e0f888263", "测试点分值");
    }

    @Test
    @Transactional
    void administratorCanAuditAssessmentsButEveryAssessmentMutationIsForbidden() throws Exception {
        UserEntity teacher = insertUser("c2_admin_boundary_teacher", UserRole.TEACHER);
        UserEntity admin = insertUser("c2_admin_boundary_admin", UserRole.ADMIN);
        ClassEntity classroom = insertClass("C2 管理员只读班", teacher);
        String teacherToken = token(teacher, "TEACHER");
        String adminToken = token(admin, "ADMIN");
        JsonNode draft = createAssessment(
                teacherToken, classroom.getId(), singleChoiceBody(
                        classroom.getId(), "管理员只读测评", "kp.variables", "A"
                )
        );
        long assessmentId = draft.at("/data/id").asLong();
        long questionId = draft.at("/data/questions/0/id").asLong();

        mockMvc.perform(get("/api/assessments").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/assessments/{id}", assessmentId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(assessmentId));

        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyAssessmentBody(classroom.getId(), "管理员不得创建")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleChoiceBody(classroom.getId(), "管理员不得保存", "kp.variables", "A")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assessments/{id}/copy", assessmentId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assessments/{id}/questions", assessmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleQuestionJson("admin-question", 2, "管理员不得新增")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/assessments/{assessmentId}/questions/{questionId}", assessmentId, questionId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleQuestionJson("single-only", 1, "管理员不得修改")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/assessments/{assessmentId}/questions/{questionId}", assessmentId, questionId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/assessments/{id}/questions/reorder", assessmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + questionId + "]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/assessments/{id}/import-files", assessmentId)
                        .file(new MockMultipartFile("file", "admin.txt", "text/plain", "denied".getBytes()))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(adminToken))
                        .header("Idempotency-Key", "29c4f6e1-8c12-4585-94a5-9b50931055b7"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment WHERE id = ? AND status = 'DRAFT'", Long.class, assessmentId
        )).isEqualTo(1L);
    }

    @Test
    @Transactional
    void draftSupportsQuestionCrudReorderAndAtomicSaveWithoutChangingStableQuestionIds() throws Exception {
        UserEntity teacher = insertUser("c2_crud_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 题目 CRUD 班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "CRUD 草稿")
        ).at("/data/id").asLong();

        String firstText = mockMvc.perform(post("/api/assessments/{id}/questions", assessmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleQuestionJson("crud-single", 1, "原始题干")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = objectMapper.readTree(firstText).at("/data/id").asLong();

        String secondText = mockMvc.perform(post("/api/assessments/{id}/questions", assessmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subjectiveQuestionJson("crud-subjective", 2, "解释变量")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long secondId = objectMapper.readTree(secondText).at("/data/id").asLong();

        mockMvc.perform(put("/api/assessments/{id}/questions/reorder", assessmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + secondId + "," + firstId + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/assessments/{assessmentId}/questions/{questionId}", assessmentId, firstId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleQuestionJson("crud-single", 2, "更新后的题干")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId))
                .andExpect(jsonPath("$.data.stem").value("更新后的题干"));

        String atomicBody = """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"原子保存后的草稿","settings":{},"questions":[
                  {"id":%d,"clientKey":"crud-subjective","questionType":"SUBJECTIVE","stem":"解释变量绑定","score":10,"position":1,
                   "referenceAnswer":{"textAnswer":"变量名绑定对象"},"rubricItems":[{"clientKey":"crud-rubric","label":"概念","description":"说明绑定关系","maxScore":10,"position":1}]},
                  {"id":%d,"clientKey":"crud-single","questionType":"SINGLE_CHOICE","stem":"原子保存仍保留ID","score":10,"position":2,
                   "options":[{"key":"A","content":"正确","isCorrect":true,"position":1},{"key":"B","content":"错误","isCorrect":false,"position":2}],"referenceAnswer":{"selectedOptionKeys":["A"]}}
                ]}
                """.formatted(classroom.getId(), secondId, firstId);
        mockMvc.perform(put("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(atomicBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].id").value(secondId))
                .andExpect(jsonPath("$.data.questions[1].id").value(firstId));

        mockMvc.perform(delete("/api/assessments/{assessmentId}/questions/{questionId}", assessmentId, secondId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions.length()").value(1))
                .andExpect(jsonPath("$.data.questions[0].id").value(firstId))
                .andExpect(jsonPath("$.data.questions[0].position").value(1))
                .andExpect(jsonPath("$.data.totalScore").value(10));
    }

    @Test
    @Transactional
    void teacherSoftDeletesPublishedAssessmentWithoutDestroyingStudentWork() throws Exception {
        UserEntity teacher = insertUser("c2_delete_published_teacher", UserRole.TEACHER);
        UserEntity student = insertUser("c2_delete_published_student", UserRole.STUDENT);
        UserEntity outsider = insertUser("c2_delete_published_outsider", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 发布删除班", teacher);
        jdbcTemplate.update("INSERT INTO edu_class_student(class_id, student_id) VALUES (?, ?)",
                classroom.getId(), student.getId());
        String teacherToken = token(teacher, "TEACHER");
        String studentToken = token(student, "STUDENT");
        String outsiderToken = token(outsider, "TEACHER");
        long assessmentId = createAssessment(
                teacherToken, classroom.getId(),
                singleChoiceBody(classroom.getId(), "可删除的已发布作业", "kp.variables", "A")
        ).at("/data/id").asLong();
        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(teacherToken))
                        .header("Idempotency-Key", "c2-delete-published-key-0001"))
                .andExpect(status().isOk());
        long submissionId = objectMapper.readTree(mockMvc.perform(
                        post("/api/assessments/{id}/submissions", assessmentId)
                                .header("Authorization", bearer(studentToken))
                                .header("Idempotency-Key", "c2-delete-start-key-000001"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/submission/id").asLong();

        mockMvc.perform(delete("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(outsiderToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM edu_assessment WHERE id=?",
                Boolean.class, assessmentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_submission WHERE id=? AND assessment_id=?",
                Long.class, submissionId, assessmentId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_version WHERE assessment_id=?",
                Long.class, assessmentId)).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void importProducesEditableCandidatesDeduplicatesAndDraftDeletionCleansSourceFiles() throws Exception {
        UserEntity teacher = insertUser("c2_import_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 导入班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "AI 导入")
        ).at("/data/id").asLong();

        when(aiServiceClient.parseAssessment(any())).thenAnswer(invocation -> successfulParse(invocation.getArgument(0)));
        byte[] fileBytes = "1. 编写程序输出 hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long taskId = uploadImport(token, assessmentId, "questions.txt", fileBytes)
                .at("/data/id").asLong();
        awaitTask(taskId, TaskStatus.SUCCEEDED);

        mockMvc.perform(get("/api/assessments/{assessmentId}/import-tasks/{taskId}", assessmentId, taskId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.candidateQuestions[0].programmingConfig.language").value("PYTHON"))
                .andExpect(jsonPath("$.data.candidateQuestions[0].programmingConfig.timeLimitMs").value(2000))
                .andExpect(jsonPath("$.data.candidateQuestions[0].warnings[0]").value(org.hamcrest.Matchers.containsString("安全默认值")))
                .andExpect(jsonPath("$.data.candidateQuestions[1].attachmentPolicy.maxFileCount").value(1));

        long replayTaskId = uploadImport(token, assessmentId, "same-again.txt", fileBytes)
                .at("/data/id").asLong();
        assertThat(replayTaskId).isEqualTo(taskId);
        verify(aiServiceClient, times(1)).parseAssessment(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_file_object WHERE owner_id = ? AND purpose = 'ASSESSMENT_SOURCE' AND status = 'AVAILABLE'",
                Long.class, teacher.getId()
        )).isEqualTo(1L);

        mockMvc.perform(delete("/api/assessments/{id}", assessmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_import_task WHERE assessment_id = ?", Long.class, assessmentId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_file_object WHERE owner_id = ? AND purpose = 'ASSESSMENT_SOURCE' AND status = 'AVAILABLE'",
                Long.class, teacher.getId()
        )).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedImportRestoresDraftAndSameFileRetriesTheSameTask() throws Exception {
        UserEntity teacher = insertUser("c2_retry_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 重试班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "失败可重试")
        ).at("/data/id").asLong();
        byte[] bytes = "失败后重试".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(aiServiceClient.parseAssessment(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 题目解析超时"))
                .thenAnswer(invocation -> successfulParse(invocation.getArgument(0)));

        long taskId = uploadImport(token, assessmentId, "retry.txt", bytes).at("/data/id").asLong();
        awaitTask(taskId, TaskStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM edu_assessment WHERE id = ?", String.class, assessmentId
        )).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_at FROM edu_assessment_import_task WHERE id = ?", java.sql.Timestamp.class, taskId
        )).isNotNull();

        long retryTaskId = uploadImport(token, assessmentId, "retry-again.txt", bytes).at("/data/id").asLong();
        assertThat(retryTaskId).isEqualTo(taskId);
        awaitTask(taskId, TaskStatus.SUCCEEDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM edu_assessment_import_task WHERE id = ?", Integer.class, taskId
        )).isEqualTo(2);
        verify(aiServiceClient, times(2)).parseAssessment(any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedSameHashCannotRetryWhileAnotherImportForAssessmentIsActive() throws Exception {
        UserEntity teacher = insertUser("c2_retry_single_active_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 导入单活班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "失败重试单活")
        ).at("/data/id").asLong();
        byte[] failedBytes = "历史失败文件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] activeBytes = "当前活动文件".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(aiServiceClient.parseAssessment(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "首次解析失败"));
        long failedTaskId = uploadImport(token, assessmentId, "failed.txt", failedBytes).at("/data/id").asLong();
        awaitTask(failedTaskId, TaskStatus.FAILED);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "测试等待超时");
            }
            return successfulParse(invocation.getArgument(0));
        }).when(aiServiceClient).parseAssessment(any());
        long activeTaskId = uploadImport(token, assessmentId, "active-second.txt", activeBytes)
                .at("/data/id").asLong();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        awaitTask(activeTaskId, TaskStatus.RUNNING);

        mockMvc.perform(multipart("/api/assessments/{id}/import-files", assessmentId)
                        .file(new MockMultipartFile("file", "failed-retry.txt", "text/plain", failedBytes))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_STATE"));
        assertThat(importTaskMapper.selectById(failedTaskId).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_assessment_import_task WHERE assessment_id = ? AND status IN ('PENDING','RUNNING')",
                Long.class, assessmentId
        )).isEqualTo(1L);

        release.countDown();
        awaitTask(activeTaskId, TaskStatus.SUCCEEDED);
        verify(aiServiceClient, times(2)).parseAssessment(any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activeImportUsesInternalTokenAndRejectedSecondFileLeavesNoDatabaseOrDiskOrphan() throws Exception {
        UserEntity teacher = insertUser("c2_active_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 并发导入班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "并发导入")
        ).at("/data/id").asLong();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiServiceClient.parseAssessment(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "测试等待超时");
            }
            return successfulParse(invocation.getArgument(0));
        });
        long taskId = uploadImport(token, assessmentId, "active.txt", "active".getBytes()).at("/data/id").asLong();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        awaitTask(taskId, TaskStatus.RUNNING);
        long sourceFileId = jdbcTemplate.queryForObject(
                "SELECT source_file_id FROM edu_assessment_import_task WHERE id = ?", Long.class, taskId
        );
        String importRequestId = jdbcTemplate.queryForObject(
                "SELECT request_id FROM edu_assessment_import_task WHERE id = ?", String.class, taskId
        );

        mockMvc.perform(get("/api/files/{id}/content", sourceFileId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/{id}/content", sourceFileId)
                        .header("X-Internal-Token", "wrong-token")
                        .header("X-Request-Id", "download-request-wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/{id}/content", sourceFileId)
                        .header("X-Internal-Token", "test-internal-token")
                        .header("X-Request-Id", "another-task-request-id"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/{id}/content", sourceFileId)
                        .header("X-Internal-Token", "test-internal-token")
                        .header("X-Request-Id", importRequestId))
                .andExpect(status().isOk())
                .andExpect(content().bytes("active".getBytes()));

        long diskBefore = regularFileCount(TEST_STORAGE);
        MockMultipartFile second = new MockMultipartFile("file", "different.txt", "text/plain", "different".getBytes());
        mockMvc.perform(multipart("/api/assessments/{id}/import-files", assessmentId)
                        .file(second)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edu_file_object WHERE owner_id = ? AND purpose = 'ASSESSMENT_SOURCE' AND status = 'AVAILABLE'",
                Long.class, teacher.getId()
        )).isEqualTo(1L);
        assertThat(regularFileCount(TEST_STORAGE)).isEqualTo(diskBefore);

        release.countDown();
        awaitTask(taskId, TaskStatus.SUCCEEDED);
        mockMvc.perform(get("/api/files/{id}/content", sourceFileId)
                        .header("X-Internal-Token", "test-internal-token")
                        .header("X-Request-Id", importRequestId))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startupRecoveryNeverTreatsInterruptedTaskAsSuccess() throws Exception {
        UserEntity teacher = insertUser("c2_recovery_teacher", UserRole.TEACHER);
        ClassEntity classroom = insertClass("C2 恢复班", teacher);
        String token = token(teacher, "TEACHER");
        long assessmentId = createAssessment(
                token, classroom.getId(), emptyAssessmentBody(classroom.getId(), "恢复任务")
        ).at("/data/id").asLong();
        String upload = mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "recover.txt", "text/plain", "recover".getBytes()))
                        .param("purpose", "ASSESSMENT_SOURCE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long fileId = objectMapper.readTree(upload).at("/data/id").asLong();
        jdbcTemplate.update("UPDATE edu_assessment SET status = 'PARSING' WHERE id = ?", assessmentId);
        AssessmentImportTaskEntity task = new AssessmentImportTaskEntity();
        task.setAssessmentId(assessmentId);
        task.setSourceFileId(fileId);
        task.setRequestId("recovery-request-" + assessmentId);
        task.setDedupeHash("a".repeat(64));
        task.setStatus(TaskStatus.RUNNING);
        task.setProgressPercent(10);
        task.setAttemptCount(1);
        importTaskMapper.insert(task);

        importRecovery.run(null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM edu_assessment_import_task WHERE id = ?", String.class, task.getId()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_code FROM edu_assessment_import_task WHERE id = ?", String.class, task.getId()
        )).isEqualTo("IMPORT_INTERRUPTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM edu_assessment WHERE id = ?", String.class, assessmentId
        )).isEqualTo("DRAFT");
    }

    private JsonNode createAssessment(String token, long classId, String body) throws Exception {
        String response = mockMvc.perform(post("/api/assessments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode uploadImport(String token, long assessmentId, String filename, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/assessments/{id}/import-files", assessmentId)
                        .file(new MockMultipartFile("file", filename, "text/plain", content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void awaitTask(long taskId, TaskStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AssessmentImportTaskEntity task = importTaskMapper.selectById(taskId);
            if (task != null && task.getStatus() == expected) {
                return;
            }
            Thread.sleep(25);
        }
        AssessmentImportTaskEntity task = importTaskMapper.selectById(taskId);
        throw new AssertionError("task " + taskId + " expected " + expected + " but was " + (task == null ? null : task.getStatus()));
    }

    private AiAssessmentParseResponse successfulParse(AiAssessmentParseRequest request) throws Exception {
        AiAssessmentParseResponse.QuestionDraft programming = new AiAssessmentParseResponse.QuestionDraft(
                "ai-programming-1", com.education.agent.assessment.model.QuestionType.PROGRAMMING,
                "读取两个整数并输出它们的和", new BigDecimal("20"), List.of(),
                objectMapper.readTree("{\"source_code\":\"a,b=map(int,input().split());print(a+b)\"}"),
                List.of(),
                List.of(
                        new AiAssessmentParseResponse.TestCaseDraft("1 2", "3", new BigDecimal("10"),
                                com.education.agent.assessment.model.TestVisibility.PUBLIC,
                                com.education.agent.assessment.model.CompareMode.EXACT),
                        new AiAssessmentParseResponse.TestCaseDraft("2 5", "7", new BigDecimal("10"),
                                com.education.agent.assessment.model.TestVisibility.HIDDEN,
                                com.education.agent.assessment.model.CompareMode.EXACT)
                ),
                List.of(new AiAssessmentParseResponse.KnowledgePointCandidate(
                        "kp.variables", "变量与对象", new BigDecimal("0.92")
                )),
                "HIGH", List.of()
        );
        AiAssessmentParseResponse.QuestionDraft attachment = new AiAssessmentParseResponse.QuestionDraft(
                "ai-attachment-1", com.education.agent.assessment.model.QuestionType.ATTACHMENT,
                "提交实验报告", new BigDecimal("10"), List.of(), null,
                List.of(new AiAssessmentParseResponse.RubricItem(
                        "完整性", "报告包含实验步骤与结论", new BigDecimal("10")
                )), List.of(), List.of(), "MEDIUM", List.of()
        );
        return new AiAssessmentParseResponse(
                request.requestId(), "AI 建议作业", List.of(programming, attachment), List.of("请核对题号"),
                "deepseek-test", "assessment-parse-v1"
        );
    }

    private void assertPublishValidationFailure(
            String token,
            long assessmentId,
            String idempotencyKey,
            String messagePart
    ) throws Exception {
        mockMvc.perform(post("/api/assessments/{id}/publish", assessmentId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messagePart)));
    }

    private String examBody(
            long classId,
            String title,
            String startAt,
            String dueAt,
            Integer durationMinutes
    ) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("assessmentType", "EXAM");
        body.put("classId", classId);
        body.put("title", title);
        if (startAt != null) {
            body.put("startAt", startAt);
        }
        if (dueAt != null) {
            body.put("dueAt", dueAt);
        }
        if (durationMinutes != null) {
            body.put("durationMinutes", durationMinutes);
        }
        body.put("settings", Map.of("antiCheatPolicy", Map.of("enabled", true)));
        body.put("questions", List.of(Map.of(
                "clientKey", "exam-single",
                "questionType", "SINGLE_CHOICE",
                "stem", "选择正确答案",
                "score", 10,
                "position", 1,
                "options", List.of(
                        Map.of("key", "A", "content", "正确", "isCorrect", true, "position", 1),
                        Map.of("key", "B", "content", "错误", "isCorrect", false, "position", 2)
                ),
                "referenceAnswer", Map.of("selectedOptionKeys", List.of("A"))
        )));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String subjectiveWithoutAnswerBody(long classId) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"主观题参考答案可选","settings":{},"questions":[
                  {"clientKey":"subjective-optional","questionType":"SUBJECTIVE","stem":"说明变量绑定","score":10,"position":1,
                   "rubricItems":[{"clientKey":"subjective-optional-rubric","label":"概念","description":"说明名称与对象关系","maxScore":10,"position":1}]}
                ]}
                """.formatted(classId);
    }

    private String publicOnlyProgrammingBody(long classId) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"编程题缺隐藏点","settings":{},"questions":[
                  {"clientKey":"program-public-only","questionType":"PROGRAMMING","stem":"输出输入值","score":10,"position":1,
                   "referenceAnswer":{"sourceCode":"print(input())"},
                   "programmingConfig":{"language":"PYTHON","starterCode":"","timeLimitMs":2000,"memoryLimitMb":128,"compareMode":"EXACT"},
                   "testCases":[{"clientKey":"public-only","input":"1","expectedOutput":"1","score":10,"visibility":"PUBLIC","position":1}]}
                ]}
                """.formatted(classId);
    }

    private String invalidScoreBody(long classId, String target, String value) {
        String question = switch (target) {
            case "question" -> """
                    {"clientKey":"invalid-question-score","questionType":"SUBJECTIVE","stem":"非法题目分值","score":%s,"position":1}
                    """.formatted(value);
            case "rubric" -> """
                    {"clientKey":"invalid-rubric-score","questionType":"SUBJECTIVE","stem":"非法评分点分值","score":10,"position":1,
                     "rubricItems":[{"clientKey":"invalid-rubric","label":"评分点","description":"说明","maxScore":%s,"position":1}]}
                    """.formatted(value);
            case "test" -> """
                    {"clientKey":"invalid-test-score","questionType":"PROGRAMMING","stem":"非法测试点分值","score":10,"position":1,
                     "referenceAnswer":{"sourceCode":"print(1)"},
                     "programmingConfig":{"language":"PYTHON","starterCode":"","timeLimitMs":2000,"memoryLimitMb":128,"compareMode":"EXACT"},
                     "testCases":[{"clientKey":"invalid-test","input":"","expectedOutput":"1","score":%s,"visibility":"PUBLIC","position":1}]}
                    """.formatted(value);
            default -> throw new IllegalArgumentException("unknown score target " + target);
        };
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"分值约束","settings":{},"questions":[%s]}
                """.formatted(classId, question);
    }

    private String overflowTotalBody(long classId) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"总分溢出","settings":{},"questions":[
                  {"clientKey":"overflow-1","questionType":"SUBJECTIVE","stem":"第一题","score":600000,"position":1},
                  {"clientKey":"overflow-2","questionType":"SUBJECTIVE","stem":"第二题","score":600000,"position":2}
                ]}
                """.formatted(classId);
    }

    private String twoKnowledgePointBody(long classId) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"混合图谱版本","settings":{},"questions":[
                  {"clientKey":"graph-v1-question","questionType":"SINGLE_CHOICE","stem":"第一题","score":10,"position":1,
                   "options":[{"key":"A","content":"正确","isCorrect":true,"position":1},{"key":"B","content":"错误","isCorrect":false,"position":2}],
                   "referenceAnswer":{"selectedOptionKeys":["A"]},"knowledgePoints":[{"id":"kp.graph-v1","name":"待核对"}]},
                  {"clientKey":"graph-v2-question","questionType":"SINGLE_CHOICE","stem":"第二题","score":10,"position":2,
                   "options":[{"key":"A","content":"正确","isCorrect":true,"position":1},{"key":"B","content":"错误","isCorrect":false,"position":2}],
                   "referenceAnswer":{"selectedOptionKeys":["A"]},"knowledgePoints":[{"id":"kp.graph-v2","name":"待核对"}]}
                ]}
                """.formatted(classId);
    }

    private String completeAssessmentBody(long classId, String title, String knowledgePointId) {
        return """
                {
                  "assessmentType":"HOMEWORK",
                  "classId":%d,
                  "title":"%s",
                  "description":"周期二五题型",
                  "settings":{"autoGradeEnabled":true,"autoReleaseEnabled":false,"aiAttachmentGradeEnabled":true,"antiCheatPolicy":{"enabled":false}},
                  "questions":[
                    {"clientKey":"single-1","questionType":"SINGLE_CHOICE","stem":"Python 中赋值符号是？","score":10,"position":1,
                     "options":[{"key":"A","content":"=","isCorrect":true,"position":1},{"key":"B","content":"==","isCorrect":false,"position":2}],
                     "referenceAnswer":{"selectedOptionKeys":["A"]},"knowledgePoints":[{"id":"%s","name":"伪造名称"}]},
                    {"clientKey":"multiple-1","questionType":"MULTIPLE_CHOICE","stem":"哪些是序列？","score":10,"position":2,
                     "options":[{"key":"A","content":"list","isCorrect":true,"position":1},{"key":"B","content":"int","isCorrect":false,"position":2},{"key":"C","content":"tuple","isCorrect":true,"position":3}],
                     "referenceAnswer":{"selectedOptionKeys":["A","C"]},"knowledgePoints":[{"id":"%s","name":"变量"}]},
                    {"clientKey":"subjective-1","questionType":"SUBJECTIVE","stem":"解释变量绑定。","score":10,"position":3,
                     "referenceAnswer":{"textAnswer":"变量名引用对象。"},
                     "rubricItems":[{"clientKey":"rubric-s-1","label":"概念","description":"说明名称与对象关系","maxScore":10,"position":1}],
                     "knowledgePoints":[{"id":"%s","name":"变量"}]},
                    {"clientKey":"programming-1","questionType":"PROGRAMMING","stem":"输出两个整数之和。","score":20,"position":4,
                     "referenceAnswer":{"sourceCode":"a,b=map(int,input().split());print(a+b)"},
                     "programmingConfig":{"language":"PYTHON","starterCode":"","timeLimitMs":2000,"memoryLimitMb":128,"compareMode":"EXACT"},
                     "testCases":[{"clientKey":"test-1","input":"1 2","expectedOutput":"3","score":10,"visibility":"PUBLIC","position":1},{"clientKey":"test-2","input":"5 7","expectedOutput":"12","score":10,"visibility":"HIDDEN","position":2}],
                     "knowledgePoints":[{"id":"%s","name":"变量"}]},
                    {"clientKey":"attachment-1","questionType":"ATTACHMENT","stem":"提交实验报告。","score":10,"position":5,
                     "rubricItems":[{"clientKey":"rubric-a-1","label":"完整性","description":"包含步骤与结论","maxScore":10,"position":1}],
                     "attachmentPolicy":{"allowedExtensions":[".pdf",".docx"],"maxFileCount":1,"maxFileSizeMb":20},
                     "knowledgePoints":[{"id":"%s","name":"变量"}]}
                  ]
                }
                """.formatted(classId, title, knowledgePointId, knowledgePointId, knowledgePointId, knowledgePointId, knowledgePointId);
    }

    private String singleChoiceBody(long classId, String title, String kp, String referenceKey) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"%s","settings":{},"questions":[
                  {"clientKey":"single-only","questionType":"SINGLE_CHOICE","stem":"选择正确项","score":10,"position":1,
                   "options":[{"key":"A","content":"正确","isCorrect":true,"position":1},{"key":"B","content":"错误","isCorrect":false,"position":2}],
                   "referenceAnswer":{"selectedOptionKeys":["%s"]},"knowledgePoints":[{"id":"%s","name":"待核对"}]}
                ]}
                """.formatted(classId, title, referenceKey, kp);
    }

    private String emptyAssessmentBody(long classId, String title) {
        return """
                {"assessmentType":"HOMEWORK","classId":%d,"title":"%s","settings":{},"questions":[]}
                """.formatted(classId, title);
    }

    private String singleQuestionJson(String clientKey, int position, String stem) {
        return """
                {"clientKey":"%s","questionType":"SINGLE_CHOICE","stem":"%s","score":10,"position":%d,
                 "options":[{"key":"A","content":"正确","isCorrect":true,"position":1},{"key":"B","content":"错误","isCorrect":false,"position":2}],
                 "referenceAnswer":{"selectedOptionKeys":["A"]}}
                """.formatted(clientKey, stem, position);
    }

    private String subjectiveQuestionJson(String clientKey, int position, String stem) {
        return """
                {"clientKey":"%s","questionType":"SUBJECTIVE","stem":"%s","score":10,"position":%d,
                 "referenceAnswer":{"textAnswer":"变量名绑定对象"},
                 "rubricItems":[{"clientKey":"crud-rubric","label":"概念","description":"说明绑定关系","maxScore":10,"position":1}]}
                """.formatted(clientKey, stem, position);
    }

    private InternalGraphRagContextResponse approvedGraphContext(String id) {
        return graphContext(id, "knowledge_point", "APPROVED", 1, "READY", "graph-cycle-2");
    }

    private InternalGraphRagContextResponse graphContext(
            String id,
            String nodeType,
            String reviewStatus,
            Integer contentVersion,
            String contextStatus,
            String graphVersion
    ) {
        return new InternalGraphRagContextResponse(1L, new InternalGraphNode(
                        id, "变量与对象", 3, nodeType, "chapter.variables", "变量保存对象引用", List.of("变量"),
                        List.of("变量", "对象"), "BEGINNER", List.of("理解赋值"), List.of("变量不是盒子"),
                        reviewStatus, contentVersion
                ), List.of(), List.of(), List.of(), graphVersion, contextStatus, List.of());
    }

    private UserEntity insertUser(String username, UserRole role) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("Password@123"));
        user.setDisplayName(username);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setForcePasswordChange(false);
        userMapper.insert(user);
        return userMapper.selectById(user.getId());
    }

    private ClassEntity insertClass(String name, UserEntity teacher) {
        ClassEntity classroom = new ClassEntity();
        classroom.setCourseId(1L);
        classroom.setName(name);
        classroom.setCourseName("Python程序设计");
        classroom.setTeacherId(teacher.getId());
        classroom.setStatus(ClassStatus.ACTIVE);
        classMapper.insert(classroom); CourseTestFixtures.teacher(jdbcTemplate, classroom.getTeacherId());
        return classMapper.selectById(classroom.getId());
    }

    private String token(UserEntity user, String role) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user.getUsername(), "password", "Password@123", "expectedRole", role
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
