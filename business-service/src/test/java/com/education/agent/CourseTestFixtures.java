package com.education.agent;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Explicit enrollment used only by legacy Python regression fixtures. */
public final class CourseTestFixtures {
  private CourseTestFixtures() {}

  public static synchronized void teacher(JdbcTemplate jdbc, Long teacherId) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM edu_course_teacher WHERE course_id=1 AND teacher_id=?", Integer.class, teacherId) == 0) {
      jdbc.update("INSERT INTO edu_course_teacher(course_id,teacher_id,member_role) VALUES(1,?,'OWNER')", teacherId);
    }
  }

  public static synchronized void student(JdbcTemplate jdbc, Long studentId) {
    var teachers=jdbc.queryForList("SELECT id FROM edu_user WHERE username='c9_fixture_python_teacher'",Long.class);
    Long teacherId=teachers.isEmpty()?insert(jdbc,"INSERT INTO edu_user(username,password_hash,display_name,role,status) VALUES('c9_fixture_python_teacher','x','Python fixture teacher','TEACHER','ACTIVE')"):teachers.get(0);
    teacher(jdbc,teacherId);
    var classes=jdbc.queryForList("SELECT id FROM edu_class WHERE course_id=1 AND teacher_id=? AND name='C9 legacy Python cohort'",Long.class,teacherId);
    Long classId=classes.isEmpty()?insert(jdbc,"INSERT INTO edu_class(course_id,name,teacher_id,status) VALUES(1,'C9 legacy Python cohort',?,'ACTIVE')",teacherId):classes.get(0);
    if(jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_student WHERE class_id=? AND student_id=?",Integer.class,classId,studentId)==0)
      jdbc.update("INSERT INTO edu_class_student(class_id,student_id) VALUES(?,?)",classId,studentId);
  }

  private static Long insert(JdbcTemplate jdbc,String sql,Object...args) {
    GeneratedKeyHolder keys=new GeneratedKeyHolder();
    jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }
}
