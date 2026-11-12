package com.education.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.integration.ai.*;
import com.education.agent.integration.knowledge.*;
import com.education.agent.grading.service.AssessmentLifecycleService;
import com.education.agent.grading.service.GradingService;
import com.education.agent.learning.service.LearningEvidenceService;
import com.education.agent.security.JwtService;
import com.education.agent.submission.service.GradingFailureHook;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.*;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CycleFourGradingIntegrationTest {
  @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserMapper users;
  @Autowired ClassMapper classes; @Autowired JdbcTemplate jdbc; @Autowired JwtService jwt;
  @Autowired AssessmentLifecycleService lifecycle;
  @Autowired GradingService gradingService;
  @Autowired LearningEvidenceService learningEvidence;
  @MockBean KnowledgeServiceClient graph; @MockBean AiServiceClient ai; @MockBean GradingFailureHook hook;

  @BeforeEach void reset(){Mockito.reset(graph,ai,hook);when(graph.ragContext(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn(new InternalGraphRagContextResponse(1L, new InternalGraphNode("kp","知识点",1,"knowledge_point",null,null,List.of(),List.of(),null,List.of(),List.of(),"APPROVED",1), List.of(), List.of(), List.of(), "g-c4", "READY", List.of()));}

  @Test void manualGradeCompleteReleaseAndIsolation() throws Exception {
    UserEntity teacher=user("c4_teacher",UserRole.TEACHER),student=user("c4_student",UserRole.STUDENT),outsider=user("c4_outsider",UserRole.TEACHER),admin=user("c4_admin",UserRole.ADMIN);
    ClassEntity room=room(teacher);member(room,student);long assessment=createSubjective(token(teacher),room.getId(),false);long sid=startAndSubmit(token(student),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(student)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_NOT_RELEASED"));
    mvc.perform(get("/api/submissions/{id}/grading",sid).header("Authorization",bearer(token(outsider)))).andExpect(status().isNotFound());
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(admin))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}")).andExpect(status().isNotFound());
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":11}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8.50,\"comment\":\"good\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(8.5));
    mvc.perform(put("/api/submissions/{id}/comment",sid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"overall\"}")).andExpect(status().isOk());
    String key="c4-complete-key-0000001";
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",key)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("GRADED")).andExpect(jsonPath("$.data.finalScore").value(8.5));
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",key)).andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(8.5));
    mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-close-before-release01")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));
    mvc.perform(post("/api/assessments/{id}/release",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-release-key-00000001").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED"));
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(student)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.achievedScore").value(8.5)).andExpect(jsonPath("$.data.overallComment").value("overall"));
    mvc.perform(post("/api/assessments/{id}/archive",assessment).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(student)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.achievedScore").value(8.5)).andExpect(jsonPath("$.data.overallComment").value("overall"));
    mvc.perform(get("/api/submissions/{id}",sid).header("Authorization",bearer(token(student)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(8.5)).andExpect(jsonPath("$.data.overallComment").value("overall"));
    mvc.perform(put("/api/submissions/{id}/comment",sid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"late edit\"}")).andExpect(status().isConflict());
    Long answerId=jdbc.queryForObject("SELECT id FROM edu_submission_answer WHERE submission_id=? AND question_id=?",Long.class,sid,qid);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_grading_record WHERE answer_id=? AND grading_source='TEACHER'",Long.class,answerId)).isEqualTo(1);
  }

  @Test void closeRejectsOpenHomeworkAndStatisticsUseAssignedStudents() throws Exception {
    UserEntity teacher=user("c4_close_teacher",UserRole.TEACHER),a=user("c4_close_a",UserRole.STUDENT),b=user("c4_close_b",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,a);member(room,b);long assessment=createSubjective(token(teacher),room.getId(),false);
    JsonNode start=json.readTree(mvc.perform(post("/api/assessments/{id}/submissions",assessment).header("Authorization",bearer(token(a))).header("Idempotency-Key","c4-close-start-000001")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long sid=start.at("/data/submission/id").asLong();long qid=start.at("/data/paper/questions/0/id").asLong();
    mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-close-key-0000001")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));
    mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,qid).header("Authorization",bearer(token(a))).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":1,\"textAnswer\":\"x\"}")).andExpect(status().isConflict());
    mvc.perform(post("/api/submissions/{id}/submit",sid).header("Authorization",bearer(token(a))).header("Idempotency-Key","c4-close-submit-00001").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isConflict());
    mvc.perform(get("/api/assessments/{id}/statistics",assessment).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignedCount").value(2)).andExpect(jsonPath("$.data.submittedCount").value(0)).andExpect(jsonPath("$.data.unsubmittedCount").value(2));
  }

  @Test void bulkCommentsAreAtomicAndFutureReleaseIsNotVisible() throws Exception {
    UserEntity teacher=user("c4_bulk_teacher",UserRole.TEACHER),s=user("c4_bulk_student",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,s);long assessment=createSubjective(token(teacher),room.getId(),false);long sid=startAndSubmit(token(s),assessment);
    mvc.perform(put("/api/assessments/{id}/bulk-comments",assessment).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"submissionId\":"+sid+",\"comment\":\"must rollback\"},{\"submissionId\":999999,\"comment\":\"bad\"}]}")).andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("SELECT overall_comment FROM edu_assessment_submission WHERE id=?",String.class,sid)).isNull();
    long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":7}")).andExpect(status().isOk());mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-future-complete001")).andExpect(status().isOk());
    mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-future-close-00001")).andExpect(status().isOk());
    mvc.perform(post("/api/assessments/{id}/release",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-future-release-001").contentType(MediaType.APPLICATION_JSON).content("{\"releasedAt\":\""+Instant.now().plusSeconds(3600)+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(s)))).andExpect(status().isConflict());
  }

  @Test void aiTaskPersistsSuggestionOnlyAndFailureRevokesAccess() throws Exception {
    UserEntity teacher=user("c4_ai_teacher",UserRole.TEACHER),s=user("c4_ai_student",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,s);long assessment=createSubjective(token(teacher),room.getId(),false);long sid=startAndSubmit(token(s),assessment);
    CountDownLatch called=new CountDownLatch(1);
    when(ai.suggestSubjectiveGrade(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{AiGradeRequest.Subjective req=inv.getArgument(0);called.countDown();return new AiGradeResponse(req.requestId(),new BigDecimal("6.00"),List.of(new AiGradeResponse.RubricGrade("accuracy",new BigDecimal("6.00"),new BigDecimal("10.00"),List.of("evidence"),new BigDecimal("0.80"),"suggestion")),"overall",List.of(),"fake-model","fake-prompt");});
    JsonNode started=json.readTree(mvc.perform(post("/api/submissions/{id}/ai-grade",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-ai-task-key-000001")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());long taskId=started.at("/data/taskId").asLong();assertThat(called.await(3,TimeUnit.SECONDS)).isTrue();awaitTask(taskId,"SUCCEEDED");
    Long answerId=jdbc.queryForObject("SELECT id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_grading_record WHERE answer_id=? AND grading_source='AI' AND suggested_score=6 AND final_score IS NULL",Long.class,answerId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT final_score FROM edu_submission_answer WHERE id=?",BigDecimal.class,answerId)).isNull();
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-ai-complete-key001")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GRADING_INCOMPLETE"));
    mvc.perform(get("/api/ai-tasks/{id}",taskId).header("Authorization",bearer(token(s)))).andExpect(status().isNotFound());
  }

  @Test void teacherCanAcceptOneOrAllAiSuggestionsWithoutTypingScores() throws Exception {
    UserEntity teacher=user("c4_accept_teacher",UserRole.TEACHER),student=user("c4_accept_student",UserRole.STUDENT);
    ClassEntity room=room(teacher);member(room,student);
    when(ai.suggestSubjectiveGrade(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{
      AiGradeRequest.Subjective request=inv.getArgument(0);
      return gradeResponse(request.requestId(),"accuracy");
    });

    long firstAssessment=createSubjective(token(teacher),room.getId(),false),firstSid=startAndSubmit(token(student),firstAssessment);
    long firstTask=startAiTask(token(teacher),firstSid,"c4-accept-one-task-001");awaitTask(firstTask,"SUCCEEDED");
    long firstQuestion=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,firstSid);
    long firstAnswer=jdbc.queryForObject("SELECT id FROM edu_submission_answer WHERE submission_id=?",Long.class,firstSid);
    mvc.perform(post("/api/submissions/{id}/answers/{qid}/accept-ai-grade",firstSid,firstQuestion).header("Authorization",bearer(token(student)))).andExpect(status().isNotFound());
    mvc.perform(post("/api/submissions/{id}/answers/{qid}/accept-ai-grade",firstSid,firstQuestion).header("Authorization",bearer(token(teacher))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(6));
    mvc.perform(get("/api/submissions/{id}/grading",firstSid).header("Authorization",bearer(token(teacher))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.gradingAnswers[0].records[1].source").value("TEACHER"))
        .andExpect(jsonPath("$.data.gradingAnswers[0].records[1].acceptedAiRecordId").isNumber())
        .andExpect(jsonPath("$.data.gradingAnswers[0].answer.finalScore").value(6));
    mvc.perform(post("/api/submissions/{id}/answers/{qid}/accept-ai-grade",firstSid,firstQuestion).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_grading_record WHERE answer_id=? AND grading_source='TEACHER'",Long.class,firstAnswer)).isEqualTo(1);

    long secondAssessment=createSubjective(token(teacher),room.getId(),false),secondSid=startAndSubmit(token(student),secondAssessment);
    long secondTask=startAiTask(token(teacher),secondSid,"c4-accept-all-task-001");awaitTask(secondTask,"SUCCEEDED");
    long secondAnswer=jdbc.queryForObject("SELECT id FROM edu_submission_answer WHERE submission_id=?",Long.class,secondSid);
    mvc.perform(post("/api/submissions/{id}/accept-ai-grades",secondSid).header("Authorization",bearer(token(teacher))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.gradingAnswers[0].answer.finalScore").value(6))
        .andExpect(jsonPath("$.data.gradingAnswers[0].records[1].acceptedAiRecordId").isNumber());
    mvc.perform(post("/api/submissions/{id}/accept-ai-grades",secondSid).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_grading_record WHERE answer_id=? AND grading_source='TEACHER'",Long.class,secondAnswer)).isEqualTo(1);
  }

  @Test void resultVisibilitySwitchesAreIndependent() throws Exception {
    UserEntity teacher=user("c4_visibility_teacher",UserRole.TEACHER),s=user("c4_visibility_student",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,s);long assessment=createHiddenScore(token(teacher),room.getId());long sid=startAndSubmit(token(s),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8,\"comment\":\"visible feedback\"}")).andExpect(status().isOk());mvc.perform(put("/api/submissions/{id}/comment",sid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"visible overall\"}")).andExpect(status().isOk());mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-vis-complete-key01")).andExpect(status().isOk());mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-vis-close-key-00001")).andExpect(status().isOk());mvc.perform(post("/api/assessments/{id}/release",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-vis-release-key-001").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(s)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.achievedScore").doesNotExist()).andExpect(jsonPath("$.data.answers[0].score").doesNotExist()).andExpect(jsonPath("$.data.answers[0].correct").value(false)).andExpect(jsonPath("$.data.answers[0].teacherComment").value("visible feedback")).andExpect(jsonPath("$.data.overallComment").value("visible overall"));
    mvc.perform(get("/api/submissions/{id}",sid).header("Authorization",bearer(token(s))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].finalScore").doesNotExist())
        .andExpect(jsonPath("$.data.overallComment").value("visible overall"))
        .andExpect(jsonPath("$.data.answers[0].teacherComment").value("visible feedback"));
  }

  @Test void submissionDetailCannotBypassHiddenFeedbackBeforeOrAfterRelease() throws Exception {
    UserEntity teacher=user("c4_hidden_feedback_teacher",UserRole.TEACHER),student=user("c4_hidden_feedback_student",UserRole.STUDENT);
    ClassEntity room=room(teacher);member(room,student);long assessment=createHiddenFeedback(token(teacher),room.getId());long sid=startAndSubmit(token(student),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8,\"comment\":\"secret answer feedback\"}")).andExpect(status().isOk());
    mvc.perform(put("/api/submissions/{id}/comment",sid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"secret overall feedback\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-hidden-feedback-complete")).andExpect(status().isOk());
    mvc.perform(get("/api/submissions/{id}",sid).header("Authorization",bearer(token(student))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").doesNotExist())
        .andExpect(jsonPath("$.data.overallComment").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].finalScore").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].teacherComment").doesNotExist());
    mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-hidden-feedback-close")).andExpect(status().isOk());
    mvc.perform(post("/api/assessments/{id}/release",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-hidden-feedback-release").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    mvc.perform(get("/api/submissions/{id}",sid).header("Authorization",bearer(token(student))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(8))
        .andExpect(jsonPath("$.data.overallComment").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].finalScore").value(8))
        .andExpect(jsonPath("$.data.answers[0].teacherComment").doesNotExist());
    mvc.perform(get("/api/submissions/{id}/result",sid).header("Authorization",bearer(token(student))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.achievedScore").value(8))
        .andExpect(jsonPath("$.data.overallComment").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].score").value(8))
        .andExpect(jsonPath("$.data.answers[0].correct").doesNotExist())
        .andExpect(jsonPath("$.data.answers[0].teacherComment").doesNotExist());
  }

  @Test void completingOneSubmissionKeepsPublishedAndAdminIsReadOnly() throws Exception {
    UserEntity teacher=user("c4_open_teacher",UserRole.TEACHER),first=user("c4_open_first",UserRole.STUDENT),second=user("c4_open_second",UserRole.STUDENT),admin=user("c4_open_admin",UserRole.ADMIN);
    ClassEntity room=room(teacher);member(room,first);member(room,second);long assessment=createSubjective(token(teacher),room.getId(),false);long sid=startAndSubmit(token(first),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":9}")).andExpect(status().isOk());
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-open-complete-00001")).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT status FROM edu_assessment WHERE id=?",String.class,assessment)).isEqualTo("PUBLISHED");
    mvc.perform(post("/api/assessments/{id}/submissions",assessment).header("Authorization",bearer(token(second))).header("Idempotency-Key","c4-open-second-start01")).andExpect(status().isCreated());
    mvc.perform(get("/api/submissions/{id}/grading",sid).header("Authorization",bearer(token(admin)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.submission.finalScore").value(9));
    mvc.perform(get("/api/assessments/{id}/statistics",assessment).header("Authorization",bearer(token(admin)))).andExpect(status().isOk());
    mvc.perform(put("/api/submissions/{id}/comment",sid).header("Authorization",bearer(token(admin))).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"forbidden\"}")).andExpect(status().isNotFound());
  }

  @Test void rubricAndPendingSubjectiveStatisticsUseOnlyFinalScores() throws Exception {
    UserEntity teacher=user("c4_stats_teacher",UserRole.TEACHER),graded=user("c4_stats_graded",UserRole.STUDENT),pending=user("c4_stats_pending",UserRole.STUDENT);
    ClassEntity room=room(teacher);member(room,graded);member(room,pending);long assessment=createSubjective(token(teacher),room.getId(),false);
    long gradedSid=startAndSubmit(token(graded),assessment),pendingSid=startAndSubmit(token(pending),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,gradedSid);
    String inconsistent="{\"score\":8,\"rubricGrades\":[{\"label\":\"accuracy\",\"score\":7,\"maxScore\":10,\"evidence\":[],\"confidence\":1}]}";
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",gradedSid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content(inconsistent)).andExpect(status().isUnprocessableEntity());
    String valid="{\"score\":8,\"rubricGrades\":[{\"label\":\"accuracy\",\"score\":8,\"maxScore\":10,\"evidence\":[],\"confidence\":1}]}";
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",gradedSid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
    mvc.perform(get("/api/assessments/{id}/questions/{qid}/statistics",assessment,qid).header("Authorization",bearer(token(teacher))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.answeredCount").value(2)).andExpect(jsonPath("$.data.correctCount").value(0))
        .andExpect(jsonPath("$.data.incorrectCount").value(1)).andExpect(jsonPath("$.data.averageScore").value(8));
    assertThat(jdbc.queryForObject("SELECT final_score FROM edu_submission_answer WHERE submission_id=?",BigDecimal.class,pendingSid)).isNull();
  }

  @Test void lifecycleIdempotencyRecoversCommittedCloseAndScheduledRelease() throws Exception {
    UserEntity teacher=user("c4_recover_teacher",UserRole.TEACHER);ClassEntity room=room(teacher);long assessment=createSubjective(token(teacher),room.getId(),false);
    String closeKey="c4-recover-close-00001",closeOp="CLOSE_ASSESSMENT";
    jdbc.update("INSERT INTO edu_idempotency_record(user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?)",teacher.getId(),closeOp,closeKey,sha256(closeOp+":"+assessment),LocalDateTime.now().plusHours(1));
    jdbc.update("UPDATE edu_assessment SET status='READY_TO_RELEASE' WHERE id=?",assessment);
    mvc.perform(post("/api/assessments/{id}/close",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",closeKey)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));
    assertThat(jdbc.queryForObject("SELECT response_status FROM edu_idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=?",Integer.class,teacher.getId(),closeOp,closeKey)).isEqualTo(200);

    Instant scheduled=Instant.now().plusSeconds(3600);String releaseKey="c4-recover-release-001",releaseOp="RELEASE_ASSESSMENT",fingerprint=scheduled.toString();
    jdbc.update("UPDATE edu_assessment SET scheduled_release_at=? WHERE id=?",LocalDateTime.ofInstant(scheduled,java.time.ZoneId.of("Asia/Shanghai")),assessment);
    jdbc.update("INSERT INTO edu_idempotency_record(user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?)",teacher.getId(),releaseOp,releaseKey,sha256(releaseOp+":"+assessment+":"+fingerprint),LocalDateTime.now().plusHours(1));
    mvc.perform(post("/api/assessments/{id}/release",assessment).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",releaseKey).contentType(MediaType.APPLICATION_JSON).content("{\"releasedAt\":\""+fingerprint+"\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY_TO_RELEASE"));
    assertThat(jdbc.queryForObject("SELECT response_status FROM edu_idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=?",Integer.class,teacher.getId(),releaseOp,releaseKey)).isEqualTo(200);
  }

  @Test void attachmentAiOptInAndTaskReadAuthorizationAreFailClosed() throws Exception {
    UserEntity teacher=user("c4_attach_teacher",UserRole.TEACHER),student=user("c4_attach_student",UserRole.STUDENT),outsider=user("c4_attach_outsider",UserRole.TEACHER),admin=user("c4_attach_admin",UserRole.ADMIN);
    ClassEntity room=room(teacher);member(room,student);
    long disabled=createAttachmentAssessment(token(teacher),room.getId(),false,false);AttachmentSubmission denied=submitAttachment(token(student),disabled,"denied.txt");
    mvc.perform(post("/api/submissions/{id}/ai-grade",denied.submissionId()).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-attach-disabled-001")).andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task WHERE resource_id=?",Long.class,denied.submissionId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task_file_grant",Long.class)).isZero();
    Mockito.verify(ai,Mockito.never()).gradeAttachment(org.mockito.ArgumentMatchers.any());

    long mixed=createAttachmentAssessment(token(teacher),room.getId(),false,true);AttachmentSubmission mixedSubmission=submitAttachment(token(student),mixed,"mixed.txt");
    when(ai.suggestSubjectiveGrade(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{AiGradeRequest.Subjective req=inv.getArgument(0);return gradeResponse(req.requestId(),"accuracy");});
    long mixedTask=startAiTask(token(teacher),mixedSubmission.submissionId(),"c4-attach-mixed-00001");awaitTask(mixedTask,"SUCCEEDED");
    Mockito.verify(ai,Mockito.times(1)).suggestSubjectiveGrade(org.mockito.ArgumentMatchers.any());
    Mockito.verify(ai,Mockito.never()).gradeAttachment(org.mockito.ArgumentMatchers.any());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task_file_grant WHERE task_id=?",Long.class,mixedTask)).isZero();

    long enabled=createAttachmentAssessment(token(teacher),room.getId(),true,false);AttachmentSubmission allowed=submitAttachment(token(student),enabled,"allowed.txt");
    CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
    when(ai.gradeAttachment(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{AiGradeRequest.Attachment req=inv.getArgument(0);entered.countDown();assertThat(finish.await(3,TimeUnit.SECONDS)).isTrue();return gradeResponse(req.requestId(),"content");});
    long taskId=startAiTask(token(teacher),allowed.submissionId(),"c4-attach-enabled-0001");assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
    String requestId=jdbc.queryForObject("SELECT request_id FROM edu_ai_task WHERE id=?",String.class,taskId);
    assertThat(requestId).startsWith("grading-task-request-").hasSizeBetween(22,40);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task_file_grant WHERE task_id=? AND request_id=? AND file_id=?",Long.class,taskId,requestId,allowed.fileId())).isEqualTo(1);
    mvc.perform(get("/api/files/{id}/content",allowed.fileId()).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
    mvc.perform(get("/api/files/{id}/content",allowed.fileId()).header("Authorization",bearer(token(outsider)))).andExpect(status().isForbidden());
    mvc.perform(get("/api/files/{id}/content",allowed.fileId()).header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId)).andExpect(status().isOk());
    mvc.perform(get("/api/files/{id}/content",allowed.fileId()).header("X-Internal-Token","test-internal-token").header("X-Request-Id","wrong-request-id")).andExpect(status().isNotFound());
    finish.countDown();awaitTask(taskId,"SUCCEEDED");
    mvc.perform(get("/api/files/{id}/content",allowed.fileId()).header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId)).andExpect(status().isNotFound());

    jdbc.update("UPDATE edu_ai_task SET status='FAILED',result_json=?,error_code='AI_SERVICE_UNAVAILABLE',error_message=? WHERE id=?","{\"submissionId\":"+allowed.submissionId()+",\"answerCount\":1,\"internalPath\":\"D:/secret.txt\"}","sensitive http://internal/file allowed.txt",taskId);
    mvc.perform(get("/api/ai-tasks/{id}",taskId).header("Authorization",bearer(token(outsider)))).andExpect(status().isNotFound());
    mvc.perform(get("/api/ai-tasks/{id}",taskId).header("Authorization",bearer(token(teacher)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.errorMessage").value(org.hamcrest.Matchers.containsString("internal")));
    mvc.perform(get("/api/ai-tasks/{id}",taskId).header("Authorization",bearer(token(admin)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.errorMessage").doesNotExist()).andExpect(jsonPath("$.data.result.submissionId").value(allowed.submissionId())).andExpect(jsonPath("$.data.result.internalPath").doesNotExist());
    jdbc.update("INSERT INTO edu_ai_task(task_type,status,owner_id,resource_type,resource_id,request_id,progress_percent,attempt_count) VALUES('ASSESSMENT_IMPORT','FAILED',?,'SUBMISSION',?,'not-a-grade-task',100,0)",teacher.getId(),allowed.submissionId());
    Long alienTask=jdbc.queryForObject("SELECT id FROM edu_ai_task WHERE request_id='not-a-grade-task'",Long.class);
    mvc.perform(get("/api/ai-tasks/{id}",alienTask).header("Authorization",bearer(token(admin)))).andExpect(status().isNotFound());
  }

  @Test void oneFileSharedByTwoAttachmentAnswersGetsTwoExactAiGrants() throws Exception {
    UserEntity teacher=user("c4_shared_file_teacher",UserRole.TEACHER),student=user("c4_shared_file_student",UserRole.STUDENT);
    ClassEntity room=room(teacher);member(room,student);long assessment=createSharedAttachmentAssessment(token(teacher),room.getId());
    SharedAttachmentSubmission submitted=submitSharedAttachment(token(student),assessment,"shared.txt");
    CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    when(ai.gradeAttachment(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{AiGradeRequest.Attachment req=inv.getArgument(0);if(calls.incrementAndGet()==1){entered.countDown();assertThat(finish.await(3,TimeUnit.SECONDS)).isTrue();}return gradeResponse(req.requestId(),"content");});
    long taskId=startAiTask(token(teacher),submitted.submissionId(),"c4-shared-file-task-0001");
    assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
    String requestId=jdbc.queryForObject("SELECT request_id FROM edu_ai_task WHERE id=?",String.class,taskId);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task_file_grant WHERE task_id=? AND file_id=?",Long.class,taskId,submitted.fileId())).isEqualTo(2L);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT answer_id) FROM edu_ai_task_file_grant WHERE task_id=? AND file_id=?",Long.class,taskId,submitted.fileId())).isEqualTo(2L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task_file_grant grant_row JOIN edu_submission_attachment attachment ON attachment.answer_id=grant_row.answer_id AND attachment.file_id=grant_row.file_id WHERE grant_row.task_id=? AND grant_row.request_id=?",Long.class,taskId,requestId)).isEqualTo(2L);
    mvc.perform(get("/api/files/{id}/content",submitted.fileId()).header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId)).andExpect(status().isOk());
    finish.countDown();awaitTask(taskId,"SUCCEEDED");assertThat(calls).hasValue(2);
  }

  @Test void lastCompletionPersistsReadyAutoReleaseAndScannerRecovery() throws Exception {
    UserEntity teacher=user("c4_state_teacher",UserRole.TEACHER),manual=user("c4_state_manual",UserRole.STUDENT),automatic=user("c4_state_auto",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,manual);member(room,automatic);
    long ready=createSubjective(token(teacher),room.getId(),false);long readySid=startAndSubmit(token(manual),ready);long readyQ=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,readySid);
    mvc.perform(post("/api/assessments/{id}/close",ready).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-state-ready-close01")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("GRADING"));
    gradeAndComplete(token(teacher),readySid,readyQ,"c4-state-ready-complete");
    assertThat(jdbc.queryForObject("SELECT status FROM edu_assessment WHERE id=?",String.class,ready)).isEqualTo("READY_TO_RELEASE");

    long auto=createSubjective(token(teacher),room.getId(),true);long autoSid=startAndSubmit(token(automatic),auto);long autoQ=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,autoSid);
    gradeAndComplete(token(teacher),autoSid,autoQ,"c4-state-auto-open-complete");
    assertThat(jdbc.queryForObject("SELECT status FROM edu_assessment WHERE id=?",String.class,auto)).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT result_released_at FROM edu_assessment_submission WHERE id=?",LocalDateTime.class,autoSid)).isNotNull();
    mvc.perform(get("/api/submissions/{id}/result",autoSid).header("Authorization",bearer(token(automatic))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.achievedScore").value(8));
    learningEvidence.reconcileStudent(automatic.getId(), 1L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_evidence WHERE submission_id=?",Long.class,autoSid)).isPositive();
    mvc.perform(post("/api/assessments/{id}/close",auto).header("Authorization",bearer(token(teacher))).header("Idempotency-Key","c4-state-auto-close-001")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED"));
    assertThat(jdbc.queryForObject("SELECT status FROM edu_assessment WHERE id=?",String.class,auto)).isEqualTo("RELEASED");
    assertThat(jdbc.queryForObject("SELECT result_released_at FROM edu_assessment_submission WHERE id=?",LocalDateTime.class,autoSid)).isNotNull();

    long scanned=createSubjective(token(teacher),room.getId(),false);jdbc.update("UPDATE edu_assessment SET due_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),scanned);lifecycle.scanLifecycle();
    assertThat(jdbc.queryForObject("SELECT status FROM edu_assessment WHERE id=?",String.class,scanned)).isEqualTo("READY_TO_RELEASE");
  }

  @Test void gradingIdempotencyAndInterruptedAiTasksRecoverWithoutDuplicateWork() throws Exception {
    UserEntity teacher=user("c4_crash_teacher",UserRole.TEACHER),student=user("c4_crash_student",UserRole.STUDENT);ClassEntity room=room(teacher);member(room,student);long assessment=createSubjective(token(teacher),room.getId(),false);long sid=startAndSubmit(token(student),assessment);long qid=jdbc.queryForObject("SELECT question_id FROM edu_submission_answer WHERE submission_id=?",Long.class,sid);
    mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(token(teacher))).contentType(MediaType.APPLICATION_JSON).content("{\"score\":9}")).andExpect(status().isOk());
    String completeKey="c4-crash-complete-0001",completeOp="COMPLETE_SUBMISSION_GRADING";
    jdbc.update("UPDATE edu_assessment_submission SET status='GRADED',grading_status='COMPLETED',final_score=9 WHERE id=?",sid);
    jdbc.update("INSERT INTO edu_idempotency_record(user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?)",teacher.getId(),completeOp,completeKey,sha256(completeOp+":"+sid),LocalDateTime.now().plusHours(1));
    mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",completeKey)).andExpect(status().isOk()).andExpect(jsonPath("$.data.finalScore").value(9));
    assertThat(jdbc.queryForObject("SELECT response_status FROM edu_idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=?",Integer.class,teacher.getId(),completeOp,completeKey)).isEqualTo(200);

    long secondAssessment=createSubjective(token(teacher),room.getId(),false);long secondSid=startAndSubmit(token(student),secondAssessment);String aiKey="c4-crash-ai-start-00001",aiOp="START_AI_GRADING";
    jdbc.update("INSERT INTO edu_idempotency_record(user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?)",teacher.getId(),aiOp,aiKey,sha256(aiOp+":"+secondSid),LocalDateTime.now().plusHours(1));
    Long claimId=jdbc.queryForObject("SELECT id FROM edu_idempotency_record WHERE user_id=? AND operation_id=? AND idempotency_key=?",Long.class,teacher.getId(),aiOp,aiKey);
    jdbc.update("INSERT INTO edu_ai_task(task_type,status,owner_id,resource_type,resource_id,request_id,active_key,progress_percent,attempt_count) VALUES('SUBMISSION_AI_GRADE','FAILED',?,'SUBMISSION',?, ?,NULL,100,1)",teacher.getId(),secondSid,"grading-task-request-"+claimId);
    mvc.perform(post("/api/submissions/{id}/ai-grade",secondSid).header("Authorization",bearer(token(teacher))).header("Idempotency-Key",aiKey)).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("FAILED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_ai_task WHERE resource_id=?",Long.class,secondSid)).isEqualTo(1);

    jdbc.update("INSERT INTO edu_ai_task(task_type,status,owner_id,resource_type,resource_id,request_id,active_key,progress_percent,attempt_count,started_at) VALUES('SUBMISSION_AI_GRADE','RUNNING',?,'SUBMISSION',?,'exhausted-task','SUBMISSION_AI_GRADE:999999',10,2,?)",teacher.getId(),999999L,LocalDateTime.now());
    gradingService.recoverInterruptedAiTasks();
    assertThat(jdbc.queryForObject("SELECT status FROM edu_ai_task WHERE request_id='exhausted-task'",String.class)).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT active_key FROM edu_ai_task WHERE request_id='exhausted-task'",String.class)).isNull();
    Mockito.verify(ai,Mockito.never()).suggestSubjectiveGrade(org.mockito.ArgumentMatchers.any());
  }

  private long createSubjective(String teacher,long classId,boolean autoRelease)throws Exception{String body="{\"assessmentType\":\"HOMEWORK\",\"classId\":"+classId+",\"title\":\"C4\",\"startAt\":\""+Instant.now().minusSeconds(30)+"\",\"dueAt\":\""+Instant.now().plusSeconds(3600)+"\",\"settings\":{\"autoReleaseEnabled\":"+autoRelease+"},\"questions\":[{\"clientKey\":\"subjective\",\"questionType\":\"SUBJECTIVE\",\"stem\":\"explain\",\"score\":10,\"position\":1,\"referenceAnswer\":{\"textAnswer\":\"reference\"},\"rubricItems\":[{\"clientKey\":\"r1\",\"label\":\"accuracy\",\"description\":\"correct\",\"maxScore\":10,\"position\":1}],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";JsonNode created=json.readTree(mvc.perform(post("/api/assessments").header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long id=created.at("/data/id").asLong();mvc.perform(post("/api/assessments/{id}/publish",id).header("Authorization",bearer(teacher)).header("Idempotency-Key","c4-publish-key-"+id+"-001")).andExpect(status().isOk());return id;}
  private long createHiddenScore(String teacher,long classId)throws Exception{String body="{\"assessmentType\":\"HOMEWORK\",\"classId\":"+classId+",\"title\":\"C4 hidden\",\"startAt\":\""+Instant.now().minusSeconds(30)+"\",\"dueAt\":\""+Instant.now().plusSeconds(3600)+"\",\"settings\":{\"showScoreAfterRelease\":false,\"showCorrectAnswerAfterRelease\":true,\"showQuestionFeedbackAfterRelease\":true},\"questions\":[{\"clientKey\":\"subjective\",\"questionType\":\"SUBJECTIVE\",\"stem\":\"explain\",\"score\":10,\"position\":1,\"referenceAnswer\":{\"textAnswer\":\"reference\"},\"rubricItems\":[{\"clientKey\":\"r1\",\"label\":\"accuracy\",\"description\":\"correct\",\"maxScore\":10,\"position\":1}],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";JsonNode created=json.readTree(mvc.perform(post("/api/assessments").header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long id=created.at("/data/id").asLong();mvc.perform(post("/api/assessments/{id}/publish",id).header("Authorization",bearer(teacher)).header("Idempotency-Key","c4-hidden-publish-"+id+"-001")).andExpect(status().isOk());return id;}
  private long createHiddenFeedback(String teacher,long classId)throws Exception{String body="{\"assessmentType\":\"HOMEWORK\",\"classId\":"+classId+",\"title\":\"C4 hidden feedback\",\"startAt\":\""+Instant.now().minusSeconds(30)+"\",\"dueAt\":\""+Instant.now().plusSeconds(3600)+"\",\"settings\":{\"showScoreAfterRelease\":true,\"showCorrectAnswerAfterRelease\":false,\"showQuestionFeedbackAfterRelease\":false},\"questions\":[{\"clientKey\":\"subjective\",\"questionType\":\"SUBJECTIVE\",\"stem\":\"explain\",\"score\":10,\"position\":1,\"referenceAnswer\":{\"textAnswer\":\"reference\"},\"rubricItems\":[{\"clientKey\":\"r1\",\"label\":\"accuracy\",\"description\":\"correct\",\"maxScore\":10,\"position\":1}],\"knowledgePoints\":[{\"id\":\"kp\",\"name\":\"point\"}]}]}";JsonNode created=json.readTree(mvc.perform(post("/api/assessments").header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long id=created.at("/data/id").asLong();mvc.perform(post("/api/assessments/{id}/publish",id).header("Authorization",bearer(teacher)).header("Idempotency-Key","c4-hidden-feedback-publish-"+id)).andExpect(status().isOk());return id;}
  private long createAttachmentAssessment(String teacher,long classId,boolean enabled,boolean mixed)throws Exception{String subjective=mixed?",{\"clientKey\":\"subjective\",\"questionType\":\"SUBJECTIVE\",\"stem\":\"explain\",\"score\":10,\"position\":2,\"referenceAnswer\":{\"textAnswer\":\"reference\"},\"rubricItems\":[{\"clientKey\":\"r2\",\"label\":\"accuracy\",\"description\":\"correct\",\"maxScore\":10,\"position\":1}]}":"";String body="{\"assessmentType\":\"HOMEWORK\",\"classId\":"+classId+",\"title\":\"C4 attachment\",\"startAt\":\""+Instant.now().minusSeconds(30)+"\",\"dueAt\":\""+Instant.now().plusSeconds(3600)+"\",\"settings\":{\"aiAttachmentGradeEnabled\":"+enabled+"},\"questions\":[{\"clientKey\":\"attachment\",\"questionType\":\"ATTACHMENT\",\"stem\":\"upload\",\"score\":10,\"position\":1,\"rubricItems\":[{\"clientKey\":\"r1\",\"label\":\"content\",\"description\":\"content\",\"maxScore\":10,\"position\":1}],\"attachmentPolicy\":{\"allowedExtensions\":[\".txt\"],\"maxFileCount\":1,\"maxFileSizeMb\":1}}"+subjective+"]}";JsonNode created=json.readTree(mvc.perform(post("/api/assessments").header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long id=created.at("/data/id").asLong();mvc.perform(post("/api/assessments/{id}/publish",id).header("Authorization",bearer(teacher)).header("Idempotency-Key","c4-attach-publish-"+id+"-001")).andExpect(status().isOk());return id;}
  private long createSharedAttachmentAssessment(String teacher,long classId)throws Exception{String question="{\"clientKey\":\"%s\",\"questionType\":\"ATTACHMENT\",\"stem\":\"upload\",\"score\":10,\"position\":%d,\"rubricItems\":[{\"clientKey\":\"%s-r\",\"label\":\"content\",\"description\":\"content\",\"maxScore\":10,\"position\":1}],\"attachmentPolicy\":{\"allowedExtensions\":[\".txt\"],\"maxFileCount\":1,\"maxFileSizeMb\":1}}";String body="{\"assessmentType\":\"HOMEWORK\",\"classId\":"+classId+",\"title\":\"shared attachment\",\"startAt\":\""+Instant.now().minusSeconds(30)+"\",\"dueAt\":\""+Instant.now().plusSeconds(3600)+"\",\"settings\":{\"aiAttachmentGradeEnabled\":true},\"questions\":["+question.formatted("a1",1,"a1")+","+question.formatted("a2",2,"a2")+"]}";JsonNode created=json.readTree(mvc.perform(post("/api/assessments").header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long id=created.at("/data/id").asLong();mvc.perform(post("/api/assessments/{id}/publish",id).header("Authorization",bearer(teacher)).header("Idempotency-Key","c4-shared-publish-"+id)).andExpect(status().isOk());return id;}
  private SharedAttachmentSubmission submitSharedAttachment(String student,long assessment,String filename)throws Exception{JsonNode start=json.readTree(mvc.perform(post("/api/assessments/{id}/submissions",assessment).header("Authorization",bearer(student)).header("Idempotency-Key","c4-shared-start-"+assessment)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long sid=start.at("/data/submission/id").asLong(),q1=start.at("/data/paper/questions/0/id").asLong(),q2=start.at("/data/paper/questions/1/id").asLong();JsonNode upload=json.readTree(mvc.perform(multipart("/api/files").file(new MockMultipartFile("file",filename,"text/plain","shared content".getBytes())).param("purpose","SUBMISSION_ATTACHMENT").header("Authorization",bearer(student))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long fileId=upload.at("/data/id").asLong();mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,q1).header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":1,\"attachmentFileIds\":["+fileId+"]}")).andExpect(status().isOk());mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,q2).header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":2,\"attachmentFileIds\":["+fileId+"]}")).andExpect(status().isOk());mvc.perform(post("/api/submissions/{id}/submit",sid).header("Authorization",bearer(student)).header("Idempotency-Key","c4-shared-submit-"+sid).contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isOk());awaitManual(sid);return new SharedAttachmentSubmission(sid,fileId);}
  private AttachmentSubmission submitAttachment(String student,long assessment,String filename)throws Exception{JsonNode start=json.readTree(mvc.perform(post("/api/assessments/{id}/submissions",assessment).header("Authorization",bearer(student)).header("Idempotency-Key","c4-attach-start-"+assessment+"-001")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long sid=start.at("/data/submission/id").asLong();long attachmentQ=start.at("/data/paper/questions/0/id").asLong();JsonNode upload=json.readTree(mvc.perform(multipart("/api/files").file(new MockMultipartFile("file",filename,"text/plain","attachment content".getBytes())).param("purpose","SUBMISSION_ATTACHMENT").header("Authorization",bearer(student))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long fileId=upload.at("/data/id").asLong();mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,attachmentQ).header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":1,\"attachmentFileIds\":["+fileId+"]}")).andExpect(status().isOk());if(start.at("/data/paper/questions").size()>1){long subjectiveQ=start.at("/data/paper/questions/1/id").asLong();mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,subjectiveQ).header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":2,\"textAnswer\":\"answer\"}")).andExpect(status().isOk());}mvc.perform(post("/api/submissions/{id}/submit",sid).header("Authorization",bearer(student)).header("Idempotency-Key","c4-attach-submit-"+sid+"-001").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isOk());awaitManual(sid);return new AttachmentSubmission(sid,fileId,attachmentQ);}
  private long startAiTask(String teacher,long sid,String key)throws Exception{JsonNode response=json.readTree(mvc.perform(post("/api/submissions/{id}/ai-grade",sid).header("Authorization",bearer(teacher)).header("Idempotency-Key",key)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());return response.at("/data/taskId").asLong();}
  private AiGradeResponse gradeResponse(String requestId,String label){return new AiGradeResponse(requestId,new BigDecimal("6.00"),List.of(new AiGradeResponse.RubricGrade(label,new BigDecimal("6.00"),new BigDecimal("10.00"),List.of("evidence"),new BigDecimal("0.80"),"suggestion")),"overall",List.of(),"fake-model","fake-prompt");}
  private void gradeAndComplete(String teacher,long sid,long qid,String key)throws Exception{mvc.perform(put("/api/submissions/{id}/answers/{qid}/grade",sid,qid).header("Authorization",bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}")).andExpect(status().isOk());mvc.perform(post("/api/submissions/{id}/complete-grading",sid).header("Authorization",bearer(teacher)).header("Idempotency-Key",key)).andExpect(status().isOk());}
  private long startAndSubmit(String student,long assessment)throws Exception{JsonNode start=json.readTree(mvc.perform(post("/api/assessments/{id}/submissions",assessment).header("Authorization",bearer(student)).header("Idempotency-Key","c4-start-key-"+assessment+"-001")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());long sid=start.at("/data/submission/id").asLong(),qid=start.at("/data/paper/questions/0/id").asLong();mvc.perform(put("/api/submissions/{id}/answers/{qid}",sid,qid).header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content("{\"clientSaveSequence\":1,\"textAnswer\":\"answer\"}")).andExpect(status().isOk());mvc.perform(post("/api/submissions/{id}/submit",sid).header("Authorization",bearer(student)).header("Idempotency-Key","c4-submit-key-"+sid+"-001").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isOk());awaitManual(sid);return sid;}
  private void awaitManual(long sid)throws Exception{for(int i=0;i<100;i++){String s=jdbc.queryForObject("SELECT grading_status FROM edu_assessment_submission WHERE id=?",String.class,sid);if("MANUAL_GRADING".equals(s))return;Thread.sleep(20);}throw new AssertionError("grading timeout");}
  private void awaitTask(long taskId,String expected)throws Exception{for(int i=0;i<150;i++){String s=jdbc.queryForObject("SELECT status FROM edu_ai_task WHERE id=?",String.class,taskId);if(expected.equals(s))return;Thread.sleep(20);}throw new AssertionError("task timeout");}
  private UserEntity user(String n,UserRole r){UserEntity u=new UserEntity();u.setUsername(n);u.setPasswordHash("x");u.setDisplayName(n);u.setRole(r);u.setStatus(UserStatus.ACTIVE);users.insert(u);return u;}
  private ClassEntity room(UserEntity t){ClassEntity c=new ClassEntity();c.setName("C4-"+t.getUsername());c.setCourseId(1L);c.setTeacherId(t.getId());c.setStatus(ClassStatus.ACTIVE);classes.insert(c); CourseTestFixtures.teacher(jdbc, c.getTeacherId());return c;}
  private void member(ClassEntity c,UserEntity s){jdbc.update("INSERT INTO edu_class_student(class_id,student_id) VALUES(?,?)",c.getId(),s.getId());}
  private String sha256(String value)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  private String token(UserEntity u){return jwt.issueAccessToken(u);}private String bearer(String t){return "Bearer "+t;}
  private record AttachmentSubmission(long submissionId,long fileId,long questionId){}
  private record SharedAttachmentSubmission(long submissionId,long fileId){}
}
