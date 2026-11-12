package com.education.agent.smartcompanion;

import com.education.agent.common.error.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.knowledge.*;
import com.education.agent.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Human confirmed mappings and explain-only drafts; never changes analysis or grades. */
@Service
public class CompanionKnowledgeService {
  final SmartCompanionService imports;
  final KnowledgeServiceClient graph;
  final AiServiceClient ai;
  final TransactionTemplate tx;
  final org.springframework.jdbc.core.JdbcTemplate jdbc;
  final ObjectMapper json;
  public CompanionKnowledgeService(SmartCompanionService imports, KnowledgeServiceClient graph,
      AiServiceClient ai, PlatformTransactionManager manager, org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper json) {
    this.imports=imports; this.graph=graph; this.ai=ai; tx=new TransactionTemplate(manager); this.jdbc=jdbc; this.json=json;
  }
  public record Binding(String indicatorId,String knowledgePointId) {}
  public record PutBindings(Long expectedRevision,String graphVersion,List<Binding> bindings) {}
  public record Diagnose(String studentId,String nodeId,String indicatorId) {}
  ObjectNode obj(Object... values) {return new SmartCompanionEngine(json).obj(values);}
  static void require(boolean valid, ErrorCode code, String message) {
    if(!valid) throw new BusinessException(code,message);
  }
  static boolean text(String v,int max) {return v!=null&&!v.isBlank()&&v.length()<=max;}
  ObjectNode state(Long c,Long id) {
    var rows=jdbc.queryForList("SELECT * FROM edu_companion_knowledge_state WHERE import_id=?",id);
    if(rows.isEmpty()) return obj("courseId",c.toString(),"importId",id.toString(),"revision",0,
        "graphVersion","","bindings",List.of(),"updatedAt",null,"items",List.of());
    var r=rows.get(0);
    return obj("courseId",c.toString(),"importId",id.toString(),"revision",r.get("revision"),
        "graphVersion",r.get("graph_version"),"bindings",read(r.get("bindings_json")),
        "updatedAt",r.get("updated_at"),"items",read(r.get("diagnoses_json")));
  }
  JsonNode read(Object v) {
    try{return json.readTree(v.toString());}
    catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"图谱关联快照不可读取");}
  }
  void save(Long id,ObjectNode s) {
    jdbc.update("DELETE FROM edu_companion_knowledge_state WHERE import_id=?",id);
    jdbc.update("INSERT INTO edu_companion_knowledge_state(import_id,revision,graph_version,bindings_json,diagnoses_json,updated_at) VALUES(?,?,?,?,?,?)",
        id,s.path("revision").asLong(),s.path("graphVersion").asText(),imports.encode(s.path("bindings")),
        imports.encode(s.path("items")),s.path("updatedAt").isNull()?null:s.path("updatedAt").asText());
  }
  public ObjectNode bindings(AuthenticatedUser u,Long c,Long id) {
    imports.get(u,c,id); var s=state(c,id); s.remove("items"); s.put("currentGraphVersion",version(c)); return s;
  }
  public ObjectNode diagnoses(AuthenticatedUser u,Long c,Long id) {
    imports.get(u,c,id); return obj("items",state(c,id).path("items"));
  }
  String version(Long c) {return graph.currentGraphVersion(c,RequestIds.current());}
  void sameVersion(String expected,String actual) {
    require(Objects.equals(expected,actual),ErrorCode.GRAPH_VERSION_CONFLICT,"图谱版本已变化，请重新确认绑定");
  }
  InternalGraphRagContextResponse context(Long c,String kp,String v) {
    var r=ApprovedGraphContextValidator.require(kp,graph.ragContext(c,kp));
    ApprovedGraphContextValidator.allowedContextNodeIds(kp,r);
    require(c.equals(r.courseId()),ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"图谱课程不符");
    sameVersion(v,r.graphVersion()); return r;
  }
  public ObjectNode putBindings(AuthenticatedUser u,Long c,Long id,PutBindings request) {
    imports.authorize(u,c,true);
    var report=imports.report(u,c,id);
    require(request!=null&&request.expectedRevision()!=null&&request.expectedRevision()>=0
        &&text(request.graphVersion(),200)&&request.bindings()!=null&&request.bindings().size()<=200,
        ErrorCode.VALIDATION_ERROR,"绑定请求无效");
    String v=request.graphVersion(); sameVersion(v,version(c));
    Set<String> indicators=ids(report.path("indicators"),"id"),seen=new HashSet<>();
    var bindings=json.createArrayNode();
    for(var b:request.bindings()) {
      require(b!=null&&text(b.indicatorId(),200)&&text(b.knowledgePointId(),200)
          &&indicators.contains(b.indicatorId())&&seen.add(b.indicatorId()),ErrorCode.VALIDATION_ERROR,"指标不存在或重复");
      context(c,b.knowledgePointId(),v);
      bindings.add(obj("indicatorId",b.indicatorId(),"knowledgePointId",b.knowledgePointId()));
    }
    sameVersion(v,version(c));
    return tx.execute(status->{
      imports.row(c,id,true); imports.authorize(u,c,true); var s=state(c,id);
      require(s.path("revision").asLong()==request.expectedRevision(),ErrorCode.CONCURRENT_MODIFICATION,"绑定修订已变化，请刷新");
      s.put("revision",request.expectedRevision()+1); s.put("graphVersion",v);
      s.set("bindings",bindings); s.put("updatedAt",OffsetDateTime.now().toString()); save(id,s);
      s.remove("items"); s.put("currentGraphVersion",v); return s;
    });
  }
  static Set<String> ids(JsonNode array,String key) {
    var ids=new LinkedHashSet<String>(); array.forEach(n->ids.add(key==null?n.asText():n.path(key).asText())); return ids;
  }
  public ObjectNode diagnose(AuthenticatedUser u,Long c,Long id,Diagnose d) {
    imports.authorize(u,c,true); var report=imports.report(u,c,id);
    require(d!=null&&text(d.studentId(),200)&&text(d.nodeId(),200)&&text(d.indicatorId(),200),ErrorCode.VALIDATION_ERROR,"诊断请求无效");
    var s=state(c,id); String v=s.path("graphVersion").asText(); long revision=s.path("revision").asLong();
    String kp=null;
    for(var b:s.path("bindings")) if(b.path("indicatorId").asText().equals(d.indicatorId())) kp=b.path("knowledgePointId").asText();
    require(kp!=null,ErrorCode.INVALID_RESOURCE_STATE,"请先人工确认指标知识点绑定");
    sameVersion(v,version(c)); var ctx=context(c,kp,v);
    JsonNode observation=null;
    for(var o:report.path("observations")) if(matches(o,d)) observation=o;
    require(observation!=null,ErrorCode.VALIDATION_ERROR,"学生、测验节点或指标不属于报告");
    final String target=kp;
    var evidence=json.createArrayNode(); var publicEvidence=json.createArrayNode();
    var materials=json.createArrayNode(); var sources=json.createArrayNode();
    var publicSources=json.createArrayNode(); var prerequisites=json.createArrayNode();
    var publicPrerequisites=json.createArrayNode(); var cautions=json.createArrayNode();
    Set<String> evidenceIds=ids(observation.path("evidenceIds"),null);
    Map<String,JsonNode> materialMap=new LinkedHashMap<>(); report.path("materials").forEach(m->materialMap.put(m.path("id").asText(),m));
    int found=0;
    for(var r:report.path("records")) {
      if(!r.path("studentId").asText().equals(d.studentId())||!r.path("nodeId").asText().equals(d.nodeId())||!evidenceIds.contains(r.path("id").asText())) continue;
      found++; if(evidence.size()>=50) continue;
      var m=materialMap.get(r.path("materialId").asText());
      require(m!=null,ErrorCode.INTERNAL_ERROR,"作答材料缺失");
      evidence.add(obj("record_id",r.path("id"),"material_id",r.path("materialId"),"score",r.path("score"),"max_score",r.path("maxScore"),
          "answer",r.path("answer"),"process",r.path("process"),"point_scores",r.path("pointScores"),"question",m.path("body"),"explanation",m.path("explanation")));
      publicEvidence.add(obj("recordId",r.path("id"),"materialId",r.path("materialId"),"score",r.path("score"),"maxScore",r.path("maxScore"),"answer",r.path("answer"),"process",r.path("process"),"pointScores",r.path("pointScores")));
    }
    if(found>50) cautions.add("直接作答仅展示前50条；完整分母保留在原始观察中。");
    Set<String> nodeIds=new LinkedHashSet<>(); nodeIds.add(target);
    require(ctx.prerequisites().size()<=20,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"前置知识数量超过解释预算");
    addSources(ctx,target,sources,publicSources);
    for(var p:ctx.prerequisites()) {
      nodeIds.add(p.id()); var pc=context(c,p.id(),v); addSources(pc,p.id(),sources,publicSources);
      var observations=json.createArrayNode(); var internalObs=json.createArrayNode();
      for(var b:s.path("bindings")) if(b.path("knowledgePointId").asText().equals(p.id()))
        for(var o:report.path("observations")) if(o.path("studentId").asText().equals(d.studentId())&&o.path("nodeId").asText().equals(d.nodeId())&&o.path("indicatorId").equals(b.path("indicatorId"))) {
          observations.add(o); internalObs.add(obj("indicator_id",o.path("indicatorId"),"status",o.path("status"),"scored_count",o.path("scoredCount"),"score_percent",o.path("scorePercent"),"evidence_ids",o.path("evidenceIds")));
        }
      prerequisites.add(obj("knowledge_point_id",p.id(),"name",p.name(),"assessment","CHECK_REQUIRED","observations",internalObs));
      publicPrerequisites.add(obj("knowledgePointId",p.id(),"name",p.name(),"assessment","CHECK_REQUIRED","observations",observations,"reason","前置关系仅提示核查；无直接作答不能判为薄弱"));
    }
    require(sources.size()<=80,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"来源超过解释预算");
    Set<String> materialIds=new LinkedHashSet<>(); evidence.forEach(e->materialIds.add(e.path("material_id").asText()));
    for(var m:report.path("materials")) {
      boolean linked=materialIds.contains(m.path("id").asText())||ids(m.path("indicatorIds"),null).contains(d.indicatorId());
      if(linked&&materials.size()<30) materials.add(obj("material_id",m.path("id"),"type",m.path("type").asText().equals("题目")?"QUESTION":"RESOURCE","body",m.path("body"),"purpose","核查/练习"));
    }
    var request=obj("course_id",c,"student_id",d.studentId(),"node_id",d.nodeId(),"indicator_id",d.indicatorId(),"graph_version",v,
        "knowledge_point",obj("id",target,"name",ctx.knowledgePoint().name(),"description",Objects.requireNonNullElse(ctx.knowledgePoint().description(),"")),
        "observation",obj("status",observation.path("status"),"scored_count",observation.path("scoredCount"),"available_question_count",observation.path("availableQuestionCount"),"score_percent",observation.path("scorePercent")),
        "evidence",evidence,"prerequisites",prerequisites,"sources",sources,"materials",materials);
    // No database transaction spans graph or model calls.
    var response=ai.explainSmartCompanion(request);
    Set<String> allowedEvidence=ids(evidence,"record_id");
    prerequisites.forEach(p->p.path("observations").forEach(o->allowedEvidence.addAll(ids(o.path("evidence_ids"),null))));
    validateResponse(response,nodeIds,ids(sources,"source_id"),allowedEvidence,ids(materials,"material_id"));
    var steps=json.createArrayNode();
    for(var step:response.path("steps")) steps.add(obj("title",step.path("title"),"action",step.path("action"),"rationale",step.path("rationale"),
        "knowledgePointIds",step.path("knowledge_point_ids"),"sourceIds",step.path("source_ids"),"evidenceIds",step.path("evidence_ids"),"materialIds",step.path("material_ids")));
    cautions.add("模型输出仅为草稿，需要教师复核；前置关系不代表薄弱结论。");
    for(var caution:response.path("cautions")) if(cautions.size()<30) cautions.add(caution);
    var result=obj("courseId",c.toString(),"importId",id.toString(),"studentId",d.studentId(),"nodeId",d.nodeId(),"indicatorId",d.indicatorId(),
        "graphVersion",v,"bindingRevision",revision,"knowledgePointId",target,"knowledgePointName",ctx.knowledgePoint().name(),
        "generatedAt",OffsetDateTime.now().toString(),"status","DRAFT","modelStatus","SUCCEEDED","provider",response.path("provider"),"model",response.path("model"),
        "observation",observation,"directEvidence",publicEvidence,"prerequisites",publicPrerequisites,"sources",publicSources,"explanation",response.path("explanation"),"steps",steps,"cautions",cautions);
    sameVersion(v,version(c)); context(c,target,v);
    return tx.execute(status->{
      imports.row(c,id,true); imports.authorize(u,c,true); var latest=state(c,id);
      require(latest.path("revision").asLong()==revision&&latest.path("graphVersion").asText().equals(v),ErrorCode.CONCURRENT_MODIFICATION,"模型调用期间绑定已变化，请重试");
      var items=json.createArrayNode(); items.add(result);
      for(var old:latest.path("items")) if(!matches(old,d)&&items.size()<100) items.add(old);
      latest.set("items",items); save(id,latest); return result;
    });
  }
  boolean matches(JsonNode o,Diagnose d) {return o.path("studentId").asText().equals(d.studentId())&&o.path("nodeId").asText().equals(d.nodeId())&&o.path("indicatorId").asText().equals(d.indicatorId());}
  void addSources(InternalGraphRagContextResponse context,String kp,ArrayNode internal,ArrayNode external) {
    require(context.sources()!=null,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"图谱来源缺失");
    for(var source:context.sources()) {
      if(source==null||!"APPROVED".equals(source.reviewStatus())||!"ALLOWED".equals(source.rightsStatus())||!kp.equals(source.nodeId())) continue;
      require(text(source.id(),200)&&text(source.title(),1000)&&text(source.locator(),2000)
          &&(source.excerpt()==null||source.excerpt().length()<=6000)
          &&(source.pageFrom()==null||source.pageFrom()>=1)&&(source.pageTo()==null||source.pageTo()>=1)
          &&(source.pageFrom()==null||source.pageTo()==null||source.pageTo()>=source.pageFrom()),ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"图谱来源字段无效");
      if(ids(internal,"source_id").contains(source.id())) continue;
      internal.add(obj("source_id",source.id(),"knowledge_point_id",kp,"title",source.title(),"locator",source.locator(),"page_from",source.pageFrom(),"page_to",source.pageTo(),"excerpt",source.excerpt()));
      external.add(obj("sourceId",source.id(),"knowledgePointId",kp,"title",source.title(),"locator",source.locator(),"pageFrom",source.pageFrom(),"pageTo",source.pageTo(),"excerpt",source.excerpt()));
    }
  }
  static void validateResponse(JsonNode r,Set<String> kp,Set<String> sources,Set<String> evidence,Set<String> materials) {
    ErrorCode code=ErrorCode.AI_SERVICE_INVALID_RESPONSE;
    require(r!=null&&r.isObject()&&r.toString().length()<=131072,code,"模型响应无效");
    require(text(r.path("explanation").isTextual()?r.path("explanation").asText():null,8000)
        &&text(r.path("provider").isTextual()?r.path("provider").asText():null,200)
        &&text(r.path("model").isTextual()?r.path("model").asText():null,300)
        &&r.path("steps").isArray()&&r.path("steps").size()>=1&&r.path("steps").size()<=8
        &&r.path("cautions").isArray()&&r.path("cautions").size()<=30,code,"模型响应字段无效");
    for(var caution:r.path("cautions")) require(caution.isTextual()&&caution.asText().length()<=2000,code,"模型提示无效");
    for(var step:r.path("steps")) {
      require(step.path("title").isTextual()&&text(step.path("title").asText(),300)
          &&step.path("action").isTextual()&&text(step.path("action").asText(),2000)
          &&step.path("rationale").isTextual()&&step.path("rationale").asText().length()<=2000,code,"模型步骤字段无效");
      var allowed=Map.of("knowledge_point_ids",kp,"source_ids",sources,"evidence_ids",evidence,"material_ids",materials);
      allowed.forEach((field,white)->{
        int max=switch(field){case "knowledge_point_ids"->21; case "source_ids"->80; case "material_ids"->30; default->100;};
        require(step.path(field).isArray()&&step.path(field).size()<=max,code,"模型引用格式无效");
        for(var ref:step.path(field)) require(ref.isTextual()&&white.contains(ref.asText()),code,"模型引用不在已审核输入白名单内");
      });
    }
  }
}

