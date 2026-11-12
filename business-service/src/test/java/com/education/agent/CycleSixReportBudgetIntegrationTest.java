package com.education.agent;

import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.entity.ClassStudentEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.report.controller.ReportController.GradeFormat;
import com.education.agent.report.controller.ReportController.LearningFormat;
import com.education.agent.report.service.ReportService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.security.JwtService;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={
    "education.report.max-rows=2","education.report.max-cells=100",
    "education.report.max-cell-chars=64","education.report.max-total-chars=5000",
    "education.report.max-bytes=1048576"})
@AutoConfigureMockMvc
@Transactional
class CycleSixReportBudgetIntegrationTest {
  @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired UserMapper users;@Autowired PasswordEncoder passwords;
  @Autowired ClassMapper classes;@Autowired ClassStudentMapper memberships;@Autowired AssessmentMapper assessments;
  @Autowired JwtService jwt;@Autowired ObjectMapper json;

  @Test
  void exactRowBudgetSucceedsAndOneExtraRowReturnsExplicit422() throws Exception {
    Fixture fixture=fixture("rows","{}");UserEntity first=student("row_a_"+fixture.suffix),second=student("row_b_"+fixture.suffix),third=student("row_c_"+fixture.suffix);
    submit(fixture.assessment,first,"ok");submit(fixture.assessment,second,"ok");
    mvc.perform(get("/api/reports/assessments/{id}/grades",fixture.assessment.getId()).param("format","CSV").header("Authorization",bearer(fixture.teacher)))
        .andExpect(status().isOk());
    submit(fixture.assessment,third,"over");
    mvc.perform(get("/api/reports/assessments/{id}/grades",fixture.assessment.getId()).param("format","CSV").header("Authorization",bearer(fixture.teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/reports/assessments/{id}/grades",fixture.assessment.getId()).param("format","XLSX").header("Authorization",bearer(fixture.teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    Fixture pdfOne=fixture("pdf-row-a","{}");Fixture pdfTwo=fixture("pdf-row-b","{}");UserEntity pdfStudent=student("pdf_row_"+fixture.suffix);submit(pdfOne.assessment,pdfStudent,"one");submit(pdfTwo.assessment,pdfStudent,"two");
    mvc.perform(get("/api/reports/students/{id}/learning",pdfStudent.getId()).param("courseId","1").param("format","PDF").header("Authorization",bearer(pdfStudent)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void oversizedVisibleCellIsRejectedButHiddenFeedbackRemainsCropped() throws Exception {
    Fixture visible=fixture("long-visible","{\"showQuestionFeedbackAfterRelease\":true}");UserEntity student=student("long_student_"+visible.suffix);submit(visible.assessment,student,"x".repeat(65));
    mvc.perform(get("/api/reports/assessments/{id}/grades",visible.assessment.getId()).param("format","XLSX").header("Authorization",bearer(visible.teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/reports/assessments/{id}/grades",visible.assessment.getId()).param("format","CSV").header("Authorization",bearer(visible.teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/reports/students/{id}/learning",student.getId()).param("courseId","1").param("format","PDF").header("Authorization",bearer(student)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    Fixture hidden=fixture("long-hidden","{\"showQuestionFeedbackAfterRelease\":false}");UserEntity hiddenStudent=student("hidden_student_"+hidden.suffix);member(hidden.room,hiddenStudent);submit(hidden.assessment,hiddenStudent,"x".repeat(500));
    mvc.perform(get("/api/reports/assessments/{id}/grades",hidden.assessment.getId()).param("format","XLSX").header("Authorization",bearer(hidden.teacher))).andExpect(status().isOk());
    mvc.perform(get("/api/reports/students/{id}/learning",hiddenStudent.getId()).param("courseId","1").header("Authorization",bearer(hiddenStudent))).andExpect(status().isOk());
  }

  @Test
  void csvXlsxAndPdfAllEnforceFinalByteBudgetWithoutPartialFiles() {
    Fixture fixture=fixture("bytes","{}");UserEntity student=student("byte_student_"+fixture.suffix);member(fixture.room,student);submit(fixture.assessment,student,"short");
    ReportService tiny=new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 200, 128, 10000, 64);
    assertValidation(()->tiny.gradeReport(AuthenticatedUser.from(fixture.teacher),fixture.assessment.getId(),GradeFormat.CSV));
    assertValidation(()->tiny.gradeReport(AuthenticatedUser.from(fixture.teacher),fixture.assessment.getId(),GradeFormat.XLSX));
    assertValidation(()->tiny.learningReport(AuthenticatedUser.from(student), student.getId(), 1L, LearningFormat.PDF));
  }

  @Test
  void cellsTotalCharactersAndOversizedSettingsAreRejected() throws Exception {
    Fixture fixture=fixture("aggregate","{}");UserEntity student=student("aggregate_student_"+fixture.suffix);submit(fixture.assessment,student,"short");
    ReportService cells=new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 19, 128, 10000, 1048576);
    assertValidation(()->cells.gradeReport(AuthenticatedUser.from(fixture.teacher),fixture.assessment.getId(),GradeFormat.CSV));
    ReportService characters=new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 200, 128, 100, 1048576);
    assertValidation(()->characters.gradeReport(AuthenticatedUser.from(fixture.teacher),fixture.assessment.getId(),GradeFormat.XLSX));

    String settings="{\"showScoreAfterRelease\":true,\"padding\":\""+"x".repeat(80)+"\"}";
    Fixture oversized=fixture("settings",settings);
    mvc.perform(get("/api/reports/assessments/{id}/grades",oversized.assessment.getId()).param("format","CSV").header("Authorization",bearer(oversized.teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unsafeAbsoluteBudgetConfigurationIsRejectedBeforeQueries() {
    assertConfigInvalid(()->new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), ReportService.ABSOLUTE_MAX_ROWS+1, 200, 128, 1000, 1024),"max-rows");
    assertConfigInvalid(()->new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, ReportService.ABSOLUTE_MAX_CELLS+1, 128, 1000, 1024),"max-cells");
    assertConfigInvalid(()->new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 200, ReportService.ABSOLUTE_MAX_CELL_CHARS+1, 1000, 1024),"max-cell-chars");
    assertConfigInvalid(()->new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 200, 128, ReportService.ABSOLUTE_MAX_TOTAL_CHARS+1, 1024),"max-total-chars");
    assertConfigInvalid(()->new ReportService(jdbc, json, new com.education.agent.course.CourseAccessService(jdbc, json), 10, 200, 128, 1000, ReportService.ABSOLUTE_MAX_BYTES+1),"max-bytes");
  }

  private void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));}
  private void assertConfigInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String property){assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("education.report."+property);}
  private Fixture fixture(String prefix,String settings){String suffix=UUID.randomUUID().toString().substring(0,8);UserEntity teacher=user(prefix+"_teacher_"+suffix,UserRole.TEACHER);ClassEntity room=room(teacher);AssessmentEntity assessment=assessment(room,teacher,settings);return new Fixture(suffix,teacher,room,assessment);}
  private UserEntity student(String username){return user(username,UserRole.STUDENT);}
  private UserEntity user(String username,UserRole role){UserEntity user=new UserEntity();user.setUsername(username);user.setPasswordHash(passwords.encode("Pass@123456"));user.setDisplayName(username);user.setRole(role);user.setStatus(UserStatus.ACTIVE);user.setForcePasswordChange(false);users.insert(user);if(role==UserRole.STUDENT)CourseTestFixtures.student(jdbc,user.getId());return users.selectById(user.getId());}
  private ClassEntity room(UserEntity teacher){ClassEntity room=new ClassEntity();room.setCourseId(1L);room.setName("Budget "+UUID.randomUUID());room.setCourseName("Python");room.setTeacherId(teacher.getId());room.setStatus(ClassStatus.ACTIVE);classes.insert(room); CourseTestFixtures.teacher(jdbc, room.getTeacherId());return room;}
  private void member(ClassEntity room,UserEntity student){ClassStudentEntity row=new ClassStudentEntity();row.setClassId(room.getId());row.setStudentId(student.getId());memberships.insert(row);}
  private AssessmentEntity assessment(ClassEntity room,UserEntity teacher,String settings){AssessmentEntity assessment=new AssessmentEntity();assessment.setAssessmentType(AssessmentType.HOMEWORK);assessment.setClassId(room.getId());assessment.setCreatorId(teacher.getId());assessment.setTitle("Budget report");assessment.setStatus(AssessmentStatus.RELEASED);assessment.setTotalScore(BigDecimal.TEN);assessment.setAutoGradeEnabled(true);assessment.setAutoReleaseEnabled(false);assessment.setAiAttachmentGradeEnabled(false);assessment.setSettingsJson(settings);assessment.setCurrentVersion(1);assessments.insert(assessment);return assessment;}
  private void submit(AssessmentEntity assessment,UserEntity student,String comment){jdbc.update("""
      INSERT INTO edu_assessment_submission(assessment_id,assessment_version,student_id,status,attempt_no,final_score,grading_status,result_released_at,overall_comment,created_at,updated_at)
      VALUES(?,1,?,'GRADED',1,9.00,'COMPLETED',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
      """,assessment.getId(),student.getId(),comment);}
  private String bearer(UserEntity user){return "Bearer "+jwt.issueAccessToken(user);}
  private record Fixture(String suffix,UserEntity teacher,ClassEntity room,AssessmentEntity assessment){}
}
