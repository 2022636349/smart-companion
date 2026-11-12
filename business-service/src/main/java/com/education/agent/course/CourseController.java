package com.education.agent.course;

import com.education.agent.common.api.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.education.agent.course.CourseDtos.*;

@RestController @Validated @RequestMapping("/api/courses")
public class CourseController {
  private final CourseService service;
  public CourseController(CourseService service){this.service=service;}
  @GetMapping public ApiResponse<PageData<CourseView>> list(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size){return ApiResponse.ok(RequestIds.current(),service.list(user,page,size));}
  @GetMapping("/{courseId}") public ApiResponse<CourseView> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long courseId){return ApiResponse.ok(RequestIds.current(),service.get(user,courseId));}
  @PostMapping public ApiResponse<CourseView> create(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody UpsertRequest request){return ApiResponse.ok(RequestIds.current(),service.save(user,null,request));}
  @PutMapping("/{courseId}") public ApiResponse<CourseView> update(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long courseId,@Valid @RequestBody UpsertRequest request){return ApiResponse.ok(RequestIds.current(),service.save(user,courseId,request));}
  @GetMapping("/{courseId}/teachers") public ApiResponse<List<CourseTeacherView>> teachers(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long courseId){return ApiResponse.ok(RequestIds.current(),service.teachers(user,courseId));}
  @PutMapping("/{courseId}/teachers/{teacherId}") public ApiResponse<CourseTeacherView> putTeacher(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long courseId,@PathVariable Long teacherId,@Valid @RequestBody TeacherRequest request){return ApiResponse.ok(RequestIds.current(),service.putTeacher(user,courseId,teacherId,request));}
  @DeleteMapping("/{courseId}/teachers/{teacherId}") public ApiResponse<SimpleData> removeTeacher(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long courseId,@PathVariable Long teacherId){service.removeTeacher(user,courseId,teacherId);return ApiResponse.ok(RequestIds.current(),new SimpleData(true));}
}
