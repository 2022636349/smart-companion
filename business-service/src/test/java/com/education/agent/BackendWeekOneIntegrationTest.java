package com.education.agent;

import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.entity.ClassStudentEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BackendWeekOneIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ClassMapper classMapper;
    @Autowired
    private ClassStudentMapper classStudentMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void readinessUsesTheFrozenResponseEnvelopeAndRequestId() throws Exception {
        mockMvc.perform(get("/api/system/readiness")
                        .header("X-Request-Id", "week-one-request-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "week-one-request-0001"))
                .andExpect(jsonPath("$.requestId").value("week-one-request-0001"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("FOUNDATION_READY"))
                .andExpect(jsonPath("$.data.service").value("education-business-service"));
    }

    @Test
    void studentCanRegisterLoginRefreshAndReadCurrentUserWithoutPasswordLeak() throws Exception {
        String registerBody = """
                {
                  "username": "student_2026",
                  "password": "Student@123",
                  "displayName": "测试学生",
                  "email": "student_2026@example.com"
                }
                """;

        mockMvc.perform(post("/api/auth/register/student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        UserEntity persisted = userMapper.selectList(null).stream()
                .filter(user -> user.getUsername().equals("student_2026"))
                .findFirst()
                .orElseThrow();
        assertThat(persisted.getPasswordHash()).isNotEqualTo("Student@123");
        assertThat(passwordEncoder.matches("Student@123", persisted.getPasswordHash())).isTrue();

        JsonNode login = login("student_2026", "Student@123", "STUDENT");
        String accessToken = login.at("/data/tokens/accessToken").asText();
        String refreshToken = login.at("/data/tokens/refreshToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("student_2026"))
                .andExpect(jsonPath("$.data.role").value("STUDENT"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void studentOnlySeesJoinedClassesAndCannotEnterAdminArea() throws Exception {
        UserEntity teacher = insertUser("teacher_2026", "Teacher@123", "测试教师", UserRole.TEACHER);
        UserEntity student = insertUser("class_student", "Student@123", "班级学生", UserRole.STUDENT);
        UserEntity otherStudent = insertUser("other_student", "Student@123", "其他学生", UserRole.STUDENT);

        ClassEntity joinedClass = insertClass("已加入班级", teacher);
        insertClass("未加入班级", teacher);
        ClassStudentEntity membership = new ClassStudentEntity();
        membership.setClassId(joinedClass.getId());
        membership.setStudentId(student.getId());
        classStudentMapper.insert(membership);

        String token = login("class_student", "Student@123", "STUDENT")
                .at("/data/tokens/accessToken").asText();

        mockMvc.perform(get("/api/classes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("已加入班级"))
                .andExpect(jsonPath("$.data.items[0].studentCount").value(1));

        mockMvc.perform(get("/api/classes/" + joinedClass.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teacherName").value("测试教师"));

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String otherToken = login("other_student", "Student@123", "STUDENT")
                .at("/data/tokens/accessToken").asText();
        mockMvc.perform(get("/api/classes/" + joinedClass.getId())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void expectedRoleMismatchAndMissingTokenReturnStableErrors() throws Exception {
        insertUser("teacher_role", "Teacher@123", "身份教师", UserRole.TEACHER);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "teacher_role",
                                  "password": "Teacher@123",
                                  "expectedRole": "STUDENT"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EXPECTED_ROLE_MISMATCH"));

        mockMvc.perform(get("/api/auth/me")
                        .header("X-Request-Id", "unauthenticated-request-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "unauthenticated-request-01"))
                .andExpect(jsonPath("$.requestId").value("unauthenticated-request-01"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "teacher_role",
                                  "password": "Teacher@123",
                                  "expectedRole": "NOT_A_ROLE"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private JsonNode login(String username, String password, String expectedRole) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username,
                "password", password,
                "expectedRole", expectedRole
        ));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
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
        return userMapper.selectById(user.getId());
    }

    private ClassEntity insertClass(String name, UserEntity teacher) {
        ClassEntity classEntity = new ClassEntity();
        classEntity.setCourseId(1L);
        classEntity.setName(name);
        classEntity.setCourseName("Python程序设计");
        classEntity.setTeacherId(teacher.getId());
        classEntity.setStatus(ClassStatus.ACTIVE);
        classMapper.insert(classEntity); CourseTestFixtures.teacher(jdbc, classEntity.getTeacherId());
        return classMapper.selectById(classEntity.getId());
    }
}
