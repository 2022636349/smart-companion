package com.education.agent.course;

import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Course authorization is evaluated from MySQL for every operation; there is no implicit course. */
@Service
public class CourseAccessService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public CourseAccessService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
  public Map<String,Object> requireCourse(Long courseId){
    if(courseId==null||courseId<1)throw new BusinessException(ErrorCode.VALIDATION_ERROR,"必须指定课程");
    var rows=jdbc.queryForList("SELECT * FROM edu_course WHERE id=?",courseId);
    if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"课程不存在");
    return rows.get(0);
  }
  public String role(AuthenticatedUser user,Long courseId){
    if(user==null)return null;
    if(user.role()==UserRole.ADMIN)return "ADMIN";
    if(user.role()==UserRole.TEACHER){
      var roles=jdbc.queryForList("SELECT member_role FROM edu_course_teacher WHERE course_id=? AND teacher_id=? AND status='ACTIVE'",String.class,courseId,user.id());
      return roles.isEmpty()?null:roles.get(0);
    }
    Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_student cs JOIN edu_class c ON c.id=cs.class_id WHERE c.course_id=? AND cs.student_id=?",Integer.class,courseId,user.id());
    return count!=null&&count>0?"STUDENT":null;
  }
  public void requireView(AuthenticatedUser user,Long courseId){
    var course=requireCourse(courseId);
    String role=role(user,courseId);
    if(role==null||("STUDENT".equals(role)&&"DRAFT".equals(course.get("status"))))throw new BusinessException(ErrorCode.FORBIDDEN,"没有该课程的访问权限");
  }
  public void requireEdit(AuthenticatedUser user,Long courseId){requireRole(user,courseId,Set.of("ADMIN","OWNER","EDITOR"));}
  public void requireStudy(AuthenticatedUser user,Long courseId){
    requireView(user,courseId);
    if(user.role()!=UserRole.STUDENT)throw new BusinessException(ErrorCode.FORBIDDEN,"只有学生可以进行课程学习");
    Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_student cs JOIN edu_class c ON c.id=cs.class_id WHERE c.course_id=? AND cs.student_id=? AND c.status='ACTIVE'",Integer.class,courseId,user.id());
    if(!"ACTIVE".equals(requireCourse(courseId).get("status"))||active==null||active==0)throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"课程或教学班已归档，仅可查看历史记录");
  }
  public void requireQuestionInCourse(AuthenticatedUser user,Long courseId,Long questionId){
    if(questionId==null)return;
    Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM edu_assessment_question q JOIN edu_assessment a ON a.id=q.assessment_id JOIN edu_class c ON c.id=a.class_id JOIN edu_class_student cs ON cs.class_id=c.id WHERE q.id=? AND c.course_id=? AND cs.student_id=? AND a.deleted_at IS NULL",Integer.class,questionId,courseId,user.id());
    if(count==null||count==0)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"来源题目不存在于本课程");
  }
  public void requireOwner(AuthenticatedUser user,Long courseId){requireRole(user,courseId,Set.of("ADMIN","OWNER"));}
  private void requireRole(AuthenticatedUser user,Long courseId,Set<String> allowed){
    var course=requireCourse(courseId);String role=role(user,courseId);
    if(role==null||!allowed.contains(role))throw new BusinessException(ErrorCode.FORBIDDEN,"没有该课程的操作权限");
    if("ARCHIVED".equals(course.get("status")))throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"归档课程不能修改");
  }
  public Long classCourse(Long classId){
    var rows=jdbc.queryForList("SELECT course_id FROM edu_class WHERE id=?",Long.class,classId);
    if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"班级不存在");return rows.get(0);
  }
  public void requireActiveClass(Long classId){
    Long courseId=classCourse(classId);
    if(!"ACTIVE".equals(requireCourse(courseId).get("status"))||!"ACTIVE".equals(jdbc.queryForObject("SELECT status FROM edu_class WHERE id=?",String.class,classId)))throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"课程或教学班已归档，仅可查看历史记录");
  }
  public Long sessionCourse(Long sessionId){
    var rows=jdbc.queryForList("SELECT course_id FROM edu_tutor_session WHERE id=?",Long.class,sessionId);
    if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"导师会话不存在");return rows.get(0);
  }
  public List<String> activities(Long courseId){
    Object raw=requireCourse(courseId).get("enabled_activity_types_json");
    try{return json.readValue(raw.toString(),new TypeReference<List<String>>(){});}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"课程能力配置无效");}
  }
  public void requireCode(Long courseId){if(!activities(courseId).contains("CODE"))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"当前课程未启用代码实验");}
}
