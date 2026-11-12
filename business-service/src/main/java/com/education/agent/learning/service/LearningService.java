package com.education.agent.learning.service;

import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentSummary;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.course.CourseAccessService;
import com.education.agent.integration.knowledge.KnowledgeRecommendationDtos;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.learning.dto.LearningDtos.*;
import com.education.agent.resource.model.ReviewStatus;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.dto.KnowledgePointBinding;
import com.education.agent.tutor.dto.CognitiveLabelView;
import com.education.agent.tutor.model.MasteryStatus;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LearningService {
  private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> EVENT_TYPES = Set.of("ASSESSMENT_SUBMITTED", "ASSESSMENT_RELEASED",
      "PRACTICE_COMPLETED", "TUTOR_SESSION_CLOSED", "MEMORY_CREATED", "MASTERY_UPDATED");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final LearningEvidenceService evidenceService;
  private final KnowledgeServiceClient knowledge;
  private final CourseAccessService courses;

  public LearningService(JdbcTemplate jdbc, ObjectMapper json,
      LearningEvidenceService evidenceService, KnowledgeServiceClient knowledge,
      CourseAccessService courses) {
    this.jdbc=jdbc; this.json=json; this.evidenceService=evidenceService; this.knowledge=knowledge;
    this.courses=courses;
  }

  public LearningDashboard dashboard(AuthenticatedUser principal, Long courseId) {
    requireSelfStudent(principal);
    courses.requireView(principal, courseId);
    evidenceService.reconcileStudent(principal.id(), courseId);
    int homework = pendingCount(principal.id(), courseId, "HOMEWORK");
    int exam = pendingCount(principal.id(), courseId, "EXAM");
    Map<String,Object> aggregate = jdbc.queryForMap("""
        SELECT COUNT(*) completed_count,AVG(s.final_score) average_score
        FROM edu_assessment_submission s JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        WHERE s.student_id=? AND a.deleted_at IS NULL
          AND c.course_id=?
          AND s.result_released_at IS NOT NULL AND s.grading_status='COMPLETED'
        """, principal.id(), courseId);
    List<AssessmentSummary> recent = jdbc.query("""
        SELECT a.id,a.assessment_type,a.title,a.class_id,c.name class_name,a.creator_id,
               a.status,a.total_score,a.start_at,a.due_at,a.duration_minutes,a.created_at,a.updated_at,
               s.status submission_status,
               (SELECT COUNT(*) FROM edu_assessment_question q WHERE q.assessment_id=a.id) question_count
        FROM edu_assessment_submission s JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        WHERE s.student_id=? AND c.course_id=? AND a.deleted_at IS NULL AND s.result_released_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM edu_assessment_submission newer
            WHERE newer.assessment_id=s.assessment_id AND newer.student_id=s.student_id
              AND newer.result_released_at IS NOT NULL AND newer.attempt_no>s.attempt_no)
        ORDER BY s.result_released_at DESC LIMIT 5
        """,(rs,n)->new AssessmentSummary(rs.getLong("id"),AssessmentType.valueOf(rs.getString("assessment_type")),
            rs.getString("title"),rs.getLong("class_id"),rs.getString("class_name"),rs.getLong("creator_id"),
            AssessmentStatus.valueOf(rs.getString("status")),rs.getBigDecimal("total_score"),
            instant(rs.getTimestamp("start_at")),instant(rs.getTimestamp("due_at")),
            nullableInt(rs,"duration_minutes"),rs.getInt("question_count"),rs.getString("submission_status"),
            instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at"))),principal.id(),courseId);
    List<KnowledgeMastery> all = masteries(principal.id(), courseId, true);
    List<KnowledgeMastery> weak = all.stream().filter(m -> "WEAK".equals(m.status())).limit(10).toList();
    List<KnowledgeRecommendation> recommendations = recommendations(principal, courseId, 5, false);
    return new LearningDashboard(homework, exam,
        number(aggregate.get("completed_count")).intValue(), nullableDecimal(aggregate.get("average_score")),
        recent, weak, recommendations);
  }

  public LearningAnalysis analysis(AuthenticatedUser principal, Long studentId, Long courseId,
      LocalDate from, LocalDate to) {
    requireAnalysisAccess(principal, studentId, courseId);
    evidenceService.reconcileStudent(studentId, courseId);
    StringBuilder sql = new StringBuilder("""
        SELECT CAST(s.result_released_at AS DATE) score_date,AVG(s.final_score) score
        FROM edu_assessment_submission s JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        WHERE s.student_id=? AND a.deleted_at IS NULL
          AND c.course_id=?
          AND s.result_released_at IS NOT NULL AND s.grading_status='COMPLETED'
        """);
    List<Object> args = new ArrayList<>(); args.add(studentId); args.add(courseId);
    if (from != null) { sql.append(" AND s.result_released_at>=?"); args.add(Timestamp.valueOf(from.atStartOfDay())); }
    if (to != null) { sql.append(" AND s.result_released_at<?"); args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay())); }
    sql.append(" GROUP BY CAST(s.result_released_at AS DATE) ORDER BY score_date");
    List<TrendPoint> trend = jdbc.query(sql.toString(), (rs,n) -> new TrendPoint(
        rs.getDate("score_date").toLocalDate(), rs.getBigDecimal("score")), args.toArray());
    List<KnowledgeMastery> mastery = masteries(studentId, courseId, true);
    List<CognitiveLabelView> labels = jdbc.query("""
        SELECT knowledge_point_id,label_status,confidence,evidence FROM edu_cognitive_label
        WHERE student_id=? AND course_id=? ORDER BY created_at DESC LIMIT 20
        """,(rs,n)->new CognitiveLabelView(rs.getString("knowledge_point_id"),
            MasteryStatus.valueOf(rs.getString("label_status")),rs.getBigDecimal("confidence"),
            rs.getString("evidence")),studentId,courseId);
    return new LearningAnalysis(studentId, trend, mastery,
        mastery.stream().filter(m -> "MASTERED".equals(m.status())).toList(),
        mastery.stream().filter(m -> "WEAK".equals(m.status())).toList(), labels);
  }

  public PageData<Mistake> mistakes(AuthenticatedUser principal, Long courseId,
      long page, long size, String kp) {
    requireSelfStudent(principal); courses.requireView(principal,courseId);
    evidenceService.reconcileStudent(principal.id(),courseId);
    String kpClause = kp == null || kp.isBlank() ? "" : " AND EXISTS (SELECT 1 FROM edu_question_knowledge_point f WHERE f.question_id=q.id AND f.knowledge_point_id=?)";
    List<Object> args = new ArrayList<>(List.of(principal.id())); if (!kpClause.isEmpty()) args.add(kp.trim());
    String from = """
        FROM edu_submission_answer sa
        JOIN edu_assessment_submission s ON s.id=sa.submission_id
        JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        JOIN edu_assessment_question q ON q.id=sa.question_id
        WHERE s.student_id=? AND c.course_id=? AND a.deleted_at IS NULL
          AND s.result_released_at IS NOT NULL AND s.grading_status='COMPLETED'
          AND sa.final_score IS NOT NULL AND sa.final_score<q.score
        """ + kpClause;
    args.add(1,courseId);
    long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
    args.add(size); args.add((page-1)*size);
    List<Mistake> items = jdbc.query("""
        SELECT a.id assessment_id,a.title assessment_title,s.id submission_id,q.id question_id,
               q.stem,sa.answer_json,sa.code_text,q.reference_answer_json,sa.teacher_comment,
               a.settings_json,s.result_released_at
        """ + from + " ORDER BY s.result_released_at DESC,sa.id DESC LIMIT ? OFFSET ?", (rs,n) -> {
      Long questionId=rs.getLong("question_id"); JsonNode settings=tree(rs.getString("settings_json"));
      boolean showCorrect=settings.path("showCorrectAnswerAfterRelease").asBoolean(true);
      boolean showFeedback=settings.path("showQuestionFeedbackAfterRelease").asBoolean(true);
      List<KnowledgePointBinding> bindings=jdbc.query("""
          SELECT knowledge_point_id,knowledge_point_name FROM edu_question_knowledge_point
          WHERE question_id=? ORDER BY knowledge_point_id
          """, (r,i)->new KnowledgePointBinding(r.getString(1),r.getString(2)),questionId);
      return new Mistake(rs.getLong("assessment_id"),rs.getString("assessment_title"),
          rs.getLong("submission_id"),questionId,rs.getString("stem"),
          answerObject(rs.getString("answer_json"),rs.getString("code_text")),showCorrect?object(rs.getString("reference_answer_json")):null,
          showFeedback?rs.getString("teacher_comment"):null,bindings,
          instant(rs.getTimestamp("result_released_at")));
    }, args.toArray());
    return new PageData<>(items,page,size,total,(total+size-1)/size);
  }

  public PageData<LearningEvent> timeline(AuthenticatedUser principal,Long courseId,
      long page,long size,String type,String kp) {
    requireSelfStudent(principal); courses.requireView(principal,courseId);
    if (type!=null && !EVENT_TYPES.contains(type)) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"学习事件类型不合法");
    evidenceService.reconcileStudent(principal.id(),courseId);
    StringBuilder where=new StringBuilder(" WHERE student_id=? AND course_id=?");
    List<Object> args=new ArrayList<>(List.of(principal.id(),courseId));
    if(type!=null){where.append(" AND event_type=?");args.add(type);}
    if(kp!=null&&!kp.isBlank()){where.append(" AND knowledge_point_ids_json LIKE ?");args.add("%\""+kp.trim()+"\"%");}
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_event"+where,Long.class,args.toArray());
    args.add(size);args.add((page-1)*size);
    List<LearningEvent> items=jdbc.query("SELECT * FROM edu_learning_event"+where+" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",
        (rs,n)->new LearningEvent(rs.getLong("id"),rs.getString("event_type"),rs.getString("title"),
            rs.getString("summary"),strings(rs.getString("knowledge_point_ids_json")),
            rs.getString("related_type"),nullableLong(rs,"related_id"),rs.getBigDecimal("score"),
            instant(rs.getTimestamp("occurred_at")),rs.getLong("course_id")),args.toArray());
    return new PageData<>(items,page,size,total,(total+size-1)/size);
  }

  public List<KnowledgeRecommendation> recommendations(AuthenticatedUser principal,Long courseId,int limit) {
    return recommendations(principal,courseId,limit,true);
  }
  private List<KnowledgeRecommendation> recommendations(AuthenticatedUser principal,Long courseId,
      int limit,boolean reconcile) {
    requireSelfStudent(principal); courses.requireView(principal,courseId);
    if(reconcile)evidenceService.reconcileStudent(principal.id(),courseId);
    List<KnowledgeRecommendationDtos.MasteryInput> inputs=masteries(principal.id(),courseId,false).stream()
        .map(m->new KnowledgeRecommendationDtos.MasteryInput(m.knowledgePointId(),m.score(),m.confidence())).toList();
    var response=knowledge.recommendNext(courseId,principal.id(),inputs,limit);
    if(response==null||response.items()==null||response.graphVersion()==null||response.graphVersion().isBlank()
        ||!courseId.equals(response.courseId())||response.items().size()>limit)
      throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"知识图谱返回了错误课程、无版本或超量推荐");
    List<KnowledgeRecommendation> result=new ArrayList<>();
    Set<Integer> priorities=new HashSet<>(); int previousPriority=0;
    for(var item:response.items()){
      if(item==null||item.node()==null||!"knowledge_point".equals(item.node().nodeType())
          ||!"APPROVED".equals(item.node().reviewStatus())||item.node().id()==null||item.node().id().isBlank()
          ||item.node().name()==null||item.node().name().isBlank()||item.reason()==null||item.reason().isBlank()
          ||item.priority()==null||item.priority()<1||item.priority()<=previousPriority
          ||!priorities.add(item.priority())||item.evidence()==null||item.evidence().isEmpty()
          ||item.evidence().stream().anyMatch(value->value==null||value.isBlank()))
        throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"知识图谱返回了未审核或不可追溯的推荐");
      previousPriority=item.priority();
      result.add(new KnowledgeRecommendation(item.node().toPublic(),item.reason(),item.priority(),
          List.copyOf(item.evidence()),response.graphVersion()));
    }
    return List.copyOf(result);
  }

  public List<LearningPathStep> path(AuthenticatedUser principal,Long courseId) {
    List<KnowledgeRecommendation> recs=recommendations(principal,courseId,20);
    Map<String,KnowledgeMastery> mastery=new HashMap<>();
    masteries(principal.id(),courseId,false).forEach(m->mastery.put(m.knowledgePointId(),m));
    List<LearningPathStep> result=new ArrayList<>(); int order=1;
    for(KnowledgeRecommendation rec:recs){
      KnowledgeMastery current=mastery.get(rec.knowledgePoint().id());
      var context=knowledge.ragContext(courseId,rec.knowledgePoint().id());
      if(context==null||context.knowledgePoint()==null||context.prerequisites()==null
          ||!courseId.equals(context.courseId())
          ||context.graphVersion()==null||!rec.graphVersion().equals(context.graphVersion())
          ||!rec.knowledgePoint().id().equals(context.knowledgePoint().id())
          ||!"knowledge_point".equals(context.knowledgePoint().nodeType())
          ||!"APPROVED".equals(context.knowledgePoint().reviewStatus())
          ||context.prerequisites().stream().anyMatch(node->node==null
          ||node.id()==null||node.id().isBlank()||node.name()==null||node.name().isBlank()
          ||!"knowledge_point".equals(node.nodeType())||!"APPROVED".equals(node.reviewStatus())))
        throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"学习路径图谱依据不一致");
      boolean prerequisitesMastered=context.prerequisites().stream().allMatch(node->{KnowledgeMastery m=mastery.get(node.id());return m!=null&&"MASTERED".equals(m.status());});
      String status=current!=null&&"MASTERED".equals(current.status())?"COMPLETED":prerequisitesMastered?"AVAILABLE":"LOCKED";
      List<Long> resources=jdbc.queryForList("""
          SELECT r.id FROM edu_teaching_resource r JOIN edu_resource_knowledge_point b ON b.resource_id=r.id
          WHERE b.knowledge_point_id=? AND r.course_id=? AND r.review_status='APPROVED' ORDER BY r.id
          """,Long.class,rec.knowledgePoint().id(),courseId);
      result.add(new LearningPathStep(order++,rec.knowledgePoint(),rec.reason(),status,
          rec.evidence(),rec.graphVersion(),resources));
    }
    return List.copyOf(result);
  }

  public List<KnowledgeMastery> masteries(Long studentId, Long courseId, boolean evidence) {
    return jdbc.query("SELECT * FROM edu_knowledge_mastery WHERE student_id=? AND course_id=? ORDER BY score,knowledge_point_id",
        (rs,n)->new KnowledgeMastery(rs.getString("knowledge_point_id"),rs.getString("knowledge_point_name"),
            rs.getBigDecimal("score").setScale(2,RoundingMode.HALF_UP),rs.getBigDecimal("confidence"),
            rs.getString("mastery_status"),rs.getInt("evidence_count"),
            evidence?recentEvidence(studentId,courseId,rs.getString("knowledge_point_id")):List.of(),
            instant(rs.getTimestamp("updated_at"))),studentId,courseId);
  }
  private List<MasteryEvidence> recentEvidence(Long studentId,Long courseId,String kp){return jdbc.query("""
      SELECT source_type,source_id,score_percent,evidence_weight,occurred_at
      FROM edu_learning_evidence WHERE student_id=? AND course_id=? AND knowledge_point_id=?
      ORDER BY occurred_at DESC,id DESC LIMIT 10
      """,(rs,n)->new MasteryEvidence(rs.getString(1),rs.getLong(2),rs.getBigDecimal(3),
          rs.getBigDecimal(4),instant(rs.getTimestamp(5))),studentId,courseId,kp);}
  private int pendingCount(Long studentId,Long courseId,String type){return jdbc.queryForObject("""
      SELECT COUNT(*) FROM edu_assessment a JOIN edu_class_student cs ON cs.class_id=a.class_id
      JOIN edu_class c ON c.id=a.class_id
      WHERE cs.student_id=? AND c.course_id=? AND a.deleted_at IS NULL AND a.status='PUBLISHED' AND a.assessment_type=?
        AND NOT EXISTS (SELECT 1 FROM edu_assessment_submission s WHERE s.assessment_id=a.id
          AND s.student_id=cs.student_id AND s.status<>'IN_PROGRESS')
      """,Integer.class,studentId,courseId,type);}
  private void requireSelfStudent(AuthenticatedUser p){if(p.role()!=UserRole.STUDENT)throw new BusinessException(ErrorCode.FORBIDDEN,"该学习接口仅对学生开放");}
  private void requireAnalysisAccess(AuthenticatedUser p,Long studentId,Long courseId){
    courses.requireView(p,courseId);
    if(p.role()==UserRole.STUDENT){if(!p.id().equals(studentId))throw new BusinessException(ErrorCode.FORBIDDEN,"不能查看其他学生学情");return;}
    if(p.role()==UserRole.ADMIN)return;
    Integer count=jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_class c JOIN edu_class_student cs ON cs.class_id=c.id
        WHERE c.course_id=? AND cs.student_id=?
        """,Integer.class,courseId,studentId);
    if(count==null||count==0)throw new BusinessException(ErrorCode.FORBIDDEN,"无权查看该学生学情");
  }
  private JsonNode tree(String s){try{return s==null?json.createObjectNode():json.readTree(s);}catch(Exception e){return json.createObjectNode();}}
  private Object object(String s){try{return s==null?null:json.readValue(s,Object.class);}catch(Exception e){return null;}}
  private Object answerObject(String encoded,String sourceCode){
    Map<String,Object> answer=new LinkedHashMap<>();
    try{if(encoded!=null&&!encoded.isBlank())answer.putAll(json.readValue(encoded,new TypeReference<Map<String,Object>>(){}));}catch(Exception ignored){}
    if(sourceCode!=null&&!sourceCode.isBlank())answer.put("sourceCode",sourceCode);
    return answer;
  }
  private List<String> strings(String s){try{return s==null?List.of():json.readValue(s,new TypeReference<>(){});}catch(Exception e){return List.of();}}
  private BigDecimal nullableDecimal(Object v){return v==null?null:new BigDecimal(v.toString()).setScale(2,RoundingMode.HALF_UP);}
  private Long number(Object v){return v instanceof Number n?n.longValue():0L;}
  private Instant instant(Timestamp ts){return ts==null?null:ts.toLocalDateTime().atZone(DB_ZONE).toInstant();}
  private Long nullableLong(java.sql.ResultSet rs,String col)throws java.sql.SQLException{long v=rs.getLong(col);return rs.wasNull()?null:v;}
  private Integer nullableInt(java.sql.ResultSet rs,String col)throws java.sql.SQLException{int v=rs.getInt(col);return rs.wasNull()?null:v;}
}
