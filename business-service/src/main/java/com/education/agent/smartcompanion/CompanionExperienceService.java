package com.education.agent.smartcompanion;

import com.education.agent.common.error.*;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.integration.ai.AiServiceClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CompanionExperienceService {
  final JdbcTemplate jdbc; final ObjectMapper json; final CompanionExperienceMerge merge;
  final SmartCompanionEngine engine; final TransactionTemplate tx; final KnowledgeServiceClient knowledge; final AiServiceClient ai;
  final boolean enabled; final long course;
  public CompanionExperienceService(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager tm,
      KnowledgeServiceClient knowledge,AiServiceClient ai,@Value("${companion.experience.enabled:false}") boolean enabled,
      @Value("${companion.experience.course-id:3}") long course) {
    this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(tm);this.knowledge=knowledge;this.ai=ai;
    this.enabled=enabled;this.course=course;merge=new CompanionExperienceMerge(json);engine=merge.engine;
  }
  ObjectNode obj(Object... p){return engine.obj(p);}
  static void require(boolean yes,ErrorCode code,String message){if(!yes)throw new BusinessException(code,message);}
  ObjectNode decode(Object value){try{return (ObjectNode)json.readTree(value.toString());}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"空间快照不可读取");}}
  void enabled(){require(enabled,ErrorCode.RESOURCE_NOT_FOUND,"体验入口未启用");}
  Map<String,Object> session(String token,boolean teacher){
    enabled(); require(token!=null&&token.length()>=32&&token.length()<=128,ErrorCode.UNAUTHENTICATED,"请重新进入体验");
    var rows=jdbc.queryForList("SELECT * FROM edu_companion_session WHERE token_hash=? AND course_id=? AND expires_at>?",CompanionExperienceMerge.hash(token),course,System.currentTimeMillis());
    require(!rows.isEmpty(),ErrorCode.UNAUTHENTICATED,"体验会话已失效"); var s=rows.get(0);
    if(teacher)require("TEACHER".equals(s.get("view_name")),ErrorCode.FORBIDDEN,"当前操作需要教师视角");return s;
  }
  boolean teacher(Map<String,Object>s){return "TEACHER".equals(s.get("view_name"));}
  String student(Map<String,Object>s){return Objects.toString(s.get("student_id"),"");}
  void own(Map<String,Object>s,String id){require(teacher(s)||student(s).equals(id),ErrorCode.FORBIDDEN,"只能访问当前学生");}
  public ObjectNode createSession(){enabled(); return tx.execute(t->{
    state(true); jdbc.update("DELETE FROM edu_companion_session WHERE expires_at<?",System.currentTimeMillis()); byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    long expires=System.currentTimeMillis()+8*3600000L;
    jdbc.update("INSERT INTO edu_companion_session(token_hash,course_id,expires_at,view_name,student_id) VALUES(?,?,?,'TEACHER',NULL)",CompanionExperienceMerge.hash(token),course,expires);
    return obj("token",token,"expiresAt",Instant.ofEpochMilli(expires).toString(),"view","TEACHER","studentId",null,"workspaceId",Long.toString(course));
  });}
  public ObjectNode switchSession(String token,JsonNode input){var s=session(token,false);String view=input.path("view").asText();
    require(Set.of("TEACHER","STUDENT").contains(view),ErrorCode.VALIDATION_ERROR,"视角无效");String sid=input.path("studentId").asText();
    if(view.equals("STUDENT")) require(find(report(state(false)),"students",sid)!=null,ErrorCode.VALIDATION_ERROR,"请选择有效学生");
    jdbc.update("UPDATE edu_companion_session SET view_name=?,student_id=? WHERE token_hash=?",view,view.equals("STUDENT")?sid:null,CompanionExperienceMerge.hash(token));
    return obj("token",token,"view",view,"studentId",view.equals("STUDENT")?sid:null,"expiresAt",Instant.ofEpochMilli(((Number)s.get("expires_at")).longValue()).toString(),"workspaceId",Long.toString(course));
  }
  ObjectNode state(boolean lock){
    var rows=jdbc.queryForList("SELECT revision,state_json FROM edu_companion_workspace WHERE course_id=?"+(lock?" FOR UPDATE":""),course);
    if(rows.isEmpty()) {
      // Serialize initial capture against the existing course, never overwrite the old import.
      jdbc.queryForList("SELECT id FROM edu_course WHERE id=? FOR UPDATE",course);
      rows=jdbc.queryForList("SELECT revision,state_json FROM edu_companion_workspace WHERE course_id=?",course);
      if(rows.isEmpty()) {
        var state=obj("revision",0,"baseline",obj(),"batches",json.createArrayNode(),"plans",obj(),"feedback",json.createArrayNode());
        var old=jdbc.queryForList("SELECT id,source_json,detail_json FROM edu_companion_import WHERE course_id=? ORDER BY id DESC",course);
        for(var r:old) if("ANALYZED".equals(decode(r.get("detail_json")).path("summary").path("status").asText())) {
          var source=decode(r.get("source_json"));state.set("baseline",source.deepCopy());merge.bind(state,source);state.set("baseline",source.deepCopy());
          ((ArrayNode)state.path("batches")).add(batch("seed-"+r.get("id"),"原课程已分析输入","明确导入的历史来源",source,"seed",true));state.put("revision",1);break;
        }
        jdbc.update("INSERT INTO edu_companion_workspace(course_id,revision,state_json) VALUES(?,?,?)",course,state.path("revision").asLong(),state.toString());return state;
      }
    }
    return decode(rows.get(0).get("state_json"));
  }
  ObjectNode batch(String id,String name,String label,ObjectNode source,String key,boolean confirmed){return obj("id",id,"fileName",name,"sourceLabel",label,"createdAt",OffsetDateTime.now().toString(),"source",source,"key",key,"confirmed",confirmed,"learningActive",confirmed&&source.path("作答记录").size()>0,"questionBankActive",confirmed&&source.path("题目与材料").size()>0,"status",confirmed?"CONFIRMED":"PREVIEW");}
  ObjectNode report(ObjectNode state){
    var tables=merge.raw(state);long students=tables.get("模拟学生").size(),nodes=tables.get("测验节点").size(),indicators=tables.get("课程要求").size();
    require(students<=1000&&tables.get("题目与材料").size()<=5000&&students*nodes*indicators<=20000,ErrorCode.VALIDATION_ERROR,"当前空间超过分析预算，请拆分数据空间");
    return merge.report(state,course,state.path("revision").asLong());
  }
  void expected(ObjectNode state,JsonNode input){require(input.has("expectedRevision")&&input.path("expectedRevision").asLong(-1)==state.path("revision").asLong(),ErrorCode.CONCURRENT_MODIFICATION,"数据已变化，请刷新重试");}
  void save(ObjectNode state,boolean advance){if(advance){state.put("revision",state.path("revision").asLong()+1);state.path("plans").forEach(p->((ObjectNode)p).put("status","NEEDS_REVIEW"));state.path("classPlans").forEach(p->((ObjectNode)p).put("status","NEEDS_REVIEW"));}jdbc.update("UPDATE edu_companion_workspace SET revision=?,state_json=? WHERE course_id=?",state.path("revision").asLong(),state.toString(),course);}
  JsonNode find(JsonNode r,String field,String id){for(var item:r.path(field))if(item.path("id").asText().equals(id))return item;return null;}
  ObjectNode getBatch(ObjectNode state,String id){var b=find(state,"batches",id);require(b!=null,ErrorCode.RESOURCE_NOT_FOUND,"上传批次不存在");return (ObjectNode)b;}
  ObjectNode counts(JsonNode r,ObjectNode state){return obj("studentCount",r.path("students").size(),"materialCount",r.path("materials").size(),"recordCount",r.path("records").size(),"batchCount",SmartCompanionEngine.list(state.path("batches")).stream().filter(b->b.path("confirmed").asBoolean()&&(b.path("learningActive").asBoolean()||b.path("questionBankActive").asBoolean())).count());}
  ObjectNode summary(JsonNode b){return obj("id",b.path("id"),"fileName",b.path("fileName"),"sourceLabel",b.path("sourceLabel"),"status",b.path("status"),"createdAt",b.path("createdAt"),"learningActive",b.path("learningActive"),"questionBankActive",b.path("questionBankActive"),"studentCount",b.path("source").path("模拟学生").size(),"materialCount",b.path("source").path("题目与材料").size(),"recordCount",b.path("source").path("作答记录").size());}
  ObjectNode detail(ObjectNode state,ObjectNode b){return obj("summary",summary(b),"preview",b.has("preview")?b.path("preview"):preview(state,b));}
  ObjectNode preview(ObjectNode state,ObjectNode b){
    var before=report(state);var copy=state.deepCopy();var candidate=getBatch(copy,b.path("id").asText());candidate.put("confirmed",true);candidate.put("learningActive",true);candidate.put("questionBankActive",true);
    var after=report(copy);
    // Limit upload feedback to this source's event revisions. Historical quality stays on /data/quality.
    var incomingVersions=new HashSet<String>();var incomingRecordIds=new HashSet<String>();
    for(var record:b.path("source").path("作答记录")){incomingVersions.add(record.path("作答事件编号").asText()+"#"+record.path("修订版本").asText());incomingRecordIds.add(record.path("记录编号").asText());}
    var relevantQuality=new HashMap<String,JsonNode>();var qualityById=new HashMap<String,JsonNode>();var affectedRecordIds=new HashSet<String>();
    for(var row:after.path("quality").path("rows"))if(incomingRecordIds.contains(row.path("recordId").asText())||incomingVersions.contains(row.path("eventId").asText()+"#"+row.path("revision").asText())){qualityById.put(row.path("recordId").asText(),row);relevantQuality.put(row.path("eventId").asText()+"#"+row.path("revision").asText(),row);affectedRecordIds.add(row.path("recordId").asText());}
    // Structural errors remain global blockers; record warnings include only the submitted revisions.
    var parsed=engine.parse(merge.raw(copy),true);var errors=json.createArrayNode();
    for(var issue:parsed.path("preview").path("issues"))if(!"作答记录".equals(issue.path("table").asText())||affectedRecordIds.contains(issue.path("recordId").asText()))errors.add(issue);
    if(b.path("source").has("课程要求"))errors.add(obj("severity","WARNING","code","FIXED_CONTENT_NOT_UPDATED","message","当前使用固定内容基线；本包课标和教材未参与知识更新"));
    if(state.path("baseline").path("课程要求").isEmpty())errors.add(obj("severity","ERROR","code","NO_BASELINE","message","当前课程没有经核验的固定要求基线"));
    for(var incoming:b.path("source").path("模拟学生"))for(var existing:before.path("students"))if(incoming.path("学生编号").asText().equals(existing.path("id").asText())&&(!incoming.path("模拟班级编号").asText().equals(existing.path("classId").asText())||!incoming.path("年级").asText().equals(existing.path("grade").asText())))errors.add(obj("severity","ERROR","code","STUDENT_IDENTITY_CONFLICT","message","同号学生身份/班级冲突，请使用独立数据空间"));
    for(var incoming:b.path("source").path("测验节点"))for(var existing:before.path("nodes"))if(incoming.path("节点编号").asText().equals(existing.path("id").asText())&&(!incoming.path("模拟测验日期").asText().equals(existing.path("date").asText())||!incoming.path("已教及测量主题").asText().equals(existing.path("scope").asText())))errors.add(obj("severity","ERROR","code","NODE_CONTEXT_CONFLICT","message","同号测验的日期或测量范围冲突，请核对节点编号"));
    boolean can=SmartCompanionEngine.list(errors).stream().noneMatch(i->"ERROR".equals(i.path("severity").asText()));
    int newM=Math.max(0,after.path("materials").size()-before.path("materials").size());int newR=Math.max(0,after.path("records").size()-before.path("records").size());
    int pending=0;
    for(var record:b.path("source").path("作答记录")){var quality=qualityById.getOrDefault(record.path("记录编号").asText(),relevantQuality.get(record.path("作答事件编号").asText()+"#"+record.path("修订版本").asText()));if(quality!=null&&Set.of("INVALID","CONFLICT").contains(quality.path("disposition").asText()))pending++;}
    return obj("canConfirm",can,"counts",obj("newMaterials",newM,"duplicateMaterials",Math.max(0,b.path("source").path("题目与材料").size()-newM),"newRecords",newR,"duplicateRecords",Math.max(0,b.path("source").path("作答记录").size()-newR-pending),"revisions",newRevisions(state,b),"pendingRecords",pending),"issues",errors);
  }
  int newRevisions(ObjectNode state,JsonNode batch){
    var latest=new HashMap<String,Integer>();
    for(var existing:merge.raw(state).get("作答记录")){Integer revision=SmartCompanionEngine.revision(existing);if(revision!=null)latest.merge(existing.getOrDefault("作答事件编号",""),revision,Math::max);}
    var revised=new HashSet<String>();for(var row:merge.rows(batch.path("source"),"作答记录")){Integer revision=SmartCompanionEngine.revision(row);String event=row.getOrDefault("作答事件编号","");if(revision!=null&&latest.containsKey(event)&&revision>latest.get(event))revised.add(event);}
    return revised.size();
  }
  public ObjectNode upload(String token,MultipartFile file,String label,String key){session(token,true);require(!file.isEmpty(),ErrorCode.FILE_EMPTY,"文件为空");require(file.getSize()<=100L*1024*1024,ErrorCode.FILE_TOO_LARGE,"文件不得超过100MiB");require(key!=null&&!key.isBlank()&&key.length()<=200,ErrorCode.VALIDATION_ERROR,"请提供Idempotency-Key");
    ObjectNode source;String name=Objects.requireNonNullElse(file.getOriginalFilename(),"upload").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);require(name.length()<=255,ErrorCode.VALIDATION_ERROR,"文件名过长");
    try{source=json.valueToTree(new SmartCompanionReader().read(file.getBytes(),name));}catch(Exception e){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"文件结构无法识别；支持已知字段XLSX/ZIP");}
    source.forEach(table->table.forEach(row->{if(row.isObject()){var names=new ArrayList<String>();row.fieldNames().forEachRemaining(k->{if(k.startsWith("_"))names.add(k);});((ObjectNode)row).remove(names);}}));
    require(source.path("模拟学生").size()<=1000&&source.path("题目与材料").size()<=5000&&source.path("作答记录").size()<=50000,ErrorCode.VALIDATION_ERROR,"上传数据超过预算");
    require(source.path("题目与材料").size()+source.path("作答记录").size()>0,ErrorCode.VALIDATION_ERROR,"未识别题库或作答记录；固定内容不参与本次更新");
    String fingerprint=CompanionExperienceMerge.hash(source.toString());String filename=name;String sourceLabel=label==null||label.isBlank()?"用户上传":label;require(sourceLabel.length()<=300,ErrorCode.VALIDATION_ERROR,"来源名称过长");
    return tx.execute(t->{var state=state(true);for(var b:state.path("batches"))if(key.equals(b.path("key").asText())){require(fingerprint.equals(b.path("fingerprint").asText()),ErrorCode.IDEMPOTENCY_CONFLICT,"重试键已用于其他文件");return detail(state,(ObjectNode)b);}
      // Copy explicit reused metadata into the new source so deleting its predecessor cannot orphan it.
      var effective=merge.raw(state);
      for(var pair:Map.of("模拟学生","学生编号","测验节点","节点编号").entrySet())if(source.path(pair.getKey()).isEmpty()){
        Set<String> referenced=new HashSet<>();for(var r:source.path("作答记录"))referenced.add(r.path(pair.getValue()).asText());
        var metadata=json.createArrayNode();for(var row:effective.get(pair.getKey()))if(referenced.contains(row.get(pair.getValue())))metadata.add(json.valueToTree(row));source.set(pair.getKey(),metadata);
      }
      merge.bind(state,source);var b=batch(UUID.randomUUID().toString(),filename,sourceLabel,source,key,false);b.put("fingerprint",fingerprint);((ArrayNode)state.path("batches")).add(b);var p=preview(state,b);b.set("preview",p);if(!p.path("canConfirm").asBoolean())b.put("status","BLOCKED");save(state,false);return detail(state,b);});
  }
  public ObjectNode batchDetail(String token,String id){session(token,true);var state=state(false);return detail(state,getBatch(state,id));}
  public ObjectNode confirm(String token,String id,JsonNode input){session(token,true);return tx.execute(t->{var state=state(true);var b=getBatch(state,id);if(b.path("confirmed").asBoolean())return obj("revision",state.path("revision"),"batch",summary(b),"counts",counts(report(state),state));expected(state,input);require(input.path("acknowledgeWarnings").asBoolean(),ErrorCode.VALIDATION_ERROR,"请核对并确认预检提示");var p=preview(state,b);require(p.path("canConfirm").asBoolean(),ErrorCode.VALIDATION_ERROR,"请先处理数据冲突");b.put("confirmed",true);b.put("learningActive",b.path("source").path("作答记录").size()>0);b.put("questionBankActive",b.path("source").path("题目与材料").size()>0);b.put("status","CONFIRMED");for(var active:state.path("batches"))if(active.path("confirmed").asBoolean())merge.bind(state,(ObjectNode)active.path("source"));save(state,true);return obj("revision",state.path("revision"),"batch",summary(b),"counts",counts(report(state),state));});}
  ObjectNode deletion(ObjectNode state,String id,String part){require(Set.of("LEARNING","QUESTION_BANK","ALL").contains(part),ErrorCode.VALIDATION_ERROR,"删除类别无效");var b=getBatch(state,id);var copy=state.deepCopy();var target=getBatch(copy,id);if(!part.equals("QUESTION_BANK"))target.put("learningActive",false);if(!part.equals("LEARNING"))target.put("questionBankActive",false);
    var before=merge.raw(state);var after=merge.raw(copy);var deps=json.createArrayNode();Set<String> available=new HashSet<>();after.get("题目与材料").forEach(m->available.add(m.get("材料编号")));Set<String> prior=new HashSet<>();before.get("题目与材料").forEach(m->prior.add(m.get("材料编号")));
    var missing=new TreeMap<String,Integer>();for(var r:after.get("作答记录"))if(prior.contains(r.get("材料编号"))&&!available.contains(r.get("材料编号")))missing.merge(r.get("材料编号"),1,Integer::sum);
    missing.forEach((m,n)->deps.add(obj("materialId",m,"recordCount",n,"message","剩余学情仍引用此唯一题目版本")));
    int rm=Math.max(0,before.get("题目与材料").size()-after.get("题目与材料").size()),rr=Math.max(0,before.get("作答记录").size()-after.get("作答记录").size());
    return obj("revision",state.path("revision"),"part",part,"canDelete",deps.isEmpty(),"removedRecords",rr,"removedMaterials",rm,"retainedSharedRecords",part.equals("QUESTION_BANK")?0:Math.max(0,b.path("source").path("作答记录").size()-rr),"retainedSharedMaterials",part.equals("LEARNING")?0:Math.max(0,b.path("source").path("题目与材料").size()-rm),"dependencies",deps,"message",deps.isEmpty()?"仅撤销本批有效来源，其他来源继续保留":"请先处理依赖学情");
  }
  public ObjectNode deletePreview(String token,String id,String part){session(token,true);return deletion(state(false),id,part);}
  public ObjectNode delete(String token,String id,String part,long revision){session(token,true);return tx.execute(t->{var state=state(true);var b=getBatch(state,id);var p=deletion(state,id,part);boolean change=(!part.equals("QUESTION_BANK")&&b.path("learningActive").asBoolean())||(!part.equals("LEARNING")&&b.path("questionBankActive").asBoolean());if(!change)return p;expected(state,obj("expectedRevision",revision));require(p.path("canDelete").asBoolean(),ErrorCode.RESOURCE_IN_USE,"仍有学情依赖此题库版本");if(!part.equals("QUESTION_BANK"))b.put("learningActive",false);if(!part.equals("LEARNING"))b.put("questionBankActive",false);if(!b.path("learningActive").asBoolean()&&!b.path("questionBankActive").asBoolean())b.put("status","REMOVED");save(state,true);p.set("revision",state.path("revision"));return p;});}

  ObjectNode projection(ObjectNode report,Map<String,Object> s){return projection(report,s,state(false));}
  ObjectNode projection(ObjectNode report,Map<String,Object> s,ObjectNode state){
    if(teacher(s))return report;
    var r=report.deepCopy();String id=student(s);
    for(String field:List.of("students","records","observations","recommendations","studentSummaries")){
      var a=json.createArrayNode();for(var item:r.path(field))if(item.path(field.equals("students")?"id":"studentId").asText().equals(id))a.add(item);r.set(field,a);
    }
    r.set("groupSummaries",json.createArrayNode());r.set("quality",obj());
    Set<String> materials=new HashSet<>();r.path("records").forEach(x->materials.add(x.path("materialId").asText()));r.path("recommendations").forEach(x->x.path("materialIds").forEach(m->materials.add(m.asText())));
    // Only confirmed plans bound to this exact data snapshot grant additional safe material access.
    var ownStudent=find(report,"students",id);if(ownStudent!=null)for(var node:report.path("nodes")){
      String nid=node.path("id").asText();var plans=List.of(state.path("plans").path(id+":"+nid),state.path("classPlans").path(ownStudent.path("classId").asText()+":"+nid));
      Set<String> available=CompanionKnowledgeService.ids(node.path("availableMaterialIds"),null);
      for(var plan:plans)if("CONFIRMED".equals(plan.path("status").asText())&&plan.path("dataRevision").asLong(-1)==state.path("revision").asLong()) {var selectedIndicators=new HashSet<String>();plan.path("steps").forEach(step->selectedIndicators.add(step.path("indicatorId").asText()));for(var step:plan.path("steps"))for(var mid:step.path("materialIds")){
        var material=find(report,"materials",mid.asText());if(material!=null&&(available.contains(mid.asText())||!"题目".equals(material.path("type").asText()))&&CompanionKnowledgeService.ids(material.path("indicatorIds"),null).stream().anyMatch(selectedIndicators::contains))materials.add(mid.asText());
      }}
    }
    var a=json.createArrayNode();for(var m:r.path("materials"))if(materials.contains(m.path("id").asText()))a.add(safeMaterial(m));r.set("materials",a);return r;
  }
  ObjectNode safeMaterial(JsonNode m){var n=(ObjectNode)m.deepCopy();n.remove(List.of("answer","explanation","scoringPoints"));return n;}
  public ObjectNode workspace(String token){var s=session(token,false);var state=state(false);var r=projection(report(state),s,state);var classes=new LinkedHashSet<String>();r.path("students").forEach(x->classes.add(x.path("classId").asText()));var count=counts(r,state);if(!teacher(s))count.put("batchCount",0);
    return obj("workspaceId",Long.toString(course),"revision",state.path("revision"),"knowledgeVersion",knowledgeVersion(r),"counts",count,"nodes",r.path("nodes"),"indicators",r.path("indicators"),"classes",classes,"method",r.path("method"),"limitations",r.path("limitations"));}
  boolean match(JsonNode item,Map<String,String> q){
    for(String field:List.of("nodeId","studentId","indicatorId","status")){String v=q.get(field);if(v!=null&&!v.isBlank()&&item.has(field)&&!v.equals(item.path(field).asText()))return false;}
    String search=q.getOrDefault("q","").toLowerCase(Locale.ROOT);return search.isBlank()||item.toString().toLowerCase(Locale.ROOT).contains(search);
  }
  public ObjectNode data(String token,String collection,Map<String,String> query){var s=session(token,false);var state=state(false);var full=report(state);var r=projection(full,s,state);require(Set.of("batches","students","materials","records","quality","observations","recommendations").contains(collection),ErrorCode.RESOURCE_NOT_FOUND,"数据集合不存在");
    var items=new ArrayList<JsonNode>();String classId=query.getOrDefault("classId","");Set<String> members=new HashSet<>();for(var st:r.path("students"))if(classId.isBlank()||classId.equals("ALL")||classId.equals(st.path("classId").asText()))members.add(st.path("id").asText());
    if(collection.equals("batches")){require(teacher(s),ErrorCode.FORBIDDEN,"仅教师可管理批次");String status=query.getOrDefault("status","");state.path("batches").forEach(b->{if(!status.isBlank()||!"REMOVED".equals(b.path("status").asText()))items.add(summary(b));});}
    else if(collection.equals("students")){for(var st:full.path("students")){
      if((!classId.isBlank()&&!classId.equals("ALL")&&!classId.equals(st.path("classId").asText()))||(!query.getOrDefault("studentId","").isBlank()&&!query.get("studentId").equals(st.path("id").asText())))continue; if(!teacher(s)||Boolean.parseBoolean(query.getOrDefault("selector","false"))){items.add(obj("id",st.path("id"),"classId",st.path("classId"),"grade",st.path("grade")));continue;}
      var item=(ObjectNode)st.deepCopy();String sid=st.path("id").asText();var observations=json.createArrayNode();for(var o:r.path("observations"))if(sid.equals(o.path("studentId").asText())&&match(o,query))observations.add(o);item.set("observations",observations);item.set("summary",json.nullNode());for(var sum:r.path("studentSummaries"))if(sid.equals(sum.path("studentId").asText())&&match(sum,query)){item.set("summary",sum);break;}items.add(item);
    }}else {var values=collection.equals("quality")?r.path("quality").path("rows"):r.path(collection);for(var item:values)if((!item.has("studentId")||members.contains(item.path("studentId").asText()))&&match(item,query))items.add(item);}
    String indicator=query.getOrDefault("indicatorId","");if(!indicator.isBlank()&&(collection.equals("records")||collection.equals("materials"))){Set<String> linked=new HashSet<>();for(var m:r.path("materials"))for(var i:m.path("indicatorIds"))if(indicator.equals(i.asText()))linked.add(m.path("id").asText());items.removeIf(i->!linked.contains(i.path(collection.equals("materials")?"id":"materialId").asText()));}
    String batchId=query.get("batchId");if(batchId!=null&&!batchId.isBlank()){require(teacher(s),ErrorCode.FORBIDDEN,"仅教师可筛选批次");var b=getBatch(state,batchId);Set<String> allowed=new HashSet<>();String table=collection.equals("materials")?"题目与材料":collection.equals("students")?"模拟学生":"作答记录";for(var row:b.path("source").path(table))allowed.add(collection.equals("materials")?merge.materialId(row):row.path(collection.equals("students")?"学生编号":"记录编号").asText());items.removeIf(i->!allowed.contains(i.path(collection.equals("quality")?"recordId":"id").asText()));}
    var finalFilters=new HashMap<>(query);if(collection.equals("batches")&&"ALL".equals(finalFilters.get("status")))finalFilters.remove("status");items.removeIf(i->!match(i,finalFilters));items.sort(Comparator.comparing(i->i.path("id").asText(i.path("recordId").asText())+i.path("studentId").asText()+i.path("nodeId").asText()+i.path("indicatorId").asText()));
    int size=20,page=1;try{size=Integer.parseInt(query.getOrDefault("pageSize","20"));page=Integer.parseInt(query.getOrDefault("page","1"));}catch(Exception e){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"分页参数无效");}require(size>0&&size<=100&&page>0,ErrorCode.VALIDATION_ERROR,"分页参数超出范围");int total=items.size();page=Math.min(page,Math.max(1,(total+size-1)/size));int from=(page-1)*size;
    return obj("revision",state.path("revision"),"page",page,"pageSize",size,"total",total,"items",items.subList(from,Math.min(total,from+size)));}
  public ObjectNode overview(String token,Map<String,String> query){
    var session=session(token,false);var state=state(false);var r=projection(report(state),session,state);
    Set<String> members=new HashSet<>();String cid=query.getOrDefault("classId","");
    for(var student:r.path("students"))if(cid.isBlank()||cid.equals("ALL")||cid.equals(student.path("classId").asText()))members.add(student.path("id").asText());
    var group=json.createArrayNode();for(var g:r.path("groupSummaries"))if(match(g,query)&&(cid.isBlank()||cid.equals(g.path("classId").asText())))group.add(g);
    var observations=json.createArrayNode();for(var o:r.path("observations"))if(members.contains(o.path("studentId").asText())&&match(o,query))observations.add(o);
    var distribution=new LinkedHashMap<String,Integer>();for(String label:List.of("不足60%","60%–84%","85%及以上","暂无评分"))distribution.put(label,0);
    var records=SmartCompanionEngine.list(r.path("records")).stream().filter(e->members.contains(e.path("studentId").asText())&&match(e,query)).toList();
    for(String sid:members){var stats=engine.stats(records.stream().filter(e->sid.equals(e.path("studentId").asText())).toList());var score=stats.path("scorePercent");String label=score.isNull()?"暂无评分":score.asDouble()<60?"不足60%":score.asDouble()<85?"60%–84%":"85%及以上";distribution.merge(label,1,Integer::sum);}
    var quality=(ObjectNode)r.path("quality").deepCopy();quality.remove("rows");var count=counts(r,state);count.put("studentCount",members.size());count.put("recordCount",records.size());if(!teacher(session))count.put("batchCount",0);
    return obj("revision",state.path("revision"),"counts",count,"quality",quality,"groupSummaries",group,"indicatorSummaries",indicatorSummaries(r,observations),"distribution",distribution.entrySet().stream().map(e->obj("label",e.getKey(),"count",e.getValue())).toList());
  }
  ArrayNode indicatorSummaries(JsonNode report,JsonNode observations){
    var classes=new HashMap<String,String>();for(var student:report.path("students"))classes.put(student.path("id").asText(),student.path("classId").asText());
    var groups=new TreeMap<String,List<JsonNode>>();
    for(var observation:observations){String cid=classes.get(observation.path("studentId").asText());if(cid!=null)groups.computeIfAbsent(List.of(cid,observation.path("nodeId").asText(),observation.path("indicatorId").asText()).toString(),k->new ArrayList<>()).add(observation);}
    var summaries=json.createArrayNode();
    for(var entry:groups.entrySet()){
      var items=entry.getValue();var first=items.get(0);var evidenceIds=new HashSet<String>();
      int covered=0,scored=0,needs=0,insufficient=0,noEvidence=0,notAssessed=0;
      for(var observation:items){int n=observation.path("scoredCount").asInt();if(n>0)covered++;scored+=n;observation.path("evidenceIds").forEach(id->evidenceIds.add(id.asText()));switch(observation.path("status").asText()){case "NEEDS_PRACTICE"->needs++;case "INSUFFICIENT"->insufficient++;case "NO_EVIDENCE"->noEvidence++;case "NOT_ASSESSED"->notAssessed++;default->{}}}
      double earned=0,max=0;for(var record:report.path("records"))if("SCORED".equals(record.path("status").asText())&&evidenceIds.contains(record.path("id").asText())){earned+=record.path("score").asDouble();max+=record.path("maxScore").asDouble();}
      summaries.add(obj("classId",classes.get(first.path("studentId").asText()),"nodeId",first.path("nodeId"),"indicatorId",first.path("indicatorId"),"studentCount",items.size(),"coveredStudentCount",covered,"scoredCount",scored,"scorePercent",max==0?null:100*earned/max,"needsPracticeCount",needs,"insufficientCount",insufficient,"noEvidenceCount",noEvidence,"notAssessedCount",notAssessed));
    }
    return summaries;
  }
  public ObjectNode studentDetail(String token,String id,String node){var s=session(token,false);own(s,id);var state=state(false);var r=projection(report(state),s,state);var st=find(r,"students",id);require(st!=null,ErrorCode.RESOURCE_NOT_FOUND,"学生不存在");var obs=json.createArrayNode();var rec=json.createArrayNode();JsonNode sum=json.nullNode();for(var o:r.path("observations"))if(id.equals(o.path("studentId").asText())&&(node==null||node.equals(o.path("nodeId").asText())))obs.add(o);for(var o:r.path("recommendations"))if(id.equals(o.path("studentId").asText())&&(node==null||node.equals(o.path("nodeId").asText())))rec.add(o);for(var x:r.path("studentSummaries"))if(id.equals(x.path("studentId").asText())&&(node==null||node.equals(x.path("nodeId").asText()))){sum=x;break;}
    String nid=node==null?sum.path("nodeId").asText():node;return obj("revision",state.path("revision"),"student",st,"summary",sum,"observations",obs,"recommendations",rec,"plan",studentPlan(s,state,plan(state,id,nid)),"classPlan",CompanionTeachingPlanService.visibleClassPlan(state,r,id,nid));}
  public ObjectNode material(String token,String id){var s=session(token,false);var state=state(false);var m=find(projection(report(state),s,state),"materials",id);require(m!=null,ErrorCode.RESOURCE_NOT_FOUND,"材料不存在或不可见");return obj("revision",state.path("revision"),"material",teacher(s)?m:safeMaterial(m));}
  ObjectNode plan(ObjectNode state,String sid,String nid){return plan(state,sid,nid,report(state));}
  ObjectNode plan(ObjectNode state,String sid,String nid,JsonNode r){require(find(r,"students",sid)!=null&&find(r,"nodes",nid)!=null,ErrorCode.RESOURCE_NOT_FOUND,"学生或节点不存在");String key=sid+":"+nid;if(state.path("plans").has(key))return enrichPlan((ObjectNode)state.path("plans").path(key).deepCopy(),sid,nid,r);var steps=json.createArrayNode();int i=0;for(var rec:r.path("recommendations"))if(sid.equals(rec.path("studentId").asText())&&nid.equals(rec.path("nodeId").asText())&&!"本节点未测量".equals(rec.path("purpose").asText()))steps.add(obj("id","step-"+(++i),"title",rec.path("purpose"),"action","查看学习内容并核对相关练习","reason",rec.path("reason"),"indicatorId",rec.path("indicatorId"),"materialIds",rec.path("materialIds"),"evidenceIds",rec.path("evidenceIds"),"hints",List.of("先读规则，再逐步核对；需要时向教师求助")));
    return enrichPlan(obj("studentId",sid,"nodeId",nid,"revision",0,"dataRevision",state.path("revision"),"status","DRAFT","updatedAt",null,"steps",steps),sid,nid,r);}
  ObjectNode enrichPlan(ObjectNode p,String sid,String nid,JsonNode r){p.put("scopeType","STUDENT");p.set("classId",find(r,"students",sid).path("classId"));if(!p.has("constraints"))p.set("constraints",CompanionTeachingPlanService.defaults(this));return p;}
  ObjectNode studentPlan(Map<String,Object> session,ObjectNode state,ObjectNode plan){if(!teacher(session)&&(!"CONFIRMED".equals(plan.path("status").asText())||plan.path("dataRevision").asLong(-1)!=state.path("revision").asLong()))plan.set("steps",json.createArrayNode());return plan;}
  public ObjectNode getPlan(String token,String sid,String nid){var s=session(token,false);own(s,sid);var state=state(false);return studentPlan(s,state,plan(state,sid,nid));}
  public ObjectNode putPlan(String token,String sid,String nid,JsonNode input){session(token,true);var q=(ObjectNode)input.deepCopy();q.put("scopeType","STUDENT");q.put("studentId",sid);q.put("nodeId",nid);if(!q.has("constraints"))q.set("constraints",plan(state(false),sid,nid).path("constraints"));return new CompanionTeachingPlanService(this).put(token,q);}
  public ObjectNode feedback(String token,JsonNode input){var s=session(token,false);String sid=input.path("studentId").asText(),nid=input.path("nodeId").asText();own(s,sid);require(Set.of("READ","NEED_HELP","COMPLETED").contains(input.path("type").asText())&&input.path("comment").asText().length()<=2000,ErrorCode.VALIDATION_ERROR,"反馈格式无效");return tx.execute(t->{var state=state(true);String scope=input.path("planScope").asText("STUDENT");require(Set.of("STUDENT","CLASS").contains(scope),ErrorCode.VALIDATION_ERROR,"反馈目标无效");JsonNode p=scope.equals("CLASS")?CompanionTeachingPlanService.visibleClassPlan(state,report(state),sid,nid):plan(state,sid,nid);require(p!=null,ErrorCode.VALIDATION_ERROR,"班级计划尚未确认或已失效");if(!teacher(s)&&scope.equals("STUDENT"))require("CONFIRMED".equals(p.path("status").asText())&&p.path("dataRevision").asLong(-1)==state.path("revision").asLong(),ErrorCode.VALIDATION_ERROR,"个人计划尚未确认或已失效");boolean found=false;for(var step:p.path("steps"))if(step.path("id").equals(input.path("taskId")))found=true;require(found,ErrorCode.VALIDATION_ERROR,"任务不存在");var result=obj("id",UUID.randomUUID().toString(),"createdAt",OffsetDateTime.now().toString(),"dataRevision",state.path("revision"));var row=(ObjectNode)input.deepCopy();row.setAll(result);((ArrayNode)state.path("feedback")).add(row);save(state,false);return result;});}
  public JsonNode learning(String token,String indicator){session(token,false);return knowledge.learningContent(course,indicator);}
  String knowledgeVersion(JsonNode report){
    for(var indicator:report.path("indicators"))try{var card=knowledge.learningContent(course,indicator.path("id").asText());return card==null?"NOT_READY":card.path("version").asText("NOT_READY");}catch(BusinessException e){if(e.errorCode()==ErrorCode.RESOURCE_NOT_FOUND||e.errorCode()==ErrorCode.GRAPH_SERVICE_UNAVAILABLE)return "NOT_READY";throw e;}
    return "NOT_READY";
  }
  /** 学习内容与要求梳理：主题 → 观察指标 → 课标/教材出处与前置联系，并给出可进入的题目。 */
  public ObjectNode structure(String token,String nodeId){
    var s=session(token,false);var state=state(false);var r=projection(report(state),s,state);
    String nid=nodeId==null?"":nodeId.trim();JsonNode node=nid.isEmpty()?null:find(r,"nodes",nid);
    require(nid.isEmpty()||node!=null,ErrorCode.RESOURCE_NOT_FOUND,"测验范围不存在");
    Set<String> available=new HashSet<>();if(node!=null)node.path("availableMaterialIds").forEach(x->available.add(x.asText()));
    var byIndicator=new LinkedHashMap<String,List<JsonNode>>();
    for(var m:r.path("materials")){String mid=m.path("id").asText();if(!available.isEmpty()&&"题目".equals(m.path("type").asText())&&!available.contains(mid))continue;for(var i:m.path("indicatorIds"))byIndicator.computeIfAbsent(i.asText(),k->new ArrayList<>()).add(m);}
    var topics=new LinkedHashMap<String,List<String>>();var topicMeta=new LinkedHashMap<String,JsonNode>();
    for(var i:r.path("indicators")){String tid=i.path("topicId").asText();topics.computeIfAbsent(tid,k->new ArrayList<>()).add(i.path("id").asText());topicMeta.putIfAbsent(tid,i);}
    // One learning card per indicator; unapproved or missing content stays visible as NOT_READY instead of a blank page.
    var cards=new LinkedHashMap<String,JsonNode>();var kpToIndicator=new HashMap<String,String>();
    for(var i:r.path("indicators")){String id=i.path("id").asText();JsonNode card=null;
      try{card=knowledge.learningContent(course,id);}catch(BusinessException e){if(e.errorCode()!=ErrorCode.RESOURCE_NOT_FOUND&&e.errorCode()!=ErrorCode.GRAPH_SERVICE_UNAVAILABLE)throw e;}
      if(card!=null){cards.put(id,card);kpToIndicator.put(card.path("knowledgePointId").asText(),id);}
    }
    String version=cards.values().stream().findFirst().map(c->c.path("version").asText("NOT_READY")).orElse("NOT_READY");
    var indicators=json.createArrayNode();var edges=json.createArrayNode();
    for(var i:r.path("indicators")){
      String id=i.path("id").asText();var card=cards.get(id);
      var standards=json.createArrayNode();var textbooks=json.createArrayNode();
      if(card!=null)for(var source:card.path("sources"))(source.path("id").asText().endsWith("-STD")?standards:textbooks).add(obj("id",source.path("id"),"title",source.path("title"),"location",source.path("location"),"excerpt",source.path("excerpt")));
      var prerequisites=json.createArrayNode();
      if(card!=null)for(var p:card.path("prerequisites")){String pid=p.path("id").asText(),pi=kpToIndicator.get(pid);prerequisites.add(obj("indicatorId",pi==null?"":pi,"knowledgePointId",pid,"label",p.path("label"),"reason",p.path("reason"),"sourceIds",p.path("sourceIds")));if(pi!=null)edges.add(obj("source",pi,"target",id,"relation","PREREQUISITE"));}
      var materials=json.createArrayNode();var seen=new HashSet<String>();
      for(var m:byIndicator.getOrDefault(id,List.of()))if(seen.add(m.path("id").asText()))materials.add(obj("id",m.path("id"),"type",m.path("type"),"questionType",m.path("questionType"),"maxScore",m.path("maxScore"),"source",m.path("source")));
      int questions=0;for(var m:materials)if("题目".equals(m.path("type").asText()))questions++;
      indicators.add(obj("id",id,"label",i.path("label"),"topicId",i.path("topicId"),"topicName",i.path("topicName"),"topicGoal",i.path("topicGoal"),"standardReference",i.path("standardReference"),"standardPages",i.path("standardPages"),"textbookPages",i.path("textbookPages"),"knowledgePointId",card==null?"":card.path("knowledgePointId").asText(""),"summary",card==null?"":card.path("summary").asText(""),"learningSteps",card==null?json.createArrayNode():card.path("learningSteps"),"knowledgeStatus",card==null?"NOT_READY":"READY","standards",standards,"textbooks",textbooks,"prerequisites",prerequisites,"questionCount",questions,"materials",materials));
    }
    var topicsNode=json.createArrayNode();
    for(var entry:topics.entrySet()){var meta=topicMeta.get(entry.getKey());topicsNode.add(obj("id",entry.getKey(),"name",meta.path("topicName"),"goal",meta.path("topicGoal"),"indicatorIds",entry.getValue()));}
    return obj("workspaceId",Long.toString(course),"revision",state.path("revision"),"nodeId",nid,"knowledgeVersion",version,"topics",topicsNode,"indicators",indicators,"edges",edges,"limitations",r.path("limitations"));
  }
  public ObjectNode export(String token){
    session(token,true);var state=state(false);var r=report(state);var cards=json.createArrayNode();var sources=json.createArrayNode();var seen=new HashSet<String>();
    for(var indicator:r.path("indicators")){var card=knowledge.learningContent(course,indicator.path("id").asText());require(card!=null,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"学习基线尚未就绪，完整成果包暂不可导出");cards.add(card);for(var source:card.path("sources"))if(seen.add(source.path("id").asText()))sources.add(source);}
    var plans=json.createArrayNode();for(var student:r.path("students"))for(var node:r.path("nodes"))plans.add(plan(state,student.path("id").asText(),node.path("id").asText(),r));
    var classPlans=json.createArrayNode();for(var cp:state.path("classPlans")){boolean member=false;for(var st:r.path("students"))if(st.path("classId").equals(cp.path("classId")))member=true;if(member&&find(r,"nodes",cp.path("nodeId").asText())!=null)classPlans.add(cp);}var batches=json.createArrayNode();state.path("batches").forEach(b->batches.add(summary(b)));
    require(state(false).path("revision").equals(state.path("revision")),ErrorCode.CONCURRENT_MODIFICATION,"导出期间数据变化，请重试");
    return obj("revision",state.path("revision"),"generatedAt",OffsetDateTime.now().toString(),"knowledge",obj("cards",cards,"requirements",r.path("indicators")),"report",r,"plans",plans,"classPlans",classPlans,"sources",sources,"fields",obj("definitions",state.path("baseline").path("字段说明"),"batches",batches,"feedback",state.path("feedback"),"revision","当前有效来源数据版本；与方案版本分别管理"));
  }
  public ObjectNode assistant(String token,JsonNode input){var s=session(token,false);String sid=input.path("studentId").asText(),nid=input.path("nodeId").asText(),iid=input.path("indicatorId").asText();own(s,sid);require(Set.of("EXPLAIN","PLAN","HINT").contains(input.path("purpose").asText("EXPLAIN")),ErrorCode.VALIDATION_ERROR,"助手用途无效");var state=state(false);expected(state,input);var r=report(state);JsonNode observation=null;for(var o:r.path("observations"))if(sid.equals(o.path("studentId").asText())&&nid.equals(o.path("nodeId").asText())&&iid.equals(o.path("indicatorId").asText()))observation=o;require(observation!=null,ErrorCode.RESOURCE_NOT_FOUND,"当前观察不存在");var card=knowledge.learningContent(course,iid);String kp=card.path("knowledgePointId").asText();var sources=json.createArrayNode();var publicSources=json.createArrayNode();Set<String> sourceIds=new HashSet<>(),knowledgeIds=new HashSet<>(),evidenceIds=new HashSet<>();knowledgeIds.add(kp);
    var prerequisites=json.createArrayNode();var evidence=json.createArrayNode();
    var cards=new LinkedHashMap<String,JsonNode>();cards.put(kp,card);
    if(!card.path("prerequisites").isEmpty())for(var indicator:r.path("indicators")){var available=knowledge.learningContent(course,indicator.path("id").asText());require(available!=null,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"前置学习内容未就绪");cards.put(available.path("knowledgePointId").asText(),available);}
    for(var p:card.path("prerequisites")){
      String pid=p.path("id").asText();var pc=cards.get(pid);require(pc!=null,ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"前置知识未覆盖");knowledgeIds.add(pid);var observations=json.createArrayNode();
      for(var o:r.path("observations"))if(sid.equals(o.path("studentId").asText())&&nid.equals(o.path("nodeId").asText())&&pc.path("id").equals(o.path("indicatorId"))){observations.add(obj("indicator_id",o.path("indicatorId"),"status",o.path("status"),"scored_count",o.path("scoredCount"),"score_percent",o.path("scorePercent"),"evidence_ids",o.path("evidenceIds")));o.path("evidenceIds").forEach(id->evidenceIds.add(id.asText()));}
      prerequisites.add(obj("knowledge_point_id",pid,"name",pc.path("name"),"assessment","CHECK_REQUIRED","observations",observations));
    }
    for(String knowledgeId:knowledgeIds)for(var source:cards.get(knowledgeId).path("sources"))if(sourceIds.add(source.path("id").asText())){sources.add(obj("source_id",source.path("id"),"knowledge_point_id",knowledgeId,"title",source.path("title"),"locator",source.path("location"),"page_from",null,"page_to",null,"excerpt",source.path("excerpt")));publicSources.add(source);}
    Set<String> directIds=new HashSet<>();observation.path("evidenceIds").forEach(id->directIds.add(id.asText()));
    for(var record:r.path("records"))if(sid.equals(record.path("studentId").asText())&&nid.equals(record.path("nodeId").asText())&&directIds.contains(record.path("id").asText())&&evidence.size()<50){evidenceIds.add(record.path("id").asText());evidence.add(obj("record_id",record.path("id"),"material_id",record.path("materialId"),"score",record.path("score"),"max_score",record.path("maxScore"),"answer",record.path("answer"),"process",record.path("process"),"point_scores",record.path("pointScores"),"question","","explanation",""));}
    // Protected internal AI receives genuine coverage inputs; its Provider projection removes raw answers/process.
    var materials=json.createArrayNode();Set<String> materialIds=new HashSet<>();for(var m:r.path("materials"))if(SmartCompanionEngine.list(m.path("indicatorIds")).stream().anyMatch(i->iid.equals(i.asText()))&&materials.size()<30){materialIds.add(m.path("id").asText());materials.add(obj("material_id",m.path("id"),"type",m.path("type").asText().equals("题目")?"QUESTION":"RESOURCE","body",m.path("body"),"purpose","解释学习目标和方法；不提供标准答案"));}
    var request=obj("audience",teacher(s)?"TEACHER":"STUDENT","purpose",input.path("purpose").asText("EXPLAIN"),"course_id",course,"student_id",sid,"node_id",nid,"indicator_id",iid,"graph_version",card.path("version"),"knowledge_point",obj("id",kp,"name",card.path("name"),"description",card.path("summary").asText()+"\n受控用途："+input.path("purpose").asText("EXPLAIN")+"。HINT仅提供逐级提示，不公布参考答案；PLAN仅提供待教师审核建议。"),"observation",obj("status",observation.path("status"),"scored_count",observation.path("scoredCount"),"available_question_count",observation.path("availableQuestionCount"),"score_percent",observation.path("scorePercent")),"evidence",evidence,"prerequisites",prerequisites,"sources",sources,"materials",materials);
    var response=ai.explainSmartCompanion(request);CompanionKnowledgeService.validateResponse(response,knowledgeIds,sourceIds,evidenceIds,materialIds);
    require(state(false).path("revision").equals(state.path("revision")),ErrorCode.CONCURRENT_MODIFICATION,"解释期间数据已变化，请重新生成");var current=knowledge.learningContent(course,iid);require(current.path("version").equals(card.path("version")),ErrorCode.CONCURRENT_MODIFICATION,"学习内容版本已变化");var steps=json.createArrayNode();for(var step:response.path("steps"))steps.add(obj("title",step.path("title"),"action",step.path("action"),"rationale",step.path("rationale"),"knowledgePointIds",step.path("knowledge_point_ids"),"sourceIds",step.path("source_ids"),"evidenceIds",step.path("evidence_ids"),"materialIds",step.path("material_ids")));
    return obj("revision",state.path("revision"),"status","DRAFT","provider",response.path("provider"),"model",response.path("model"),"explanation",response.path("explanation"),"steps",steps,"sources",publicSources);
  }
}
