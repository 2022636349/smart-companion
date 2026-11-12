package com.education.agent.smartcompanion;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.education.agent.common.error.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.Mockito.*;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.integration.ai.AiServiceClient;

@SpringBootTest(properties={"companion.experience.enabled=true","companion.experience.course-id=1"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CompanionExperienceIntegrationTest {
  @Autowired CompanionExperienceService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @MockBean KnowledgeServiceClient knowledge;
  @MockBean AiServiceClient ai;
  Map<String,List<Map<String,String>>> raw;
  String token;
  @BeforeEach void seed() throws Exception {
    Assumptions.assumeTrue(Files.exists(SmartCompanionEngineTest.BASE.resolve("source.zip")));
    raw=new SmartCompanionReader().read(Files.readAllBytes(SmartCompanionEngineTest.BASE.resolve("source.zip")),"source.zip");
    jdbc.update("DELETE FROM edu_companion_workspace");jdbc.update("DELETE FROM edu_companion_session");jdbc.update("DELETE FROM edu_companion_import");
    jdbc.update("INSERT INTO edu_companion_import(course_id,sha256,detail_json,source_json,normalized_json) VALUES(1,?,?,?,?)","a".repeat(64),"{\"summary\":{\"status\":\"ANALYZED\"}}",json.writeValueAsString(raw),"{}");
    token=service.createSession().path("token").asText();
  }
  void error(Runnable call,ErrorCode code){assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
  ObjectNode obj(Object...pairs){return service.obj(pairs);}
  long rev(){return service.workspace(token).path("revision").asLong();}
  MockMultipartFile file(Map<String,List<Map<String,String>>> tables) throws Exception {
    var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){for(var table:tables.entrySet()){
      z.putNextEntry(new ZipEntry(table.getKey()+".csv"));var rows=table.getValue();if(rows.isEmpty())continue;var headers=new ArrayList<>(rows.get(0).keySet());var lines=new ArrayList<List<String>>();lines.add(headers);for(var row:rows)lines.add(headers.stream().map(h->row.getOrDefault(h,"")).toList());
      for(var line:lines)z.write((String.join(",",line.stream().map(v->"\""+v.replace("\"","\"\"")+"\"").toList())+"\n").getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }}return new MockMultipartFile("file","data.zip","application/zip",out.toByteArray());
  }
  String upload(Map<String,List<Map<String,String>>> tables) throws Exception {return service.upload(token,file(tables),"测试来源",UUID.randomUUID().toString()).path("summary").path("id").asText();}
  void confirm(String id){service.confirm(token,id,obj("expectedRevision",rev(),"acknowledgeWarnings",true));}
  @Test void actualOfficialSeedPaginationAndFullStatistics() {
    assertThat(service.workspace(token).path("counts").path("recordCount").asInt()).isEqualTo(794);
    var page=service.data(token,"records",Map.of("page","2","pageSize","10"));assertThat(page.path("items").size()).isEqualTo(10);assertThat(page.path("total").asInt()).isEqualTo(794);
    var report=service.report(service.state(false));var stats=service.engine.stats(SmartCompanionEngine.list(report.path("records")));assertThat(stats.path("earnedScore").asInt()).isEqualTo(741);assertThat(stats.path("maxScore").asInt()).isEqualTo(1301);
    assertThat(service.data(token,"students",Map.of("pageSize","7")).path("total").asInt()).isEqualTo(40);
    assertThat(service.data(token,"records",Map.of("page","99999","pageSize","100")).path("page").asInt()).isEqualTo(8);
    error(()->service.data(token,"records",Map.of("pageSize","999")),ErrorCode.VALIDATION_ERROR);
  }
  @Test void studentEntrySelectorReturnsOnlyIdentityMetadata() {
    var selector=service.data(token,"students",Map.of("selector","true","pageSize","100"));
    assertThat(selector.path("total").asInt()).isEqualTo(40);
    for(var row:selector.path("items")) {
      var fields=new HashSet<String>();row.fieldNames().forEachRemaining(fields::add);
      assertThat(fields).containsExactlyInAnyOrder("id","classId","grade");
    }
    var diagnostic=service.data(token,"students",Map.of("pageSize","1"));
    assertThat(diagnostic.path("items").get(0).has("observations")).isTrue();
    assertThat(diagnostic.path("items").get(0).has("summary")).isTrue();
  }
  @Test void sharedSourcesRetryAndSeparateDeletion() throws Exception {
    var f=file(raw);String key=UUID.randomUUID().toString();var batch=service.upload(token,f,"重复来源",key);String id=batch.path("summary").path("id").asText();assertThat(service.upload(token,f,"重复来源",key).path("summary").path("id").asText()).isEqualTo(id);confirm(id);
    assertThat(service.workspace(token).path("counts").path("recordCount").asInt()).isEqualTo(794);
    String seed=service.state(false).path("batches").get(0).path("id").asText();
    var preview=service.deletePreview(token,seed,"ALL");assertThat(preview.path("canDelete").asBoolean()).isTrue();assertThat(preview.path("removedRecords").asInt()).isZero();
    service.delete(token,seed,"ALL",rev());assertThat(service.workspace(token).path("counts").path("recordCount").asInt()).isEqualTo(794);
    error(()->service.delete(token,id,"QUESTION_BANK",rev()),ErrorCode.RESOURCE_IN_USE);
    service.delete(token,id,"LEARNING",rev());assertThat(service.workspace(token).path("counts").path("recordCount").asInt()).isZero();
    service.delete(token,id,"QUESTION_BANK",rev());assertThat(service.workspace(token).path("counts").path("materialCount").asInt()).isZero();
    long current=rev();service.delete(token,id,"ALL",0);assertThat(rev()).isEqualTo(current);
  }
  @Test void sameNumberNewVersionDoesNotReinterpretOldRecords() throws Exception {
    var material=new LinkedHashMap<>(raw.get("题目与材料").get(0));String original=material.get("材料编号");material.put("题干或内容",material.get("题干或内容")+"（新版本）");
    var before=service.report(service.state(false));var oldIds=SmartCompanionEngine.list(before.path("records")).stream().filter(r->r.path("materialId").asText().startsWith(original+"~")).map(r->r.path("materialId").asText()).toList();
    String id=upload(Map.of("题目与材料",List.of(material)));confirm(id);
    var after=service.report(service.state(false));assertThat(after.path("materials").size()).isEqualTo(before.path("materials").size()+1);
    assertThat(after.path("studentSummaries")).isEqualTo(before.path("studentSummaries"));
    assertThat(after.path("observations")).isEqualTo(before.path("observations"));
    assertThat(SmartCompanionEngine.list(after.path("records")).stream().filter(r->r.path("materialId").asText().startsWith(original+"~")).map(r->r.path("materialId").asText()).toList()).isEqualTo(oldIds);
    assertThat(service.deletePreview(token,id,"QUESTION_BANK").path("canDelete").asBoolean()).isTrue();
  }
  @Test void recordsOnlyConflictsPendingAndStaleRevision() throws Exception {
    var record=new LinkedHashMap<>(raw.get("作答记录").get(0));record.put("学生最终作答",record.get("学生最终作答")+"冲突");String id=upload(Map.of("作答记录",List.of(record)));long stale=rev();confirm(id);
    assertThat(service.report(service.state(false)).path("quality").path("conflictCount").asInt()).isGreaterThan(0);
    error(()->service.delete(token,id,"ALL",stale),ErrorCode.CONCURRENT_MODIFICATION);
    var unknown=new LinkedHashMap<>(record);unknown.put("材料编号","unknown");unknown.put("作答事件编号",unknown.get("学生编号")+"-"+unknown.get("节点编号")+"-unknown");unknown.put("记录编号","unknown-record");String pending=upload(Map.of("作答记录",List.of(unknown)));confirm(pending);
    assertThat(service.report(service.state(false)).path("quality").path("invalidCount").asInt()).isGreaterThan(5);
  }
  @Test void studentScopePlansFeedbackAndNoTeacherCredentials() throws Exception {
    var student=raw.get("模拟学生").get(0).get("学生编号");var node=raw.get("测验节点").get(0).get("节点编号");var other=raw.get("模拟学生").get(1).get("学生编号");
    var p=service.getPlan(token,student,node);var input=obj("expectedRevision",0,"expectedDataRevision",rev(),"status","CONFIRMED","steps",p.path("steps"));service.putPlan(token,student,node,input);
    error(()->service.putPlan(token,student,node,input),ErrorCode.CONCURRENT_MODIFICATION);
    service.switchSession(token,obj("view","STUDENT","studentId",student));
    assertThat(service.data(token,"students",Map.of()).path("items").get(0).has("summary")).isFalse();
    assertThat(SmartCompanionEngine.list(service.data(token,"records",Map.of("pageSize","100")).path("items"))).allMatch(r->r.path("studentId").asText().equals(student));
    for(var m:service.data(token,"materials",Map.of("pageSize","100")).path("items"))assertThat(m.has("answer")).isFalse();
    error(()->service.studentDetail(token,other,node),ErrorCode.FORBIDDEN);error(()->service.export(token),ErrorCode.FORBIDDEN);error(()->service.confirm(token,"missing",obj()),ErrorCode.FORBIDDEN);
    long before=rev();service.feedback(token,obj("studentId",student,"nodeId",node,"taskId",p.path("steps").get(0).path("id"),"type","COMPLETED"));assertThat(rev()).isEqualTo(before);
    mvc.perform(get("/api/companion-experience/workspace")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/classes").header("X-Companion-Session",token)).andExpect(status().isUnauthorized());
  }
  @Test void recordsOnlyRetainsExplicitStudentMetadataAfterOriginalSourceDeletion() throws Exception {
    String seed=service.state(false).path("batches").get(0).path("id").asText();
    String id=upload(Map.of("作答记录",raw.get("作答记录")));confirm(id);
    service.delete(token,seed,"LEARNING",rev());
    assertThat(service.workspace(token).path("counts").path("recordCount").asInt()).isEqualTo(794);
    assertThat(service.workspace(token).path("counts").path("studentCount").asInt()).isEqualTo(40);
  }
  @Test void disabledAndExpiredSessionAreRejected() {
    var disabled=new CompanionExperienceService(jdbc,json,mock(org.springframework.transaction.PlatformTransactionManager.class),knowledge,ai,false,1);
    error(disabled::createSession,ErrorCode.RESOURCE_NOT_FOUND);
    jdbc.update("UPDATE edu_companion_session SET expires_at=0");error(()->service.workspace(token),ErrorCode.UNAUTHENTICATED);
  }
  @Test void assistantValidatesReferencesAndDiscardsStaleResults() {
    var r=service.report(service.state(false));var o=r.path("observations").get(0);String iid=o.path("indicatorId").asText();
    var card=obj("id",iid,"knowledgePointId","kp","version","approved-v1","name","已审核知识","summary","真实服务由图谱提供；此处仅测试协议","sources",List.of(obj("id","s1","title","来源","location","第1页","excerpt","摘要")));
    when(knowledge.learningContent(1L,iid)).thenReturn(card);
    var response=obj("provider","test-fixture","model","test-fixture","explanation","先核查证据再学习。","cautions",List.of(),"steps",List.of(obj("title","核查","action","阅读知识说明","rationale","证据有限","knowledge_point_ids",List.of("kp"),"source_ids",List.of("s1"),"evidence_ids",List.of(),"material_ids",List.of())));
    when(ai.explainSmartCompanion(any())).thenAnswer(call->{JsonNode request=call.getArgument(0);assertThat(request.path("audience").asText()).isEqualTo(service.session(token,false).get("view_name"));assertThat(request.path("purpose").asText()).isEqualTo("HINT");assertThat(request.path("evidence").isEmpty()).isFalse();for(var evidence:request.path("evidence")){var original=SmartCompanionEngine.list(r.path("records")).stream().filter(e->e.path("id").equals(evidence.path("record_id"))).findFirst().orElseThrow();assertThat(evidence.path("answer")).isEqualTo(original.path("answer"));assertThat(evidence.path("process")).isEqualTo(original.path("process"));}for(var material:request.path("materials")){assertThat(material.has("answer")).isFalse();assertThat(material.has("explanation")).isFalse();}return response;});
    var input=obj("studentId",o.path("studentId"),"nodeId",o.path("nodeId"),"indicatorId",iid,"expectedRevision",rev(),"purpose","HINT");
    assertThat(service.assistant(token,input).path("provider").asText()).isEqualTo("test-fixture");
    service.switchSession(token,obj("view","STUDENT","studentId",o.path("studentId")));input.put("audience","TEACHER");assertThat(service.assistant(token,input).path("provider").asText()).isEqualTo("test-fixture");
    service.switchSession(token,obj("view","TEACHER"));
    doAnswer(call->{var state=service.state(false);service.save(state,true);return response;}).when(ai).explainSmartCompanion(any());
    error(()->service.assistant(token,input),ErrorCode.CONCURRENT_MODIFICATION);
    when(knowledge.learningContent(1L,iid)).thenThrow(new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"不可用"));
    input.put("expectedRevision",rev());error(()->service.assistant(token,input),ErrorCode.GRAPH_SERVICE_UNAVAILABLE);
  }
  @Test void exportEnvelopeHasFullReportPlansAndReviewedHttpContent() {
    when(knowledge.learningContent(eq(1L),anyString())).thenAnswer(call->{String id=call.getArgument(1);return obj("id",id,"knowledgePointId","kp-"+id,"version","reviewed-v1","reviewStatus","APPROVED","content","协议测试内容","sources",List.of(obj("id","source-"+id,"title","核验来源","location","第1页","excerpt","已审摘录")));});
    var export=service.export(token);
    assertThat(export.has("generatedAt")).isTrue();assertThat(export.path("report").path("records").size()).isEqualTo(794);
    assertThat(export.path("plans").size()).isEqualTo(80);assertThat(export.path("knowledge").path("cards").size()).isEqualTo(9);assertThat(export.path("sources").size()).isEqualTo(9);
    assertThat(export.path("fields").path("definitions").isEmpty()).isFalse();
    assertThat(service.workspace(token).path("knowledgeVersion").asText()).isEqualTo("reviewed-v1");
  }
  @Test void persistedPreNodeBindingSnapshotRebuildIsIdempotentAndKeepsOriginalScope() throws Exception {
    var state=service.state(false);
    for(var n:state.path("baseline").path("测验节点"))((ObjectNode)n).remove("_availableVersions");
    for(var b:state.path("batches"))for(var n:b.path("source").path("测验节点"))((ObjectNode)n).remove("_availableVersions");
    service.save(state,false);
    String persisted=jdbc.queryForObject("SELECT state_json FROM edu_companion_workspace WHERE course_id=1",String.class);
    var before=service.report(service.state(false));
    assertThat(before.path("records").size()).isEqualTo(794);
    assertThat(service.engine.stats(SmartCompanionEngine.list(before.path("records"))).path("earnedScore").asInt()).isEqualTo(741);
    assertThat(service.report(service.state(false))).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT state_json FROM edu_companion_workspace WHERE course_id=1",String.class)).isEqualTo(persisted);
    assertThat(rev()).isEqualTo(1);
    var material=new LinkedHashMap<>(raw.get("题目与材料").get(0));material.put("题干或内容",material.get("题干或内容")+"兼容性新版本");
    String id=upload(Map.of("题目与材料",List.of(material)));confirm(id);
    var after=service.report(service.state(false));assertThat(after.path("records").size()).isEqualTo(794);
    assertThat(after.path("studentSummaries")).isEqualTo(before.path("studentSummaries"));
    assertThat(after.path("observations")).isEqualTo(before.path("observations"));
  }
  @Test void recordsOnlyHigherRevisionUsesExistingEventVersionAfterAnotherQuestionVersion() throws Exception {
    var original=new LinkedHashMap<>(raw.get("作答记录").get(0));
    String event=original.get("作答事件编号"),mid=original.get("材料编号"),nid=original.get("节点编号");
    var before=service.report(service.state(false));
    var old=SmartCompanionEngine.list(before.path("records")).stream().filter(r->event.equals(r.path("eventId").asText())).findFirst().orElseThrow();
    var changed=new LinkedHashMap<>(raw.get("题目与材料").stream().filter(m->mid.equals(m.get("材料编号"))).findFirst().orElseThrow());changed.put("题干或内容",changed.get("题干或内容")+"第二版");
    var otherNode=new LinkedHashMap<>(raw.get("测验节点").stream().filter(n->nid.equals(n.get("节点编号"))).findFirst().orElseThrow());otherNode.put("节点编号","NEW-NODE");otherNode.put("可用题目编号",mid);
    var otherRecord=new LinkedHashMap<>(original);otherRecord.put("记录编号","OTHER-VERSION");otherRecord.put("节点编号","NEW-NODE");otherRecord.put("作答事件编号",original.get("学生编号")+"-NEW-NODE-"+mid);
    String second=upload(Map.of("题目与材料",List.of(changed),"测验节点",List.of(otherNode),"作答记录",List.of(otherRecord)));confirm(second);
    var revision=new LinkedHashMap<>(original);revision.put("记录编号","REVISION-ONLY");revision.put("修订版本",Integer.toString(old.path("revision").asInt()+1));
    String incremental=upload(Map.of("作答记录",List.of(revision)));
    var preview=service.batchDetail(token,incremental).path("preview");
    assertThat(preview.path("counts").path("pendingRecords").asInt()).isZero();
    assertThat(preview.path("issues").isEmpty()).isTrue();confirm(incremental);
    var after=service.report(service.state(false));var selected=SmartCompanionEngine.list(after.path("records")).stream().filter(r->event.equals(r.path("eventId").asText())).findFirst().orElseThrow();
    assertThat(selected.path("id").asText()).isEqualTo("REVISION-ONLY");assertThat(selected.path("materialId")).isEqualTo(old.path("materialId"));
    assertThat(SmartCompanionEngine.list(after.path("nodes")).stream().filter(n->nid.equals(n.path("id").asText())).findFirst().orElseThrow()).isEqualTo(SmartCompanionEngine.list(before.path("nodes")).stream().filter(n->nid.equals(n.path("id").asText())).findFirst().orElseThrow());
  }
  @Test void cleanBatchPreviewDoesNotRepeatHistoricalInvalidAndSupersededWarnings() throws Exception {
    var material=new LinkedHashMap<>(raw.get("题目与材料").get(0));material.put("材料编号","CLEAN-NEW");
    String id=upload(Map.of("题目与材料",List.of(material)));var preview=service.batchDetail(token,id).path("preview");
    assertThat(preview.path("counts").path("pendingRecords").asInt()).isZero();assertThat(preview.path("issues").isEmpty()).isTrue();
    assertThat(service.report(service.state(false)).path("quality").path("invalidCount").asInt()).isEqualTo(5);
  }
  @Test void overviewReturnsWeightedClassIndicatorAggregatesWithoutStudentObservations() {
    var report=service.report(service.state(false));var result=service.overview(token,Map.of());
    assertThat(result.path("indicatorSummaries").size()).isEqualTo(36);
    for(var aggregate:result.path("indicatorSummaries")){
      assertThat(aggregate.has("studentId")).isFalse();assertThat(aggregate.has("evidenceIds")).isFalse();assertThat(aggregate.has("status")).isFalse();
      var memberIds=new HashSet<String>();for(var student:report.path("students"))if(student.path("classId").equals(aggregate.path("classId")))memberIds.add(student.path("id").asText());
      var observations=SmartCompanionEngine.list(report.path("observations")).stream().filter(o->memberIds.contains(o.path("studentId").asText())&&o.path("nodeId").equals(aggregate.path("nodeId"))&&o.path("indicatorId").equals(aggregate.path("indicatorId"))).toList();
      assertThat(aggregate.path("studentCount").asInt()).isEqualTo(memberIds.size());assertThat(aggregate.path("coveredStudentCount").asLong()).isEqualTo(observations.stream().filter(o->o.path("scoredCount").asInt()>0).count());
      var evidenceIds=new HashSet<String>();observations.forEach(o->o.path("evidenceIds").forEach(id->evidenceIds.add(id.asText())));
      var stats=service.engine.stats(SmartCompanionEngine.list(report.path("records")).stream().filter(r->evidenceIds.contains(r.path("id").asText())).toList());
      assertThat(aggregate.path("scoredCount").asInt()).isEqualTo(stats.path("scoredCount").asInt());
      if(stats.path("scorePercent").isNull())assertThat(aggregate.path("scorePercent").isNull()).isTrue();else assertThat(aggregate.path("scorePercent").asDouble()).isCloseTo(stats.path("scorePercent").asDouble(),within(0.0000001));
      for(var pair:Map.of("needsPracticeCount","NEEDS_PRACTICE","insufficientCount","INSUFFICIENT","noEvidenceCount","NO_EVIDENCE","notAssessedCount","NOT_ASSESSED").entrySet())assertThat(aggregate.path(pair.getKey()).asLong()).isEqualTo(observations.stream().filter(o->pair.getValue().equals(o.path("status").asText())).count());
    }
    var first=result.path("indicatorSummaries").get(0);var filtered=service.overview(token,Map.of("nodeId",first.path("nodeId").asText(),"classId",first.path("classId").asText()));
    assertThat(filtered.path("indicatorSummaries").size()).isEqualTo(9);
    var id=report.path("students").get(0).path("id").asText();service.switchSession(token,obj("view","STUDENT","studentId",id));
    for(var aggregate:service.overview(token,Map.of()).path("indicatorSummaries"))assertThat(aggregate.path("studentCount").asInt()).isEqualTo(1);
  }
  @Test void batchListDefaultsToVisibleStatesAndSupportsRemovedOrAllHistory() throws Exception {
    var material=new LinkedHashMap<>(raw.get("题目与材料").get(0));material.put("材料编号","REMOVE-ONLY");
    String removed=upload(Map.of("题目与材料",List.of(material)));confirm(removed);service.delete(token,removed,"ALL",rev());
    String preview=upload(Map.of("题目与材料",List.of(material)));
    var state=service.state(false);var blocked=service.getBatch(state,preview).deepCopy();blocked.put("id","blocked-test");blocked.put("status","BLOCKED");((ArrayNode)state.path("batches")).add(blocked);service.save(state,false);
    var visible=service.data(token,"batches",Map.of());assertThat(visible.path("total").asInt()).isEqualTo(3);
    assertThat(SmartCompanionEngine.list(visible.path("items"))).noneMatch(b->"REMOVED".equals(b.path("status").asText()));
    var all=service.data(token,"batches",Map.of("status","ALL"));assertThat(all.path("total").asInt()).isEqualTo(4);
    var history=service.data(token,"batches",Map.of("status","REMOVED"));assertThat(history.path("total").asInt()).isEqualTo(1);assertThat(history.path("items").get(0).path("id").asText()).isEqualTo(removed);
    assertThat(service.data(token,"batches",Map.of("status","CONFIRMED")).path("total").asInt()).isEqualTo(1);
  }
  CompanionTeachingPlanService teaching(){return new CompanionTeachingPlanService(service);}
  ObjectNode targetInput(String scope){var r=service.report(service.state(false));var st=r.path("students").get(0);return obj("scopeType",scope,"studentId",scope.equals("STUDENT")?st.path("id"):null,"classId",st.path("classId"),"nodeId",r.path("nodes").get(0).path("id"));}
  void teachingCards() throws Exception {var catalog=json.readTree(Files.readString(Path.of("C:/Project/EducationAgent/knowledge-service/data/smart-companion/learning-content-v1.json")));for(var card:catalog.path("cards"))when(knowledge.learningContent(1L,card.path("id").asText())).thenReturn(card);}
  ObjectNode generation(String scope) throws Exception {teachingCards();var q=targetInput(scope);var ctx=teaching().get(token,q);String iid="";for(var i:ctx.path("summary").path("indicatorSummaries"))if(i.path("availableQuestionCount").asInt()>0){iid=i.path("indicatorId").asText();break;}q.set("indicatorIds",json.valueToTree(List.of(iid)));q.set("constraints",obj("objective","核查并练习","durationMinutes",45,"maxSteps",6));q.put("expectedRevision",rev());q.put("expectedPlanRevision",0);return q;}
  JsonNode validPlanning(JsonNode req){var steps=json.createArrayNode();for(var f:req.path("focuses"))steps.add(obj("title","核查与练习","action","先核对概念，再尝试练习","rationale","按当前证据补充观察","indicator_id",f.path("indicator_id"),"duration_minutes",10,"knowledge_point_ids",List.of(f.path("knowledge_point_id").asText()),"source_ids",List.of(),"evidence_ids",List.of(),"material_ids",List.of(),"hints",List.of("先写已知条件")));return obj("provider","test-fixture","model","controlled","explanation","教师审核后使用","steps",steps,"cautions",List.of("前置关系仅供核查"));}
  ObjectNode saveInput(ObjectNode target,JsonNode proposal){var q=target.deepCopy();q.put("expectedRevision",proposal.path("basePlanRevision").asLong());q.put("expectedDataRevision",proposal.path("dataRevision").asLong());q.put("status","CONFIRMED");q.set("steps",proposal.path("steps"));q.set("constraints",proposal.path("constraints"));return q;}
  @Test void teachingSelectorFiltersBeforeProjectionAndPaginationForBothRoles(){var r=service.report(service.state(false));for(String cid:SmartCompanionEngine.list(r.path("students")).stream().map(st->st.path("classId").asText()).distinct().toList()){long n=SmartCompanionEngine.list(r.path("students")).stream().filter(st->cid.equals(st.path("classId").asText())).count();if(n==0)continue;var page=service.data(token,"students",Map.of("selector","true","classId",cid,"pageSize","7","page","2"));assertThat(page.path("total").asLong()).isEqualTo(n);for(var st:page.path("items"))assertThat(st.path("classId").asText()).isEqualTo(cid);}
    String sid=r.path("students").get(30).path("id").asText();var one=service.data(token,"students",Map.of("selector","true","studentId",sid));assertThat(one.path("total").asInt()).isEqualTo(1);assertThat(one.path("items").get(0).path("id").asText()).isEqualTo(sid);service.switchSession(token,obj("view","STUDENT","studentId",sid));assertThat(service.data(token,"students",Map.of("selector","true","pageSize","100")).path("total").asInt()).isEqualTo(40);
  }
  @Test void teachingRejectsInvalidTargetsAndStudentAuthority() throws Exception {var q=generation("STUDENT");q.put("classId","FAKE");error(()->teaching().get(token,q),ErrorCode.VALIDATION_ERROR);q.put("scopeType","CLASS");q.remove("studentId");error(()->teaching().get(token,q),ErrorCode.RESOURCE_NOT_FOUND);q.put("nodeId","");error(()->teaching().get(token,q),ErrorCode.RESOURCE_NOT_FOUND);var valid=generation("STUDENT");service.switchSession(token,obj("view","STUDENT","studentId",valid.path("studentId")));error(()->teaching().get(token,valid),ErrorCode.FORBIDDEN);error(()->teaching().generate(token,valid),ErrorCode.FORBIDDEN);}
  @Test void teachingGenerationIsDraftWeightedAndContainsNoAnswerRows() throws Exception {var q=generation("CLASS");doAnswer(call->{JsonNode req=call.getArgument(0);var r=service.report(service.state(false));var t=teaching().target(r,q);var stats=service.engine.stats(teaching().records(r,t));assertThat(req.path("summary").path("earned_score")).isEqualTo(stats.path("earnedScore"));assertThat(req.path("summary").path("student_count").asInt()).isEqualTo(t.members().size());assertThat(req.toString()).doesNotContain("point_scores","\"answer\"","\"process\"","\"student_id\"");assertThat(req.path("focuses").get(0).path("status_counts").size()).isEqualTo(6);assertThat(req.path("focuses").get(0).path("name").isTextual()).isTrue();assertThat(req.path("focuses").get(0).path("name").asText()).isNotBlank();return validPlanning(req);}).when(ai).planSmartCompanion(any());var result=teaching().generate(token,q);assertThat(result.path("status").asText()).isEqualTo("DRAFT");assertThat(teaching().get(token,q).path("plan").path("revision").asInt()).isZero();assertThat(result.path("steps").get(0).path("id").asText()).startsWith("plan-");verify(ai,times(1)).planSmartCompanion(any());}
  @Test void teachingScopesPersistIndependentlyAndClassFeedbackRequiresCurrentConfirmation() throws Exception {doAnswer(call->validPlanning(call.getArgument(0))).when(ai).planSmartCompanion(any());var cq=generation("CLASS");var cp=teaching().generate(token,cq);var saved=teaching().put(token,saveInput(cq,cp));assertThat(saved.path("revision").asInt()).isEqualTo(1);var sq=generation("STUDENT");var sp=teaching().generate(token,sq);teaching().put(token,saveInput(sq,sp));assertThat(service.getPlan(token,sq.path("studentId").asText(),sq.path("nodeId").asText()).path("revision").asInt()).isEqualTo(1);long revision=rev();String sid=sq.path("studentId").asText(),nid=sq.path("nodeId").asText();service.switchSession(token,obj("view","STUDENT","studentId",sid));assertThat(service.studentDetail(token,sid,nid).path("classPlan").path("revision").asInt()).isEqualTo(1);var feedback=obj("studentId",sid,"nodeId",nid,"planScope","CLASS","taskId",saved.path("steps").get(0).path("id"),"type","READ");service.feedback(token,feedback);assertThat(rev()).isEqualTo(revision);var state=service.state(false);service.save(state,true);assertThat(service.studentDetail(token,sid,nid).path("classPlan").isNull()).isTrue();error(()->service.feedback(token,feedback),ErrorCode.VALIDATION_ERROR);assertThat(service.state(false).path("classPlans").elements().next().path("status").asText()).isEqualTo("NEEDS_REVIEW");}
  @Test void teachingRejectsInvalidModelRefsDurationsAndDataRaces() throws Exception {var q=generation("STUDENT");doAnswer(call->{var out=(ObjectNode)validPlanning(call.getArgument(0));((ObjectNode)out.path("steps").get(0)).put("duration_minutes",46);return out;}).when(ai).planSmartCompanion(any());error(()->teaching().generate(token,q),ErrorCode.AI_SERVICE_INVALID_RESPONSE);doAnswer(call->{var out=(ObjectNode)validPlanning(call.getArgument(0));((ObjectNode)out.path("steps").get(0)).set("evidence_ids",json.valueToTree(List.of("OTHER-STUDENT")));return out;}).when(ai).planSmartCompanion(any());error(()->teaching().generate(token,q),ErrorCode.AI_SERVICE_INVALID_RESPONSE);doAnswer(call->{var state=service.state(false);service.save(state,true);return validPlanning(call.getArgument(0));}).when(ai).planSmartCompanion(any());error(()->teaching().generate(token,q),ErrorCode.CONCURRENT_MODIFICATION);}
  @Test void teachingRejectsStaleSaveAndForgedReferences() throws Exception {var q=generation("STUDENT");doAnswer(call->validPlanning(call.getArgument(0))).when(ai).planSmartCompanion(any());var proposal=teaching().generate(token,q);var save=saveInput(q,proposal);var invalid=save.deepCopy();((ObjectNode)invalid.path("steps").get(0)).set("materialIds",json.valueToTree(List.of("FAKE")));error(()->teaching().put(token,invalid),ErrorCode.VALIDATION_ERROR);teaching().put(token,save);error(()->teaching().put(token,save),ErrorCode.CONCURRENT_MODIFICATION);error(()->teaching().generate(token,q),ErrorCode.CONCURRENT_MODIFICATION);}
  @Test void teachingConfirmedPlanGrantsOnlyOwnCurrentSafeMaterials() throws Exception {
    var r=service.report(service.state(false));JsonNode selectedStudent=null,selectedMaterial=null;String iid="",nid="";
    outer:for(var st:r.path("students")){var projected=service.projection(r,Map.of("view_name","STUDENT","student_id",st.path("id").asText()));for(var node:r.path("nodes"))for(var m:r.path("materials"))if("题目".equals(m.path("type").asText())&&CompanionKnowledgeService.ids(node.path("availableMaterialIds"),null).contains(m.path("id").asText())&&service.find(projected,"materials",m.path("id").asText())==null&&!m.path("indicatorIds").isEmpty()){selectedStudent=st;selectedMaterial=m;iid=m.path("indicatorIds").get(0).asText();nid=node.path("id").asText();break outer;}}
    assertThat(selectedStudent).as("fixture includes an unattempted non-recommended question").isNotNull();String sid=selectedStudent.path("id").asText(),mid=selectedMaterial.path("id").asText();var target=obj("scopeType","CLASS","classId",selectedStudent.path("classId"),"nodeId",nid,"expectedRevision",0,"expectedDataRevision",rev(),"status","CONFIRMED","constraints",CompanionTeachingPlanService.defaults(service),"steps",List.of(obj("id","class-extra","title","补充练习","action","核查条件再练习","reason","补充观察","indicatorId",iid,"materialIds",List.of(mid),"evidenceIds",List.of(),"hints",List.of())));teaching().put(token,target);service.switchSession(token,obj("view","STUDENT","studentId",sid));var m=service.material(token,mid).path("material");assertThat(m.has("answer")).isFalse();assertThat(m.has("explanation")).isFalse();assertThat(m.has("scoringPoints")).isFalse();var state=service.state(false);service.save(state,true);error(()->service.material(token,mid),ErrorCode.RESOURCE_NOT_FOUND);
  }
  @Test void teachingModelCallRunsOutsideTransactionAndRejectsPlanOrKnowledgeRaces() throws Exception {
    var q=generation("STUDENT");doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var save=q.deepCopy();save.put("expectedRevision",0);save.put("expectedDataRevision",rev());save.put("status","DRAFT");save.set("steps",json.createArrayNode());teaching().put(token,save);return validPlanning(call.getArgument(0));}).when(ai).planSmartCompanion(any());error(()->teaching().generate(token,q),ErrorCode.CONCURRENT_MODIFICATION);
    q.put("expectedPlanRevision",1);doAnswer(call->{var out=validPlanning(call.getArgument(0));String iid=q.path("indicatorIds").get(0).asText();var changed=(ObjectNode)knowledge.learningContent(1L,iid).deepCopy();changed.put("version","changed");when(knowledge.learningContent(1L,iid)).thenReturn(changed);return out;}).when(ai).planSmartCompanion(any());error(()->teaching().generate(token,q),ErrorCode.CONCURRENT_MODIFICATION);
  }
  @Test void teachingMaterialProjectionSeparatesClassDraftAndPersonalPlans(){
    var r=obj("students",List.of(obj("id","s1","classId","c1"),obj("id","s2","classId","c2")),"nodes",List.of(obj("id","n","availableMaterialIds",List.of("m"))),"materials",List.of(obj("id","m","type","题目","indicatorIds",List.of("i"),"body","题干","answer","秘密","explanation","参考","scoringPoints","评分")),"records",List.of(),"recommendations",List.of(),"observations",List.of(),"studentSummaries",List.of());
    var plan=obj("status","CONFIRMED","dataRevision",2,"steps",List.of(obj("indicatorId","i","materialIds",List.of("m"))));var state=obj("revision",2,"plans",obj(),"classPlans",obj("c1:n",plan));var own=Map.<String,Object>of("view_name","STUDENT","student_id","s1");var other=Map.<String,Object>of("view_name","STUDENT","student_id","s2");
    assertThat(service.projection(r,own,state).path("materials").size()).isEqualTo(1);assertThat(service.projection(r,other,state).path("materials").isEmpty()).isTrue();((ObjectNode)state.path("classPlans").path("c1:n")).put("status","DRAFT");assertThat(service.projection(r,own,state).path("materials").isEmpty()).isTrue();((ObjectNode)state.path("plans")).set("s1:n",plan.deepCopy().put("status","CONFIRMED"));assertThat(service.projection(r,own,state).path("materials").size()).isEqualTo(1);state.put("revision",3);assertThat(service.projection(r,own,state).path("materials").isEmpty()).isTrue();
  }
  @Test void teachingTwoFocusDraftAllowsCrossFocusMaterialsAndStillRejectsOtherNode() throws Exception {
    var q=generation("STUDENT");var ctx=teaching().get(token,q);var ids=SmartCompanionEngine.list(ctx.path("summary").path("indicatorSummaries")).stream().filter(i->i.path("availableQuestionCount").asInt()>0).limit(2).map(i->i.path("indicatorId").asText()).toList();assertThat(ids).hasSize(2);q.set("indicatorIds",json.valueToTree(ids));
    doAnswer(call->{JsonNode req=call.getArgument(0);var out=(ObjectNode)validPlanning(req);var r=service.report(service.state(false));var target=teaching().target(r,q);var first=CompanionKnowledgeService.ids(json.valueToTree(teaching().linked(r,target,ids.get(0))),"id");String cross=teaching().linked(r,target,ids.get(1)).stream().map(m->m.path("id").asText()).filter(id->!first.contains(id)).findFirst().orElseThrow();((ObjectNode)out.path("steps").get(0)).set("material_ids",json.valueToTree(List.of(cross)));return out;}).when(ai).planSmartCompanion(any());var proposal=teaching().generate(token,q);var saved=teaching().put(token,saveInput(q,proposal));assertThat(saved.path("steps").size()).isEqualTo(2);
    var r=service.report(service.state(false));var target=teaching().target(r,q);var allowed=new HashSet<String>();ids.forEach(i->teaching().linked(r,target,i).forEach(m->allowed.add(m.path("id").asText())));String forbidden=SmartCompanionEngine.list(r.path("materials")).stream().filter(m->"题目".equals(m.path("type").asText())&&!allowed.contains(m.path("id").asText())).findFirst().orElseThrow().path("id").asText();var bad=saveInput(q,proposal);bad.put("expectedRevision",saved.path("revision").asLong());((ObjectNode)bad.path("steps").get(0)).set("materialIds",json.valueToTree(List.of(forbidden)));assertThatThrownBy(()->teaching().put(token,bad)).isInstanceOfSatisfying(BusinessException.class,e->{assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);assertThat(e.getMessage()).contains("materialIds");});
  }
  @Test void teachingMaterialUnionNeverAdmitsUnselectedIndicatorOrQuestionVersion(){
    var r=obj("students",List.of(obj("id","s","classId","c")),"nodes",List.of(obj("id","n","availableMaterialIds",List.of("m1","m2","m3"))),"indicators",List.of(obj("id","i1"),obj("id","i2"),obj("id","i3")),"materials",List.of(obj("id","m1","type","题目","indicatorIds",List.of("i1")),obj("id","m2","type","题目","indicatorIds",List.of("i2")),obj("id","m3","type","题目","indicatorIds",List.of("i3")),obj("id","old-version","type","题目","indicatorIds",List.of("i2"))),"records",List.of());var target=teaching().target(r,obj("scopeType","STUDENT","studentId","s","nodeId","n"));
    var step1=obj("id","1","title","综合","action","学习","reason","","indicatorId","i1","materialIds",List.of("m2"),"evidenceIds",List.of(),"hints",List.of());var step2=step1.deepCopy().put("id","2").put("indicatorId","i2");var steps=json.createArrayNode().add(step1).add(step2);teaching().validate(steps,CompanionTeachingPlanService.defaults(service),r,target,Map.of(),ErrorCode.VALIDATION_ERROR);for(String forbidden:List.of("m3","old-version")){step1.set("materialIds",json.valueToTree(List.of(forbidden)));error(()->teaching().validate(steps,CompanionTeachingPlanService.defaults(service),r,target,Map.of(),ErrorCode.VALIDATION_ERROR),ErrorCode.VALIDATION_ERROR);}
  }
  @Test void teachingPersonalStudentTasksRequireCurrentTeacherConfirmation() throws Exception {
    var q=generation("STUDENT");doAnswer(call->validPlanning(call.getArgument(0))).when(ai).planSmartCompanion(any());var proposal=teaching().generate(token,q);var save=saveInput(q,proposal);save.put("status","DRAFT");var draft=teaching().put(token,save);String sid=q.path("studentId").asText(),nid=q.path("nodeId").asText();assertThat(service.getPlan(token,sid,nid).path("steps").isEmpty()).isFalse();var feedback=obj("studentId",sid,"nodeId",nid,"taskId",draft.path("steps").get(0).path("id"),"type","READ");
    service.switchSession(token,obj("view","STUDENT","studentId",sid));assertThat(service.getPlan(token,sid,nid).path("steps").isEmpty()).isTrue();assertThat(service.studentDetail(token,sid,nid).path("plan").path("steps").isEmpty()).isTrue();error(()->service.feedback(token,feedback),ErrorCode.VALIDATION_ERROR);
    service.switchSession(token,obj("view","TEACHER"));save.put("expectedRevision",1);save.put("status","CONFIRMED");teaching().put(token,save);service.switchSession(token,obj("view","STUDENT","studentId",sid));assertThat(service.getPlan(token,sid,nid).path("steps").size()).isEqualTo(1);service.feedback(token,feedback);var state=service.state(false);service.save(state,true);assertThat(service.getPlan(token,sid,nid).path("steps").isEmpty()).isTrue();assertThat(service.studentDetail(token,sid,nid).path("plan").path("status").asText()).isEqualTo("NEEDS_REVIEW");error(()->service.feedback(token,feedback),ErrorCode.VALIDATION_ERROR);
  }
  @Test void structureGroupsTopicsAndKeepsPrerequisitesDirectional() {
    var r=service.report(service.state(false));var first=r.path("indicators").get(0);String id=first.path("id").asText();String nid=r.path("nodes").get(0).path("id").asText();
    var card=obj("id",id,"knowledgePointId","SC-PREREQ","version","approved-v1","name","已审核知识","summary","摘要","learningSteps",List.of("先读规则"),
      "prerequisites",List.of(obj("id","SC-EXTERNAL","label","外部前置","reason","学习顺序联系","sourceIds",List.of("s1"))),
      "sources",List.of(obj("id",id+"-STD","title","课程标准","location","PDF第2页／正文55页","excerpt","课标要求"),obj("id",id+"-S1","title","教材切片","location","第12页","excerpt","教材摘录")));
    when(knowledge.learningContent(1L,id)).thenReturn(card);
    var structure=service.structure(token,nid);
    assertThat(structure.path("topics").size()).isEqualTo(3);assertThat(structure.path("indicators").size()).isEqualTo(9);
    var item=structure.path("indicators").get(0);
    assertThat(item.path("knowledgeStatus").asText()).isEqualTo("READY");assertThat(item.path("standards").get(0).path("location").asText()).contains("55页");
    assertThat(item.path("textbooks").size()).isEqualTo(1);assertThat(item.path("prerequisites").get(0).path("label").asText()).isEqualTo("外部前置");
    // 前置指向九项指标之外不生成悬空边；未就绪内容保持可读而不是整页失败
    assertThat(structure.path("edges").isEmpty()).isTrue();assertThat(structure.path("indicators").get(1).path("knowledgeStatus").asText()).isEqualTo("NOT_READY");
    int questions=0;for(var indicator:structure.path("indicators"))questions+=indicator.path("questionCount").asInt();assertThat(questions).isGreaterThan(0);
    var student=r.path("students").get(0).path("id").asText();service.switchSession(token,obj("view","STUDENT","studentId",student));
    for(var material:service.structure(token,nid).path("indicators").get(0).path("materials"))assertThat(material.has("answer")||material.has("explanation")||material.has("scoringPoints")).isFalse();
    assertThat(service.structure(token,"").path("indicators").size()).isEqualTo(9);
    error(()->service.structure(token,"missing"),ErrorCode.RESOURCE_NOT_FOUND);
  }
}
