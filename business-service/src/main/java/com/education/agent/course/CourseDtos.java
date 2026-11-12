package com.education.agent.course;

import jakarta.validation.constraints.*;
import java.util.List;

public final class CourseDtos {
  private CourseDtos() {}
  public record UpsertRequest(
      @NotBlank @Size(max=64) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]*") String code,
      @NotBlank @Size(max=100) String name, @Size(max=5000) String description,
      @NotBlank @Pattern(regexp="DRAFT|ACTIVE|ARCHIVED") String status,
      @NotBlank @Size(max=64) String subjectType,
      @NotBlank @Size(min=2,max=20) String defaultLanguage,
      @Pattern(regexp="PYTHON") String codeLanguage,
      @NotEmpty @Size(max=4) List<@Pattern(regexp="EXPLAIN|QUIZ|CODE|VISUAL") String> enabledActivityTypes) {}
  public record CourseView(Long id,String code,String name,String description,String status,
      String subjectType,String defaultLanguage,String codeLanguage,List<String> enabledActivityTypes,String membershipRole) {}
  public record TeacherRequest(@NotBlank @Pattern(regexp="OWNER|EDITOR|VIEWER") String memberRole) {}
  public record CourseTeacherView(Long teacherId,String teacherName,String memberRole,String status) {}
}
