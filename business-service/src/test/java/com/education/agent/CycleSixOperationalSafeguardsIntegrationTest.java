package com.education.agent;

import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.entity.ClassStudentEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.maintenance.MaintenanceLeaseService;
import com.education.agent.maintenance.RetentionMaintenanceCoordinator;
import com.education.agent.ratelimit.DatabaseRateLimiter;
import com.education.agent.security.JwtService;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"education.rate-limit.report-per-minute=5","education.rate-limit.auth-per-minute=3"})
@AutoConfigureMockMvc
class CycleSixOperationalSafeguardsIntegrationTest {
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired UserMapper users; @Autowired PasswordEncoder passwords;
  @Autowired ClassMapper classes; @Autowired ClassStudentMapper memberships; @Autowired AssessmentMapper assessments;
  @Autowired JwtService jwt; @Autowired DatabaseRateLimiter limiter; @Autowired MaintenanceLeaseService leases;
  @Autowired RetentionMaintenanceCoordinator maintenance; @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactionManager;

  @Test @Transactional
  void reportsAreRealFilesAndHonorOwnershipReleaseAndVisibility() throws Exception {
    String suffix=UUID.randomUUID().toString().substring(0,8);
    UserEntity teacher=user("r_teacher_"+suffix,UserRole.TEACHER,"Teacher");
    UserEntity outsider=user("r_out_"+suffix,UserRole.TEACHER,"Other");
    UserEntity student=user("r_student_"+suffix,UserRole.STUDENT,"=2+3");
    UserEntity other=user("r_other_"+suffix,UserRole.STUDENT,"Other student");
    UserEntity admin=user("r_admin_"+suffix,UserRole.ADMIN,"Admin");
    ClassEntity room=room(teacher);member(room,student);
    AssessmentEntity visible=assessment(room,teacher,AssessmentStatus.RELEASED,"{\"showScoreAfterRelease\":true,\"showQuestionFeedbackAfterRelease\":true}");
    AssessmentEntity hidden=assessment(room,teacher,AssessmentStatus.RELEASED,"{\"showScoreAfterRelease\":false,\"showQuestionFeedbackAfterRelease\":false}");
    AssessmentEntity unreleased=assessment(room,teacher,AssessmentStatus.PUBLISHED,"{}");
    submission(visible,student,"9.25","=danger"); submission(hidden,student,"7.00","private comment");
    jdbc.update("INSERT INTO edu_knowledge_mastery(course_id,student_id,knowledge_point_id,knowledge_point_name,score,confidence,mastery_status,evidence_count,updated_at) VALUES(1,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",student.getId(),"kp-report","Loops",new BigDecimal("72.50"),new BigDecimal("0.8000"),"DEVELOPING",2);

    byte[] csv=mvc.perform(get("/api/reports/assessments/{id}/grades",visible.getId()).param("format","CSV").header("Authorization",bearer(teacher)))
        .andExpect(status().isOk()).andExpect(header().string("Content-Type","text/csv;charset=UTF-8"))
        .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("assessment-"+visible.getId()+"-grades.csv")))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(csv).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
    String csvText=new String(csv,java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csvText).contains("\"'=2+3\"").contains("\"'=danger\"");

    byte[] xlsx=mvc.perform(get("/api/reports/assessments/{id}/grades",hidden.getId()).header("Authorization",bearer(admin)))
        .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(xlsx).startsWith((byte)'P',(byte)'K');
    List<String> entries=new ArrayList<>();String sheetXml=null;
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(xlsx))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){entries.add(entry.getName());if(entry.getName().equals("xl/worksheets/sheet1.xml"))sheetXml=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    assertThat(entries).contains("[Content_Types].xml","xl/workbook.xml","xl/worksheets/sheet1.xml");
    assertThat(sheetXml).doesNotContain("7.00","private comment");

    byte[] pdf=mvc.perform(get("/api/reports/students/{id}/learning",student.getId()).param("courseId","1").header("Authorization",bearer(student)))
        .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/pdf"))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(new String(pdf,0,8,java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
    assertThat(new String(pdf,java.nio.charset.StandardCharsets.ISO_8859_1)).contains("/Type /Page").doesNotContain("private comment");

    mvc.perform(get("/api/reports/students/{id}/learning",student.getId()).param("courseId","1").param("format","XLSX").header("Authorization",bearer(teacher))).andExpect(status().isOk());
    mvc.perform(get("/api/reports/assessments/{id}/grades",visible.getId()).header("Authorization",bearer(outsider))).andExpect(status().isNotFound());
    mvc.perform(get("/api/reports/students/{id}/learning",student.getId()).param("courseId","1").header("Authorization",bearer(other))).andExpect(status().isForbidden());
    mvc.perform(get("/api/reports/assessments/{id}/grades",unreleased.getId()).header("Authorization",bearer(teacher))).andExpect(status().isConflict());
  }

  @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void sharedBucketsAreAtomicAcrossInstancesAndResetAtWindowBoundary() throws Exception {
    String key="rate-test-"+UUID.randomUUID(); Instant start=Instant.parse("2026-08-23T10:00:30Z");
    DatabaseRateLimiter first=new DatabaseRateLimiter(jdbc,Clock.fixed(start,ZoneId.of("UTC")));
    DatabaseRateLimiter second=new DatabaseRateLimiter(jdbc,Clock.fixed(start,ZoneId.of("UTC")));
    var firstDecision=first.acquire(key,2,60);var secondDecision=second.acquire(key,2,60);var rejected=first.acquire(key,2,60);
    assertThat(firstDecision.allowed()).isTrue();assertThat(firstDecision.remaining()).isEqualTo(1);assertThat(secondDecision.allowed()).isTrue();assertThat(secondDecision.remaining()).isZero();assertThat(rejected.allowed()).isFalse();assertThat(rejected.remaining()).isZero();
    DatabaseRateLimiter nextWindow=new DatabaseRateLimiter(jdbc,Clock.fixed(start.plusSeconds(30),ZoneId.of("UTC")));
    assertThat(nextWindow.acquire(key,2,60).allowed()).isTrue();

    String concurrent="rate-concurrent-"+UUID.randomUUID();ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch gate=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
    for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();return limiter.acquire(concurrent,3,60).allowed();}));gate.countDown();
    int allowed=0;for(Future<Boolean> future:futures)if(future.get(10,TimeUnit.SECONDS))allowed++;pool.shutdownNow();assertThat(allowed).isEqualTo(3);

    UserEntity admin=user("rate_admin_"+UUID.randomUUID().toString().substring(0,8),UserRole.ADMIN,"Admin");String token=bearer(admin);
    for(int i=0;i<5;i++)mvc.perform(get("/api/reports/students/99999999/learning").param("courseId","1").header("Authorization",token)).andExpect(status().isNotFound());
    mvc.perform(get("/api/reports/students/99999999/learning").param("courseId","1").header("Authorization",token))
        .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After",org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
    UserEntity isolated=user("rate_other_"+UUID.randomUUID().toString().substring(0,8),UserRole.ADMIN,"Other admin");
    mvc.perform(get("/api/reports/students/99999999/learning").param("courseId","1").header("Authorization",bearer(isolated))).andExpect(status().isNotFound());

    for(int i=0;i<3;i++)mvc.perform(post("/api/auth/login").with(request->{request.setRemoteAddr("203.0.113.10");return request;})
        .header("X-Forwarded-For","198.51.100."+i).contentType("application/json")
        .content("{\"username\":\"missing\",\"password\":\"invalid-password\",\"expectedRole\":\"STUDENT\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").with(request->{request.setRemoteAddr("203.0.113.10");return request;})
        .header("X-Forwarded-For","198.51.100.200").contentType("application/json")
        .content("{\"username\":\"missing\",\"password\":\"invalid-password\",\"expectedRole\":\"STUDENT\"}"))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_rate_limit_bucket WHERE bucket_key LIKE '%203.0.113.10%' OR bucket_key LIKE '%198.51.100.%'",Integer.class)).isZero();
  }

  @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void maintenanceLeaseProtectsReferencesAndRecordsSuccessFailureAndReplay() {
    String suffix=UUID.randomUUID().toString().substring(0,8);UserEntity user=user("maint_"+suffix,UserRole.STUDENT,"Maint");Instant now=Instant.now();
    assertThat(leases.tryAcquire("lease-race-"+suffix,"instance-a",now,now.plusSeconds(60))).isTrue();
    assertThat(leases.tryAcquire("lease-race-"+suffix,"instance-b",now,now.plusSeconds(60))).isFalse();
    leases.release("lease-race-"+suffix,"instance-a");assertThat(leases.tryAcquire("lease-race-"+suffix,"instance-b",now,now.plusSeconds(60))).isTrue();

    jdbc.update("INSERT INTO edu_rate_limit_bucket(bucket_key,window_started_at,request_count,expires_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)","expired-"+suffix,java.sql.Timestamp.from(now.minusSeconds(120)),1,java.sql.Timestamp.from(now.minusSeconds(60)));
    jdbc.update("INSERT INTO edu_refresh_token(user_id,token_hash,expires_at,revoked_at,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",user.getId(),"a".repeat(56)+suffix,java.sql.Timestamp.from(now.plusSeconds(3600)),null);
    jdbc.update("INSERT INTO edu_outbox_event(event_type,aggregate_type,aggregate_id,payload_json,status,available_at,created_at) VALUES('TEST','USER',?,'{}','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user.getId().toString());
    jdbc.update("INSERT INTO edu_audit_log(action,target_type,target_id,severity,success,created_at) VALUES('OLD','USER',?,'INFO',TRUE,?)",user.getId().toString(),java.sql.Timestamp.from(now.minus(Duration.ofDays(366))));
    jdbc.update("INSERT INTO edu_audit_log(action,target_type,target_id,severity,success,created_at) VALUES('BOUNDARY','USER',?,'INFO',TRUE,?)",user.getId().toString(),java.sql.Timestamp.from(now.minus(Duration.ofDays(365)).plusSeconds(1)));
    String runId="maint-run-"+suffix;var result=maintenance.run(runId);assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.affectedCount()).isGreaterThanOrEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_rate_limit_bucket WHERE bucket_key=?",Integer.class,"expired-"+suffix)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_refresh_token WHERE token_hash=?",Integer.class,"a".repeat(56)+suffix)).isOne();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_outbox_event WHERE aggregate_id=? AND status='PENDING'",Integer.class,user.getId().toString())).isOne();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_audit_log WHERE action='OLD' AND target_id=?",Integer.class,user.getId().toString())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_audit_log WHERE action='BOUNDARY' AND target_id=?",Integer.class,user.getId().toString())).isOne();
    assertThat(maintenance.run(runId)).isEqualTo(result);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_maintenance_run WHERE run_id=?",Integer.class,runId)).isOne();

    RetentionMaintenanceCoordinator failing=new RetentionMaintenanceCoordinator(jdbc,leases,(instant,fence)->{throw new IllegalStateException("controlled failure");},json,transactionManager,Clock.systemUTC(),"failure-instance",30);
    var failed=failing.run("maint-fail-"+suffix);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("CLEANUP_FAILED");assertThat(failed.errorMessage()).contains("controlled failure");
  }

  @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void maintenanceFencingPreventsExpiredOwnerAndSameRunRaces() throws Exception {
    String suffix=UUID.randomUUID().toString().substring(0,8);String bucket="fenced-delete-"+suffix;
    jdbc.update("INSERT INTO edu_rate_limit_bucket(bucket_key,window_started_at,request_count,expires_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",bucket,java.sql.Timestamp.from(Instant.now().minusSeconds(120)),1,java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),oldDeleteAttempts=new AtomicInteger();
    RetentionMaintenanceCoordinator oldOwner=new RetentionMaintenanceCoordinator(jdbc,leases,(instant,fence)->{
      fence.verify();int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);
      try{int deleted=jdbc.update("DELETE FROM edu_rate_limit_bucket WHERE bucket_key=?",bucket);oldDeleteAttempts.incrementAndGet();if(deleted!=1)throw new IllegalStateException("old owner did not execute delete");entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}fence.verify();return Map.of("old",deleted);}finally{active.decrementAndGet();}
    },json,transactionManager,Clock.systemUTC(),"old-owner",1);
    RetentionMaintenanceCoordinator newOwner=new RetentionMaintenanceCoordinator(jdbc,leases,(instant,fence)->{
      fence.verify();int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);try{return Map.of("new",jdbc.update("DELETE FROM edu_rate_limit_bucket WHERE bucket_key=?",bucket));}finally{active.decrementAndGet();}
    },json,transactionManager,Clock.systemUTC(),"new-owner",5);
    ExecutorService pool=Executors.newFixedThreadPool(2);String oldRun="fence-old-"+suffix,newRun="fence-new-"+suffix;
    Future<?> first=pool.submit(()->oldOwner.run(oldRun));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(1200);
    Future<?> second=pool.submit(()->newOwner.run(newRun));Thread.sleep(200);assertThat(second.isDone()).isFalse();release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
    assertThat(oldOwner.find(oldRun).status()).isEqualTo("FAILED");assertThat(oldOwner.find(oldRun).errorCode()).isEqualTo("STALE_RUN_RECOVERED");
    assertThat(newOwner.find(newRun).status()).isEqualTo("SUCCEEDED");assertThat(newOwner.find(newRun).affectedCount()).isEqualTo(1);assertThat(oldDeleteAttempts.get()).isEqualTo(1);assertThat(maximum.get()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_rate_limit_bucket WHERE bucket_key=?",Integer.class,bucket)).isZero();

    AtomicInteger sameExecutions=new AtomicInteger();
    RetentionMaintenanceCoordinator sameA=new RetentionMaintenanceCoordinator(jdbc,leases,(instant,fence)->{fence.verify();sameExecutions.incrementAndGet();return Map.of("same",0);},json,transactionManager,Clock.systemUTC(),"same-a",5);
    RetentionMaintenanceCoordinator sameB=new RetentionMaintenanceCoordinator(jdbc,leases,(instant,fence)->{fence.verify();sameExecutions.incrementAndGet();return Map.of("same",0);},json,transactionManager,Clock.systemUTC(),"same-b",5);
    String sameRun="same-run-"+suffix;CountDownLatch gate=new CountDownLatch(1);Callable<RetentionMaintenanceCoordinator.RunResult> a=()->{gate.await();return sameA.run(sameRun);};Callable<RetentionMaintenanceCoordinator.RunResult> b=()->{gate.await();return sameB.run(sameRun);};
    Future<RetentionMaintenanceCoordinator.RunResult> one=pool.submit(a),two=pool.submit(b);gate.countDown();var oneResult=one.get(10,TimeUnit.SECONDS);var twoResult=two.get(10,TimeUnit.SECONDS);pool.shutdownNow();
    assertThat(oneResult.status()).isEqualTo("SUCCEEDED");assertThat(twoResult.status()).isEqualTo("SUCCEEDED");assertThat(sameExecutions.get()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_maintenance_run WHERE run_id=?",Integer.class,sameRun)).isOne();
  }

  private UserEntity user(String username,UserRole role,String display){UserEntity u=new UserEntity();u.setUsername(username);u.setPasswordHash(passwords.encode("Pass@123456"));u.setDisplayName(display);u.setRole(role);u.setStatus(UserStatus.ACTIVE);u.setForcePasswordChange(false);users.insert(u);return users.selectById(u.getId());}
  private String bearer(UserEntity user){return "Bearer "+jwt.issueAccessToken(user);}
  private ClassEntity room(UserEntity teacher){ClassEntity c=new ClassEntity();c.setCourseId(1L);c.setName("Report "+UUID.randomUUID());c.setCourseName("Python");c.setTeacherId(teacher.getId());c.setStatus(ClassStatus.ACTIVE);classes.insert(c); CourseTestFixtures.teacher(jdbc, c.getTeacherId());return c;}
  private void member(ClassEntity room,UserEntity student){ClassStudentEntity row=new ClassStudentEntity();row.setClassId(room.getId());row.setStudentId(student.getId());memberships.insert(row);}
  private AssessmentEntity assessment(ClassEntity room,UserEntity teacher,AssessmentStatus status,String settings){AssessmentEntity a=new AssessmentEntity();a.setAssessmentType(AssessmentType.HOMEWORK);a.setClassId(room.getId());a.setCreatorId(teacher.getId());a.setTitle("Report assessment");a.setStatus(status);a.setTotalScore(BigDecimal.TEN);a.setAutoGradeEnabled(true);a.setAutoReleaseEnabled(false);a.setAiAttachmentGradeEnabled(false);a.setSettingsJson(settings);a.setCurrentVersion(1);assessments.insert(a);return a;}
  private void submission(AssessmentEntity assessment,UserEntity student,String score,String comment){jdbc.update("""
      INSERT INTO edu_assessment_submission(assessment_id,assessment_version,student_id,status,attempt_no,final_score,grading_status,result_released_at,overall_comment,created_at,updated_at)
      VALUES(?,1,?,'GRADED',1,?,'COMPLETED',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
      """,assessment.getId(),student.getId(),new BigDecimal(score),comment);}
}
