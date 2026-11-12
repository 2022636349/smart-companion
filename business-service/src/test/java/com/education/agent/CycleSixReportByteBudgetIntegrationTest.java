package com.education.agent;

import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.security.JwtService;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="education.report.max-bytes=64")
@AutoConfigureMockMvc
@Transactional
class CycleSixReportByteBudgetIntegrationTest {
  @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired UserMapper users;@Autowired PasswordEncoder passwords;
  @Autowired ClassMapper classes;@Autowired AssessmentMapper assessments;@Autowired JwtService jwt;

  @Test
  void csvXlsxAndPdfReturn422WhenFinalByteBudgetWouldBeExceeded() throws Exception {
    String suffix=UUID.randomUUID().toString().substring(0,8);UserEntity teacher=user("byte_teacher_"+suffix,UserRole.TEACHER);UserEntity student=user("byte_student_"+suffix,UserRole.STUDENT);
    ClassEntity room=new ClassEntity();room.setCourseId(1L);room.setName("Byte budget");room.setCourseName("Python");room.setTeacherId(teacher.getId());room.setStatus(ClassStatus.ACTIVE);classes.insert(room); CourseTestFixtures.teacher(jdbc, room.getTeacherId());
    AssessmentEntity assessment=new AssessmentEntity();assessment.setAssessmentType(AssessmentType.HOMEWORK);assessment.setClassId(room.getId());assessment.setCreatorId(teacher.getId());assessment.setTitle("Byte report");assessment.setStatus(AssessmentStatus.RELEASED);assessment.setTotalScore(BigDecimal.TEN);assessment.setAutoGradeEnabled(true);assessment.setAutoReleaseEnabled(false);assessment.setAiAttachmentGradeEnabled(false);assessment.setSettingsJson("{}");assessment.setCurrentVersion(1);assessments.insert(assessment);
    jdbc.update("""
        INSERT INTO edu_assessment_submission(assessment_id,assessment_version,student_id,status,attempt_no,final_score,grading_status,result_released_at,overall_comment,created_at,updated_at)
        VALUES(?,1,?,'GRADED',1,9.00,'COMPLETED',CURRENT_TIMESTAMP,'short',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        """,assessment.getId(),student.getId());
    for(String format:new String[]{"CSV","XLSX"})mvc.perform(get("/api/reports/assessments/{id}/grades",assessment.getId()).param("format",format).header("Authorization",bearer(teacher)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/reports/students/{id}/learning",student.getId()).param("courseId","1").param("format","PDF").header("Authorization",bearer(student)))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private UserEntity user(String username,UserRole role){UserEntity user=new UserEntity();user.setUsername(username);user.setPasswordHash(passwords.encode("Pass@123456"));user.setDisplayName(username);user.setRole(role);user.setStatus(UserStatus.ACTIVE);user.setForcePasswordChange(false);users.insert(user);if(role==UserRole.STUDENT)CourseTestFixtures.student(jdbc,user.getId());return users.selectById(user.getId());}
  private String bearer(UserEntity user){return "Bearer "+jwt.issueAccessToken(user);}
}
