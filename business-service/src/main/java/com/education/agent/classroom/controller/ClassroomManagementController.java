package com.education.agent.classroom.controller;

import com.education.agent.classroom.dto.ClassDetail;
import com.education.agent.classroom.service.ClassroomQueryService;
import com.education.agent.common.api.*;
import com.education.agent.common.error.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.course.CourseAccessService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.dto.UserView;
import com.education.agent.user.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;

@Validated @RestController @RequestMapping("/api/classes")
public class ClassroomManagementController {
  private final JdbcTemplate jdbc;private final CourseAccessService courses;private final ClassroomQueryService query;private final UserMapper users;
  public ClassroomManagementController(JdbcTemplate jdbc,CourseAccessService courses,ClassroomQueryService query,UserMapper users){this.jdbc=jdbc;this.courses=courses;this.query=query;this.users=users;}
  public record ClassUpsertRequest(@NotNull @Min(1) Long courseId,@NotBlank @Size(max=100) String name,@Size(max=100) String courseName,@Size(max=2000) String description,@NotNull @Min(1) Long teacherId){}
  public record StudentIds(@NotEmpty List<@NotNull @Min(1) Long> studentIds){}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional public ApiResponse<ClassDetail> create(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(required=false) Long courseId,@Valid @RequestBody ClassUpsertRequest r){if(courseId!=null&&!courseId.equals(r.courseId()))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"查询课程与请求课程不一致");return save(user,null,r);}
  @PutMapping("/{classId}") @Transactional public ApiResponse<ClassDetail> update(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long classId,@RequestParam(required=false) Long courseId,@Valid @RequestBody ClassUpsertRequest r){if(courseId!=null&&!courseId.equals(r.courseId()))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"查询课程与请求课程不一致");return save(user,classId,r);}
  private ApiResponse<ClassDetail> save(AuthenticatedUser user,Long id,ClassUpsertRequest r){courses.requireEdit(user,r.courseId());if(id!=null){if(!courses.classCourse(id).equals(r.courseId()))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"已有班级不能移动到其他课程");requireActive(id);}
    Integer member=jdbc.queryForObject("SELECT COUNT(*) FROM edu_course_teacher WHERE course_id=? AND teacher_id=? AND status='ACTIVE' AND member_role IN ('OWNER','EDITOR')",Integer.class,r.courseId(),r.teacherId());if(member==null||member==0)throw new BusinessException(ErrorCode.VALIDATION_ERROR,"任课教师必须拥有本课程编辑权限");
    String courseName=(String)courses.requireCourse(r.courseId()).get("name");
    if(id==null){var keys=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement("INSERT INTO edu_class(course_id,name,course_name,description,teacher_id,status) VALUES(?,?,?,?,?,'ACTIVE')",new String[]{"id"});p.setLong(1,r.courseId());p.setString(2,r.name().trim());p.setString(3,courseName);p.setString(4,r.description());p.setLong(5,r.teacherId());return p;},keys);id=Objects.requireNonNull(keys.getKey()).longValue();}
    else jdbc.update("UPDATE edu_class SET name=?,description=?,teacher_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",r.name().trim(),r.description(),r.teacherId(),id);
    return ApiResponse.ok(RequestIds.current(),query.getVisible(user,id));}
  @DeleteMapping("/{classId}") public ApiResponse<SimpleData> archive(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long classId){courses.requireEdit(user,courses.classCourse(classId));jdbc.update("UPDATE edu_class SET status='ARCHIVED' WHERE id=?",classId);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
  @GetMapping("/{classId}/students") public ApiResponse<PageData<UserView>> students(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long classId,@RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size){courses.requireView(user,courses.classCourse(classId));if(user.role()==UserRole.STUDENT)throw new BusinessException(ErrorCode.FORBIDDEN,"学生不能查看班级成员资料");var ids=jdbc.queryForList("SELECT student_id FROM edu_class_student WHERE class_id=? ORDER BY student_id",Long.class,classId);int from=(int)Math.min(ids.size(),(page-1)*size),to=(int)Math.min(ids.size(),from+size);return ApiResponse.ok(RequestIds.current(),new PageData<>(ids.subList(from,to).stream().map(users::selectById).map(UserView::from).toList(),page,size,ids.size(),(ids.size()+size-1)/size));}
  @PostMapping("/{classId}/students") @Transactional public ApiResponse<SimpleData> addStudents(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long classId,@Valid @RequestBody StudentIds r){courses.requireEdit(user,courses.classCourse(classId));requireActive(classId);for(Long id:new HashSet<>(r.studentIds())){var u=users.selectById(id);if(u==null||u.getRole()!=UserRole.STUDENT)throw new BusinessException(ErrorCode.VALIDATION_ERROR,"学生不存在");if(jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_student WHERE class_id=? AND student_id=?",Integer.class,classId,id)==0)jdbc.update("INSERT INTO edu_class_student(class_id,student_id) VALUES(?,?)",classId,id);}return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
  @DeleteMapping("/{classId}/students/{studentId}") public ApiResponse<SimpleData> removeStudent(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long classId,@PathVariable Long studentId){courses.requireEdit(user,courses.classCourse(classId));requireActive(classId);jdbc.update("DELETE FROM edu_class_student WHERE class_id=? AND student_id=?",classId,studentId);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
  private void requireActive(Long classId){if(!"ACTIVE".equals(jdbc.queryForObject("SELECT status FROM edu_class WHERE id=?",String.class,classId)))throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"教学班已归档，仅可查看历史记录");}
}
