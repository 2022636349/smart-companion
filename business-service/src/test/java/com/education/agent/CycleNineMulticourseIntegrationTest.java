package com.education.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.education.agent.common.error.*;
import com.education.agent.course.*;
import com.education.agent.course.CourseDtos.*;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.service.GraphIngestionService;
import com.education.agent.integration.ai.*;
import com.education.agent.integration.knowledge.*;
import com.education.agent.learning.memory.*;
import com.education.agent.learning.memory.MemoryDtos.*;
import com.education.agent.learning.service.*;
import com.education.agent.report.service.ReportService;
import com.education.agent.report.controller.ReportController.LearningFormat;
import com.education.agent.resource.service.TeachingResourceService;
import com.education.agent.resource.dto.*;
import com.education.agent.resource.model.*;
import com.education.agent.security.*;
import com.education.agent.tutor.dto.*;
import com.education.agent.tutor.model.*;
import com.education.agent.tutor.service.TutorService;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CycleNineMulticourseIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired CourseService courses;
  @Autowired CourseAccessService access;
  @Autowired TutorService tutor;
  @Autowired TeachingResourceService resources;
  @Autowired com.education.agent.file.service.FileStorageService resourceFiles;
  @Autowired MemoryService memories;
  @Autowired MemoryOutboxWorker memoryWorker;
  @Autowired ProfileSummaryService profiles;
  @Autowired LearningService learning;
  @Autowired LearningEvidenceService evidence;
  @Autowired ReportService reports;
  @Autowired GraphIngestionService ingestion;
  @Autowired UserMapper users;
  @Autowired JwtService jwt;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockBean AiServiceClient ai;
  @MockBean KnowledgeServiceClient graph;
  @MockBean(name="graphIngestionExecutor") TaskExecutor graphExecutor;
  UserEntity admin, owner, editor, viewer, student, outsider;
  CourseView a,b;
  long classA,classB;

  @BeforeEach void setup() {
    reset(ai,graph,graphExecutor);
    admin=user(UserRole.ADMIN);owner=user(UserRole.TEACHER);editor=user(UserRole.TEACHER);
    viewer=user(UserRole.TEACHER);student=user(UserRole.STUDENT);outsider=user(UserRole.STUDENT);
    a=course("算法课程",true);b=course("学术写作",false);
    for(var course:List.of(a,b))courses.putTeacher(as(admin),course.id(),owner.getId(),new TeacherRequest("OWNER"));
    courses.putTeacher(as(admin),b.id(),editor.getId(),new TeacherRequest("EDITOR"));
    courses.putTeacher(as(admin),b.id(),viewer.getId(),new TeacherRequest("VIEWER"));
    classA=classroom(a.id(),student.getId());classB=classroom(b.id(),student.getId());
    when(graph.ragContext(anyLong(),anyString())).thenAnswer(inv->context(inv.getArgument(0),inv.getArgument(1)));
    when(graph.recommendNext(anyLong(),anyLong(),anyList(),anyInt())).thenAnswer(inv->new KnowledgeRecommendationDtos.Response(inv.getArgument(0),List.of(),"shared-v1"));
    when(graph.currentGraphVersion(anyLong(),anyString())).thenReturn("0.0.0");
    when(ai.tutorChat(any())).thenAnswer(inv->{AiTutorChatRequest r=inv.getArgument(0);return answer(r,r.courseId());});
  }

  @Test void courseMembershipAndCapabilitiesAreAuthoritativeAndDraftsStayHidden() throws Exception {
    assertThat(courses.list(as(student),1,100).items()).extracting(CourseView::id).contains(a.id(),b.id());
    assertThat(courses.list(as(outsider),1,100).items()).extracting(CourseView::id).doesNotContain(a.id(),b.id());
    assertError(()->courses.get(as(outsider),a.id()),ErrorCode.FORBIDDEN);
    assertError(()->courses.save(as(owner),null,request("new",false,"ACTIVE")),ErrorCode.FORBIDDEN);
    mvc.perform(post("/api/courses").header("Authorization",bearer(admin)).contentType("application/json")
        .content(json.writeValueAsString(request("第三门课程",false,"DRAFT"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.description").value("课程说明"));
    courses.save(as(admin),b.id(),request(b.code(),b.name(),false,"DRAFT"));
    assertThat(courses.list(as(student),1,100).items()).extracting(CourseView::id).doesNotContain(b.id());
    mvc.perform(get("/api/courses/{id}",b.id()).header("Authorization",bearer(student))).andExpect(status().isForbidden());
    mvc.perform(get("/api/learning/me/analysis").header("Authorization",bearer(student))).andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/tutor/sessions").header("Authorization",bearer(student)).contentType("application/json")
        .content("{\"knowledgePointId\":\"shared-kp\",\"entrySource\":\"MANUAL\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test void teachingClassManagementAndGraphConstructionUseTeacherCourseRoles() throws Exception {
    Map<String,Object> payload=Map.of("courseId",b.id(),"teacherId",owner.getId(),"name","写作教学班","description","原有说明");
    String created=mvc.perform(post("/api/classes").header("Authorization",bearer(owner)).contentType("application/json")
        .content(json.writeValueAsString(payload))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    long classId=json.readTree(created).at("/data/id").asLong();
    mvc.perform(post("/api/classes/{id}/students",classId).header("Authorization",bearer(owner)).contentType("application/json")
        .content(json.writeValueAsString(Map.of("studentIds",List.of(outsider.getId()))))).andExpect(status().isOk());
    assertThat(courses.get(as(outsider),b.id()).membershipRole()).isEqualTo("STUDENT");
    mvc.perform(post("/api/classes").param("courseId",a.id().toString()).header("Authorization",bearer(owner)).contentType("application/json")
        .content(json.writeValueAsString(payload))).andExpect(status().isUnprocessableEntity());
    mvc.perform(post("/api/classes").header("Authorization",bearer(viewer)).contentType("application/json")
        .content(json.writeValueAsString(payload))).andExpect(status().isForbidden());
    long fileId=file(owner.getId());
    var request=new GraphIngestionDtos.CreateRequest(b.id(),fileId,"写作课程大纲","TEACHING_RESOURCE","AUTHORIZED",null);
    String job=mvc.perform(post("/api/courses/{id}/knowledge/ingestion-jobs",b.id()).header("Authorization",bearer(owner))
        .header("Idempotency-Key","cycle9-create-graph-"+fileId).contentType("application/json").content(json.writeValueAsString(request)))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.courseId").value(b.id())).andReturn().getResponse().getContentAsString();
    long jobId=json.readTree(job).at("/data/id").asLong();
    mvc.perform(get("/api/courses/{courseId}/knowledge/ingestion-jobs/{jobId}",a.id(),jobId).header("Authorization",bearer(owner))).andExpect(status().isNotFound());
    assertThat(ingestion.list(as(owner),a.id(),1,20,null).items()).isEmpty();
    assertThat(ingestion.list(as(viewer),b.id(),1,20,null).items()).extracting(GraphIngestionDtos.JobView::id).contains(jobId);
    assertError(()->access.requireEdit(as(viewer),b.id()),ErrorCode.FORBIDDEN);
    assertError(()->access.requireOwner(as(editor),b.id()),ErrorCode.FORBIDDEN);
    mvc.perform(post("/api/courses/{id}/knowledge/ingestion-jobs",a.id()).header("Authorization",bearer(owner))
        .header("Idempotency-Key","cycle9-mismatch-graph").contentType("application/json").content(json.writeValueAsString(request)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test void identicalKnowledgeIdsProduceIndependentTutorHistoriesAndRejectCrossCourseAiEcho() {
    var sessionA=session(a.id());var sessionB=session(b.id());
    tutor.sendMessage(as(student),sessionA.id(),"cycle9-message-course-a","请讲解");
    tutor.sendMessage(as(student),sessionB.id(),"cycle9-message-course-b","请讲解");
    assertThat(tutor.list(as(student),a.id(),1,20,null).items()).extracting(TutorSessionView::id).containsExactly(sessionA.id());
    assertThat(tutor.list(as(student),b.id(),1,20,null).items()).extracting(TutorSessionView::id).containsExactly(sessionB.id());
    verify(ai).tutorChat(argThat(r->r.courseId().equals(a.id())&&r.courseName().equals(a.name())));
    verify(ai).tutorChat(argThat(r->r.courseId().equals(b.id())&&!r.enabledActivityTypes().contains("CODE")));
    doAnswer(inv->{AiTutorChatRequest r=inv.getArgument(0);return answer(r,a.id());}).when(ai).tutorChat(any());
    int before=jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",Integer.class,sessionB.id());
    assertError(()->tutor.sendMessage(as(student),sessionB.id(),"cycle9-reject-wrong-course","继续"),ErrorCode.AI_SERVICE_INVALID_RESPONSE);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",Integer.class,sessionB.id())).isEqualTo(before);
    assertError(()->tutor.create(as(outsider),new TutorSessionCreateRequest(a.id(),"shared-kp",TutorEntrySource.MANUAL,null)),ErrorCode.FORBIDDEN);
  }

  @Test void aNonProgrammingCourseDowngradesExplicitCodeModeBeforeModelInvocation() {
    var session=session(b.id());
    tutor.sendMessage(as(student),session.id(),"cycle9-non-code-mode-key","请写代码","cycle9-non-code-request",TutorTeachingMode.CODE);
    verify(ai).tutorChat(argThat(r->r.courseId().equals(b.id())&&r.teachingMode().equals("EXPLAIN")&&!r.enabledActivityTypes().contains("CODE")));
    assertError(()->access.requireCode(b.id()),ErrorCode.VALIDATION_ERROR);
    access.requireCode(a.id());
  }

  @Test void sourceQuestionAndResourceCannotBeMovedAcrossCourses() {
    long assessment=insert("INSERT INTO edu_assessment(class_id,creator_id,assessment_type,title,status,total_score,settings_json) VALUES(?,?,'HOMEWORK','来源测评','DRAFT',0,'{}')",classA,owner.getId());
    long question=insert("INSERT INTO edu_assessment_question(assessment_id,client_key,position_no,question_type,stem,score) VALUES(?,'q1',1,'SHORT_ANSWER','题目',10)",assessment);
    assertError(()->tutor.create(as(student),new TutorSessionCreateRequest(b.id(),"shared-kp",TutorEntrySource.MISTAKE,question)),ErrorCode.RESOURCE_NOT_FOUND);
    var resource=resources.create(as(owner),new ResourceUpsertRequest(a.id(),"课程 A 资料",null,ResourceType.EXTERNAL_LINK,null,"https://example.org/resource",Difficulty.BEGINNER,List.of("shared-kp")));
    assertThat(resource.courseId()).isEqualTo(a.id());
    assertError(()->resources.update(as(owner),resource.id(),new ResourceUpsertRequest(b.id(),"不应转移",null,ResourceType.EXTERNAL_LINK,null,"https://example.org/resource",Difficulty.BEGINNER,List.of("shared-kp"))),ErrorCode.VALIDATION_ERROR);
  }

  @Test void resourceFilesFollowPublicationAndCourseTeacherAccess() throws Exception {
    var file=resourceFiles.store(as(owner),new org.springframework.mock.web.MockMultipartFile("file","lesson.txt","text/plain","Course lesson".getBytes()),com.education.agent.file.model.FilePurpose.TEACHING_RESOURCE);
    var input=new ResourceUpsertRequest(b.id(),"课程讲义",null,ResourceType.DOCUMENT,file.id(),null,Difficulty.BASIC,List.of("shared-kp"));
    var resource=resources.create(as(owner),input);
    for(var member:List.of(editor,viewer))mvc.perform(get("/api/files/{id}/content",file.id()).header("Authorization",bearer(member))).andExpect(status().isOk()).andExpect(content().string("Course lesson"));
    assertThat(resources.list(as(student),b.id(),1,20,null,null,null).items()).isEmpty();
    assertError(()->resourceFiles.metadata(as(student),file.id()),ErrorCode.RESOURCE_NOT_OWNED);
    assertError(()->resourceFiles.metadata(as(outsider),file.id()),ErrorCode.RESOURCE_NOT_OWNED);
    assertError(()->resources.review(as(viewer),resource.id(),new ResourceReviewRequest(ReviewStatus.APPROVED,null)),ErrorCode.FORBIDDEN);
    resources.review(as(editor),resource.id(),new ResourceReviewRequest(ReviewStatus.APPROVED,"可供学习"));
    mvc.perform(get("/api/files/{id}/content",file.id()).header("Authorization",bearer(student))).andExpect(status().isOk()).andExpect(content().string("Course lesson"));
    var updated=resources.update(as(editor),resource.id(),new ResourceUpsertRequest(b.id(),"讲义修订版","补充说明",ResourceType.DOCUMENT,file.id(),null,Difficulty.BASIC,List.of("shared-kp")));
    assertThat(updated.reviewStatus()).isEqualTo(ReviewStatus.PENDING);
    assertThat(updated.file().id()).isEqualTo(file.id());
    assertThat(updated.reviewComment()).isNull();
    assertError(()->resourceFiles.metadata(as(student),file.id()),ErrorCode.RESOURCE_NOT_OWNED);
    resources.review(as(owner),resource.id(),new ResourceReviewRequest(ReviewStatus.APPROVED,null));
    resources.archive(as(editor),resource.id());
    assertThat(resources.list(as(student),b.id(),1,20,null,null,null).items()).isEmpty();
    assertError(()->resourceFiles.metadata(as(student),file.id()),ErrorCode.RESOURCE_NOT_OWNED);
    assertError(()->resources.update(as(owner),resource.id(),input),ErrorCode.INVALID_RESOURCE_STATE);
    assertThat(resourceFiles.metadata(as(viewer),file.id()).id()).isEqualTo(file.id());
    courses.removeTeacher(as(admin),b.id(),viewer.getId());
    assertError(()->resourceFiles.metadata(as(viewer),file.id()),ErrorCode.RESOURCE_NOT_OWNED);
  }

  @Test void resourceEditsClearOldSourcesAndDescriptionsWhenSwitchingType() {
    var file=resourceFiles.store(as(owner),new org.springframework.mock.web.MockMultipartFile("file","source.txt","text/plain","lesson".getBytes()),com.education.agent.file.model.FilePurpose.TEACHING_RESOURCE);
    var resource=resources.create(as(owner),new ResourceUpsertRequest(b.id(),"资料","旧说明",ResourceType.DOCUMENT,file.id(),null,Difficulty.BASIC,List.of("shared-kp")));
    var link=resources.update(as(owner),resource.id(),new ResourceUpsertRequest(b.id(),"改为链接",null,ResourceType.EXTERNAL_LINK,null,"https://example.org/lesson",Difficulty.BASIC,List.of("shared-kp")));
    assertThat(link.file()).isNull();assertThat(link.description()).isNull();assertThat(link.externalUrl()).isEqualTo("https://example.org/lesson");
    var document=resources.update(as(owner),resource.id(),new ResourceUpsertRequest(b.id(),"改回文件",null,ResourceType.DOCUMENT,file.id(),null,Difficulty.BASIC,List.of("shared-kp")));
    assertThat(document.file().id()).isEqualTo(file.id());assertThat(document.externalUrl()).isNull();
  }

  @Test void sharedResourceEditingDoesNotPermitBorrowingOtherFilesOrCourses() {
    var file=resourceFiles.store(as(owner),new org.springframework.mock.web.MockMultipartFile("file","notes.txt","text/plain","notes".getBytes()),com.education.agent.file.model.FilePurpose.TEACHING_RESOURCE);
    var input=new ResourceUpsertRequest(b.id(),"共享讲义",null,ResourceType.DOCUMENT,file.id(),null,Difficulty.BASIC,List.of("shared-kp"));
    var resource=resources.create(as(owner),input);
    assertError(()->resources.create(as(editor),input),ErrorCode.RESOURCE_NOT_OWNED);
    var other=resourceFiles.store(as(owner),new org.springframework.mock.web.MockMultipartFile("file","other.txt","text/plain","other".getBytes()),com.education.agent.file.model.FilePurpose.TEACHING_RESOURCE);
    assertError(()->resources.update(as(editor),resource.id(),new ResourceUpsertRequest(b.id(),"错误替换",null,ResourceType.DOCUMENT,other.id(),null,Difficulty.BASIC,List.of("shared-kp"))),ErrorCode.RESOURCE_NOT_OWNED);
    assertError(()->resources.update(as(viewer),resource.id(),input),ErrorCode.FORBIDDEN);
    assertError(()->resourceFiles.metadata(as(editor),other.id()),ErrorCode.RESOURCE_NOT_OWNED);
    var courseAFile=resourceFiles.store(as(owner),new org.springframework.mock.web.MockMultipartFile("file","a.txt","text/plain","a".getBytes()),com.education.agent.file.model.FilePurpose.TEACHING_RESOURCE);
    resources.create(as(owner),new ResourceUpsertRequest(a.id(),"其他课程",null,ResourceType.DOCUMENT,courseAFile.id(),null,Difficulty.BASIC,List.of("shared-kp")));
    assertError(()->resourceFiles.metadata(as(editor),courseAFile.id()),ErrorCode.RESOURCE_NOT_OWNED);
  }

  @Test void learningEvidenceMasteryTimelineAndExportsStayCourseScoped() throws Exception {
    evidence.recordPractice(student.getId(),a.id(),1001L,insertId(),"shared-kp","算法知识点","shared-v1","算法练习",new BigDecimal("90"),BigDecimal.ONE,LocalDateTime.now());
    evidence.recordPractice(student.getId(),b.id(),1002L,insertId(),"shared-kp","写作知识点","shared-v1","写作练习",new BigDecimal("30"),BigDecimal.ONE,LocalDateTime.now());
    assertThat(learning.analysis(as(student),student.getId(),a.id(),null,null).mastery()).singleElement().satisfies(m->assertThat(m.score()).isEqualByComparingTo("90"));
    assertThat(learning.analysis(as(student),student.getId(),b.id(),null,null).mastery()).singleElement().satisfies(m->assertThat(m.score()).isEqualByComparingTo("30"));
    assertThat(learning.timeline(as(student),a.id(),1,100,null,null).items()).allSatisfy(e->assertThat(e.courseId()).isEqualTo(a.id()));
    String report=unzip(reports.learningReport(as(student),student.getId(),a.id(),LearningFormat.XLSX).content());
    assertThat(report).contains("算法课程","算法知识点").doesNotContain("写作知识点");
    assertError(()->learning.analysis(as(viewer),student.getId(),a.id(),null,null),ErrorCode.FORBIDDEN);
  }

  @Test void memoryRowsAndDurableIndexReplayKeepThePersistedCourse() {
    var ma=memories.createCandidate(student.getId(),a.id(),MemoryType.SESSION_SUMMARY,"shared-kp","算法记忆","算法记忆正文","SYSTEM",1L,new BigDecimal("0.8"),true,true);
    var mb=memories.createCandidate(student.getId(),b.id(),MemoryType.SESSION_SUMMARY,"shared-kp","写作记忆","写作记忆正文","SYSTEM",2L,new BigDecimal("0.8"),true,true);
    assertThat(memories.list(as(student),a.id(),1,20,null,null,null).items()).extracting(MemoryView::id).containsExactly(ma.id());
    assertThat(memories.list(as(student),b.id(),1,20,null,null,null).items()).extracting(MemoryView::id).containsExactly(mb.id());
    assertError(()->memories.validateRecall(student.getId(),a.id(),List.of(mb.id()),true),ErrorCode.AI_SERVICE_INVALID_RESPONSE);
    when(ai.upsertMemory(any())).thenAnswer(inv->{AiLearningDtos.MemoryUpsertRequest r=inv.getArgument(0);return new AiLearningDtos.MemoryUpsertResponse(r.requestId(),"learning_memory",r.memoryId(),r.generation(),true,"test-embedding",null,false);});
    memoryWorker.processOnce(100);
    verify(ai,atLeastOnce()).upsertMemory(argThat(r->r.memoryId().equals(ma.id())&&r.courseId().equals(a.id())));
    verify(ai,atLeastOnce()).upsertMemory(argThat(r->r.memoryId().equals(mb.id())&&r.courseId().equals(b.id())));
    var revised=memories.revise(as(student),mb.id(),"cycle9-memory-revision",new RevisionRequest("写作记忆","修订后的写作记忆","纠正内容"));
    assertThat(revised.courseId()).isEqualTo(b.id());
  }

  @Test void recoveredSessionSummariesWriteAllLearningFactsIntoTheirStoredCourse() {
    var session=session(b.id());
    tutor.sendMessage(as(student),session.id(),"cycle9-summary-message","解释写作方法");
    tutor.close(as(student),session.id());
    when(ai.summarizeProfile(any())).thenAnswer(inv->{AiLearningDtos.ProfileRequest r=inv.getArgument(0);return new AiLearningDtos.ProfileResponse(r.requestId(),r.courseId(),"写作总结",
        List.of(new AiCognitiveLabel("shared-kp","LEARNING",new BigDecimal("0.8"),"对话中的学习证据")),
        List.of(new AiLearningDtos.MemoryCandidate("SESSION_SUMMARY","shared-kp","写作记忆","写作需要证据",new BigDecimal("0.8"),true,true)),
        List.of(new AiLearningDtos.ResourceSuggestion("shared-kp","巩固知识",List.of("DOCUMENT"))),"test-model","test-prompt");});
    profiles.recoverMissingTasks(100);profiles.processOnce(100);
    verify(ai,atLeastOnce()).summarizeProfile(argThat(r->r.courseId().equals(b.id())&&r.userId().equals(student.getId())));
    for(String table:List.of("edu_learning_memory","edu_cognitive_label","edu_profile_resource_suggestion","edu_learning_event"))
      assertThat(jdbc.queryForList("SELECT DISTINCT course_id FROM "+table+" WHERE student_id=?",Long.class,student.getId())).containsExactly(b.id());
  }

  @Test void archiveAndRevocationStopNewWorkWithoutDeletingHistoricalRows() {
    var session=session(b.id());tutor.sendMessage(as(student),session.id(),"cycle9-before-archive","讲解");
    courses.save(as(admin),b.id(),request(b.code(),b.name(),false,"ARCHIVED"));
    assertThat(tutor.get(as(student),session.id()).courseId()).isEqualTo(b.id());
    assertError(()->tutor.create(as(student),new TutorSessionCreateRequest(b.id(),"shared-kp",TutorEntrySource.MANUAL,null)),ErrorCode.INVALID_RESOURCE_STATE);
    assertError(()->tutor.sendMessage(as(student),session.id(),"cycle9-after-archive","继续"),ErrorCode.INVALID_RESOURCE_STATE);
    assertError(()->access.requireEdit(as(owner),b.id()),ErrorCode.INVALID_RESOURCE_STATE);
    courses.removeTeacher(as(admin),a.id(),owner.getId());
    assertError(()->courses.get(as(owner),a.id()),ErrorCode.FORBIDDEN);
    jdbc.update("DELETE FROM edu_class_student WHERE class_id=? AND student_id=?",classB,student.getId());
    assertError(()->tutor.get(as(student),session.id()),ErrorCode.FORBIDDEN);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",Integer.class,session.id())).isEqualTo(2);
  }

  private CourseView course(String name,boolean code){return courses.save(as(admin),null,request(name,code,"ACTIVE"));}
  private UpsertRequest request(String name,boolean code,String status){return request("C"+UUID.randomUUID().toString().replace("-",""),name,code,status);}
  private UpsertRequest request(String code,String name,boolean programming,String status){return new UpsertRequest(code,name,"课程说明",status,"GENERAL","zh-CN",programming?"PYTHON":null,programming?List.of("EXPLAIN","QUIZ","CODE","VISUAL"):List.of("EXPLAIN","QUIZ","VISUAL"));}
  private UserEntity user(UserRole role){UserEntity u=new UserEntity();u.setUsername("c9_"+UUID.randomUUID().toString().replace("-",""));u.setPasswordHash("x");u.setDisplayName(role.name());u.setRole(role);u.setStatus(UserStatus.ACTIVE);u.setForcePasswordChange(false);users.insert(u);return users.selectById(u.getId());}
  private long classroom(Long courseId,Long studentId){long id=insert("INSERT INTO edu_class(course_id,name,course_name,teacher_id,status) VALUES(?,'C9 教学班','课程',?,'ACTIVE')",courseId,owner.getId());jdbc.update("INSERT INTO edu_class_student(class_id,student_id) VALUES(?,?)",id,studentId);return id;}
  private TutorSessionView session(Long courseId){return tutor.create(as(student),new TutorSessionCreateRequest(courseId,"shared-kp",TutorEntrySource.MANUAL,null));}
  private long file(Long ownerId){return insert("INSERT INTO edu_file_object(original_name,storage_key,content_type,size_bytes,sha256,owner_id,purpose,status) VALUES('outline.txt',?,'text/plain',12,?,?,'KNOWLEDGE_SOURCE','AVAILABLE')","c9/"+UUID.randomUUID(),"a".repeat(64),ownerId);}
  private long insertId(){return Math.abs(UUID.randomUUID().getMostSignificantBits())%1_000_000_000L+1_000_000L;}
  private long insert(String sql,Object...args){GeneratedKeyHolder keys=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
  private AuthenticatedUser as(UserEntity u){return AuthenticatedUser.from(u);}
  private String bearer(UserEntity u){return "Bearer "+jwt.issueAccessToken(u);}
  private InternalGraphRagContextResponse context(Long courseId,String kp){return new InternalGraphRagContextResponse(courseId,new InternalGraphNode(kp,"知识点 "+courseId,1,"knowledge_point",null,"已审核的课程定义",List.of(),List.of(),"BASIC",List.of(),List.of(),"APPROVED",1),List.of(),List.of(),List.of(),"shared-v1","READY",List.of());}
  private AiTutorChatResponse answer(AiTutorChatRequest r,Long courseId){return new AiTutorChatResponse(courseId,r.requestId(),r.knowledgePointId(),"课程讲解",List.of(new AiTutorContentBlock("MARKDOWN","课程讲解",null,null,null,Map.of())),List.of(Map.of("title","已审核来源","reviewed",true,"review_status","APPROVED","rights_status","ALLOWED")),List.of(),null,"test-model","test-prompt");}
  private void assertError(Runnable action,ErrorCode expected){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(expected));}
  private String unzip(byte[] bytes)throws Exception {StringBuilder result=new StringBuilder();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){while(zip.getNextEntry()!=null)result.append(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}return result.toString();}
}
