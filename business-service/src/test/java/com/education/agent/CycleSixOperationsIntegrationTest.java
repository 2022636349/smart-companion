package com.education.agent;

import com.education.agent.admin.dto.AdminDtos.UserStatusRequest;
import com.education.agent.admin.entity.AuditLogEntity;
import com.education.agent.admin.mapper.AuditLogMapper;
import com.education.agent.admin.service.AdminOperationsService;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.*;
import com.education.agent.auth.mapper.RefreshTokenMapper;
import com.education.agent.classroom.entity.*;
import com.education.agent.classroom.mapper.*;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.telemetry.entity.ModelInvocationEntity;
import com.education.agent.telemetry.mapper.ModelInvocationMapper;
import com.education.agent.telemetry.dto.ModelInvocationDtos.*;
import com.education.agent.telemetry.service.ModelInvocationService;
import com.education.agent.notification.service.BusinessNotificationDispatcher;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CycleSixOperationsIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserMapper users; @Autowired PasswordEncoder passwords;
 @Autowired RefreshTokenMapper refreshTokens; @Autowired AuditLogMapper auditRows; @Autowired AuditLogService audit;
 @Autowired ClassMapper classes; @Autowired ClassStudentMapper memberships; @Autowired AssessmentMapper assessments;
 @Autowired JdbcTemplate jdbc; @Autowired ModelInvocationMapper modelCalls; @Autowired AdminOperationsService adminService;
 @Autowired ModelInvocationService modelInvocationService;
 @Autowired BusinessNotificationDispatcher notificationDispatcher;
 @Autowired PlatformTransactionManager transactionManager;

 @Test
 void adminManagementProtectsSelfRevokesRefreshAndReturnsSanitizedAudits() throws Exception {
  UserEntity admin=user("c6_admin","AdminPass@123",UserRole.ADMIN,UserStatus.ACTIVE);
  UserEntity student=user("c6_managed","StudentPass@123",UserRole.STUDENT,UserStatus.ACTIVE);
  JsonNode adminLogin=login(admin,"ADMIN");String adminToken=adminLogin.at("/data/tokens/accessToken").asText();
  JsonNode studentLogin=login(student,"STUDENT");String refresh=studentLogin.at("/data/tokens/refreshToken").asText();

  mvc.perform(get("/api/admin/dashboard").header("Authorization","Bearer "+adminToken))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.userCountByRole.ADMIN").isNumber());
  String createdBody=mvc.perform(post("/api/admin/users").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\"c6_created_teacher\",\"displayName\":\"新教师\",\"role\":\"TEACHER\",\"initialPassword\":\"TempPass@123\"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("TEACHER")).andReturn().getResponse().getContentAsString();
  long createdId=json.readTree(createdBody).at("/data/id").asLong();
  String createdRefresh=login("c6_created_teacher","TempPass@123","TEACHER").at("/data/tokens/refreshToken").asText();
  mvc.perform(put("/api/admin/users/"+createdId).header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON)
    .content("{\"displayName\":\"已转学生\",\"role\":\"STUDENT\",\"email\":null}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STUDENT"));
  mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken",createdRefresh))))
    .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/admin/users/"+createdId+"/reset-password").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON)
    .content("{\"newPassword\":\"ResetPass@123\",\"forceChangeOnNextLogin\":true}")) .andExpect(status().isOk());
  login("c6_created_teacher","ResetPass@123","STUDENT");
  mvc.perform(get("/api/admin/users/"+createdId).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.displayName").value("已转学生"));
  mvc.perform(put("/api/admin/users/"+admin.getId()+"/status").header("Authorization","Bearer "+adminToken)
    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\",\"reason\":\"self\"}"))
    .andExpect(status().isConflict());
  mvc.perform(put("/api/admin/users/"+student.getId()+"/status").header("Authorization","Bearer "+adminToken)
    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\",\"reason\":\"security review\"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LOCKED"));
  mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("refreshToken",refresh))))
    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

  audit.record("C6_SENSITIVE_TEST","USER",student.getId().toString(),admin.getId(),true,
    Map.of("reason","x".repeat(800),"password","never-return","token","never-return","filePath","C:/secret"));
  mvc.perform(get("/api/admin/audit-logs").param("action","C6_SENSITIVE_TEST").header("Authorization","Bearer "+adminToken))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].details.reason").value("x".repeat(500)))
    .andExpect(jsonPath("$.data.items[0].details.password").doesNotExist())
    .andExpect(jsonPath("$.data.items[0].details.token").doesNotExist())
    .andExpect(jsonPath("$.data.items[0].details.filePath").doesNotExist());
  mvc.perform(get("/api/admin/login-logs").param("username","c6_admin").header("Authorization","Bearer "+adminToken))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].username").value("c6_admin"));
  mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\"c6_managed\",\"password\":\"wrong-password\",\"expectedRole\":\"STUDENT\"}"))
    .andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/login-logs").param("username","c6_managed").param("status","FAILED").header("Authorization","Bearer "+adminToken))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].success").value(false));
 }

 @Test
 void notificationsAreIsolatedIdempotentSafeAndBusinessEventsAreDeduplicated() throws Exception {
  UserEntity admin=user("c6_notify_admin","AdminPass@123",UserRole.ADMIN,UserStatus.ACTIVE);
  UserEntity s1=user("c6_notify_s1","StudentPass@123",UserRole.STUDENT,UserStatus.ACTIVE);
  UserEntity s2=user("c6_notify_s2","StudentPass@123",UserRole.STUDENT,UserStatus.ACTIVE);
  String adminToken=login(admin,"ADMIN").at("/data/tokens/accessToken").asText();String t1=login(s1,"STUDENT").at("/data/tokens/accessToken").asText();String t2=login(s2,"STUDENT").at("/data/tokens/accessToken").asText();
  String key="c6-notification-key-0001";String body="""
   {"title":"系统维护","content":"今晚维护","recipientRoles":["STUDENT"],"type":"SYSTEM","actionPath":"/notifications"}
   """;
  String first=mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key)
    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  JsonNode firstJson=json.readTree(first);long created=firstJson.at("/data/createdCount").asLong();assertThat(created).isGreaterThanOrEqualTo(2);
  mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key)
    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
    .andExpect(jsonPath("$.data.notificationGroupId").value(firstJson.at("/data/notificationGroupId").asText()));
  mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key)
    .contentType(MediaType.APPLICATION_JSON).content(body.replace("今晚维护","不同内容"))).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+adminToken).header("Idempotency-Key","c6-notification-key-0002")
    .contentType(MediaType.APPLICATION_JSON).content(body.replace("/notifications","//evil.example"))).andExpect(status().isUnprocessableEntity());

  JsonNode list1=json.readTree(mvc.perform(get("/api/notifications").header("Authorization","Bearer "+t1)).andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString());long notificationId=list1.at("/data/items/0/id").asLong();
  mvc.perform(post("/api/notifications/"+notificationId+"/read").header("Authorization","Bearer "+t2)).andExpect(status().isNotFound());
  mvc.perform(post("/api/notifications/"+notificationId+"/read").header("Authorization","Bearer "+t1)).andExpect(status().isOk());
  mvc.perform(post("/api/notifications/"+notificationId+"/read").header("Authorization","Bearer "+t1)).andExpect(status().isOk());

  ClassEntity classroom=classroom("C6 通知班",admin);membership(classroom,s1);AssessmentEntity assessment=assessment(classroom,admin,"事件通知测评");
  notificationDispatcher.onAuditEvent("ASSESSMENT_PUBLISHED","ASSESSMENT",assessment.getId().toString());
  notificationDispatcher.onAuditEvent("ASSESSMENT_PUBLISHED","ASSESSMENT",assessment.getId().toString());
  mvc.perform(get("/api/notifications").param("type","ASSESSMENT_PUBLISHED").header("Authorization","Bearer "+t1))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].actionPath").value("/assessments/"+assessment.getId()));
 }

 @Test
 void feedbackRequiresOwnedFeedbackAttachmentsAndAdminHandlingIsAtomic() throws Exception {
  UserEntity admin=user("c6_feedback_admin","AdminPass@123",UserRole.ADMIN,UserStatus.ACTIVE);
  UserEntity author=user("c6_feedback_author","StudentPass@123",UserRole.STUDENT,UserStatus.ACTIVE);
  UserEntity other=user("c6_feedback_other","StudentPass@123",UserRole.STUDENT,UserStatus.ACTIVE);
  String adminToken=login(admin,"ADMIN").at("/data/tokens/accessToken").asText();String authorToken=login(author,"STUDENT").at("/data/tokens/accessToken").asText();String otherToken=login(other,"STUDENT").at("/data/tokens/accessToken").asText();
  long owned=upload(authorToken,"owned.txt");long foreign=upload(otherToken,"foreign.txt");
  mvc.perform(post("/api/feedback").header("Authorization","Bearer "+authorToken).contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("category","BUG","title","附件越权","content","不能引用别人附件","attachmentFileIds",List.of(foreign)))))
    .andExpect(status().isForbidden());
  String response=mvc.perform(post("/api/feedback").header("Authorization","Bearer "+authorToken).contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("category","BUG","title","页面问题","content","详情内容","attachmentFileIds",List.of(owned),"pagePath","/learning/dashboard"))))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.attachmentFileIds[0]").value(owned)).andReturn().getResponse().getContentAsString();
  long feedbackId=json.readTree(response).at("/data/id").asLong();
  mvc.perform(get("/api/files/"+owned+"/content").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
  mvc.perform(put("/api/admin/feedback/"+feedbackId).header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON)
    .content("{\"status\":\"RESOLVED\",\"adminReply\":\"已修复\"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"))
    .andExpect(jsonPath("$.data.adminReply").value("已修复")).andExpect(jsonPath("$.data.handledBy").value(admin.getId()))
    .andExpect(jsonPath("$.data.handledAt").isNotEmpty());
 }

 @Test
 void modelInvocationRequiresInternalCredentialsStrictBodyAndIsConflictSafe() throws Exception {
  String requestId="c6-model-request-000001";String invocation="c6-model-invocation-0001";
  String body=modelBody(invocation,requestId,120);
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Request-Id",requestId).contentType(MediaType.APPLICATION_JSON).content(body))
    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail.owner").value("B"));
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Internal-Token","test-internal-token").header("X-Request-Id","different-request-0001").contentType(MediaType.APPLICATION_JSON).content(body))
    .andExpect(status().isUnprocessableEntity());
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId).contentType(MediaType.APPLICATION_JSON)
    .content(body.substring(0,body.length()-1)+",\"user_id\":99}")) .andExpect(status().isUnprocessableEntity());
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId).contentType(MediaType.APPLICATION_JSON).content(body))
    .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(true)).andExpect(jsonPath("$.duplicate").value(false));
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId).contentType(MediaType.APPLICATION_JSON).content(body))
    .andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(true));
  mvc.perform(post("/api/internal/ai/model-invocations").header("X-Internal-Token","test-internal-token").header("X-Request-Id",requestId).contentType(MediaType.APPLICATION_JSON).content(modelBody(invocation,requestId,121)))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.detail.code").value("IDEMPOTENCY_CONFLICT"));
  ModelInvocationEntity stored=modelCalls.selectByInvocationForUpdate(invocation);assertThat(stored.getProvider()).isEqualTo("deepseek");
  assertThat(stored.getDurationMs()).isEqualTo(120);assertThat(stored.getPayloadHash()).hasSize(64);assertThat(stored.getUserId()).isNull();
 }

 @Test
 @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentAdminDemotionsCannotRemoveLastActiveAdmin() throws Exception {
  TransactionTemplate tx=new TransactionTemplate(transactionManager);String suffix=UUID.randomUUID().toString().substring(0,8);
  List<Long> preexisting=tx.execute(s->users.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserEntity>()
    .eq(UserEntity::getRole,UserRole.ADMIN).eq(UserEntity::getStatus,UserStatus.ACTIVE)).stream().map(UserEntity::getId).toList());
  tx.executeWithoutResult(s->preexisting.forEach(id->{UserEntity existing=users.selectById(id);existing.setStatus(UserStatus.DISABLED);users.updateById(existing);}));
  UserEntity operator=tx.execute(s->{UserEntity u=user("c6_inactive_operator_"+suffix,"AdminPass@123",UserRole.ADMIN,UserStatus.DISABLED);return u;});
  UserEntity a=tx.execute(s->user("c6_concurrent_a_"+suffix,"AdminPass@123",UserRole.ADMIN,UserStatus.ACTIVE));
  UserEntity b=tx.execute(s->user("c6_concurrent_b_"+suffix,"AdminPass@123",UserRole.ADMIN,UserStatus.ACTIVE));
  AuthenticatedUser principal=AuthenticatedUser.from(operator);ExecutorService pool=Executors.newFixedThreadPool(2);
  CountDownLatch start=new CountDownLatch(1);
  Callable<String> first=()->{start.await();try{adminService.updateStatus(principal,a.getId(),new UserStatusRequest(UserStatus.DISABLED,"concurrent"));return "OK";}catch(Exception e){return e.getClass().getSimpleName()+":"+e.getMessage();}};
  Callable<String> second=()->{start.await();try{adminService.updateStatus(principal,b.getId(),new UserStatusRequest(UserStatus.DISABLED,"concurrent"));return "OK";}catch(Exception e){return e.getClass().getSimpleName()+":"+e.getMessage();}};
  Future<String> f1=pool.submit(first),f2=pool.submit(second);start.countDown();List<String> outcomes=List.of(f1.get(10,TimeUnit.SECONDS),f2.get(10,TimeUnit.SECONDS));pool.shutdownNow();
  assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
  long active=users.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserEntity>()
    .in(UserEntity::getId,List.of(a.getId(),b.getId())).eq(UserEntity::getRole,UserRole.ADMIN).eq(UserEntity::getStatus,UserStatus.ACTIVE));
  assertThat(active).isEqualTo(1);
  tx.executeWithoutResult(s->preexisting.forEach(id->{UserEntity existing=users.selectById(id);existing.setStatus(UserStatus.ACTIVE);users.updateById(existing);}));
 }

 @Test
 @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void concurrentTelemetryRetriesPersistOneInvocation() throws Exception {
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  ModelInvocationRequest request=new ModelInvocationRequest("c6-concurrent-"+suffix,"c6-request-concurrent-"+suffix,
    "grading","ollama","qwen-local","grade-v1",88,10,5,1,null,Outcome.SUCCESS,null,"local-v1",0L,"CNY",
    java.time.OffsetDateTime.parse("2026-08-23T01:00:00Z"));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  Callable<ModelInvocationAcceptedResponse> call=()->{start.await();return modelInvocationService.record(request);};
  Future<ModelInvocationAcceptedResponse> a=pool.submit(call),b=pool.submit(call);start.countDown();
  List<ModelInvocationAcceptedResponse> results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));pool.shutdownNow();
  assertThat(results).extracting(ModelInvocationAcceptedResponse::duplicate).containsExactlyInAnyOrder(false,true);
  assertThat(modelCalls.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelInvocationEntity>()
    .eq(ModelInvocationEntity::getInvocationId,request.invocationId()))).isEqualTo(1);
 }

 private String modelBody(String invocation,String request,int duration)throws Exception{return json.writeValueAsString(Map.ofEntries(
  Map.entry("invocation_id",invocation),Map.entry("request_id",request),Map.entry("capability","tutor"),Map.entry("provider","deepseek"),Map.entry("model","deepseek-chat"),
  Map.entry("prompt_version","tutor-v2"),Map.entry("duration_ms",duration),Map.entry("input_units",100),Map.entry("output_units",40),Map.entry("attempts",1),
  Map.entry("outcome","SUCCESS"),Map.entry("pricing_version","2026-08"),Map.entry("estimated_cost_microunits",42),Map.entry("currency","CNY"),Map.entry("occurred_at","2026-08-23T01:00:00Z")));}
 private long upload(String token,String name)throws Exception{MockMultipartFile file=new MockMultipartFile("file",name,"text/plain","hello".getBytes());String response=mvc.perform(multipart("/api/files").file(file).param("purpose","FEEDBACK_ATTACHMENT").header("Authorization","Bearer "+token))
  .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();return json.readTree(response).at("/data/id").asLong();}
 private JsonNode login(UserEntity user,String role)throws Exception{return login(user.getUsername(),role.equals("ADMIN")?"AdminPass@123":"StudentPass@123",role);}
 private JsonNode login(String username,String password,String role)throws Exception{String response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
  .content(json.writeValueAsString(Map.of("username",username,"password",password,"expectedRole",role))))
  .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return json.readTree(response);}
 private UserEntity user(String username,String password,UserRole role,UserStatus status){UserEntity u=new UserEntity();u.setUsername(username);u.setPasswordHash(passwords.encode(password));u.setDisplayName(username);u.setRole(role);u.setStatus(status);u.setForcePasswordChange(false);users.insert(u);return users.selectById(u.getId());}
 private ClassEntity classroom(String name,UserEntity teacher){ClassEntity c=new ClassEntity();c.setCourseId(1L);c.setName(name);c.setCourseName("Python程序设计");c.setTeacherId(teacher.getId());c.setStatus(ClassStatus.ACTIVE);classes.insert(c); CourseTestFixtures.teacher(jdbc, c.getTeacherId());return c;}
 private void membership(ClassEntity c,UserEntity s){ClassStudentEntity m=new ClassStudentEntity();m.setClassId(c.getId());m.setStudentId(s.getId());memberships.insert(m);}
 private AssessmentEntity assessment(ClassEntity c,UserEntity creator,String title){AssessmentEntity a=new AssessmentEntity();a.setAssessmentType(AssessmentType.HOMEWORK);a.setClassId(c.getId());a.setCreatorId(creator.getId());a.setTitle(title);a.setStatus(AssessmentStatus.PUBLISHED);a.setTotalScore(java.math.BigDecimal.TEN);a.setAutoGradeEnabled(true);a.setAutoReleaseEnabled(false);a.setAiAttachmentGradeEnabled(false);a.setSettingsJson("{}");a.setCurrentVersion(1);assessments.insert(a);return a;}
}
