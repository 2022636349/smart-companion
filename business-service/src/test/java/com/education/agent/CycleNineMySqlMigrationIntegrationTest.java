package com.education.agent;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in gate against empty, isolated MySQL databases created by invoke-cycle9-live-gate.ps1. */
@EnabledIfEnvironmentVariable(named="RUN_CYCLE9_MYSQL_INTEGRATION", matches="1")
class CycleNineMySqlMigrationIntegrationTest {
  private static final List<String> SCOPED_TABLES = List.of("edu_tutor_session", "edu_teaching_resource",
      "edu_learning_memory", "edu_learning_event", "edu_learning_evidence", "edu_knowledge_mastery",
      "edu_cognitive_label", "edu_profile_resource_suggestion", "edu_graph_ingestion_job",
      "edu_graph_release_rollback_outbox");

  @Test void freshMySqlMigratesAllFifteenVersions() throws Exception {
    String url = url("fresh");
    try (var db = DriverManager.getConnection(url, "root", "")) {
      requireEmpty(db);
      assertThat(migrate(url, "15")).isEqualTo(15);
      assertThat(text(db, "SELECT code_language FROM edu_course WHERE id=1")).isEqualTo("PYTHON");
      assertThat(text(db, "SELECT enabled_activity_types_json FROM edu_course WHERE id=1")).contains("CODE", "EXPLAIN");
      assertThat(number(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(15);
      assertThat(number(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'")).isEqualTo(58);
      assertNoImplicitCourse(db);
    }
  }

  @Test void upgradeRetainsPythonHistoryAndCreatesIndependentCourseKeys() throws Exception {
    String url = url("upgrade");
    try (var db = DriverManager.getConnection(url, "root", "")) {
      requireEmpty(db);
      assertThat(migrate(url, "14")).isEqualTo(14);
      seedLegacyHistory(db);
      Map<String, Long> before = counts(db);
      String oldContent = text(db, "SELECT content FROM edu_learning_memory WHERE id=801");
      String oldHash = text(db, "SELECT content_hash FROM edu_learning_memory WHERE id=801");
      assertThat(migrate(url, "15")).isEqualTo(1);
      assertThat(counts(db)).isEqualTo(before);
      for (String table : SCOPED_TABLES) {
        assertThat(number(db, "SELECT COUNT(*) FROM " + table + " WHERE course_id<>1 OR course_id IS NULL"))
            .as("Python course backfill: %s", table).isZero();
      }
      assertThat(text(db, "SELECT content FROM edu_learning_memory WHERE id=801")).isEqualTo(oldContent);
      assertThat(text(db, "SELECT content_hash FROM edu_learning_memory WHERE id=801")).isEqualTo(oldHash);
      assertThat(text(db, "SELECT content FROM edu_tutor_message WHERE id=701")).isEqualTo("原有 Python 回答保持不变。");
      assertThat(text(db, "SELECT member_role FROM edu_course_teacher WHERE course_id=1 AND teacher_id=101")).isEqualTo("OWNER");
      assertThat(number(db, "SELECT COUNT(*) FROM edu_class_student WHERE class_id=501 AND student_id=102")).isEqualTo(1);
      execute(db, "INSERT INTO edu_course(id,code,name,subject_type,default_language,enabled_activity_types_json) VALUES(2,'WRITING','学术写作','GENERAL','zh-CN',JSON_ARRAY('EXPLAIN','QUIZ','VISUAL'))");
      execute(db, "INSERT INTO edu_knowledge_mastery(student_id,course_id,knowledge_point_id,knowledge_point_name,score,confidence,mastery_status,evidence_count,updated_at) VALUES(102,2,'shared-kp','写作论据',20,0.5,'WEAK',1,CURRENT_TIMESTAMP(3))");
      assertThat(number(db, "SELECT COUNT(*) FROM edu_knowledge_mastery WHERE student_id=102 AND knowledge_point_id='shared-kp'")).isEqualTo(2);
      assertThat(text(db, "SELECT score FROM edu_knowledge_mastery WHERE student_id=102 AND course_id=1 AND knowledge_point_id='shared-kp'")).isEqualTo("68.5000");
      assertThat(number(db, "SELECT COUNT(*) FROM edu_learning_memory WHERE course_id=2")).isZero();
      assertNoImplicitCourse(db);
      assertThat(migrate(url, "15")).isZero();
    }
  }

  private static String url(String kind) {
    String base = System.getenv("CYCLE9_MYSQL_URL_PREFIX");
    if (base == null || !base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{4,5}/education_cycle9_"))
      throw new IllegalArgumentException("Only explicit, isolated loopback cycle9 databases are allowed");
    return base + kind + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
  }
  private static int migrate(String url, String target) {
    var config = Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").cleanDisabled(true);
    if (target != null) config.target(target);
    var flyway = config.load();
    int count = flyway.migrate().migrationsExecuted;
    flyway.validate();
    return count;
  }
  private static void requireEmpty(Connection db) throws SQLException {
    assertThat(number(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))
        .as("Refuse a reused database: only an empty gate database is allowed").isZero();
  }
  private static void assertNoImplicitCourse(Connection db) throws SQLException {
    for (String table : SCOPED_TABLES) {
      assertThat(text(db, "SELECT CONCAT(is_nullable,':',COALESCE(column_default,'<none>')) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='course_id'"))
          .as("Required course on %s", table).isEqualTo("NO:<none>");
    }
  }
  private static Map<String, Long> counts(Connection db) throws SQLException {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String table : SCOPED_TABLES) values.put(table, number(db, "SELECT COUNT(*) FROM " + table));
    for (String table : List.of("edu_user", "edu_class", "edu_class_student", "edu_tutor_message", "edu_memory_outbox", "edu_profile_summary_task"))
      values.put(table, number(db, "SELECT COUNT(*) FROM " + table));
    return values;
  }
  private static void seedLegacyHistory(Connection db) throws SQLException {
    execute(db, "INSERT INTO edu_user(id,username,password_hash,display_name,role) VALUES(101,'legacy_teacher','test-only','原授课教师','TEACHER'),(102,'legacy_student','test-only','原学生','STUDENT')");
    execute(db, "INSERT INTO edu_class(id,course_id,name,course_name,teacher_id) VALUES(501,1,'原 Python 教学班','Python程序设计',101)");
    execute(db, "INSERT INTO edu_class_student(class_id,student_id) VALUES(501,102)");
    execute(db, "INSERT INTO edu_tutor_session(id,student_id,knowledge_point_id,knowledge_point_name,status,summary) VALUES(601,102,'shared-kp','Python集合','CLOSED','原有学习总结')");
    execute(db, "INSERT INTO edu_tutor_message(id,session_id,message_role,content) VALUES(701,601,'ASSISTANT','原有 Python 回答保持不变。')");
    execute(db, "INSERT INTO edu_learning_memory(id,student_id,memory_type,knowledge_point_id,content,content_hash,source_type,source_id) VALUES(801,102,'SESSION_SUMMARY','shared-kp','原有 Python 学习记忆',SHA2('原有 Python 学习记忆',256),'TUTOR_SESSION',601)");
    execute(db, "INSERT INTO edu_teaching_resource(id,title,resource_type,uploader_id,review_status) VALUES(901,'原有课程资料','DOCUMENT',101,'APPROVED')");
    execute(db, "INSERT INTO edu_learning_event(id,student_id,event_type,title,occurred_at) VALUES(1001,102,'TUTOR_SESSION_CLOSED','原有学习事件',CURRENT_TIMESTAMP(3))");
    execute(db, "INSERT INTO edu_learning_evidence(id,student_id,knowledge_point_id,knowledge_point_name,graph_version,source_type,source_id,score_percent,evidence_weight,occurred_at) VALUES(1101,102,'shared-kp','Python集合','2.7.1','TUTOR_PRACTICE',701,68.5,1,CURRENT_TIMESTAMP(3))");
    execute(db, "INSERT INTO edu_knowledge_mastery(student_id,knowledge_point_id,knowledge_point_name,score,confidence,mastery_status,evidence_count,updated_at) VALUES(102,'shared-kp','Python集合',68.5,0.8,'LEARNING',3,CURRENT_TIMESTAMP(3))");
    execute(db, "INSERT INTO edu_cognitive_label(id,student_id,knowledge_point_id,source_type,source_id,label_status,confidence,evidence) VALUES(1201,102,'shared-kp','TUTOR',601,'LEARNING',0.8,'原有认知证据')");
    execute(db, "INSERT INTO edu_profile_summary_task(id,session_id,student_id,request_id,status) VALUES(1301,601,102,'cycle9-legacy-summary','SUCCEEDED')");
    execute(db, "INSERT INTO edu_profile_resource_suggestion(id,task_id,session_id,student_id,position_no,knowledge_point_id,reason,preferred_resource_types_json) VALUES(1401,1301,601,102,1,'shared-kp','原有资源建议',JSON_ARRAY('DOCUMENT'))");
    execute(db, "INSERT INTO edu_file_object(id,original_name,storage_key,content_type,size_bytes,sha256,owner_id,purpose) VALUES(1501,'legacy.txt','cycle9/legacy.txt','text/plain',1,REPEAT('a',64),101,'KNOWLEDGE_SOURCE')");
    execute(db, "INSERT INTO edu_graph_ingestion_job(id,source_file_id,title,source_type,copyright_status,created_by,idempotency_key,request_hash,request_id,status) VALUES(1601,1501,'原有建图记录','TEXTBOOK','AUTHORIZED',101,'cycle9-legacy-ingestion',REPEAT('a',64),'cycle9-legacy-ingestion','FAILED')");
    execute(db, "INSERT INTO edu_idempotency_record(id,user_id,operation_id,idempotency_key,request_hash,expires_at) VALUES(1701,101,'rollbackGraphRelease','cycle9-legacy-rollback',REPEAT('b',64),DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY))");
    execute(db, "INSERT INTO edu_graph_release_rollback_outbox(id,idempotency_record_id,requested_by,target_graph_version,expected_current_graph_version,request_hash,request_id,status) VALUES(1801,1701,101,'2.7.0','2.7.1',REPEAT('b',64),'cycle9-legacy-rollback','SUCCEEDED')");
  }
  private static void execute(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.executeUpdate(sql); } }
  private static long number(Connection db, String sql) throws SQLException { return Long.parseLong(text(db, sql)); }
  private static String text(Connection db, String sql) throws SQLException { try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) { if (!rows.next()) throw new SQLException("Missing evidence row"); return rows.getString(1); } }
}
