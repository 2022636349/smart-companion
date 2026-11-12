package com.education.agent.smartcompanion;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.education.agent.common.error.*;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.graphingestion.service.*;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CompanionPackageIntegrationTest {
  @Autowired CompanionPackageService packages;
  @Autowired SmartCompanionService imports;
  @Autowired JdbcTemplate jdbc;
  @Autowired FileStorageService files;
  @SpyBean GraphIngestionService graph;
  @MockBean GraphIngestionProcessor processor;
  final AuthenticatedUser admin=new AuthenticatedUser(9301L,"kgpackadmin","",UserRole.ADMIN,true,true),
      editor=new AuthenticatedUser(9302L,"kgpackeditor","",UserRole.TEACHER,true,true),
      viewer=new AuthenticatedUser(9303L,"kgpackviewer","",UserRole.TEACHER,true,true),
      student=new AuthenticatedUser(9304L,"kgpackstudent","",UserRole.STUDENT,true,true);
  @BeforeEach void setup() {
    if(jdbc.queryForObject("SELECT COUNT(*) FROM edu_user WHERE id=9301",Integer.class)==0) {
      for(var user:List.of(admin,editor,viewer,student))jdbc.update("INSERT INTO edu_user(id,username,password_hash,display_name,role) VALUES(?,?,?,'package test',?)",user.id(),user.username(),"x",user.role().name());
      jdbc.update("INSERT INTO edu_course_teacher(course_id,teacher_id,member_role) VALUES(1,9302,'EDITOR'),(1,9303,'VIEWER')");
    }
  }
  CompanionPackageService.Start start(List<String> paths){return new CompanionPackageService.Start("self-created test package",true,"AUTHORIZED",paths);}
  CompanionMultipartFile zip(Map<String,byte[]> entries) throws Exception{return new CompanionMultipartFile(UUID.randomUUID()+".zip",CompanionPackageScannerTest.zip(entries));}
  void error(Runnable work,ErrorCode code){assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
  @Test void pureTextPackageUsesRealDurableJobsAndSurvivesRefreshWithoutDuplicates() throws Exception {
    var file=zip(Map.of("新学科教材.txt",CompanionPackageScannerTest.text("A different discipline lesson"),"参赛说明.txt",CompanionPackageScannerTest.text("competition guide")));
    error(()->packages.upload(student,1L,file),ErrorCode.FORBIDDEN);error(()->packages.upload(viewer,1L,file),ErrorCode.FORBIDDEN);
    var uploaded=packages.upload(admin,1L,file);long id=Long.parseLong(uploaded.path("id").asText());
    assertThat(uploaded.path("analysisStatus").asText()).isEqualTo("NOT_PRESENT");assertThat(packages.upload(admin,1L,file).path("id")).isEqualTo(uploaded.path("id"));
    error(()->packages.start(viewer,1L,id,start(List.of())),ErrorCode.FORBIDDEN);
    error(()->packages.start(admin,1L,id,start(List.of("../outside.txt"))),ErrorCode.VALIDATION_ERROR);
    var started=packages.start(editor,1L,id,start(List.of("新学科教材.txt")));
    assertThat(started.path("status").asText()).isEqualTo("PROCESSING");
    long jobs=jdbc.queryForObject("SELECT COUNT(*) FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",Long.class,id);assertThat(jobs).isEqualTo(1);
    packages.start(admin,1L,id,start(List.of("新学科教材.txt")));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",Long.class,id)).isEqualTo(1);
    var row=jdbc.queryForMap("SELECT file_id,job_id FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",id);
    error(()->files.delete(admin,((Number)row.get("file_id")).longValue()),ErrorCode.RESOURCE_IN_USE);
    jdbc.update("UPDATE edu_graph_ingestion_job SET status='WAITING_HUMAN_REVIEW' WHERE id=?",row.get("job_id"));
    assertThat(packages.get(viewer,1L,id).path("status").asText()).isEqualTo("COMPLETED");
    error(()->packages.get(admin,2L,id),ErrorCode.RESOURCE_NOT_FOUND);
    verify(processor,atLeastOnce()).processAsync();
  }
  @Test void unknownDataDoesNotBlockTextAndTaskFailuresRemainVisible() throws Exception {
    var uploaded=packages.upload(admin,1L,zip(Map.of("教材.txt",CompanionPackageScannerTest.text("new material"),"unknown.csv",CompanionPackageScannerTest.text("id,value\n1,2"))));
    long id=Long.parseLong(uploaded.path("id").asText());assertThat(uploaded.path("analysisStatus").asText()).isEqualTo("NEEDS_MAPPING");
    var started=packages.start(admin,1L,id,start(List.of("教材.txt")));assertThat(started.path("status").asText()).isEqualTo("PROCESSING");
    long jobId=jdbc.queryForObject("SELECT job_id FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",Long.class,id);
    jdbc.update("UPDATE edu_graph_ingestion_job SET status='FAILED',error_message='provider unavailable' WHERE id=?",jobId);
    var failed=packages.get(admin,1L,id);assertThat(failed.path("status").asText()).isEqualTo("NEEDS_ATTENTION");assertThat(failed.toString()).contains("provider unavailable");
    packages.start(admin,1L,id,start(List.of("教材.txt")));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",Long.class,id)).isEqualTo(1);
  }
  @Test void perDocumentCreateFailureDoesNotHideOtherSuccessfulJobs() throws Exception {
    var uploaded=packages.upload(admin,1L,zip(Map.of("good教材.txt",CompanionPackageScannerTest.text("good"),"bad教材.txt",CompanionPackageScannerTest.text("bad"))));long id=Long.parseLong(uploaded.path("id").asText());
    doThrow(new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"controlled creation failure")).when(graph).create(any(),anyString(),argThat((GraphIngestionDtos.CreateRequest r)->r.title().startsWith("bad")));
    var result=packages.start(admin,1L,id,start(List.of("good教材.txt","bad教材.txt")));
    assertThat(result.path("status").asText()).isEqualTo("PROCESSING");assertThat(result.toString()).contains("controlled creation failure");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edu_companion_package_document WHERE package_id=? AND job_id IS NOT NULL",Long.class,id)).isEqualTo(1);
  }
  @Test void recognizedTablesAreReallyConfirmedAnalyzedAndKeepOriginalStatistics() throws Exception {
    byte[] canonical=CompanionPackageScanner.canonical(new SmartCompanionEngineTest().raw());
    var uploaded=packages.upload(admin,1L,new CompanionMultipartFile("renamed-data.zip",canonical));long id=Long.parseLong(uploaded.path("id").asText());
    assertThat(uploaded.path("analysisStatus").asText()).isEqualTo("READY");
    String sourceLabel="source "+"x".repeat(443);
    var complete=packages.start(admin,1L,id,new CompanionPackageService.Start(sourceLabel,true,"AUTHORIZED",List.of()));assertThat(complete.path("analysisStatus").asText()).isEqualTo("ANALYZED");assertThat(complete.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT source_label FROM edu_companion_package WHERE id=?",String.class,id)).isEqualTo(sourceLabel);
    assertThat(complete.path("analysisMessage").asText()).contains("前300字");
    var report=imports.report(admin,1L,Long.parseLong(complete.path("importId").asText()));
    assertThat(report.path("records").size()).isEqualTo(794);assertThat(report.path("observations").size()).isEqualTo(720);assertThat(report.path("studentSummaries").size()).isEqualTo(80);assertThat(report.path("groupSummaries").size()).isEqualTo(6);
  }
}
