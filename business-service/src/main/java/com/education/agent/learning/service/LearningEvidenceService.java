package com.education.agent.learning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningEvidenceService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public LearningEvidenceService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc; this.json = json;
  }

  @Transactional
  public void reconcileAssessment(Long assessmentId) {
    List<Long> courseIds = jdbc.queryForList("""
        SELECT c.course_id FROM edu_assessment a JOIN edu_class c ON c.id=a.class_id
        WHERE a.id=? AND a.deleted_at IS NULL
        """, Long.class, assessmentId);
    if (!courseIds.isEmpty()) reconcile(assessmentId,null,courseIds.get(0));
  }

  @Transactional
  public void reconcileStudent(Long studentId, Long courseId) {
    reconcile(null,studentId,courseId);
  }

  /** Records one completed, deterministically graded Tutor activity and recomputes mastery. */
  @Transactional
  public MasteryChange recordPractice(
      Long studentId,
      Long courseId,
      Long activityId,
      Long attemptId,
      String knowledgePointId,
      String knowledgePointName,
      String graphVersion,
      String activityTitle,
      BigDecimal scorePercent,
      BigDecimal evidenceWeight,
      LocalDateTime occurredAt) {
    BigDecimal before = currentMasteryScore(studentId, courseId, knowledgePointId);
    try {
      jdbc.update("""
          INSERT INTO edu_learning_evidence
          (student_id,course_id,assessment_id,submission_id,answer_id,question_id,
           knowledge_point_id,knowledge_point_name,graph_version,source_type,source_id,
           score_percent,evidence_weight,occurred_at)
          VALUES (?,?,NULL,NULL,NULL,NULL,?,?,?,?,?,?,?,?)
          """, studentId, courseId, knowledgePointId, knowledgePointName, graphVersion,
          "PRACTICE", attemptId, scorePercent, evidenceWeight, occurredAt);
    } catch (DuplicateKeyException ignored) {
      // source_type + attempt + knowledge point is the replay-safe practice identity.
    }
    recomputeMastery(studentId,courseId);
    BigDecimal after = currentMasteryScore(studentId, courseId, knowledgePointId);
    try {
      jdbc.update("""
          INSERT INTO edu_learning_event
          (dedupe_key,student_id,course_id,event_type,title,summary,knowledge_point_ids_json,
           related_type,related_id,score,occurred_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """, "TUTOR_PRACTICE:" + attemptId, studentId, courseId, "PRACTICE_COMPLETED",
          activityTitle, "AI 导师互动练习已完成", writeJson(List.of(knowledgePointId)),
          "TUTOR_ACTIVITY", activityId, scorePercent, occurredAt);
    } catch (DuplicateKeyException ignored) {
      // The learning timeline is idempotent with the evidence row.
    }
    return new MasteryChange(before, after, after.subtract(before).setScale(4, RoundingMode.HALF_UP));
  }

  private BigDecimal currentMasteryScore(Long studentId, Long courseId, String knowledgePointId) {
    List<BigDecimal> values = jdbc.query(
        "SELECT score FROM edu_knowledge_mastery WHERE student_id=? AND course_id=? AND knowledge_point_id=?",
        (rs, rowNum) -> rs.getBigDecimal(1), studentId, courseId, knowledgePointId);
    return values.isEmpty() || values.get(0) == null
        ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
        : values.get(0).setScale(4, RoundingMode.HALF_UP);
  }

  public record MasteryChange(BigDecimal before, BigDecimal after, BigDecimal delta) {}

  private void reconcile(Long assessmentId,Long studentId,Long courseId) {
    StringBuilder sql=new StringBuilder("""
        SELECT s.student_id,c.course_id,s.id submission_id,s.assessment_id,s.assessment_version_id,
               s.result_released_at,sa.id answer_id,sa.question_id,sa.final_score,
               q.score question_score,qkp.knowledge_point_id,qkp.knowledge_point_name,
               v.snapshot_json
        FROM edu_assessment_submission s
        JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        JOIN edu_submission_answer sa ON sa.submission_id=s.id
        JOIN edu_assessment_question q ON q.id=sa.question_id
        JOIN edu_question_knowledge_point qkp ON qkp.question_id=q.id
        LEFT JOIN edu_assessment_version v ON v.id=s.assessment_version_id
        WHERE a.deleted_at IS NULL
          AND s.result_released_at IS NOT NULL AND s.grading_status='COMPLETED'
          AND sa.final_score IS NOT NULL AND q.score>0
          AND c.course_id=?
        """);
    List<Object> args=new java.util.ArrayList<>(); args.add(courseId);
    if(assessmentId!=null){sql.append(" AND s.assessment_id=?");args.add(assessmentId);}
    if(studentId!=null){sql.append(" AND s.student_id=?");args.add(studentId);}
    List<Map<String,Object>> rows = jdbc.queryForList(sql.toString(),args.toArray());
    Map<Long,String> versions = new HashMap<>();
    for (Map<String,Object> row : rows) {
      long versionId = number(row.get("assessment_version_id"), 0L);
      String graphVersion = versions.computeIfAbsent(versionId,
          ignored -> graphVersion(row.get("snapshot_json")));
      BigDecimal finalScore = decimal(row.get("final_score"));
      BigDecimal weight = decimal(row.get("question_score"));
      BigDecimal percent = finalScore.multiply(BigDecimal.valueOf(100))
          .divide(weight, 4, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
          .min(BigDecimal.valueOf(100));
      try {
        jdbc.update("""
            INSERT INTO edu_learning_evidence
            (student_id,course_id,assessment_id,submission_id,answer_id,question_id,
             knowledge_point_id,knowledge_point_name,graph_version,source_type,source_id,
             score_percent,evidence_weight,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, row.get("student_id"), row.get("course_id"), row.get("assessment_id"), row.get("submission_id"),
            row.get("answer_id"), row.get("question_id"), row.get("knowledge_point_id"),
            row.get("knowledge_point_name"), graphVersion, "ASSESSMENT", row.get("answer_id"),
            percent, weight, row.get("result_released_at"));
      } catch (DuplicateKeyException ignored) {
        // Stable answer+knowledge-point evidence makes release/recovery replay harmless.
      }
    }
    for (Long affectedStudent : rows.stream().map(r -> number(r.get("student_id"), 0L)).distinct().toList()) {
      recomputeMastery(affectedStudent,courseId);
    }
    for(Map<String,Object> row:rows.stream().filter(r->r.get("student_id")!=null&&r.get("assessment_id")!=null)
        .collect(java.util.stream.Collectors.toMap(r->r.get("student_id")+":"+r.get("assessment_id"),r->r,(a,b)->a)).values()){
      insertReleaseEvent(number(row.get("student_id"),0L),courseId,number(row.get("assessment_id"),0L));
    }
  }

  private void recomputeMastery(Long studentId,Long courseId) {
    List<Map<String,Object>> groups = jdbc.queryForList("""
        SELECT knowledge_point_id,MAX(knowledge_point_name) knowledge_point_name,
               SUM(score_percent*evidence_weight)/SUM(evidence_weight) score,
               COUNT(*) evidence_count,MAX(occurred_at) updated_at
        FROM edu_learning_evidence WHERE student_id=? AND course_id=?
        GROUP BY knowledge_point_id
        """, studentId,courseId);
    for (Map<String,Object> group : groups) {
      BigDecimal score = decimal(group.get("score")).setScale(4, RoundingMode.HALF_UP);
      int count = number(group.get("evidence_count"), 0L).intValue();
      BigDecimal confidence = BigDecimal.valueOf(Math.min(1D, count / 5D)).setScale(4, RoundingMode.HALF_UP);
      String status = score.compareTo(BigDecimal.valueOf(60)) < 0 ? "WEAK"
          : score.compareTo(BigDecimal.valueOf(85)) < 0 ? "LEARNING" : "MASTERED";
      int updated = jdbc.update("""
          UPDATE edu_knowledge_mastery SET knowledge_point_name=?,score=?,confidence=?,
          mastery_status=?,evidence_count=?,updated_at=?
          WHERE student_id=? AND course_id=? AND knowledge_point_id=?
          """, group.get("knowledge_point_name"), score, confidence, status, count,
          group.get("updated_at"), studentId, courseId, group.get("knowledge_point_id"));
      if (updated == 0) {
        try {
          jdbc.update("""
              INSERT INTO edu_knowledge_mastery
              (student_id,course_id,knowledge_point_id,knowledge_point_name,score,confidence,
               mastery_status,evidence_count,updated_at) VALUES (?,?,?,?,?,?,?,?,?)
              """, studentId, courseId, group.get("knowledge_point_id"), group.get("knowledge_point_name"),
              score, confidence, status, count, group.get("updated_at"));
        } catch (DuplicateKeyException race) {
          recomputeMastery(studentId,courseId);
          return;
        }
      }
    }
  }

  private void insertReleaseEvent(Long studentId, Long courseId, Long assessmentId) {
    Map<String,Object> source = jdbc.queryForMap("""
        SELECT a.title,s.id submission_id,s.final_score,s.result_released_at
        FROM edu_assessment_submission s JOIN edu_assessment a ON a.id=s.assessment_id
        WHERE s.student_id=? AND s.assessment_id=? AND a.deleted_at IS NULL AND s.result_released_at IS NOT NULL
        ORDER BY s.attempt_no DESC LIMIT 1
        """, studentId, assessmentId);
    List<String> kpIds = jdbc.queryForList("""
        SELECT DISTINCT knowledge_point_id FROM edu_learning_evidence
        WHERE student_id=? AND course_id=? AND assessment_id=? ORDER BY knowledge_point_id
        """, String.class, studentId, courseId, assessmentId);
    try {
      jdbc.update("""
          INSERT INTO edu_learning_event
          (dedupe_key,student_id,course_id,event_type,title,summary,knowledge_point_ids_json,
           related_type,related_id,score,occurred_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """, "ASSESSMENT_RELEASED:" + source.get("submission_id"), studentId, courseId,
          "ASSESSMENT_RELEASED", source.get("title"), "测评结果已公布",
          writeJson(kpIds), "SUBMISSION", source.get("submission_id"), source.get("final_score"),
          source.get("result_released_at"));
    } catch (DuplicateKeyException ignored) {}
  }

  private String graphVersion(Object snapshot) {
    if (snapshot != null) try {
      JsonNode node = json.readTree(snapshot.toString());
      String value = node.path("graphVersion").asText();
      if (!value.isBlank()) return value;
    } catch (Exception ignored) {}
    // Historical rows without a publication graph version remain explicit, never null.
    return "legacy-unversioned";
  }
  private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { return "[]"; } }
  private BigDecimal decimal(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(value.toString()); }
  private Long number(Object value, Long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
}
