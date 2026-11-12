package com.education.agent.smartcompanion;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SmartCompanionEngineTest {
  static final Path BASE =
      Path.of("D:/CodexEnvs/EducationAgent/smart-companion/input/official-20261106");
  final ObjectMapper json = new ObjectMapper();
  final SmartCompanionEngine engine = new SmartCompanionEngine(json);

  Map<String, List<Map<String, String>>> raw() throws Exception {
    Assumptions.assumeTrue(
        Files.exists(BASE.resolve("source.zip")),
        "Official package absent: golden verification explicitly skipped");
    return new SmartCompanionReader()
        .read(Files.readAllBytes(BASE.resolve("source.zip")), "source.zip");
  }

  @Test
  void officialZipAndXlsxMatchIndependentGolden() throws Exception {
    var raw = raw();
    var parsed = engine.parse(raw);
    assertThat(parsed.path("preview").path("canConfirm").asBoolean())
        .as(parsed.path("preview").path("issues").toPrettyString())
        .isTrue();
    var q = parsed.path("preview").path("quality");
    assertThat(q.path("effectiveRecordCount").asInt()).isEqualTo(794);
    assertThat(q.path("scoredCount").asInt()).isEqualTo(785);
    assertThat(q.path("invalidCount").asInt()).isEqualTo(5);
    assertThat(q.path("supersededCount").asInt()).isEqualTo(7);
    assertThat(q.path("pendingCount").asInt()).isEqualTo(4);
    assertThat(q.path("unansweredCount").asInt()).isEqualTo(5);
    var stats = engine.stats(SmartCompanionEngine.list(parsed.path("records")));
    assertThat(stats.path("earnedScore").asDouble()).isEqualTo(741);
    assertThat(stats.path("maxScore").asDouble()).isEqualTo(1301);
    Path xlsx;
    try (var paths = Files.walk(BASE.resolve("raw"))) {
      xlsx = paths.filter(p -> p.toString().endsWith(".xlsx")).findFirst().orElseThrow();
    }
    var fromXlsx =
        engine.parse(new SmartCompanionReader().read(Files.readAllBytes(xlsx), "data.xlsx"));
    assertThat(fromXlsx).isEqualTo(parsed);
    assertThat(raw).containsKey("数据说明");
    var report = engine.report(parsed, "1", "1", "test", "fixed");
    var golden =
        json.readTree(BASE.resolve("inspection/student-node-indicator-golden.json").toFile());
    for (var o : report.path("observations")) {
      var g =
          golden
              .path(o.path("studentId").asText())
              .path(o.path("nodeId").asText())
              .path(o.path("indicatorId").asText());
      assertThat(o.path("scoredCount").asInt()).isEqualTo(g.path("scored").asInt());
      assertThat(new HashSet<>(SmartCompanionEngine.list(o.path("evidenceIds"))))
          .isEqualTo(new HashSet<>(SmartCompanionEngine.list(g.path("evidence_record_ids"))));
      if (g.path("rate").isNull()) assertThat(o.path("scorePercent").isNull()).isTrue();
      else
        assertThat(o.path("scorePercent").asDouble())
            .isCloseTo(g.path("rate").asDouble() * 100, within(0.000001));
    }
  }

  @Test
  void latestPendingInvalidConflictDuplicateAndZeroAreDistinct() throws Exception {
    var raw = raw();
    var original = new LinkedHashMap<>(raw.get("作答记录").get(0));
    String event = original.get("作答事件编号");
    var old = new LinkedHashMap<>(original);
    old.put("修订版本", "1");
    var latest = new LinkedHashMap<>(original);
    latest.put("记录编号", "new");
    latest.put("修订版本", "2");
    latest.put("评分状态", "待评分");
    latest.put("得分", "");
    latest.put("评分点得分", "");
    raw.put("作答记录", new ArrayList<>(List.of(latest, old)));
    var p = engine.parse(raw);
    assertThat(p.path("records").size()).isEqualTo(1);
    assertThat(p.path("records").get(0).path("status").asText()).isEqualTo("PENDING");
    latest.put("学生编号", "unknown");
    p = engine.parse(raw);
    assertThat(p.path("records").size()).isZero();
    assertThat(p.path("preview").path("quality").path("supersededCount").asInt()).isEqualTo(1);
    latest.put("学生编号", original.get("学生编号"));
    var duplicate = new LinkedHashMap<>(latest);
    duplicate.put("记录编号", "duplicate");
    raw.get("作答记录").add(duplicate);
    p = engine.parse(raw);
    assertThat(p.path("preview").path("quality").path("duplicateCount").asInt()).isEqualTo(1);
    duplicate.put("学生最终作答", "different");
    p = engine.parse(raw);
    assertThat(p.path("preview").path("quality").path("conflictCount").asInt()).isEqualTo(2);
    assertThat(p.path("records").size()).isZero();
  }

  @Test
  void csvPreservesQuotedNewlinesAndRejectsMalformedQuotes() throws Exception {
    assertThat(SmartCompanionReader.csv("a,b\r\n\"x\ny\",\"z\"\"q\"\r\n").get(1))
        .containsExactly("x\ny", "z\"q");
    assertThatThrownBy(() -> SmartCompanionReader.csv("a\n\"broken"))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(
            () ->
                SmartCompanionReader.xml(
                    "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><x>&a;</x>".getBytes()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void duplicateIdsEventIdentityAndRecommendationsUseEvidence() throws Exception {
    var raw = raw();
    var first = new LinkedHashMap<>(raw.get("作答记录").get(0));
    raw.put("作答记录", new ArrayList<>(List.of(first, new LinkedHashMap<>(first))));
    var p = engine.parse(raw);
    assertThat(p.path("preview").path("canConfirm").asBoolean()).isTrue();
    assertThat(p.path("preview").path("quality").path("duplicateCount").asInt()).isEqualTo(1);
    var other = raw.get("作答记录").get(1);
    other.put("学生编号", "D40");
    p = engine.parse(raw);
    assertThat(p.path("records").size()).isZero();
    assertThat(p.path("preview").path("quality").path("conflictCount").asInt()).isEqualTo(2);
    raw.put("作答记录", new ArrayList<>(List.of(first)));
    first.put("作答事件编号", "bad-event");
    assertThat(engine.parse(raw).path("records").size()).isZero();
    var full = engine.parse(raw());
    var report = engine.report(full, "1", "1", "test", "fixed");
    var recommendations = SmartCompanionEngine.list(report.path("recommendations"));
    var weak =
        recommendations.stream()
            .filter(
                r ->
                    r.path("studentId").asText().equals("D01")
                        && r.path("nodeId").asText().equals("N02")
                        && r.path("indicatorId").asText().equals("T03I1"))
            .findFirst()
            .orElseThrow();
    var strong =
        recommendations.stream()
            .filter(
                r ->
                    r.path("studentId").asText().equals("D14")
                        && r.path("nodeId").asText().equals("N02")
                        && r.path("indicatorId").asText().equals("T03I1"))
            .findFirst()
            .orElseThrow();
    assertThat(weak.path("reason")).isNotEqualTo(strong.path("reason"));
    assertThat(weak.path("materialIds")).isNotEqualTo(strong.path("materialIds"));
    assertThat(weak.path("reason").asText()).contains("重新观察");
    for (var o : report.path("observations"))
      if (o.path("nodeId").asText().equals("N02") && o.path("indicatorId").asText().equals("T02I1"))
        assertThat(o.path("status").asText()).isEqualTo("NOT_ASSESSED");
  }
}
