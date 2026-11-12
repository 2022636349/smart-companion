package com.education.agent.smartcompanion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.education.agent.common.error.*;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.knowledge.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CompanionKnowledgeIntegrationTest {
  @Autowired CompanionKnowledgeService service;
  @Autowired SmartCompanionService imports;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @MockBean KnowledgeServiceClient graph;
  @MockBean AiServiceClient ai;
  final AuthenticatedUser admin=new AuthenticatedUser(9201L,"admin","",UserRole.ADMIN,true,true),
      viewer=new AuthenticatedUser(9202L,"viewer","",UserRole.TEACHER,true,true),
      student=new AuthenticatedUser(9203L,"student","",UserRole.STUDENT,true,true);
  long id;
  CompanionKnowledgeService.Diagnose request=new CompanionKnowledgeService.Diagnose("D","N","I");
  @BeforeEach void setup() throws Exception {
    jdbc.update("DELETE FROM edu_companion_knowledge_state"); jdbc.update("DELETE FROM edu_companion_import");
    if(jdbc.queryForObject("SELECT COUNT(*) FROM edu_user WHERE id=9202",Integer.class)==0) {
      jdbc.update("INSERT INTO edu_user(id,username,password_hash,display_name,role) VALUES(9202,'kgviewer','x','Viewer','TEACHER')");
      jdbc.update("INSERT INTO edu_course_teacher(course_id,teacher_id,member_role) VALUES(1,9202,'VIEWER')");
    }
    var report=json.readTree("""
        {"indicators":[{"id":"I"},{"id":"P"}],
        "observations":[
          {"studentId":"D","nodeId":"N","indicatorId":"I","availableQuestionCount":2,"scoredCount":1,"scorePercent":0,"status":"INSUFFICIENT","evidenceIds":["R1","R2"]},
          {"studentId":"D","nodeId":"N","indicatorId":"P","availableQuestionCount":3,"scoredCount":3,"scorePercent":100,"status":"READY","evidenceIds":["R0"]}],
        "records":[
          {"id":"R1","studentId":"D","nodeId":"N","materialId":"Q1","score":0,"maxScore":3,"answer":"a","process":"p","pointScores":"s"},
          {"id":"R2","studentId":"D","nodeId":"N","materialId":"Q2","score":null,"maxScore":3,"answer":"pending","process":"","pointScores":""},
          {"id":"R0","studentId":"OTHER","nodeId":"N","materialId":"Q0","score":3,"maxScore":3,"answer":"other private answer"}],
        "materials":[{"id":"Q1","type":"题目","body":"question","explanation":"explain","indicatorIds":["I"]},
        {"id":"Q2","type":"题目","body":"pending question","explanation":"","indicatorIds":["I"]}]}
        """);
    var detail=new SmartCompanionEngine(json).obj("summary",Map.of("status","ANALYZED"),"report",report);
    jdbc.update("INSERT INTO edu_companion_import(course_id,sha256,detail_json,source_json,normalized_json) VALUES(1,?,?,?,?)",
        UUID.randomUUID().toString(),detail.toString(),"{}","{}");
    id=jdbc.queryForObject("SELECT MAX(id) FROM edu_companion_import",Long.class);
    when(graph.currentGraphVersion(eq(1L),any())).thenReturn("v1");
    when(graph.ragContext(eq(1L),anyString())).thenAnswer(inv->context(inv.getArgument(1)));
    when(ai.explainSmartCompanion(any())).thenAnswer(inv->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return response();
    });
  }
  InternalGraphNode node(String id) {return new InternalGraphNode(id,id,1,"knowledge_point",null,"definition",List.of(),List.of(),"BASIC",List.of(),List.of(),"APPROVED",1);}
  InternalGraphRagContextResponse context(String kp) {
    return new InternalGraphRagContextResponse(1L,node(kp),kp.equals("kp")?List.of(node("pre")):List.of(),List.of(),List.of(),"v1","READY",List.of(),
        List.of(new InternalGraphSource("s-"+kp,kp,"Textbook","lines 1-2","excerpt",1,1,"APPROVED","ALLOWED"),
            new InternalGraphSource("restricted",kp,"Secret","lines 3-4","forbidden",null,null,"APPROVED","RESTRICTED")));
  }
  JsonNode response() throws Exception {return json.readTree("""
    {"explanation":"Based on direct evidence; check prerequisites separately", "provider":"test-provider","model":"test-model",
    "steps":[{"title":"Check","action":"Review answer","rationale":"direct evidence","knowledge_point_ids":["kp"],"source_ids":["s-kp"],"evidence_ids":["R1"],"material_ids":["Q1"]}],"cautions":[]}
    """);}
  void bind(long revision) {service.putBindings(admin,1L,id,new CompanionKnowledgeService.PutBindings(revision,"v1",List.of(new CompanionKnowledgeService.Binding("I","kp"),new CompanionKnowledgeService.Binding("P","pre"))));}
  void error(Runnable work,ErrorCode code) {assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
  @Test void bindingsPermissionsCasAndStaleGraph() {
    assertThat(service.bindings(admin,1L,id).path("revision").asLong()).isZero();
    error(()->service.bindings(student,1L,id),ErrorCode.FORBIDDEN);
    error(()->service.bindings(admin,2L,id),ErrorCode.RESOURCE_NOT_FOUND);
    error(()->service.putBindings(viewer,1L,id,new CompanionKnowledgeService.PutBindings(0L,"v1",List.of())),ErrorCode.FORBIDDEN);
    bind(0); error(()->bind(0),ErrorCode.CONCURRENT_MODIFICATION);
    error(()->service.putBindings(admin,1L,id,new CompanionKnowledgeService.PutBindings(1L,"v1",List.of(new CompanionKnowledgeService.Binding("unknown","kp")))),ErrorCode.VALIDATION_ERROR);
    when(graph.currentGraphVersion(eq(1L),any())).thenReturn("v2");
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.GRAPH_VERSION_CONFLICT);
    verifyNoInteractions(ai);
  }
  @Test void successfulDraftPreservesStatisticsPrerequisiteEvidenceAndPages() {
    bind(0); String before=imports.report(admin,1L,id).toString();
    var result=service.diagnose(admin,1L,id,request);
    assertThat(result.path("directEvidence").size()).isEqualTo(2);
    assertThat(result.path("directEvidence").get(1).path("score").isNull()).isTrue();
    assertThat(result.path("prerequisites").get(0).path("assessment").asText()).isEqualTo("CHECK_REQUIRED");
    assertThat(result.path("prerequisites").get(0).path("observations").get(0).path("status").asText()).isEqualTo("READY");
    assertThat(result.path("sources").size()).isEqualTo(2);
    assertThat(result.path("sources").get(0).path("pageFrom").asInt()).isEqualTo(1);
    assertThat(service.diagnoses(viewer,1L,id).path("items").get(0).toString()).isEqualTo(result.toString());
    service.diagnose(admin,1L,id,request);
    assertThat(service.diagnoses(admin,1L,id).path("items").size()).isEqualTo(1);
    assertThat(imports.report(admin,1L,id).toString()).isEqualTo(before);
    var captured=org.mockito.ArgumentCaptor.forClass(JsonNode.class); verify(ai,times(2)).explainSmartCompanion(captured.capture());
    assertThat(captured.getValue().toString()).doesNotContain("other private answer","forbidden");
  }
  @Test void modelFailureOrInventedReferenceNeverReplacesExistingDraft() throws Exception {
    bind(0); var first=service.diagnose(admin,1L,id,request);
    when(ai.explainSmartCompanion(any())).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"offline"));
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.AI_SERVICE_UNAVAILABLE);
    var forged=response(); ((com.fasterxml.jackson.databind.node.ArrayNode)forged.path("steps").get(0).path("source_ids")).add("fake");
    doReturn(forged).when(ai).explainSmartCompanion(any());
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.AI_SERVICE_INVALID_RESPONSE);
    assertThat(service.diagnoses(admin,1L,id).path("items").get(0).toString()).isEqualTo(first.toString());
  }
  @Test void changedBindingDuringModelCallRejectsSave() {
    bind(0);
    when(ai.explainSmartCompanion(any())).thenAnswer(inv->{bind(1);return response();});
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.CONCURRENT_MODIFICATION);
    assertThat(service.diagnoses(admin,1L,id).path("items").isEmpty()).isTrue();
  }
  @Test void changedGraphDuringModelCallRejectsSave() {
    bind(0);
    when(ai.explainSmartCompanion(any())).thenAnswer(inv->{when(graph.currentGraphVersion(eq(1L),any())).thenReturn("v2");return response();});
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.GRAPH_VERSION_CONFLICT);
    assertThat(service.diagnoses(admin,1L,id).path("items").isEmpty()).isTrue();
  }
  @Test void missingBindingAndWrongStudentNeverCallModel() {
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.INVALID_RESOURCE_STATE); bind(0);
    error(()->service.diagnose(admin,1L,id,new CompanionKnowledgeService.Diagnose("missing","N","I")),ErrorCode.VALIDATION_ERROR);
    service.putBindings(admin,1L,id,new CompanionKnowledgeService.PutBindings(1L,"v1",List.of()));
    assertThat(service.bindings(admin,1L,id).path("bindings").isEmpty()).isTrue(); verifyNoInteractions(ai);
  }
  @Test void modelCautionsRespectPublicBudgetAndRetainMandatoryNotice() throws Exception {
    bind(0); var model=response();
    var cautions=(com.fasterxml.jackson.databind.node.ArrayNode)model.path("cautions");
    for(int i=0;i<30;i++) cautions.add("model caution "+i);
    doReturn(model).when(ai).explainSmartCompanion(any());
    var result=service.diagnose(admin,1L,id,request);
    assertThat(result.path("cautions").size()).isEqualTo(30);
    assertThat(result.path("cautions").get(0).asText()).contains("教师复核");
  }
  @Test void malformedGraphContextFailsExplicitlyBeforeCallingModel() {
    bind(0);
    when(graph.ragContext(1L,"kp")).thenReturn(new InternalGraphRagContextResponse(1L,node("kp"),null,List.of(),List.of(),"v1","READY",List.of(),List.of()));
    error(()->service.diagnose(admin,1L,id,request),ErrorCode.GRAPH_SERVICE_UNAVAILABLE);
    verifyNoInteractions(ai);
  }
}


