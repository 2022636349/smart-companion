package com.education.agent.smartcompanion;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Deterministic analysis of an isolated external batch, never official grades. */
public final class SmartCompanionEngine {
  final ObjectMapper json;

  public SmartCompanionEngine(ObjectMapper json) {
    this.json = json;
  }

  ObjectNode obj(Object... pairs) {
    var n = json.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2)
      n.set(pairs[i].toString(), json.valueToTree(pairs[i + 1]));
    return n;
  }

  static String s(Map<String, String> r, String k) {
    return r.getOrDefault(k, "");
  }

  static Double num(String s) {
    try {
      double d = Double.parseDouble(s);
      return Double.isFinite(d) ? d : null;
    } catch (Exception e) {
      return null;
    }
  }

  static List<String> ids(String s) {
    return s.isBlank() ? List.of() : Arrays.asList(s.split("[、,，；;\\s]+"));
  }

  static Integer revision(Map<String, String> r) {
    try {
      int v = Integer.parseInt(s(r, "修订版本"));
      return v > 0 ? v : null;
    } catch (Exception e) {
      return null;
    }
  }

  void issue(
      ArrayNode issues,
      String severity,
      String table,
      Integer row,
      String id,
      String code,
      String message) {
    issues.add(
        obj(
            "severity",
            severity,
            "table",
            table,
            "rowNumber",
            row,
            "recordId",
            id,
            "code",
            code,
            "message",
            message));
  }

  public ObjectNode parse(Map<String, List<Map<String, String>>> tables) {
    return parse(tables, false);
  }

  ObjectNode parse(Map<String, List<Map<String, String>>> tables, boolean partial) {
    var issues = json.createArrayNode();
    var students = json.createArrayNode();
    var nodes = json.createArrayNode();
    var indicators = json.createArrayNode();
    var materials = json.createArrayNode();
    var records = json.createArrayNode();
    var qr = json.createArrayNode();
    Map<String, Map<String, String>> sm = new LinkedHashMap<>(),
        nm = new LinkedHashMap<>(),
        im = new LinkedHashMap<>(),
        mm = new LinkedHashMap<>();
    Map<String, String> pk =
        Map.of("模拟学生", "学生编号", "测验节点", "节点编号", "课程要求", "指标编号", "题目与材料", "材料编号");
    Map<String, Map<String, Map<String, String>>> indexes =
        Map.of("模拟学生", sm, "测验节点", nm, "课程要求", im, "题目与材料", mm);
    Map<String, List<String>> headers =
        Map.of(
            "模拟学生",
            List.of("学生编号", "模拟班级编号", "年级"),
            "测验节点",
            List.of("节点编号", "模拟测验日期", "已教及测量主题", "说明", "可用题目编号"),
            "课程要求",
            List.of("主题编号", "主题", "主题目标", "指标编号", "观察指标", "课标整理编号", "课标文件页／正文页", "教材正文页"),
            "题目与材料",
            List.of(
                "材料编号", "主题编号", "材料类型", "题型", "题干或内容", "选项", "参考答案", "参考解析", "满分", "评分点", "指标编号",
                "来源或资料文件"),
            "作答记录",
            List.of(
                "记录编号", "学生编号", "材料编号", "主题编号", "节点编号", "作答事件编号", "修订版本", "模拟提交时间", "评分状态", "得分",
                "满分", "学生最终作答", "解答过程", "评分点得分"),
            "字段说明",
            List.of("数据表", "字段名", "类型", "含义", "取值或格式", "示例", "为空时"));
    for (String t : SmartCompanionReader.TABLES) {
      var rows = tables.get(t);
      if (rows == null || rows.isEmpty()) {
        if (!partial) issue(issues, "ERROR", t, null, null, "MISSING_TABLE", "必需数据表缺失或为空");
        continue;
      }
      if (!rows.get(0).keySet().containsAll(headers.get(t)))
        issue(issues, "ERROR", t, 1, null, "MISSING_COLUMN", "缺少必需列");
      if (pk.containsKey(t)) {
        int i = 1;
        for (var r : rows) {
          i++;
          String id = s(r, pk.get(t));
          if (id.isBlank() || indexes.get(t).putIfAbsent(id, r) != null)
            issue(issues, "ERROR", t, i, id, "ENTITY_ID", "实体编号为空或重复");
        }
      }
    }
    sm.forEach(
        (id, r) -> students.add(obj("id", id, "classId", s(r, "模拟班级编号"), "grade", s(r, "年级"))));
    nm.forEach(
        (id, r) -> {
          nodes.add(
              obj(
                  "id",
                  id,
                  "date",
                  s(r, "模拟测验日期"),
                  "scope",
                  s(r, "已教及测量主题"),
                  "description",
                  s(r, "说明"),
                  "availableMaterialIds",
                  ids(s(r, "可用题目编号"))));
          try {
            LocalDate.parse(s(r, "模拟测验日期"));
          } catch (Exception e) {
            issue(issues, "ERROR", "测验节点", null, id, "DATE", "节点日期无效");
          }
        });
    im.forEach(
        (id, r) ->
            indicators.add(
                obj(
                    "id",
                    id,
                    "topicId",
                    s(r, "主题编号"),
                    "topicName",
                    s(r, "主题"),
                    "topicGoal",
                    s(r, "主题目标"),
                    "label",
                    s(r, "观察指标"),
                    "standardReference",
                    s(r, "课标整理编号"),
                    "standardPages",
                    s(r, "课标文件页／正文页"),
                    "textbookPages",
                    s(r, "教材正文页"))));
    mm.forEach(
        (id, r) -> {
          materials.add(
              obj(
                  "id",
                  id,
                  "topicId",
                  s(r, "主题编号").isEmpty() ? null : s(r, "主题编号"),
                  "type",
                  s(r, "材料类型"),
                  "questionType",
                  s(r, "题型"),
                  "body",
                  s(r, "题干或内容"),
                  "options",
                  s(r, "选项"),
                  "answer",
                  s(r, "参考答案"),
                  "explanation",
                  s(r, "参考解析"),
                  "maxScore",
                  num(s(r, "满分")),
                  "scoringPoints",
                  s(r, "评分点"),
                  "indicatorIds",
                  ids(s(r, "指标编号")),
                  "source",
                  s(r, "来源或资料文件")));
          for (String indicator : ids(s(r, "指标编号")))
            if (!im.containsKey(indicator))
              issue(issues, "ERROR", "题目与材料", null, id, "INDICATOR", "材料引用不存在的指标");
          if ("题目".equals(s(r, "材料类型")) && (num(s(r, "满分")) == null || num(s(r, "满分")) <= 0))
            issue(issues, "ERROR", "题目与材料", null, id, "MAX_SCORE", "题目满分无效");
        });
    nm.forEach(
        (id, r) -> {
          for (String m : ids(s(r, "可用题目编号")))
            if (!mm.containsKey(m) || !"题目".equals(s(mm.get(m), "材料类型")))
              issue(issues, "ERROR", "测验节点", null, id, "AVAILABLE_MATERIAL", "节点可用题目不存在或不是题目");
        });
    if ((long) sm.size() * nm.size() * im.size() > 20000 || sm.size() > 1000 || mm.size() > 5000)
      issue(issues, "ERROR", "文件", null, null, "REPORT_BUDGET", "学生×节点×指标超过20000报告单元预算，或实体数量过多");
    var raw = tables.getOrDefault("作答记录", List.of());
    var grouped = new LinkedHashMap<String, List<Integer>>();
    var recordIds = new HashSet<String>();
    for (int i = 0; i < raw.size(); i++) {
      var r = raw.get(i);
      String id = s(r, "记录编号");
      if (id.isBlank()) issue(issues, "ERROR", "作答记录", i + 2, id, "RECORD_ID", "记录编号为空");
      recordIds.add(id);
      grouped.computeIfAbsent(s(r, "作答事件编号"), k -> new ArrayList<>()).add(i);
    }
    var conflictingIds = new HashSet<String>();
    var byRecordId = new HashMap<String, Map<String, String>>();
    for (var r : raw) {
      String id = s(r, "记录编号");
      var prior = byRecordId.putIfAbsent(id, r);
      if (prior != null && !prior.equals(r)) conflictingIds.add(id);
    }
    var disposition = new HashMap<Integer, String>();
    var reasons = new HashMap<Integer, List<String>>();
    for (var group : grouped.values()) {
      int max =
          group.stream()
              .map(i -> revision(raw.get(i)))
              .filter(Objects::nonNull)
              .max(Integer::compare)
              .orElse(0);
      boolean badRevision = group.stream().anyMatch(i -> revision(raw.get(i)) == null);
      var top = group.stream().filter(i -> Objects.equals(revision(raw.get(i)), max)).toList();
      boolean conflict =
          top.stream()
                  .map(
                      i -> {
                        var copy = new LinkedHashMap<>(raw.get(i));
                        copy.remove("记录编号");
                        return copy;
                      })
                  .distinct()
                  .count()
              > 1;
      boolean selected = false;
      for (int i : group) {
        var r = raw.get(i);
        var why = new ArrayList<String>();
        String d;
        if (badRevision) {
          d = "INVALID";
          why.add("事件含无效修订版本，不能可靠选择最高版");
        } else if (revision(r) < max) {
          d = "SUPERSEDED";
          why.add("已被同事件更高修订版本替代");
        } else if (conflict) {
          d = "CONFLICT";
          why.add("同事件最高版本内容冲突，整事件隔离");
        } else if (selected) {
          d = "DUPLICATE";
          why.add("同事件最高版本内容完全重复");
        } else if (group.stream().anyMatch(j -> conflictingIds.contains(s(raw.get(j), "记录编号")))) {
          d = "CONFLICT";
          why.add("记录编号对应不同内容，事件隔离");
        } else {
          selected = true;
          validate(r, sm, nm, mm, why);
          d = why.isEmpty() ? "SELECTED" : "INVALID";
          if (why.isEmpty()) records.add(normalize(r));
        }
        disposition.put(i, d);
        reasons.put(i, why);
      }
    }
    var quality =
        obj(
            "rawRecordCount",
            raw.size(),
            "effectiveRecordCount",
            records.size(),
            "scoredCount",
            0,
            "pendingCount",
            0,
            "unansweredCount",
            0,
            "supersededCount",
            0,
            "duplicateCount",
            0,
            "invalidCount",
            0,
            "conflictCount",
            0);
    for (var r : records) {
      String k =
          switch (r.path("status").asText()) {
            case "SCORED" -> "scoredCount";
            case "PENDING" -> "pendingCount";
            default -> "unansweredCount";
          };
      quality.put(k, quality.path(k).asInt() + 1);
    }
    for (int i = 0; i < raw.size(); i++) {
      var r = raw.get(i);
      String d = disposition.get(i);
      qr.add(
          obj(
              "rowNumber",
              i + 2,
              "recordId",
              s(r, "记录编号"),
              "eventId",
              s(r, "作答事件编号"),
              "revision",
              revision(r),
              "disposition",
              d,
              "reasons",
              reasons.get(i)));
      if (!d.equals("SELECTED")) {
        String k = d.toLowerCase(Locale.ROOT) + "Count";
        quality.put(k, quality.path(k).asInt() + 1);
        issue(issues, "WARNING", "作答记录", i + 2, s(r, "记录编号"), d, String.join("；", reasons.get(i)));
      }
    }
    quality.set("rows", qr);
    boolean can = true;
    for (var issue : issues) if (issue.path("severity").asText().equals("ERROR")) can = false;
    return obj(
        "preview",
        obj(
            "canConfirm",
            can,
            "quality",
            quality,
            "issues",
            issues,
            "notes",
            List.of(
                "输入是外部模拟学情，未写入正式成绩或学生账户。",
                "先取最高修订版本，再验证并隔离异常；后续依赖评分点零分不作为独立错误。",
                tables.containsKey("数据说明") ? "已读取并保存xlsx数据说明。" : "CSV包无额外xlsx说明；评分点依赖须由教师核查。"),
            "students",
            students,
            "nodes",
            nodes),
        "students",
        students,
        "nodes",
        nodes,
        "indicators",
        indicators,
        "materials",
        materials,
        "records",
        records);
  }

  ObjectNode normalize(Map<String, String> r) {
    return obj(
        "id",
        s(r, "记录编号"),
        "studentId",
        s(r, "学生编号"),
        "materialId",
        s(r, "材料编号"),
        "topicId",
        s(r, "主题编号"),
        "nodeId",
        s(r, "节点编号"),
        "eventId",
        s(r, "作答事件编号"),
        "revision",
        revision(r),
        "submittedAt",
        s(r, "模拟提交时间"),
        "status",
        switch (s(r, "评分状态")) {
          case "已评分" -> "SCORED";
          case "待评分" -> "PENDING";
          default -> "UNANSWERED";
        },
        "score",
        num(s(r, "得分")),
        "maxScore",
        num(s(r, "满分")),
        "answer",
        s(r, "学生最终作答"),
        "process",
        s(r, "解答过程"),
        "pointScores",
        s(r, "评分点得分"));
  }

  void validate(
      Map<String, String> r,
      Map<String, Map<String, String>> students,
      Map<String, Map<String, String>> nodes,
      Map<String, Map<String, String>> materials,
      List<String> why) {
    var m = materials.get(s(r, "材料编号"));
    var n = nodes.get(s(r, "节点编号"));
    if (!students.containsKey(s(r, "学生编号"))) why.add("学生不存在");
    if (m == null) why.add("材料不存在");
    if (n == null) why.add("节点不存在");
    if (!s(r, "作答事件编号").equals(s(r, "学生编号") + "-" + s(r, "节点编号") + "-" + r.getOrDefault("_originalMaterialId", s(r, "材料编号"))))
      why.add("事件编号与学生、节点、材料不一致");
    if (m != null) {
      if (!s(m, "主题编号").equals(s(r, "主题编号"))) why.add("主题与材料不符");
      if (!Objects.equals(num(s(m, "满分")), num(s(r, "满分")))) why.add("满分与材料不符");
    }
    if (n != null) {
      if (!ids(s(n, "可用题目编号")).contains(s(r, "材料编号"))) why.add("材料不在节点可用范围");
      try {
        var t = OffsetDateTime.parse(s(r, "模拟提交时间"));
        if (!t.getOffset().equals(ZoneOffset.ofHours(8))
            || t.toLocalDate().isBefore(LocalDate.parse(s(n, "模拟测验日期")))) why.add("时间不符节点或北京时间");
      } catch (Exception e) {
        why.add("提交或评分时间格式无效");
      }
    }
    String state = s(r, "评分状态");
    Double score = num(s(r, "得分")), max = num(s(r, "满分"));
    if (max == null || max <= 0) why.add("满分无效");
    if (state.equals("已评分")) {
      if (score == null || max == null || score < 0 || score > max || score != Math.rint(score))
        why.add("得分缺失或越界");
      if (m != null) {
        var points = new LinkedHashMap<String, Double>();
        var matcher = Pattern.compile("(?m)^(\\S+)\\s+.*?([0-9]+)分\\s*$").matcher(s(m, "评分点"));
        while (matcher.find()) points.put(matcher.group(1), Double.valueOf(matcher.group(2)));
        var seen = new HashSet<String>();
        double sum = 0;
        boolean bad = points.isEmpty();
        for (String item : s(r, "评分点得分").split("；")) {
          String[] pair = item.split("=", -1);
          Double v = pair.length == 2 ? num(pair[1]) : null;
          if (pair.length != 2
              || !seen.add(pair[0])
              || v == null
              || !points.containsKey(pair[0])
              || v < 0
              || v > points.getOrDefault(pair[0], 0d)
              || v != Math.rint(v)) {
            bad = true;
            continue;
          }
          sum += v;
        }
        if (bad || seen.size() != points.size() || score == null || sum != score)
          why.add("评分点编号、范围或合计与得分不符");
      }
    } else if (state.equals("待评分") || state.equals("未作答")) {
      if (!s(r, "得分").isBlank() || !s(r, "评分点得分").isBlank()) why.add("未评分状态不得带得分");
      if (state.equals("未作答") && !s(r, "学生最终作答").isBlank()) why.add("未作答状态带最终作答");
    } else why.add("评分状态无效");
  }

  static List<JsonNode> list(JsonNode n) {
    var a = new ArrayList<JsonNode>();
    n.forEach(a::add);
    return a;
  }

  static Double percent(double a, double b) {
    return b == 0 ? null : 100 * a / b;
  }

  ObjectNode stats(List<JsonNode> records) {
    var scored = records.stream().filter(r -> r.path("status").asText().equals("SCORED")).toList();
    double earned = scored.stream().mapToDouble(r -> r.path("score").asDouble()).sum(),
        max = scored.stream().mapToDouble(r -> r.path("maxScore").asDouble()).sum();
    return obj(
        "scoredCount",
        scored.size(),
        "pendingCount",
        records.stream().filter(r -> r.path("status").asText().equals("PENDING")).count(),
        "unansweredCount",
        records.stream().filter(r -> r.path("status").asText().equals("UNANSWERED")).count(),
        "earnedScore",
        earned,
        "maxScore",
        max,
        "scorePercent",
        percent(earned, max),
        "meanRecordPercent",
        scored.isEmpty()
            ? null
            : scored.stream()
                .mapToDouble(r -> 100 * r.path("score").asDouble() / r.path("maxScore").asDouble())
                .average()
                .orElse(0));
  }

  public ObjectNode report(
      JsonNode data, String importId, String courseId, String label, String at) {
    if ((long) data.path("students").size()
            * data.path("nodes").size()
            * data.path("indicators").size()
        > 20000) throw new IllegalArgumentException("报告单元超过预算");
    var result =
        obj(
            "version",
            "official-1.0",
            "importId",
            importId,
            "courseId",
            courseId,
            "sourceLabel",
            label,
            "generatedAt",
            at,
            "method",
            List.of(
                "同事件先选最高修订，再验范围与评分点；无效最高版不回退。",
                "得分率按满分加权；逐题均值与逐生均值分别呈现。",
                "至少3道不同题与60/85分档为项目演示规则。"),
            "limitations",
            List.of(
                "输入是模拟数据，建议为教师可调整草稿，不是校准能力诊断或正式成绩。",
                "提交/评分时间不能推断学习时长；两节点范围不同，不能断言教学效果。",
                "依赖前一步结果的后续零分不能当作独立错误；教材空指标不自动关联。"));
    for (String k : List.of("students", "nodes", "indicators", "materials", "records"))
      result.set(k, data.path(k));
    result.set("quality", data.path("preview").path("quality"));
    var ss = result.putArray("studentSummaries");
    var gs = result.putArray("groupSummaries");
    var obs = result.putArray("observations");
    var recs = result.putArray("recommendations");
    var records = list(data.path("records"));
    var materials = list(data.path("materials"));
    var students = list(data.path("students"));
    for (var node : data.path("nodes")) {
      String nid = node.path("id").asText();
      Set<String> available = new LinkedHashSet<>();
      node.path("availableMaterialIds").forEach(m -> available.add(m.asText()));
      for (var student : students) {
        String sid = student.path("id").asText();
        var rs =
            records.stream()
                .filter(
                    r ->
                        r.path("studentId").asText().equals(sid)
                            && r.path("nodeId").asText().equals(nid))
                .toList();
        var sum = stats(rs);
        sum.put("studentId", sid);
        sum.put("nodeId", nid);
        sum.put("availableQuestionCount", available.size());
        long distinct =
            rs.stream()
                .filter(r -> r.path("status").asText().equals("SCORED"))
                .map(r -> r.path("materialId").asText())
                .distinct()
                .count();
        sum.set("coveragePercent", json.valueToTree(percent(distinct, available.size())));
        ss.add(sum);
        for (var indicator : data.path("indicators")) {
          String iid = indicator.path("id").asText();
          var linked =
              materials.stream()
                  .filter(
                      m ->
                          list(m.path("indicatorIds")).stream()
                              .anyMatch(v -> v.asText().equals(iid)))
                  .toList();
          Set<String> questions = new LinkedHashSet<>();
          linked.stream()
              .filter(m -> available.contains(m.path("id").asText()))
              .forEach(m -> questions.add(m.path("id").asText()));
          var evidence =
              rs.stream()
                  .filter(
                      r ->
                          questions.contains(r.path("materialId").asText())
                              && r.path("status").asText().equals("SCORED"))
                  .toList();
          var st = stats(evidence);
          int distinctCount =
              (int) evidence.stream().map(r -> r.path("materialId").asText()).distinct().count();
          String status =
              questions.isEmpty()
                  ? "NOT_ASSESSED"
                  : evidence.isEmpty()
                      ? "NO_EVIDENCE"
                      : distinctCount < 3
                          ? "INSUFFICIENT"
                          : st.path("scorePercent").asDouble() < 60
                              ? "NEEDS_PRACTICE"
                              : st.path("scorePercent").asDouble() < 85 ? "DEVELOPING" : "READY";
          var evidenceIds =
              rs.stream()
                  .filter(r -> questions.contains(r.path("materialId").asText()))
                  .map(r -> r.path("id").asText())
                  .toList();
          obs.add(
              obj(
                  "studentId",
                  sid,
                  "nodeId",
                  nid,
                  "indicatorId",
                  iid,
                  "availableQuestionCount",
                  questions.size(),
                  "scoredCount",
                  evidence.size(),
                  "scorePercent",
                  st.get("scorePercent"),
                  "status",
                  status,
                  "evidenceIds",
                  evidenceIds));
          var candidates = new ArrayList<String>();
          var scoredMaterials = new HashSet<String>();
          evidence.forEach(r -> scoredMaterials.add(r.path("materialId").asText()));
          var unscoredQuestions =
              questions.stream().filter(q -> !scoredMaterials.contains(q)).toList();
          var lost =
              evidence.stream()
                  .filter(r -> r.path("score").asDouble() < r.path("maxScore").asDouble())
                  .toList();
          if (!status.equals("NOT_ASSESSED")) {
            if (status.equals("NEEDS_PRACTICE") || status.equals("DEVELOPING")) {
              linked.stream()
                  .filter(m -> !m.path("type").asText().equals("题目"))
                  .limit(1)
                  .forEach(m -> candidates.add(m.path("id").asText()));
              lost.stream()
                  .map(r -> r.path("materialId").asText())
                  .distinct()
                  .limit(2)
                  .forEach(candidates::add);
            }
            for (String q : unscoredQuestions) if (candidates.size() < 3) candidates.add(q);
          }
          String purpose =
              switch (status) {
                case "NOT_ASSESSED" -> "本节点未测量";
                case "NO_EVIDENCE" -> "补充观察或人工核查";
                case "INSUFFICIENT" -> "补充不同题与人工核查";
                case "NEEDS_PRACTICE" -> "基础练习";
                case "DEVELOPING" -> "巩固练习";
                default -> "变式观察";
              };
          String reason =
              questions.isEmpty()
                  ? "节点无该指标可用题，不判断薄弱、不强排新课。"
                  : "已评分不同题"
                      + distinctCount
                      + "/本节点可用"
                      + questions.size()
                      + "题；有效得分"
                      + st.path("earnedScore").asDouble()
                      + "/满分"
                      + st.path("maxScore").asDouble()
                      + "。";
          if (!questions.isEmpty()) {
            if (status.equals("NO_EVIDENCE") || status.equals("INSUFFICIENT"))
              reason += "优先补充未评分的不同题，并由教师核查待评分与未答；至少3道不同题后重新观察。";
            else if (status.equals("READY")) reason += "优先解释未做题的方法选择与变式，补充新证据后重新观察。";
            else {
              reason += "先读关联学习卡，再解释失分题的逐步依据，完成不同题后重新观察。";
              if (!lost.isEmpty()) {
                var r = lost.get(0);
                reason +=
                    "本次记录"
                        + r.path("id").asText()
                        + "（题"
                        + r.path("materialId").asText()
                        + "）得分"
                        + r.path("score").asDouble()
                        + "/"
                        + r.path("maxScore").asDouble()
                        + "，评分点为"
                        + r.path("pointScores").asText()
                        + "；须对照原解答过程核查，后续依赖步骤零分不另判独立错因。";
              }
            }
            if (questions.size() < 3) reason += "本节点题目不足，不作稳定掌握判断。";
            if (unscoredQuestions.isEmpty()) reason += "本节点没有更多未评分的不同题，现有材料不足以增加不同题覆盖，请教师补充或人工核查。";
            if (candidates.isEmpty()) reason += "关联候选材料不足。";
          }
          recs.add(
              obj(
                  "studentId",
                  sid,
                  "nodeId",
                  nid,
                  "indicatorId",
                  iid,
                  "purpose",
                  purpose,
                  "reason",
                  reason,
                  "materialIds",
                  candidates,
                  "evidenceIds",
                  evidenceIds,
                  "draft",
                  true));
        }
      }
      var classes = new LinkedHashSet<String>();
      classes.add("ALL");
      students.forEach(s -> classes.add(s.path("classId").asText()));
      for (String cid : classes) {
        var members =
            students.stream()
                .filter(s -> cid.equals("ALL") || s.path("classId").asText().equals(cid))
                .map(s -> s.path("id").asText())
                .toList();
        var rs =
            records.stream()
                .filter(
                    r ->
                        members.contains(r.path("studentId").asText())
                            && r.path("nodeId").asText().equals(nid))
                .toList();
        var st = stats(rs);
        st.remove("meanRecordPercent");
        var scoredStudents =
            list(ss).stream()
                .filter(
                    s ->
                        s.path("nodeId").asText().equals(nid)
                            && members.contains(s.path("studentId").asText())
                            && !s.path("scorePercent").isNull())
                .toList();
        st.put("classId", cid);
        st.put("nodeId", nid);
        st.put("totalStudentCount", members.size());
        st.put("scoredStudentCount", scoredStudents.size());
        st.set(
            "studentMeanPercent",
            json.valueToTree(
                scoredStudents.isEmpty()
                    ? null
                    : scoredStudents.stream()
                        .mapToDouble(s -> s.path("scorePercent").asDouble())
                        .average()
                        .orElse(0)));
        gs.add(st);
      }
    }
    return result;
  }
}
