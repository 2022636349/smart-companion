package com.education.agent.smartcompanion;

import static org.assertj.core.api.Assertions.*;

import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SmartCompanionIntegrationTest {
  @Autowired SmartCompanionService service;
  @Autowired JdbcTemplate jdbc;
  AuthenticatedUser admin = new AuthenticatedUser(9001L, "admin", "", UserRole.ADMIN, true, true),
      owner = new AuthenticatedUser(9002L, "owner", "", UserRole.TEACHER, true, true),
      viewer = new AuthenticatedUser(9003L, "viewer", "", UserRole.TEACHER, true, true),
      student = new AuthenticatedUser(9004L, "student", "", UserRole.STUDENT, true, true);

  void error(Runnable r, ErrorCode code) {
    assertThatThrownBy(r::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  @Test
  void persistentLifecyclePermissionsIsolationAndArchive() throws Exception {
    Assumptions.assumeTrue(
        Files.exists(SmartCompanionEngineTest.BASE.resolve("source.zip")),
        "Official package absent; lifecycle explicitly skipped");
    for (var u : java.util.List.of(admin, owner, viewer, student))
      jdbc.update(
          "INSERT INTO edu_user(id,username,password_hash,display_name,role) VALUES(?,?,?,?,?)",
          u.id(),
          u.username(),
          "test",
          u.username(),
          u.role().name());
    jdbc.update(
        "INSERT INTO edu_course_teacher(course_id,teacher_id,member_role)"
            + " VALUES(1,9002,'OWNER'),(1,9003,'VIEWER')");
    jdbc.update(
        "INSERT INTO"
            + " edu_course(id,code,name,subject_type,default_language,enabled_activity_types_json)"
            + " VALUES(2,'isolated','Isolated','GENERAL','zh-CN','[]')");
    var file =
        new MockMultipartFile(
            "file",
            "original.zip",
            "application/zip",
            Files.readAllBytes(SmartCompanionEngineTest.BASE.resolve("source.zip")));
    error(() -> service.upload(student, 1L, file), ErrorCode.FORBIDDEN);
    error(() -> service.upload(viewer, 1L, file), ErrorCode.FORBIDDEN);
    var preview = service.upload(owner, 1L, file);
    long id = Long.parseLong(preview.path("summary").path("id").asText());
    assertThat(preview.path("summary").path("status").asText()).isEqualTo("PREVIEW");
    assertThat(service.upload(owner, 1L, file)).isEqualTo(preview);
    error(() -> service.analyze(owner, 1L, id), ErrorCode.INVALID_RESOURCE_STATE);
    error(() -> service.report(viewer, 1L, id), ErrorCode.INVALID_RESOURCE_STATE);
    error(() -> service.get(admin, 2L, id), ErrorCode.RESOURCE_NOT_FOUND);
    error(() -> service.get(owner, 2L, id), ErrorCode.FORBIDDEN);
    error(() -> service.get(student, 1L, id), ErrorCode.FORBIDDEN);
    error(
        () ->
            service.confirm(
                owner, 1L, id, new SmartCompanionService.Confirm("bad", "source", true)),
        ErrorCode.VALIDATION_ERROR);
    var request =
        new SmartCompanionService.Confirm(
            preview.path("summary").path("sha256").asText(), "公开模拟数据", true);
    var confirmed = service.confirm(owner, 1L, id, request);
    assertThat(service.confirm(owner, 1L, id, request)).isEqualTo(confirmed);
    var analyzed =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(service.analyze(owner, 1L, id).toString());
    assertThat(service.analyze(owner, 1L, id)).isEqualTo(analyzed);
    assertThat(service.confirm(owner, 1L, id, request)).isEqualTo(analyzed);
    assertThat(service.get(viewer, 1L, id)).isEqualTo(analyzed);
    assertThat(service.report(viewer, 1L, id)).isEqualTo(analyzed.path("report"));
    assertThat(
            new SmartCompanionService(
                    jdbc,
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new com.education.agent.course.CourseAccessService(
                        jdbc, new com.fasterxml.jackson.databind.ObjectMapper()))
                .get(owner, 1L, id))
        .isEqualTo(analyzed);
    jdbc.update("UPDATE edu_course SET status='ARCHIVED' WHERE id=1");
    assertThat(service.get(owner, 1L, id)).isEqualTo(analyzed);
    error(() -> service.analyze(owner, 1L, id), ErrorCode.INVALID_RESOURCE_STATE);
    error(() -> service.confirm(owner, 1L, id, request), ErrorCode.INVALID_RESOURCE_STATE);
  }
}
