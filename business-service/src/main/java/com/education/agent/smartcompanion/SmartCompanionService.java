package com.education.agent.smartcompanion;

import com.education.agent.common.error.*;
import com.education.agent.course.CourseAccessService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SmartCompanionService {
  final JdbcTemplate jdbc;
  final ObjectMapper json;
  final CourseAccessService access;
  final SmartCompanionEngine engine;

  public SmartCompanionService(JdbcTemplate jdbc, ObjectMapper json, CourseAccessService access) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
    engine = new SmartCompanionEngine(json);
  }

  void authorize(AuthenticatedUser u, Long c, boolean edit) {
    if (u == null || u.role() == UserRole.STUDENT)
      throw new BusinessException(ErrorCode.FORBIDDEN, "仅授权教师可查看外部学情");
    if (edit) access.requireEdit(u, c);
    else access.requireView(u, c);
  }

  ObjectNode decode(Object v) {
    try {
      return (ObjectNode) json.readTree(v.toString());
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批次快照不可读取");
    }
  }

  String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "批次快照不可保存");
    }
  }

  Map<String, Object> row(Long c, Long id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM edu_companion_import WHERE course_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            c,
            id);
    if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前课程内不存在此批次");
    return rows.get(0);
  }

  public ObjectNode list(AuthenticatedUser u, Long c) {
    authorize(u, c, false);
    var items = json.createArrayNode();
    jdbc.queryForList(
            "SELECT detail_json FROM edu_companion_import WHERE course_id=? ORDER BY id DESC LIMIT"
                + " 100",
            c)
        .forEach(r -> items.add(decode(r.get("detail_json")).path("summary")));
    return engine.obj("items", items);
  }

  public ObjectNode get(AuthenticatedUser u, Long c, Long id) {
    authorize(u, c, false);
    return decode(row(c, id, false).get("detail_json"));
  }

  @Transactional
  public ObjectNode upload(AuthenticatedUser u, Long c, MultipartFile file) {
    authorize(u, c, true);
    if (file.isEmpty()) throw new BusinessException(ErrorCode.FILE_EMPTY, "文件为空");
    if (file.getSize() > 100L * 1024 * 1024)
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件不得超过100MiB");
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (name.length() > 255) name = name.substring(name.length() - 255);
    if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|zip)$"))
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "只支持xlsx或zip");
    byte[] bytes;
    String hash;
    try {
      bytes = file.getBytes();
      hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR, "无法读取文件");
    }
    jdbc.queryForList("SELECT id FROM edu_course WHERE id=? FOR UPDATE", c);
    authorize(u, c, true);
    var existing =
        jdbc.queryForList(
            "SELECT detail_json FROM edu_companion_import WHERE course_id=? AND sha256=? FOR"
                + " UPDATE",
            c,
            hash);
    if (!existing.isEmpty()) return decode(existing.get(0).get("detail_json"));
    Map<String, List<Map<String, String>>> raw = new LinkedHashMap<>();
    ObjectNode normalized;
    try {
      raw = new SmartCompanionReader().read(bytes, name);
      normalized = engine.parse(raw);
    } catch (Exception e) {
      normalized = engine.parse(Map.of());
      ((ArrayNode) normalized.path("preview").path("issues"))
          .add(
              engine.obj(
                  "severity",
                  "ERROR",
                  "table",
                  "文件",
                  "rowNumber",
                  null,
                  "recordId",
                  null,
                  "code",
                  "PARSE_ERROR",
                  "message",
                  "文件结构无法可靠解析"));
      ((ObjectNode) normalized.path("preview")).put("canConfirm", false);
    }
    var summary =
        engine.obj(
            "id",
            "0",
            "courseId",
            c.toString(),
            "fileName",
            name,
            "sha256",
            hash,
            "sourceLabel",
            "上传的外部学情数据（来源待确认）",
            "status",
            normalized.path("preview").path("canConfirm").asBoolean() ? "PREVIEW" : "BLOCKED",
            "createdAt",
            OffsetDateTime.now().toString(),
            "confirmedAt",
            null,
            "analyzedAt",
            null,
            "studentCount",
            normalized.path("students").size(),
            "materialCount",
            normalized.path("materials").size(),
            "rawRecordCount",
            normalized.path("preview").path("quality").path("rawRecordCount"),
            "nodeCount",
            normalized.path("nodes").size());
    var detail =
        engine.obj("summary", summary, "preview", normalized.path("preview"), "report", null);
    final String dj = encode(detail), sj = encode(raw), nj = encode(normalized);
    var key = new GeneratedKeyHolder();
    jdbc.update(
        conn -> {
          var p =
              conn.prepareStatement(
                  "INSERT INTO"
                      + " edu_companion_import(course_id,sha256,detail_json,source_json,normalized_json)"
                      + " VALUES(?,?,?,?,?)",
                  new String[] {"id"});
          p.setLong(1, c);
          p.setString(2, hash);
          p.setString(3, dj);
          p.setString(4, sj);
          p.setString(5, nj);
          return p;
        },
        key);
    long id = Objects.requireNonNull(key.getKey()).longValue();
    ((ObjectNode) detail.path("summary")).put("id", Long.toString(id));
    save(c, id, detail);
    return detail;
  }

  void save(Long c, Long id, ObjectNode d) {
    jdbc.update(
        "UPDATE edu_companion_import SET detail_json=? WHERE course_id=? AND id=?",
        encode(d),
        c,
        id);
  }

  @Transactional
  public ObjectNode confirm(AuthenticatedUser u, Long c, Long id, Confirm request) {
    authorize(u, c, true);
    var r = row(c, id, true);
    authorize(u, c, true);
    var d = decode(r.get("detail_json"));
    var s = (ObjectNode) d.path("summary");
    if (request == null
        || !s.path("sha256").asText().equals(request.sha256())
        || !request.acknowledgeWarnings()
        || request.sourceLabel() == null
        || request.sourceLabel().isBlank()
        || request.sourceLabel().length() > 300)
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请核对哈希、来源说明并确认预检警告");
    if (!d.path("preview").path("canConfirm").asBoolean())
      throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "结构问题未解决，无法确认");
    if (!s.path("confirmedAt").isNull()) return d;
    s.put("sourceLabel", request.sourceLabel().strip());
    s.put("confirmedAt", OffsetDateTime.now().toString());
    s.put("status", "CONFIRMED");
    save(c, id, d);
    return d;
  }

  @Transactional
  public ObjectNode analyze(AuthenticatedUser u, Long c, Long id) {
    authorize(u, c, true);
    var r = row(c, id, true);
    authorize(u, c, true);
    var d = decode(r.get("detail_json"));
    var s = (ObjectNode) d.path("summary");
    if (s.path("confirmedAt").isNull())
      throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "请先确认保存批次");
    if (!d.path("report").isNull()) return d;
    String at = OffsetDateTime.now().toString();
    d.set(
        "report",
        engine.report(
            decode(r.get("normalized_json")),
            id.toString(),
            c.toString(),
            s.path("sourceLabel").asText(),
            at));
    if (encode(d).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48 * 1024 * 1024)
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "报告超过48MiB输出预算，请拆分批次");
    s.put("analyzedAt", at);
    s.put("status", "ANALYZED");
    save(c, id, d);
    return d;
  }

  public JsonNode report(AuthenticatedUser u, Long c, Long id) {
    var d = get(u, c, id);
    if (d.path("report").isNull())
      throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "批次尚未分析");
    return d.path("report");
  }

  public record Confirm(String sha256, String sourceLabel, boolean acknowledgeWarnings) {}
}
