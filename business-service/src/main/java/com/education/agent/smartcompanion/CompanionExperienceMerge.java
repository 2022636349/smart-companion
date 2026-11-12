package com.education.agent.smartcompanion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source snapshots are immutable. Active source flags determine the effective data. */
final class CompanionExperienceMerge {
  final ObjectMapper json;
  final SmartCompanionEngine engine;
  CompanionExperienceMerge(ObjectMapper json) { this.json=json; engine=new SmartCompanionEngine(json); }
  static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
  String materialId(JsonNode row) {
    var sorted=new TreeMap<String,String>(); row.fields().forEachRemaining(e->{if(!e.getKey().startsWith("_")) sorted.put(e.getKey(),e.getValue().asText());});
    return row.path("材料编号").asText()+"~"+hash(sorted.toString()).substring(0,16);
  }
  List<Map<String,String>> rows(JsonNode source,String table) {
    var result=new ArrayList<Map<String,String>>();
    for(var r:source.path(table)) { var row=new LinkedHashMap<String,String>(); r.fields().forEachRemaining(e->row.put(e.getKey(),e.getValue().asText())); result.add(row); }
    return result;
  }
  /** Older persisted snapshots predate node bindings. Recover from that snapshot's own
   * materials, never from a later same-number replacement in another batch. */
  List<Map<String,String>> snapshotNodes(JsonNode source) {
    var nodes=rows(source,"测验节点");
    var local=new LinkedHashMap<String,Set<String>>();
    for(var material:source.path("题目与材料"))
      local.computeIfAbsent(material.path("材料编号").asText(),k->new LinkedHashSet<>()).add(materialId(material));
    for(var node:nodes)if(!node.containsKey("_availableVersions")) {
      var versions=new LinkedHashSet<String>();
      for(String original:SmartCompanionEngine.ids(node.getOrDefault("可用题目编号",""))) {
        if(original.contains("~")){versions.add(original);continue;}
        var candidates=local.getOrDefault(original,Set.of());
        if(candidates.size()==1)versions.add(candidates.iterator().next());
        else if(candidates.isEmpty())for(var record:source.path("作答记录"))
          if(node.getOrDefault("节点编号","").equals(record.path("节点编号").asText())
              &&original.equals(record.path("材料编号").asText())&&!record.path("_materialVersion").asText().isBlank())
            versions.add(record.path("_materialVersion").asText());
      }
      if(!versions.isEmpty())node.put("_availableVersions",String.join(";",versions));
    }
    return nodes;
  }
  Map<String,List<Map<String,String>>> raw(ObjectNode state) {
    var raw=new LinkedHashMap<String,List<Map<String,String>>>();
    raw.put("课程要求",rows(state.path("baseline"),"课程要求"));
    raw.put("字段说明",rows(state.path("baseline"),"字段说明"));
    var mats=new LinkedHashMap<String,Map<String,String>>();
    var students=new LinkedHashMap<String,Map<String,String>>();
    var nodes=new LinkedHashMap<String,Map<String,String>>();
    for(var r:snapshotNodes(state.path("baseline"))) nodes.put(r.get("节点编号"),r);
    for(var b:state.path("batches")) {
      if(!b.path("confirmed").asBoolean()) continue;
      var source=b.path("source");
      if(b.path("questionBankActive").asBoolean()) for(var m:source.path("题目与材料")) {
        var r=rows(json.createObjectNode().set("x",json.createArrayNode().add(m)),"x").get(0);
        r.put("材料编号",materialId(m)); mats.putIfAbsent(materialId(m),r);
      }
      if(b.path("learningActive").asBoolean()) {
        for(var r:rows(source,"模拟学生")) students.putIfAbsent(r.get("学生编号"),r);
        for(var r:snapshotNodes(source)) {
          var old=nodes.putIfAbsent(r.get("节点编号"),r);
          if(old!=null){var union=new LinkedHashSet<>(SmartCompanionEngine.ids(old.getOrDefault("可用题目编号","")));union.addAll(SmartCompanionEngine.ids(r.getOrDefault("可用题目编号","")));old.put("可用题目编号",String.join(";",union));if(old.containsKey("_availableVersions")||r.containsKey("_availableVersions")){var versions=new LinkedHashSet<>(SmartCompanionEngine.ids(old.getOrDefault("_availableVersions","")));versions.addAll(SmartCompanionEngine.ids(r.getOrDefault("_availableVersions","")));old.put("_availableVersions",String.join(";",versions));}}
        }
      }
    }
    var records=new LinkedHashMap<String,Map<String,String>>();
    for(var b:state.path("batches")) if(b.path("confirmed").asBoolean()&&b.path("learningActive").asBoolean()) {
      for(var r:rows(b.path("source"),"作答记录")) {
        String original=r.getOrDefault("材料编号","");
        String bound=r.getOrDefault("_materialVersion","");
        if(bound.isEmpty()) {
          var candidates=mats.keySet().stream().filter(k->k.startsWith(original+"~")).toList();
          if(candidates.size()==1) bound=candidates.get(0);
        }
        r.put("_originalMaterialId",original); r.put("材料编号",bound.isEmpty()?original:bound);
        var signature=new TreeMap<>(r); signature.remove("记录编号");
        records.putIfAbsent(signature.toString(),r);
      }
    }
    for(var n:nodes.values()) {
      var available=SmartCompanionEngine.ids(n.getOrDefault("可用题目编号",""));
      var expanded=new LinkedHashSet<String>();
      if(n.containsKey("_availableVersions"))for(var id:SmartCompanionEngine.ids(n.get("_availableVersions"))) {if(mats.containsKey(id))expanded.add(id);}
      else for(var id:available){var matches=mats.keySet().stream().filter(k->k.equals(id)||k.startsWith(id+"~")).toList();if(matches.size()==1)expanded.add(matches.get(0));}
      n.put("可用题目编号",String.join(";",expanded));
    }
    raw.put("题目与材料",new ArrayList<>(mats.values())); raw.put("模拟学生",new ArrayList<>(students.values()));
    raw.put("测验节点",new ArrayList<>(nodes.values())); raw.put("作答记录",new ArrayList<>(records.values()));
    return raw;
  }
  ObjectNode report(ObjectNode state,long course,long revision) {
    var parsed=engine.parse(raw(state),true);
    var report=engine.report(parsed,"experience",Long.toString(course),"当前有效批次来源",Long.toString(revision));
    for(var m:report.path("materials")) ((ObjectNode)m).put("originalId",m.path("id").asText().split("~",2)[0]);
    return report;
  }
  void bind(ObjectNode state,ObjectNode source) {
    var effective=raw(state);
    var known=new LinkedHashMap<String,Set<String>>();
    for(var r:effective.get("题目与材料")) { String id=r.get("材料编号"); known.computeIfAbsent(id.split("~",2)[0],k->new LinkedHashSet<>()).add(id); }
    var local=new LinkedHashMap<String,Set<String>>();
    for(var m:source.path("题目与材料")) local.computeIfAbsent(m.path("材料编号").asText(),k->new LinkedHashSet<>()).add(materialId(m));
    for(var n:source.path("测验节点")) {
      var available=new LinkedHashSet<String>(SmartCompanionEngine.ids(n.path("_availableVersions").asText()));
      for(String id:SmartCompanionEngine.ids(n.path("可用题目编号").asText())){
        if(id.contains("~")){available.add(id);continue;}
        if(available.stream().anyMatch(v->v.startsWith(id+"~")))continue;
        var options=local.getOrDefault(id,known.getOrDefault(id,Set.of()));if(options.size()==1)available.add(options.iterator().next());
      }
      ((ObjectNode)n).put("_availableVersions",String.join(";",available));
    }
    for(var r:source.path("作答记录")) {
      if(r.hasNonNull("_materialVersion")&&!r.path("_materialVersion").asText().isBlank())continue;
      String id=r.path("材料编号").asText();
      Set<String> options=local.get(id);
      if(options==null){
        var eventVersions=new LinkedHashSet<String>();
        for(var old:effective.get("作答记录"))if(r.path("作答事件编号").asText().equals(old.get("作答事件编号"))&&old.getOrDefault("材料编号","").startsWith(id+"~"))eventVersions.add(old.get("材料编号"));
        options=eventVersions;
        if(options.size()!=1){var nodeVersions=new LinkedHashSet<String>();for(var node:effective.get("测验节点"))if(r.path("节点编号").asText().equals(node.get("节点编号")))for(String version:SmartCompanionEngine.ids(node.getOrDefault("可用题目编号","")))if(version.startsWith(id+"~"))nodeVersions.add(version);options=nodeVersions;}
        if(options.size()!=1)options=known.getOrDefault(id,Set.of());
      }
      if(options.size()==1) ((ObjectNode)r).put("_materialVersion",options.iterator().next());
    }
  }
}
