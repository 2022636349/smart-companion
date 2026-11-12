package com.education.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.CodeExplainRequest;
import com.education.agent.integration.ai.CodeExplainResponse;
import com.education.agent.integration.ai.SandboxRunRequest;
import com.education.agent.integration.ai.SandboxRunResponse;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.security.JwtService;
import com.education.agent.submission.service.GradingFailureHook;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CycleThreeSubmissionIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserMapper users;
  @Autowired ClassMapper classes;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtService jwt;
  @MockBean KnowledgeServiceClient graph;
  @MockBean AiServiceClient ai;
  @MockBean GradingFailureHook gradingFailureHook;

  @BeforeEach
  void reset() {
    Mockito.reset(graph, ai, gradingFailureHook);
    when(graph.ragContext(org.mockito.ArgumentMatchers.eq(1L), anyString()))
        .thenReturn(
            new InternalGraphRagContextResponse(1L, new InternalGraphNode(
                    "kp",
                    "知识点",
                    3,
                    "knowledge_point",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    List.of(),
                    "APPROVED",
                    1), List.of(), List.of(), List.of(), "g-c3", "READY", List.of()));
  }

  @Test
  void studentIsolationSnapshotAutosaveSubmitAndReleaseGate() throws Exception {
    UserEntity teacher = user("c3_teacher", UserRole.TEACHER),
        a = user("c3_a", UserRole.STUDENT),
        b = user("c3_b", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, a);
    member(room, b);
    long assessment = createPublish(token(teacher), room.getId(), false);
    long otherAssessment = createPublish(token(teacher), room.getId(), false);
    String start =
        mvc.perform(
                post("/api/assessments/{id}/submissions", assessment)
                    .header("Authorization", bearer(token(a)))
                    .header("Idempotency-Key", "c3-start-idempotent-0001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.paper.questions[0].options[0].isCorrect").doesNotExist())
            .andExpect(jsonPath("$.data.paper.questions[0].referenceAnswer").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode started = json.readTree(start);
    long submission = started.at("/data/submission/id").asLong();
    long question = started.at("/data/paper/questions/0/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/submissions", assessment)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-start-idempotent-0001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.submission.id").value(submission));
    mvc.perform(
            post("/api/assessments/{id}/submissions", otherAssessment)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-start-idempotent-0001"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    mvc.perform(get("/api/submissions/{id}", submission).header("Authorization", bearer(token(b))))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", submission, question)
                .header("Authorization", bearer(token(a)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedOptionKeys\":[\"B\"],\"clientSaveSequence\":2}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", submission, question)
                .header("Authorization", bearer(token(a)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedOptionKeys\":[\"A\"],\"clientSaveSequence\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    mvc.perform(
            post("/api/submissions/{id}/submit", submission)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-submit-idempotent-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.autoScore").doesNotExist());
    mvc.perform(
            post("/api/submissions/{id}/submit", submission)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-submit-idempotent-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/submissions/{id}/submit", submission)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-submit-idempotent-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"examSessionToken\":\"different-body\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    mvc.perform(
            post("/api/submissions/{id}/submit", submission)
                .header("Authorization", bearer(token(a)))
                .header("Idempotency-Key", "c3-submit-idempotent-02")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(submission));
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", submission, question)
                .header("Authorization", bearer(token(a)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedOptionKeys\":[\"A\"],\"clientSaveSequence\":3}"))
        .andExpect(status().isConflict());
    mvc.perform(
            get("/api/submissions/{id}/result", submission)
                .header("Authorization", bearer(token(a))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESULT_NOT_RELEASED"))
        .andExpect(jsonPath("$.details.score").doesNotExist());
    mvc.perform(
            get("/api/submissions/{id}/review", submission)
                .header("Authorization", bearer(token(a))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.submission.id").value(submission))
        .andExpect(jsonPath("$.data.submission.answers[0].selectedOptionKeys[0]").value("B"))
        .andExpect(jsonPath("$.data.submission.answers[0].finalScore").doesNotExist())
        .andExpect(jsonPath("$.data.paper.questions[0].stem").value("2+2?"))
        .andExpect(jsonPath("$.data.paper.questions[0].options[0].isCorrect").doesNotExist())
        .andExpect(jsonPath("$.data.paper.questions[0].referenceAnswer").doesNotExist());
    awaitSubmissionStatus(submission, "GRADED");
    assertThat(
            jdbc.queryForObject(
                "SELECT auto_score FROM edu_assessment_submission WHERE id=?",
                String.class,
                submission))
        .isEqualTo("10.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT assessment_version_id FROM edu_assessment_submission WHERE id=?",
                Long.class,
                submission))
        .isNotNull();
  }

  @Test
  void releasedResultStillHonorsSnapshotVisibilitySwitches() throws Exception {
    UserEntity teacher = user("c3_visibility_teacher", UserRole.TEACHER),
        student = user("c3_visibility_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishHiddenResult(token(teacher), room.getId());
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-visibility-start-01"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-visibility-submit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk());
    awaitSubmissionStatus(sid, "GRADED");
    jdbc.update("UPDATE edu_assessment SET status='RELEASED' WHERE id=?", assessment);
    jdbc.update(
        "UPDATE edu_assessment_submission SET result_released_at=CURRENT_TIMESTAMP WHERE id=?",
        sid);

    mvc.perform(get("/api/submissions/{id}", sid).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.autoScore").doesNotExist());
    mvc.perform(get("/api/submissions/{id}/result", sid).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.achievedScore").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].score").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].correct").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].correctOptionKeys").isEmpty())
        .andExpect(jsonPath("$.data.answers[0].teacherComment").doesNotExist());
  }

  @Test
  void examRequiresStableDeviceAndDeduplicatesEvents() throws Exception {
    UserEntity teacher = user("c3_exam_teacher", UserRole.TEACHER),
        student = user("c3_exam_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), true);
    String auth = bearer(token(student));
    String device = "browser-session-device-0001";
    mvc.perform(
            post("/api/assessments/{id}/submissions", assessment)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-exam-start-000001"))
        .andExpect(status().isUnprocessableEntity());
    String body =
        mvc.perform(
                post("/api/assessments/{id}/submissions", assessment)
                    .header("Authorization", auth)
                    .header("Idempotency-Key", "c3-exam-start-000002")
                    .header("X-Device-Id", device))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.examSession.sessionToken").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = json.readTree(body);
    long sid = node.at("/data/submission/id").asLong();
    String session = node.at("/data/examSession/sessionToken").asText();
    assertThat(node.at("/data/examSession/recordCopyPaste").asBoolean()).isTrue();
    assertThat(node.at("/data/examSession/recordPageVisibility").asBoolean()).isTrue();
    mvc.perform(
            post("/api/assessments/{id}/submissions", assessment)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-exam-start-000002")
                .header("X-Device-Id", "browser-session-device-9999"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sessionToken"))))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("paper"))));
    jdbc.update(
        "UPDATE edu_assessment_submission SET accepted_save_sequence=7, status='GRADING', grading_status='COMPLETED' WHERE id=?",
        sid);
    assertThat(submissionsMapperForTest().touchHeartbeat(sid, java.time.LocalDateTime.now()))
        .isZero();
    assertThat(submissionsMapperForTest().markSuspiciousIfInProgress(sid)).isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT accepted_save_sequence, status, grading_status FROM edu_assessment_submission WHERE id=?",
                sid))
        .containsEntry("ACCEPTED_SAVE_SEQUENCE", 7L)
        .containsEntry("STATUS", "GRADING")
        .containsEntry("GRADING_STATUS", "COMPLETED");
    jdbc.update(
        "UPDATE edu_assessment_submission SET accepted_save_sequence=0, status='IN_PROGRESS', grading_status='NOT_STARTED' WHERE id=?",
        sid);
    mvc.perform(
            post("/api/assessments/{id}/submissions", assessment)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-exam-start-000003")
                .header("X-Device-Id", "browser-session-device-9999"))
        .andExpect(status().isConflict());
    String event =
        "{\"submissionId\":"
            + sid
            + ",\"examSessionToken\":\""
            + session
            + "\",\"events\":[{\"clientEventId\":\"evt-1\",\"eventType\":\"EXIT_FULLSCREEN\",\"occurredAt\":\""
            + Instant.now()
            + "\"}]}";
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/exams/{id}/anti-cheat-events", assessment)
                  .header("Authorization", auth)
                  .header("X-Device-Id", device)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(event))
          .andExpect(status().isAccepted());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_anti_cheat_event WHERE submission_id=?", Long.class, sid))
        .isEqualTo(1L);
    String invalidBatch =
        "{\"submissionId\":"
            + sid
            + ",\"examSessionToken\":\""
            + session
            + "\",\"events\":["
            + "{\"clientEventId\":\"evt-atomic-valid\",\"eventType\":\"EXIT_FULLSCREEN\",\"occurredAt\":\""
            + Instant.now()
            + "\"},"
            + "{\"clientEventId\":\"evt-atomic-invalid\",\"eventType\":\"UNKNOWN_EVENT\",\"occurredAt\":\""
            + Instant.now()
            + "\"}]}";
    mvc.perform(
            post("/api/exams/{id}/anti-cheat-events", assessment)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBatch))
        .andExpect(status().isUnprocessableEntity());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_anti_cheat_event WHERE submission_id=? AND client_event_id LIKE 'evt-atomic-%'",
                Long.class,
                sid))
        .isZero();
    String oversizedDetails = "x".repeat(4097);
    String oversizedBatch =
        "{\"submissionId\":"
            + sid
            + ",\"examSessionToken\":\""
            + session
            + "\",\"events\":["
            + "{\"clientEventId\":\"evt-size-valid\",\"eventType\":\"EXIT_FULLSCREEN\",\"occurredAt\":\""
            + Instant.now()
            + "\"},"
            + "{\"clientEventId\":\"evt-size-invalid\",\"eventType\":\"EXIT_FULLSCREEN\",\"occurredAt\":\""
            + Instant.now()
            + "\",\"details\":{\"payload\":\""
            + oversizedDetails
            + "\"}}]}";
    mvc.perform(
            post("/api/exams/{id}/anti-cheat-events", assessment)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversizedBatch))
        .andExpect(status().isUnprocessableEntity());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_anti_cheat_event WHERE submission_id=? AND client_event_id LIKE 'evt-size-%'",
                Long.class,
                sid))
        .isZero();
    mvc.perform(
            post("/api/exams/{id}/heartbeat", assessment)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"submissionId\":"
                        + sid
                        + ",\"examSessionToken\":\""
                        + session
                        + "\",\"clientTime\":\""
                        + Instant.now()
                        + "\",\"lastSaveSequence\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionValid").value(true))
        .andExpect(jsonPath("$.data.remainingSeconds").isNumber());
    assertThat(
            jdbc.queryForObject(
                "SELECT exam_session_token_hash FROM edu_assessment_submission WHERE id=?",
                String.class,
                sid))
        .doesNotContain(session);
    assertThat(
            jdbc.queryForObject(
                "SELECT device_id_hash FROM edu_assessment_submission WHERE id=?",
                String.class,
                sid))
        .doesNotContain(device);
    var bound = submissionsForTest(sid);
    bound.setExamDeadlineAt(java.time.LocalDateTime.now().minusSeconds(2));
    submissionsMapperForTest().updateById(bound);
    mvc.perform(
            post("/api/exams/{id}/heartbeat", assessment)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"submissionId\":"
                        + sid
                        + ",\"examSessionToken\":\""
                        + session
                        + "\",\"clientTime\":\"2999-01-01T00:00:00Z\",\"lastSaveSequence\":999}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shouldAutoSubmit").value(true))
        .andExpect(jsonPath("$.data.sessionValid").value(false))
        .andExpect(jsonPath("$.data.remainingSeconds").value(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM edu_assessment_submission WHERE id=?", String.class, sid))
        .isEqualTo("AUTO_SUBMITTED");
  }

  @Test
  void disabledAntiCheatReturnsEffectiveFalseFlags() throws Exception {
    UserEntity teacher = user("c3_policy_teacher", UserRole.TEACHER),
        student = user("c3_policy_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishExamWithDisabledAntiCheat(token(teacher), room.getId());
    mvc.perform(
            post("/api/assessments/{id}/submissions", assessment)
                .header("Authorization", bearer(token(student)))
                .header("Idempotency-Key", "c3-policy-start-00001"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.examSession.requireFullscreen").value(false))
        .andExpect(jsonPath("$.data.examSession.recordCopyPaste").value(false))
        .andExpect(jsonPath("$.data.examSession.recordPageVisibility").value(false));
  }

  @Test
  void directExamSubmitAfterServerDeadlineIsAuthoritativeTimeout() throws Exception {
    UserEntity teacher = user("c3_deadline_teacher", UserRole.TEACHER),
        student = user("c3_deadline_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), true);
    String auth = bearer(token(student));
    String device = "deadline-device-session-0001";
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-deadline-start-001")
                        .header("X-Device-Id", device))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    String session = started.at("/data/examSession/sessionToken").asText();
    jdbc.update(
        "UPDATE edu_assessment_submission SET exam_deadline_at=? WHERE id=?",
        java.time.LocalDateTime.now().minusSeconds(1),
        sid);

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .header("Idempotency-Key", "c3-deadline-submit-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"examSessionToken\":\"" + session + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AUTO_SUBMITTED"));
    assertThat(
            jdbc.queryForMap(
                "SELECT status, submitted_by_timeout FROM edu_assessment_submission WHERE id=?",
                sid))
        .containsEntry("STATUS", "AUTO_SUBMITTED")
        .containsEntry("SUBMITTED_BY_TIMEOUT", true);
  }

  @Test
  void subSecondExamRemainderRoundsUpAndDoesNotBecomeEarlyTimeout() throws Exception {
    UserEntity teacher = user("c3_subsecond_teacher", UserRole.TEACHER),
        student = user("c3_subsecond_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), true);
    String auth = bearer(token(student));
    String device = "subsecond-device-session-001";
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-subsecond-start-01")
                        .header("X-Device-Id", device))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    String session = started.at("/data/examSession/sessionToken").asText();
    jdbc.update(
        "UPDATE edu_assessment_submission SET exam_deadline_at=? WHERE id=?",
        java.time.LocalDateTime.now().plusNanos(900_000_000L),
        sid);

    mvc.perform(
            post("/api/exams/{id}/heartbeat", assessment)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"submissionId\":"
                        + sid
                        + ",\"examSessionToken\":\""
                        + session
                        + "\",\"clientTime\":\""
                        + Instant.now()
                        + "\",\"lastSaveSequence\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.remainingSeconds").value(1))
        .andExpect(jsonPath("$.data.shouldAutoSubmit").value(false));
    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("X-Device-Id", device)
                .header("Idempotency-Key", "c3-subsecond-submit-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"examSessionToken\":\"" + session + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("GRADING"));
    assertThat(
            jdbc.queryForObject(
                "SELECT submitted_by_timeout FROM edu_assessment_submission WHERE id=?",
                Boolean.class,
                sid))
        .isFalse();
  }

  @Test
  void shuffledPaperIsStableForOneSubmissionAndKeepsAnswerKeysOpaque() throws Exception {
    UserEntity teacher = user("c3_shuffle_teacher", UserRole.TEACHER),
        student = user("c3_shuffle_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishShuffled(token(teacher), room.getId());
    String auth = bearer(token(student));

    JsonNode first =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-shuffle-start-0001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paper.questions[0].position").value(1))
                .andExpect(jsonPath("$.data.paper.questions[1].position").value(2))
                .andExpect(jsonPath("$.data.paper.questions[0].referenceAnswer").doesNotExist())
                .andExpect(
                    jsonPath("$.data.paper.questions[0].options[0].isCorrect").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode resumed =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-shuffle-start-0002"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    JsonNode firstQuestions = first.at("/data/paper/questions");
    JsonNode resumedQuestions = resumed.at("/data/paper/questions");
    assertThat(resumedQuestions).isEqualTo(firstQuestions);
    assertThat(firstQuestions.get(0).path("stem").asText()).isEqualTo("second original");
    assertThat(firstQuestions.get(0).path("options").get(0).path("position").asInt()).isEqualTo(1);
    assertThat(firstQuestions.get(0).path("options").get(1).path("position").asInt()).isEqualTo(2);
    assertThat(firstQuestions.get(0).path("options").get(0).path("key").asText()).isEqualTo("B");
  }

  @Test
  void codeRunUsesOnlyPublicTestsAndSubmitRunsHiddenOutsideTransaction() throws Exception {
    UserEntity teacher = user("c3_code_teacher", UserRole.TEACHER),
        student = user("c3_code_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String auth = bearer(token(student));
    String started =
        mvc.perform(
                post("/api/assessments/{id}/submissions", assessment)
                    .header("Authorization", auth)
                    .header("Idempotency-Key", "c3-code-start-000001"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = json.readTree(started);
    long sid = node.at("/data/submission/id").asLong();
    long qid = node.at("/data/paper/questions/0/id").asLong();
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", sid, qid)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCode\":\"print(input())\",\"clientSaveSequence\":1}"))
        .andExpect(status().isOk());
    AtomicBoolean transactionObserved = new AtomicBoolean(true);
    AtomicBoolean forgeAggregate = new AtomicBoolean(false);
    AtomicBoolean forgeUnknownStatus = new AtomicBoolean(false);
    java.util.List<Integer> caseCounts = new java.util.ArrayList<>();
    java.util.List<String> executionModes = new java.util.ArrayList<>();
    Mockito.doAnswer(
            invocation -> {
              SandboxRunRequest request = invocation.getArgument(0);
              caseCounts.add(request.testCases().size());
              executionModes.add(request.executionMode());
              transactionObserved.set(
                  transactionObserved.get()
                      && org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
              var results =
                  request.testCases().stream()
                      .map(
                          t ->
                              new SandboxRunResponse.SandboxTestResult(
                              t.testCaseId(),
                                  forgeUnknownStatus.get() ? "MALICIOUS_UNKNOWN" : "ACCEPTED",
                                  t.score(),
                                  t.score(),
                                  1,
                                  1024,
                                  t.visibility(),
                                  "HIDDEN".equals(t.visibility()) ? null : "ok",
                                  "HIDDEN".equals(t.visibility()) ? null : t.expectedOutput(),
                                  null))
                      .toList();
              return new SandboxRunResponse(
                  request.requestId(),
                  "ACCEPTED",
                  request.testCases().stream()
                      .map(SandboxRunRequest.SandboxTestCase::score)
                      .reduce(BigDecimal.ZERO, BigDecimal::add)
                      .add(forgeAggregate.get() ? BigDecimal.ONE : BigDecimal.ZERO),
                  request.testCases().stream()
                      .map(SandboxRunRequest.SandboxTestCase::score)
                      .reduce(BigDecimal.ZERO, BigDecimal::add),
                  results.size(),
                  results.size(),
                  null,
                  results);
            })
        .when(ai)
        .runSandbox(any());
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contextType\":\"ASSESSMENT\",\"questionId\":"
                        + qid
                        + ",\"submissionId\":"
                        + sid
                        + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.testCases.length()").value(1))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hidden-output"))));
    forgeAggregate.set(true);
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contextType\":\"ASSESSMENT\",\"questionId\":"
                        + qid
                        + ",\"submissionId\":"
                        + sid
                        + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("CODE_SANDBOX_INVALID_RESPONSE"));
    forgeAggregate.set(false);
    forgeUnknownStatus.set(true);
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contextType\":\"ASSESSMENT\",\"questionId\":"
                        + qid
                        + ",\"submissionId\":"
                        + sid
                        + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("CODE_SANDBOX_INVALID_RESPONSE"));
    forgeUnknownStatus.set(false);
    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-code-submit-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.autoScore").doesNotExist());
    awaitSubmissionStatus(sid, "GRADED");
    assertThat(caseCounts).containsExactly(1, 1, 1, 2);
    assertThat(executionModes)
        .containsExactly("PUBLIC_RUN", "PUBLIC_RUN", "PUBLIC_RUN", "GRADING");
    Mockito.verify(ai, Mockito.never()).explainCodeResult(any(CodeExplainRequest.class));
    assertThat(transactionObserved).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_code_run_record WHERE student_id=?",
                Long.class,
                student.getId()))
        .isEqualTo(2L);
  }

  @Test
  void homeworkDeadlineClosesSavePublicRunAndSubmitWithoutChangingSubmission() throws Exception {
    UserEntity teacher = user("c3_due_teacher", UserRole.TEACHER),
        student = user("c3_due_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-due-start-000001"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    jdbc.update(
        "UPDATE edu_assessment SET due_at=? WHERE id=?",
        java.time.LocalDateTime.now().minusSeconds(1),
        assessment);

    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", sid, qid)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCode\":\"print(input())\",\"clientSaveSequence\":1}"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contextType\":\"ASSESSMENT\",\"questionId\":"
                        + qid
                        + ",\"submissionId\":"
                        + sid
                        + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-due-submit-000001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isConflict());

    assertThat(
            jdbc.queryForMap(
                "SELECT status, accepted_save_sequence FROM edu_assessment_submission WHERE id=?",
                sid))
        .containsEntry("STATUS", "IN_PROGRESS")
        .containsEntry("ACCEPTED_SAVE_SEQUENCE", 0L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_submission_answer WHERE submission_id=?",
                Long.class,
                sid))
        .isZero();
    Mockito.verify(ai, Mockito.never()).runSandbox(any());
  }

  @Test
  void publicCodeRunIsSingleFlightExplainsOnlyPublicFailuresAndReleasesOnFailure()
      throws Exception {
    UserEntity teacher = user("c3_public_run_teacher", UserRole.TEACHER),
        student = user("c3_public_run_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-public-run-start-01"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    String body =
        "{\"contextType\":\"ASSESSMENT\",\"questionId\":"
            + qid
            + ",\"submissionId\":"
            + sid
            + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}";
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(ai.runSandbox(any()))
        .thenAnswer(
            invocation -> {
              SandboxRunRequest request = invocation.getArgument(0);
              assertThat(request.executionMode()).isEqualTo("PUBLIC_RUN");
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test latch timed out");
                }
                throw new com.education.agent.common.error.BusinessException(
                    com.education.agent.common.error.ErrorCode.CODE_SANDBOX_UNAVAILABLE,
                    "temporary sandbox outage");
              }
              SandboxRunRequest.SandboxTestCase test = request.testCases().get(0);
              return new SandboxRunResponse(
                  request.requestId(),
                  "WRONG_ANSWER",
                  BigDecimal.ZERO,
                  test.score(),
                  0,
                  1,
                  null,
                  List.of(
                      new SandboxRunResponse.SandboxTestResult(
                          test.testCaseId(),
                          "WRONG_ANSWER",
                          BigDecimal.ZERO,
                          test.score(),
                          1,
                          1024,
                          "PUBLIC",
                          "actual-public",
                          test.expectedOutput(),
                          null)));
            });
    when(ai.explainCodeResult(any(CodeExplainRequest.class)))
        .thenAnswer(
            invocation -> {
              CodeExplainRequest request = invocation.getArgument(0);
              assertThat(request.failures()).singleElement().satisfies(
                  failure -> {
                    assertThat(failure.publicInput()).isEqualTo("ok");
                    assertThat(failure.publicExpectedOutput()).isEqualTo("ok");
                    assertThat(failure.actualOutput()).isEqualTo("actual-public");
                  });
              return new CodeExplainResponse(
                  request.requestId(), "检查公开样例。", List.of(), "deterministic", "v1");
            });

    java.util.concurrent.CompletableFuture<Integer> first =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                return mvc.perform(
                        post("/api/code/run")
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              } catch (Exception exception) {
                throw new RuntimeException(exception);
              }
            });
    assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("CODE_SANDBOX_RATE_LIMITED"));
    release.countDown();
    assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(503);

    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teachingFeedback").value("检查公开样例。"));

    when(ai.explainCodeResult(any(CodeExplainRequest.class)))
        .thenThrow(
            new com.education.agent.common.error.BusinessException(
                com.education.agent.common.error.ErrorCode.AI_SERVICE_UNAVAILABLE,
                "explanation offline"));
    mvc.perform(
            post("/api/code/run")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WRONG_ANSWER"))
        .andExpect(jsonPath("$.data.teachingFeedback").doesNotExist());
  }

  @Test
  void closeWaitsForPublicRunThenRejectsEveryLaterRun() throws Exception {
    UserEntity teacher = user("c3_run_close_teacher", UserRole.TEACHER),
        student = user("c3_run_close_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String teacherAuth = bearer(token(teacher)), studentAuth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", studentAuth)
                        .header("Idempotency-Key", "c3-run-close-start-01"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    String body = "{\"contextType\":\"ASSESSMENT\",\"questionId\":" + qid
        + ",\"submissionId\":" + sid
        + ",\"language\":\"PYTHON\",\"sourceCode\":\"print(input())\"}";
    CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(ai.runSandbox(any())).thenAnswer(invocation -> {
      SandboxRunRequest request = invocation.getArgument(0);
      calls.incrementAndGet(); entered.countDown();
      if (!finish.await(3, TimeUnit.SECONDS)) throw new AssertionError("sandbox latch timed out");
      SandboxRunRequest.SandboxTestCase test = request.testCases().get(0);
      return new SandboxRunResponse(request.requestId(),"ACCEPTED",test.score(),test.score(),1,1,null,
          List.of(new SandboxRunResponse.SandboxTestResult(test.testCaseId(),"ACCEPTED",test.score(),test.score(),1,1024,"PUBLIC",test.expectedOutput(),test.expectedOutput(),null)));
    });
    java.util.concurrent.CompletableFuture<Integer> run =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try { return mvc.perform(post("/api/code/run").header("Authorization",studentAuth)
              .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(); }
          catch(Exception e){throw new RuntimeException(e);}
        });
    assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
    java.util.concurrent.CompletableFuture<Integer> close =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try { return mvc.perform(post("/api/assessments/{id}/close",assessment)
              .header("Authorization",teacherAuth).header("Idempotency-Key","c3-run-close-key-0001"))
              .andReturn().getResponse().getStatus(); }
          catch(Exception e){throw new RuntimeException(e);}
        });
    Thread.sleep(100);
    assertThat(close.isDone()).isFalse();
    finish.countDown();
    assertThat(run.get(3,TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(close.get(3,TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_code_run_record WHERE student_id=? AND question_id=? AND run_type='PUBLIC'",Long.class,student.getId(),qid)).isEqualTo(1L);
    mvc.perform(post("/api/code/run").header("Authorization",studentAuth)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsAndDoesNotPersistLeakingHiddenSandboxResponse() throws Exception {
    UserEntity teacher = user("c3_leak_teacher", UserRole.TEACHER),
        student = user("c3_leak_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-leak-start-000001"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", sid, qid)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCode\":\"print(input())\",\"clientSaveSequence\":1}"))
        .andExpect(status().isOk());
    when(ai.runSandbox(any()))
        .thenAnswer(
            invocation -> {
              SandboxRunRequest request = invocation.getArgument(0);
              var results =
                  request.testCases().stream()
                      .map(
                          test ->
                              new SandboxRunResponse.SandboxTestResult(
                                  test.testCaseId(),
                                  "HIDDEN".equals(test.visibility())
                                      ? "MALICIOUS_UNKNOWN"
                                      : "ACCEPTED",
                                  test.score(),
                                  test.score(),
                                  1,
                                  1024,
                                  test.visibility(),
                                  "HIDDEN".equals(test.visibility()) ? null : "ok",
                                  "HIDDEN".equals(test.visibility()) ? null : test.expectedOutput(),
                                  "HIDDEN".equals(test.visibility())
                                      ? "secret hidden diagnostic"
                                      : null))
                      .toList();
              return new SandboxRunResponse(
                  request.requestId(),
                  "ACCEPTED",
                  BigDecimal.TEN,
                  BigDecimal.TEN,
                  results.size(),
                  results.size(),
                  null,
                  results);
            });

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-leak-submit-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret hidden diagnostic"))));
    awaitGradingStatus(sid, "FAILED");
    submissionServiceForTest().autoSubmitExpired();
    awaitGradingAttemptCount(sid, 2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_code_run_record WHERE student_id=?",
                Long.class,
                student.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT grading_status FROM edu_submission_answer WHERE submission_id=? AND question_id=?",
                String.class,
                sid,
                qid))
        .isEqualTo("NOT_STARTED");
  }

  @Test
  void recoversIncompleteSubmitIdempotencyClaimWithoutReopeningAnswers() throws Exception {
    UserEntity teacher = user("c3_recover_teacher", UserRole.TEACHER),
        student = user("c3_recover_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), false);
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-recover-start-001"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", sid, qid)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedOptionKeys\":[\"B\"],\"clientSaveSequence\":1}"))
        .andExpect(status().isOk());
    String key = "c3-recover-submit-01";
    String hash =
        sha256ForTest(
            "SUBMIT_ASSESSMENT:" + sid + ":{\"confirm\":true,\"examSessionToken\":null}:NONE");
    jdbc.update(
        "INSERT INTO edu_idempotency_record(user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?)",
        student.getId(),
        "SUBMIT_ASSESSMENT",
        key,
        hash,
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusHours(24)));
    jdbc.update(
        "UPDATE edu_assessment_submission SET status='GRADING', grading_status='FAILED', submitted_at=CURRENT_TIMESTAMP WHERE id=?",
        sid);

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("GRADING"));
    submissionServiceForTest().autoSubmitExpired();
    awaitSubmissionStatus(sid, "GRADED");
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json IS NOT NULL FROM edu_idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=?",
                Boolean.class,
                student.getId(),
                "SUBMIT_ASSESSMENT",
                key))
        .isTrue();
    mvc.perform(
            put("/api/submissions/{id}/answers/{q}", sid, qid)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedOptionKeys\":[\"A\"],\"clientSaveSequence\":2}"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsProgrammingQuestionWithMoreThanOneHundredTestCases() throws Exception {
    UserEntity teacher = user("c3_limit_teacher", UserRole.TEACHER);
    ClassEntity room = room(teacher);
    StringBuilder cases = new StringBuilder();
    for (int i = 1; i <= 101; i++) {
      if (i > 1) cases.append(',');
      cases
          .append("{\"clientKey\":\"tc-")
          .append(i)
          .append("\",\"input\":\"x\",\"expectedOutput\":\"x\",\"score\":1,\"visibility\":\"")
          .append(i == 101 ? "HIDDEN" : "PUBLIC")
          .append("\",\"position\":")
          .append(i)
          .append('}');
    }
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + room.getId()
            + ",\"title\":\"too many cases\",\"settings\":{},\"questions\":[{\"clientKey\":\"code\",\"questionType\":\"PROGRAMMING\",\"stem\":\"echo\",\"score\":101,\"position\":1,\"referenceAnswer\":{\"sourceCode\":\"print(input())\"},\"programmingConfig\":{\"language\":\"PYTHON\",\"starterCode\":\"\",\"timeLimitMs\":100,\"memoryLimitMb\":16,\"compareMode\":\"EXACT\"},\"testCases\":["
            + cases
            + "],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"知识点\"}]}]}";
    mvc.perform(
            post("/api/assessments")
                .header("Authorization", bearer(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsAttachmentPolicyThatStudentsCannotUpload() throws Exception {
    UserEntity teacher = user("c3_attachment_policy_teacher", UserRole.TEACHER);
    ClassEntity room = room(teacher);
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + room.getId()
            + ",\"title\":\"unsupported attachment\",\"settings\":{},\"questions\":[{\"clientKey\":\"file\",\"questionType\":\"ATTACHMENT\",\"stem\":\"upload\",\"score\":10,\"position\":1,\"rubricItems\":[{\"clientKey\":\"r1\",\"label\":\"content\",\"description\":\"content\",\"maxScore\":10,\"position\":1}],\"attachmentPolicy\":{\"allowedExtensions\":[\".xlsx\"],\"maxFileCount\":1,\"maxFileSizeMb\":10},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";
    mvc.perform(
            post("/api/assessments")
                .header("Authorization", bearer(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsOversizedProgrammingTestCaseText() throws Exception {
    UserEntity teacher = user("c3_test_text_teacher", UserRole.TEACHER);
    ClassEntity room = room(teacher);
    String oversized = "x".repeat(65537);
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + room.getId()
            + ",\"title\":\"oversized test text\",\"settings\":{},\"questions\":[{\"clientKey\":\"code\",\"questionType\":\"PROGRAMMING\",\"stem\":\"echo\",\"score\":10,\"position\":1,\"referenceAnswer\":{\"sourceCode\":\"print(input())\"},\"programmingConfig\":{\"language\":\"PYTHON\",\"starterCode\":\"\",\"timeLimitMs\":100,\"memoryLimitMb\":16,\"compareMode\":\"EXACT\"},\"testCases\":[{\"clientKey\":\"pub\",\"input\":\""
            + oversized
            + "\",\"expectedOutput\":\"x\",\"score\":5,\"visibility\":\"PUBLIC\",\"position\":1},{\"clientKey\":\"hid\",\"input\":\"x\",\"expectedOutput\":\"x\",\"score\":5,\"visibility\":\"HIDDEN\",\"position\":2}],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";
    mvc.perform(
            post("/api/assessments")
                .header("Authorization", bearer(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unansweredProgrammingQuestionDeterministicallyCompletesWithZero() throws Exception {
    UserEntity teacher = user("c3_empty_code_teacher", UserRole.TEACHER),
        student = user("c3_empty_code_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-empty-code-start-01"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-empty-code-submit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("GRADING"));
    awaitSubmissionStatus(sid, "GRADED");
    assertThat(
            jdbc.queryForMap(
                "SELECT auto_score, grading_status FROM edu_submission_answer WHERE submission_id=? AND question_id=?",
                sid,
                qid))
        .containsEntry("AUTO_SCORE", new BigDecimal("0.00"))
        .containsEntry("GRADING_STATUS", "COMPLETED");
    Mockito.verify(ai, Mockito.never()).runSandbox(any());
  }

  @Test
  void rejectedInitialGradingEnqueueReturnsDurableClaimAndRecoveryFinishes() throws Exception {
    UserEntity teacher = user("c3_queue_teacher", UserRole.TEACHER),
        student = user("c3_queue_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), false);
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-queue-start-0001"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();

    ThreadPoolTaskExecutor executor =
        applicationContext.getBean("submissionGradingExecutor", ThreadPoolTaskExecutor.class);
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
    int capacity = pool.getQueue().remainingCapacity();
    CountDownLatch release = new CountDownLatch(1);
    Runnable blocker =
        () -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        };
    pool.execute(blocker);
    for (int index = 0; index < capacity; index++) pool.execute(blocker);

    try {
      mvc.perform(
              post("/api/submissions/{id}/submit", sid)
                  .header("Authorization", auth)
                  .header("Idempotency-Key", "c3-queue-submit-0001")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"confirm\":true}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("GRADING"));
      assertThat(
              jdbc.queryForMap(
                  "SELECT status, grading_status FROM edu_assessment_submission WHERE id=?", sid))
          .containsEntry("STATUS", "GRADING")
          .containsEntry("GRADING_STATUS", "AUTO_GRADING");
    } finally {
      release.countDown();
    }
    awaitExecutorCapacity(pool);
    submissionServiceForTest().autoSubmitExpired();
    awaitSubmissionStatus(sid, "GRADED");
  }

  @Test
  void completedSubmitClaimRecoversOneWorkerInterruptionButDoesNotRetryForever()
      throws Exception {
    UserEntity teacher = user("c3_worker_teacher", UserRole.TEACHER),
        student = user("c3_worker_student", UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublish(token(teacher), room.getId(), false);
    String auth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "c3-worker-start-0001"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    AtomicInteger claims = new AtomicInteger();
    Mockito.doAnswer(
            invocation -> {
              if (claims.incrementAndGet() == 1) throw new IllegalStateException("interrupt once");
              return null;
            })
        .when(gradingFailureHook)
        .afterClaim(sid);

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", auth)
                .header("Idempotency-Key", "c3-worker-submit-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("GRADING"));
    awaitGradingStatus(sid, "FAILED");
    submissionServiceForTest().autoSubmitExpired();
    awaitSubmissionStatus(sid, "GRADED");
    assertThat(claims).hasValue(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT grading_attempt_count FROM edu_assessment_submission WHERE id=?",
                Integer.class,
                sid))
        .isEqualTo(2);

    UserEntity alwaysFailingStudent = user("c3_worker_student_fail", UserRole.STUDENT);
    member(room, alwaysFailingStudent);
    long alwaysFailAssessment = createPublish(token(teacher), room.getId(), false);
    String failAuth = bearer(token(alwaysFailingStudent));
    JsonNode failedStart =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", alwaysFailAssessment)
                        .header("Authorization", failAuth)
                        .header("Idempotency-Key", "c3-worker-fail-start-1"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long failedSid = failedStart.at("/data/submission/id").asLong();
    AtomicInteger failedClaims = new AtomicInteger();
    Mockito.doAnswer(
            invocation -> {
              failedClaims.incrementAndGet();
              throw new IllegalStateException("always interrupted");
            })
        .when(gradingFailureHook)
        .afterClaim(failedSid);
    mvc.perform(
            post("/api/submissions/{id}/submit", failedSid)
                .header("Authorization", failAuth)
                .header("Idempotency-Key", "c3-worker-fail-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk());
    awaitGradingStatus(failedSid, "FAILED");
    submissionServiceForTest().autoSubmitExpired();
    awaitGradingAttemptCount(failedSid, 2);
    awaitGradingStatus(failedSid, "FAILED");
    submissionServiceForTest().autoSubmitExpired();
    Thread.sleep(100);
    assertThat(failedClaims).hasValue(2);
    jdbc.update(
        "UPDATE edu_assessment_submission SET grading_status='AUTO_GRADING' WHERE id=?", failedSid);
    submissionServiceForTest().autoSubmitExpired();
    Thread.sleep(100);
    assertThat(failedClaims).hasValue(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT grading_attempt_count FROM edu_assessment_submission WHERE id=?",
                Integer.class,
                failedSid))
        .isEqualTo(2);
    jdbc.update(
        "UPDATE edu_assessment_submission SET grading_status='FAILED' WHERE id=?", failedSid);
    mvc.perform(
            post("/api/submissions/{id}/submit", failedSid)
                .header("Authorization", failAuth)
                .header("Idempotency-Key", "c3-worker-fail-submit-new-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.gradingStatus").value("FAILED"));
    submissionServiceForTest().autoSubmitExpired();
    Thread.sleep(100);
    assertThat(failedClaims).hasValue(2);
  }

  @Test
  void teacherTakeoverInvalidatesInFlightAutoGradingOnSuccessAndFailure() throws Exception {
    teacherTakeoverInvalidatesInFlightAutoGrading(false);
    teacherTakeoverInvalidatesInFlightAutoGrading(true);
  }

  private void teacherTakeoverInvalidatesInFlightAutoGrading(boolean sandboxFails)
      throws Exception {
    String suffix = sandboxFails ? "failure" : "success";
    UserEntity teacher = user("c3_takeover_teacher_" + suffix, UserRole.TEACHER),
        student = user("c3_takeover_student_" + suffix, UserRole.STUDENT);
    ClassEntity room = room(teacher);
    member(room, student);
    long assessment = createPublishProgramming(token(teacher), room.getId());
    String studentAuth = bearer(token(student));
    JsonNode started =
        json.readTree(
            mvc.perform(
                    post("/api/assessments/{id}/submissions", assessment)
                        .header("Authorization", studentAuth)
                        .header("Idempotency-Key", "c3-takeover-start-" + suffix))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long sid = started.at("/data/submission/id").asLong();
    long qid = started.at("/data/paper/questions/0/id").asLong();
    mvc.perform(
            put("/api/submissions/{id}/answers/{qid}", sid, qid)
                .header("Authorization", studentAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientSaveSequence\":1,\"sourceCode\":\"print(input())\"}"))
        .andExpect(status().isOk());

    CountDownLatch sandboxEntered = new CountDownLatch(1);
    CountDownLatch releaseSandbox = new CountDownLatch(1);
    Mockito.doAnswer(
            invocation -> {
              SandboxRunRequest request = invocation.getArgument(0);
              sandboxEntered.countDown();
              assertThat(releaseSandbox.await(5, TimeUnit.SECONDS)).isTrue();
              if (sandboxFails) throw new IllegalStateException("simulated sandbox failure");
              List<SandboxRunResponse.SandboxTestResult> results =
                  request.testCases().stream()
                      .map(
                          test ->
                              new SandboxRunResponse.SandboxTestResult(
                                  test.testCaseId(),
                                  "ACCEPTED",
                                  test.score(),
                                  test.score(),
                                  1,
                                  1024,
                                  test.visibility(),
                                  null,
                                  null,
                                  null))
                      .toList();
              BigDecimal score =
                  request.testCases().stream()
                      .map(SandboxRunRequest.SandboxTestCase::score)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              return new SandboxRunResponse(
                  request.requestId(),
                  "ACCEPTED",
                  score,
                  score,
                  results.size(),
                  results.size(),
                  null,
                  results);
            })
        .when(ai)
        .runSandbox(any());

    mvc.perform(
            post("/api/submissions/{id}/submit", sid)
                .header("Authorization", studentAuth)
                .header("Idempotency-Key", "c3-takeover-submit-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.gradingStatus").value("AUTO_GRADING"));
    assertThat(sandboxEntered.await(5, TimeUnit.SECONDS)).isTrue();

    mvc.perform(
            put("/api/submissions/{id}/answers/{qid}/grade", sid, qid)
                .header("Authorization", bearer(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":3.25,\"comment\":\"teacher takeover\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.finalScore").value(3.25));
    releaseSandbox.countDown();

    ThreadPoolTaskExecutor executor =
        applicationContext.getBean("submissionGradingExecutor", ThreadPoolTaskExecutor.class);
    awaitExecutorCapacity(executor.getThreadPoolExecutor());
    assertThat(
            jdbc.queryForMap(
                "SELECT status,grading_status FROM edu_assessment_submission WHERE id=?", sid))
        .containsEntry("STATUS", "GRADING")
        .containsEntry("GRADING_STATUS", "MANUAL_GRADING");
    assertThat(
            jdbc.queryForObject(
                "SELECT final_score FROM edu_submission_answer WHERE submission_id=? AND question_id=?",
                BigDecimal.class,
                sid,
                qid))
        .isEqualByComparingTo("3.25");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM edu_grading_record record_row "
                    + "JOIN edu_submission_answer answer_row ON answer_row.id=record_row.answer_id "
                    + "WHERE answer_row.submission_id=? AND record_row.grading_source='AUTO'",
                Long.class,
                sid))
        .isZero();
  }

  private long createPublish(String teacher, long classId, boolean exam) throws Exception {
    String type = exam ? "EXAM" : "HOMEWORK";
    String start = Instant.now().minusSeconds(60).toString(),
        due = Instant.now().plusSeconds(3600).toString();
    String body =
        "{\"assessmentType\":\""
            + type
            + "\",\"classId\":"
            + classId
            + ",\"title\":\"C3\",\"startAt\":\""
            + start
            + "\",\"dueAt\":\""
            + due
            + "\","
            + (exam ? "\"durationMinutes\":30," : "")
            + "\"settings\":{\"antiCheatPolicy\":{\"enabled\":"
            + exam
            + ",\"singleDeviceOnly\":"
            + exam
            + "}},\"questions\":[{\"clientKey\":\"q1\",\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"2+2?\",\"score\":10,\"position\":1,\"options\":[{\"key\":\"A\",\"content\":\"3\",\"isCorrect\":false,\"position\":1},{\"key\":\"B\",\"content\":\"4\",\"isCorrect\":true,\"position\":2}],\"referenceAnswer\":{\"selectedOptionKeys\":[\"B\"]},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"知识点\"}]}]}";
    String created =
        mvc.perform(
                post("/api/assessments")
                    .header("Authorization", bearer(teacher))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(created).at("/data/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/publish", id)
                .header("Authorization", bearer(teacher))
                .header("Idempotency-Key", "c3-publish-key-" + id + "-0000"))
        .andExpect(status().isOk());
    return id;
  }

  private long createPublishHiddenResult(String teacher, long classId) throws Exception {
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + classId
            + ",\"title\":\"C3 hidden result\",\"startAt\":\""
            + Instant.now().minusSeconds(60)
            + "\",\"dueAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\",\"settings\":{\"showScoreAfterRelease\":false,\"showCorrectAnswerAfterRelease\":false,\"showQuestionFeedbackAfterRelease\":false},\"questions\":[{\"clientKey\":\"q1\",\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"2+2?\",\"score\":10,\"position\":1,\"options\":[{\"key\":\"A\",\"content\":\"3\",\"isCorrect\":false,\"position\":1},{\"key\":\"B\",\"content\":\"4\",\"isCorrect\":true,\"position\":2}],\"referenceAnswer\":{\"selectedOptionKeys\":[\"B\"]},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";
    String created =
        mvc.perform(
                post("/api/assessments")
                    .header("Authorization", bearer(teacher))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(created).at("/data/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/publish", id)
                .header("Authorization", bearer(teacher))
                .header("Idempotency-Key", "c3-visibility-publish-" + id))
        .andExpect(status().isOk());
    return id;
  }

  private long createPublishProgramming(String teacher, long classId) throws Exception {
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + classId
            + ",\"title\":\"C3 code\",\"startAt\":\""
            + Instant.now().minusSeconds(60)
            + "\",\"dueAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\",\"settings\":{},\"questions\":[{\"clientKey\":\"code\",\"questionType\":\"PROGRAMMING\",\"stem\":\"echo\",\"score\":10,\"position\":1,\"referenceAnswer\":{\"sourceCode\":\"print(input())\"},\"programmingConfig\":{\"language\":\"PYTHON\",\"starterCode\":\"\",\"timeLimitMs\":2000,\"memoryLimitMb\":128,\"compareMode\":\"EXACT\"},\"testCases\":[{\"clientKey\":\"pub\",\"input\":\"ok\",\"expectedOutput\":\"ok\",\"score\":5,\"visibility\":\"PUBLIC\",\"position\":1},{\"clientKey\":\"hid\",\"input\":\"secret\",\"expectedOutput\":\"hidden-output\",\"score\":5,\"visibility\":\"HIDDEN\",\"position\":2}],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"知识点\"}]}]}";
    String created =
        mvc.perform(
                post("/api/assessments")
                    .header("Authorization", bearer(teacher))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(created).at("/data/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/publish", id)
                .header("Authorization", bearer(teacher))
                .header("Idempotency-Key", "c3-code-publish-" + id + "-0000"))
        .andExpect(status().isOk());
    return id;
  }

  private long createPublishShuffled(String teacher, long classId) throws Exception {
    String body =
        "{\"assessmentType\":\"HOMEWORK\",\"classId\":"
            + classId
            + ",\"title\":\"C3 shuffle\",\"startAt\":\""
            + Instant.now().minusSeconds(60)
            + "\",\"dueAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\",\"settings\":{\"shuffleQuestions\":true,\"shuffleOptions\":true},\"questions\":["
            + "{\"clientKey\":\"q1\",\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"first original\",\"score\":5,\"position\":1,\"options\":[{\"key\":\"A\",\"content\":\"wrong\",\"isCorrect\":false,\"position\":1},{\"key\":\"B\",\"content\":\"right\",\"isCorrect\":true,\"position\":2}],\"referenceAnswer\":{\"selectedOptionKeys\":[\"B\"]},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]},"
            + "{\"clientKey\":\"q2\",\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"second original\",\"score\":5,\"position\":2,\"options\":[{\"key\":\"A\",\"content\":\"wrong\",\"isCorrect\":false,\"position\":1},{\"key\":\"B\",\"content\":\"right\",\"isCorrect\":true,\"position\":2}],\"referenceAnswer\":{\"selectedOptionKeys\":[\"B\"]},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";
    String created =
        mvc.perform(
                post("/api/assessments")
                    .header("Authorization", bearer(teacher))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(created).at("/data/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/publish", id)
                .header("Authorization", bearer(teacher))
                .header("Idempotency-Key", "c3-shuffle-publish-" + id + "-0000"))
        .andExpect(status().isOk());
    return id;
  }

  private long createPublishExamWithDisabledAntiCheat(String teacher, long classId)
      throws Exception {
    String body =
        "{\"assessmentType\":\"EXAM\",\"classId\":"
            + classId
            + ",\"title\":\"C3 policy\",\"startAt\":\""
            + Instant.now().minusSeconds(60)
            + "\",\"dueAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\",\"durationMinutes\":30,\"settings\":{\"antiCheatPolicy\":{\"enabled\":false,\"singleDeviceOnly\":false,\"recordCopyPaste\":true,\"recordPageVisibility\":true}},\"questions\":[{\"clientKey\":\"q1\",\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"2+2?\",\"score\":10,\"position\":1,\"options\":[{\"key\":\"A\",\"content\":\"3\",\"isCorrect\":false,\"position\":1},{\"key\":\"B\",\"content\":\"4\",\"isCorrect\":true,\"position\":2}],\"referenceAnswer\":{\"selectedOptionKeys\":[\"B\"]},\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"知识点\"}]}]}";
    String created =
        mvc.perform(
                post("/api/assessments")
                    .header("Authorization", bearer(teacher))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = json.readTree(created).at("/data/id").asLong();
    mvc.perform(
            post("/api/assessments/{id}/publish", id)
                .header("Authorization", bearer(teacher))
                .header("Idempotency-Key", "c3-policy-publish-" + id + "-0000"))
        .andExpect(status().isOk());
    return id;
  }

  private UserEntity user(String name, UserRole role) {
    UserEntity u = new UserEntity();
    u.setUsername(name);
    u.setPasswordHash("x");
    u.setDisplayName(name);
    u.setRole(role);
    u.setStatus(UserStatus.ACTIVE);
    users.insert(u);
    return u;
  }

  private ClassEntity room(UserEntity t) {
    ClassEntity c = new ClassEntity();
    c.setName("C3-" + t.getUsername());
    c.setCourseId(1L);
    c.setTeacherId(t.getId());
    c.setStatus(ClassStatus.ACTIVE);
    classes.insert(c); CourseTestFixtures.teacher(jdbc, c.getTeacherId());
    return c;
  }

  private void member(ClassEntity c, UserEntity s) {
    jdbc.update(
        "INSERT INTO edu_class_student(class_id,student_id) VALUES(?,?)", c.getId(), s.getId());
  }

  private String token(UserEntity u) {
    return jwt.issueAccessToken(u);
  }

  private String bearer(String t) {
    return "Bearer " + t;
  }

  private String sha256ForTest(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private void awaitSubmissionStatus(long submissionId, String expected) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      String current =
          jdbc.queryForObject(
              "SELECT status FROM edu_assessment_submission WHERE id=?",
              String.class,
              submissionId);
      if (expected.equals(current)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("submission status did not become " + expected);
  }

  private void awaitGradingStatus(long submissionId, String expected) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      String current =
          jdbc.queryForObject(
              "SELECT grading_status FROM edu_assessment_submission WHERE id=?",
              String.class,
              submissionId);
      if (expected.equals(current)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("grading status did not become " + expected);
  }

  private void awaitAnswerGradingStatus(long submissionId, long questionId, String expected)
      throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      String current =
          jdbc.queryForObject(
              "SELECT grading_status FROM edu_submission_answer WHERE submission_id=? AND question_id=?",
              String.class,
              submissionId,
              questionId);
      if (expected.equals(current)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("answer grading status did not become " + expected);
  }

  private void awaitExecutorCapacity(ThreadPoolExecutor pool) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("grading executor did not drain");
  }

  private void awaitGradingAttemptCount(long submissionId, int expected) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      Integer current =
          jdbc.queryForObject(
              "SELECT grading_attempt_count FROM edu_assessment_submission WHERE id=?",
              Integer.class,
              submissionId);
      if (current != null && current == expected) return;
      Thread.sleep(20);
    }
    throw new AssertionError("grading attempt count did not become " + expected);
  }

  private com.education.agent.submission.service.SubmissionService submissionServiceForTest() {
    return applicationContext.getBean(
        com.education.agent.submission.service.SubmissionService.class);
  }

  private com.education.agent.submission.entity.SubmissionEntity submissionsForTest(long id) {
    return submissionsMapperForTest().selectById(id);
  }

  private com.education.agent.submission.mapper.SubmissionMapper submissionsMapperForTest() {
    return applicationContext.getBean(com.education.agent.submission.mapper.SubmissionMapper.class);
  }

  @Autowired org.springframework.context.ApplicationContext applicationContext;
}
