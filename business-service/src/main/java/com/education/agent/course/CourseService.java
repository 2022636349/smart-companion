package com.education.agent.course;

import com.education.agent.common.api.PageData;
import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.education.agent.course.CourseDtos.*;

@Service
public class CourseService {
  private final JdbcTemplate jdbc; private final CourseAccessService access; private final ObjectMapper json;
  public CourseService(JdbcTemplate jdbc,CourseAccessService access,ObjectMapper json){this.jdbc=jdbc;this.access=access;this.json=json;}
  public PageData<CourseView> list(AuthenticatedUser user,long page,long size){
    List<Long> ids=jdbc.queryForList("SELECT id FROM edu_course ORDER BY id",Long.class).stream().filter(id->access.role(user,id)!=null).filter(id->user.role()!=UserRole.STUDENT||!"DRAFT".equals(access.requireCourse(id).get("status"))).toList();
    int from=(int)Math.min(ids.size(),(page-1)*size),to=(int)Math.min(ids.size(),from+size);
    return new PageData<>(ids.subList(from,to).stream().map(id->get(user,id)).toList(),page,size,ids.size(),(ids.size()+size-1)/size);
  }
  public CourseView get(AuthenticatedUser user,Long id){access.requireView(user,id);var activities=access.activities(id);String role=access.role(user,id);return jdbc.queryForObject("SELECT * FROM edu_course WHERE id=?",(rs,n)->new CourseView(id,rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getString("status"),rs.getString("subject_type"),rs.getString("default_language"),rs.getString("code_language"),activities,role),id);}
  @Transactional public CourseView save(AuthenticatedUser user,Long id,UpsertRequest r){
    admin(user);if(id!=null)access.requireCourse(id);
    if(!r.enabledActivityTypes().contains("EXPLAIN")||new HashSet<>(r.enabledActivityTypes()).size()!=r.enabledActivityTypes().size()||r.enabledActivityTypes().contains("CODE")!=(r.codeLanguage()!=null))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"课程须启用 EXPLAIN，CODE 与代码语言须一致");
    String activities;try{activities=json.writeValueAsString(r.enabledActivityTypes());}catch(Exception e){throw new IllegalStateException(e);}
    try{if(id==null){var keys=new GeneratedKeyHolder();String payload=activities;jdbc.update(c->{var p=c.prepareStatement("INSERT INTO edu_course(code,name,description,status,subject_type,default_language,code_language,enabled_activity_types_json) VALUES(?,?,?,?,?,?,?,?)",new String[]{"id"});p.setString(1,r.code());p.setString(2,r.name().trim());p.setString(3,r.description());p.setString(4,r.status());p.setString(5,r.subjectType());p.setString(6,r.defaultLanguage());p.setString(7,r.codeLanguage());p.setString(8,payload);return p;},keys);id=Objects.requireNonNull(keys.getKey()).longValue();}
      else{jdbc.update("UPDATE edu_course SET code=?,name=?,description=?,status=?,subject_type=?,default_language=?,code_language=?,enabled_activity_types_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",r.code(),r.name().trim(),r.description(),r.status(),r.subjectType(),r.defaultLanguage(),r.codeLanguage(),activities,id);jdbc.update("UPDATE edu_class SET course_name=? WHERE course_id=?",r.name().trim(),id);}
    }catch(DuplicateKeyException e){throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"课程代码已存在");}return get(user,id);
  }
  public List<CourseTeacherView> teachers(AuthenticatedUser user,Long courseId){access.requireView(user,courseId);return jdbc.query("SELECT t.teacher_id,u.display_name,t.member_role,t.status FROM edu_course_teacher t JOIN edu_user u ON u.id=t.teacher_id WHERE t.course_id=? ORDER BY t.teacher_id",(rs,n)->new CourseTeacherView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),courseId);}
  @Transactional public CourseTeacherView putTeacher(AuthenticatedUser user,Long courseId,Long teacherId,TeacherRequest r){admin(user);access.requireCourse(courseId);Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM edu_user WHERE id=? AND role='TEACHER' AND status='ACTIVE'",Integer.class,teacherId);if(exists==null||exists==0)throw new BusinessException(ErrorCode.VALIDATION_ERROR,"请选择有效教师");int changed=jdbc.update("UPDATE edu_course_teacher SET member_role=?,status='ACTIVE',joined_at=CURRENT_TIMESTAMP WHERE course_id=? AND teacher_id=?",r.memberRole(),courseId,teacherId);if(changed==0)jdbc.update("INSERT INTO edu_course_teacher(course_id,teacher_id,member_role) VALUES(?,?,?)",courseId,teacherId,r.memberRole());return teacher(courseId,teacherId);}
  public void removeTeacher(AuthenticatedUser user,Long courseId,Long teacherId){admin(user);access.requireCourse(courseId);jdbc.update("UPDATE edu_course_teacher SET status='REVOKED' WHERE course_id=? AND teacher_id=?",courseId,teacherId);}
  private CourseTeacherView teacher(Long courseId,Long teacherId){return jdbc.queryForObject("SELECT t.teacher_id,u.display_name,t.member_role,t.status FROM edu_course_teacher t JOIN edu_user u ON u.id=t.teacher_id WHERE t.course_id=? AND t.teacher_id=?",(rs,n)->new CourseTeacherView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),courseId,teacherId);}
  private void admin(AuthenticatedUser user){if(user.role()!=UserRole.ADMIN)throw new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可管理课程和教师授权");}
}
