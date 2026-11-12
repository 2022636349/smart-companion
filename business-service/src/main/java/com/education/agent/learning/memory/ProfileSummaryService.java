package com.education.agent.learning.memory;

import com.education.agent.common.error.*;
import com.education.agent.integration.ai.*;
import com.education.agent.learning.memory.MemoryDtos.*;
import com.education.agent.tutor.entity.TutorSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProfileSummaryService {
  private static final Set<String> RESOURCE_TYPES=Set.of("DOCUMENT","ANIMATION","VIDEO","CODE","IMAGE","EXERCISE","EXTERNAL_LINK");
  private final JdbcTemplate jdbc;private final AiServiceClient ai;private final MemoryService memories;private final ObjectMapper json;private final TransactionTemplate tx;
  public ProfileSummaryService(JdbcTemplate jdbc,AiServiceClient ai,MemoryService memories,ObjectMapper json,PlatformTransactionManager manager){this.jdbc=jdbc;this.ai=ai;this.memories=memories;this.json=json;this.tx=new TransactionTemplate(manager);}
  public void enqueue(TutorSessionEntity session){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM edu_tutor_message WHERE session_id=?",Integer.class,session.getId());if(count==null||count==0)return;try{jdbc.update("""
      INSERT INTO edu_profile_summary_task(session_id,student_id,request_id,status,next_attempt_at)
      VALUES(?,?,?,'PENDING',CURRENT_TIMESTAMP)
      """,session.getId(),session.getStudentId(),"profile-summary-request-"+session.getId());}catch(DuplicateKeyException ignored){}}
  public void cancelForDeletedSession(Long sessionId){jdbc.update("""
      UPDATE edu_profile_summary_task SET status='FAILED',next_attempt_at=CURRENT_TIMESTAMP,
      lease_owner=NULL,lease_until=NULL,error_code='SESSION_DELETED',error_message='导师会话已由学生删除',
      updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND status IN ('PENDING','PROCESSING')
      """,sessionId);}
  @Scheduled(fixedDelayString="${education.profile.summary-scan-ms:4000}",initialDelayString="${education.profile.summary-initial-delay-ms:0}") public void scheduled(){try{recoverMissingTasks(100);processOnce(10);}catch(RuntimeException ignored){}}
  public int recoverMissingTasks(int limit){List<Long> ids=jdbc.queryForList("""
      SELECT s.id FROM edu_tutor_session s WHERE s.status='CLOSED' AND s.deleted_at IS NULL
        AND EXISTS(SELECT 1 FROM edu_tutor_message m WHERE m.session_id=s.id)
        AND NOT EXISTS(SELECT 1 FROM edu_profile_summary_task t WHERE t.session_id=s.id)
      ORDER BY s.id LIMIT ?
      """,Long.class,limit);for(Long id:ids){var session=jdbc.queryForMap("SELECT id,student_id FROM edu_tutor_session WHERE id=?",id);try{jdbc.update("INSERT INTO edu_profile_summary_task(session_id,student_id,request_id,status,next_attempt_at) VALUES(?,?,?,'PENDING',CURRENT_TIMESTAMP)",id,session.get("student_id"),"profile-summary-request-"+id);}catch(DuplicateKeyException ignored){}}return ids.size();}
  public int processOnce(int limit){List<Long> ids=jdbc.queryForList("""
      SELECT id FROM edu_profile_summary_task WHERE attempt_count<5 AND next_attempt_at<=CURRENT_TIMESTAMP
      AND (status='PENDING' OR (status='PROCESSING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP))) ORDER BY id LIMIT ?
      """,Long.class,limit);int handled=0;for(Long id:ids){Task task=claim(id);if(task==null)continue;handled++;try{process(task);}catch(RuntimeException failure){fail(task,failure);}}return handled;}
  private Task claim(Long id){String claimToken=UUID.randomUUID().toString();return tx.execute(s->{int n=jdbc.update("""
      UPDATE edu_profile_summary_task SET status='PROCESSING',attempt_count=attempt_count+1,lease_owner=?,lease_until=?,updated_at=CURRENT_TIMESTAMP
      WHERE id=? AND attempt_count<5 AND next_attempt_at<=CURRENT_TIMESTAMP AND
      (status='PENDING' OR (status='PROCESSING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)))
      """,claimToken,Timestamp.valueOf(LocalDateTime.now().plusSeconds(90)),id);if(n==0)return null;return jdbc.queryForObject("SELECT t.*,s.course_id FROM edu_profile_summary_task t JOIN edu_tutor_session s ON s.id=t.session_id AND s.student_id=t.student_id WHERE t.id=?",this::task,id);});}
  private void process(Task task){List<AiLearningDtos.Evidence> evidence=jdbc.query("""
      SELECT m.id,m.content,m.created_at,s.knowledge_point_id FROM edu_tutor_message m
      JOIN edu_tutor_session s ON s.id=m.session_id WHERE m.session_id=?
        AND s.student_id=? AND s.course_id=? AND s.deleted_at IS NULL AND s.status='CLOSED'
      ORDER BY m.id DESC LIMIT 50
      """,(rs,n)->new AiLearningDtos.Evidence("TUTOR",rs.getLong("id"),rs.getString("knowledge_point_id"),limit(rs.getString("content"),4000),instant(rs.getTimestamp("created_at"))),task.sessionId(),task.studentId(),task.courseId());Collections.reverse(evidence);
    if(evidence.isEmpty())throw invalid("摘要证据为空");String previous=jdbc.queryForObject("SELECT summary FROM edu_tutor_session WHERE id=?",String.class,task.sessionId());
    AiLearningDtos.ProfileResponse response=ai.summarizeProfile(new AiLearningDtos.ProfileRequest(task.requestId(),task.courseId(),task.studentId(),previous,evidence));Validated validated=validate(task,response,new HashSet<>(evidence.stream().map(AiLearningDtos.Evidence::knowledgePointId).toList()));
    tx.executeWithoutResult(s->{Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM edu_profile_summary_task WHERE id=? AND status='PROCESSING' AND lease_owner=? AND attempt_count=?",Integer.class,task.id(),task.claimToken(),task.attempts());if(active==null||active!=1)return;
      int updated=jdbc.update("UPDATE edu_tutor_session SET summary=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND student_id=? AND course_id=? AND status='CLOSED' AND deleted_at IS NULL",validated.summary(),task.sessionId(),task.studentId(),task.courseId());
      if(updated!=1)throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"导师会话已不可总结");
      for(AiLearningDtos.MemoryCandidate c:validated.candidates())memories.createCandidate(task.studentId(),task.courseId(),MemoryType.valueOf(c.memoryType()),c.knowledgePointId(),c.title(),c.content(),"TUTOR_SESSION",task.sessionId(),c.confidence(),c.editable(),c.deletable());
      for(AiCognitiveLabel label:validated.labels())jdbc.update("""
          INSERT INTO edu_cognitive_label(student_id,course_id,knowledge_point_id,source_type,source_id,label_status,confidence,evidence)
          VALUES(?,?,?, 'TUTOR',?,?,?,?)
          """,task.studentId(),task.courseId(),label.knowledgePointId(),task.sessionId(),label.status(),label.confidence(),label.evidence());
      for(int i=0;i<validated.suggestions().size();i++){var suggestion=validated.suggestions().get(i);try{jdbc.update("""
          INSERT INTO edu_profile_resource_suggestion(task_id,session_id,student_id,course_id,position_no,
          knowledge_point_id,reason,preferred_resource_types_json) VALUES(?,?,?,?,?,?,?,?)
          """,task.id(),task.sessionId(),task.studentId(),task.courseId(),i+1,suggestion.knowledgePointId(),
          suggestion.reason().trim(),json.writeValueAsString(suggestion.preferredResourceTypes()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"资源建议序列化失败");}}
      String knowledgePointsJson;try{knowledgePointsJson=json.writeValueAsString(evidence.stream().map(AiLearningDtos.Evidence::knowledgePointId).distinct().toList());}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"学习事件序列化失败");}try{jdbc.update("""
          INSERT INTO edu_learning_event(dedupe_key,student_id,course_id,event_type,title,summary,knowledge_point_ids_json,related_type,related_id,occurred_at)
          VALUES(?,?,?, 'TUTOR_SESSION_CLOSED','导师会话已总结',?,?, 'TUTOR_SESSION',?,CURRENT_TIMESTAMP)
          ""","TUTOR_SESSION_CLOSED:"+task.sessionId(),task.studentId(),task.courseId(),validated.summary(),knowledgePointsJson,task.sessionId());}catch(DuplicateKeyException ignored){}
      try{jdbc.update("UPDATE edu_profile_summary_task SET status='SUCCEEDED',result_json=?,lease_owner=NULL,lease_until=NULL,error_code=NULL,error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING' AND lease_owner=? AND attempt_count=?",json.writeValueAsString(response),task.id(),task.claimToken(),task.attempts());}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"摘要结果序列化失败");}
    });}
  private Validated validate(Task task,AiLearningDtos.ProfileResponse r,Set<String> allowedKps){if(r==null||!task.requestId().equals(r.requestId())||!task.courseId().equals(r.courseId())||blank(r.summary())||r.summary().length()>10000||blank(r.modelVersion())||blank(r.promptVersion())||r.memoryCandidates()==null||r.cognitiveLabels()==null||r.resourceSuggestions()==null||r.memoryCandidates().size()>20||r.cognitiveLabels().size()>20||r.resourceSuggestions().size()>20)throw invalid("基础字段缺失或课程不符");
    List<AiLearningDtos.MemoryCandidate> candidates=new ArrayList<>();for(var c:r.memoryCandidates()){if(c==null||!candidateType(c.memoryType())||blank(c.content())||c.content().length()>10000||(c.title()!=null&&c.title().length()>200)||(c.knowledgePointId()!=null&&!allowedKps.contains(c.knowledgePointId()))||c.confidence()==null||c.confidence().compareTo(BigDecimal.ZERO)<0||c.confidence().compareTo(BigDecimal.ONE)>0||c.editable()==null||c.deletable()==null)throw invalid("记忆候选不合法");candidates.add(c);}
    List<AiCognitiveLabel> labels=new ArrayList<>();for(var l:r.cognitiveLabels()){if(l==null||blank(l.knowledgePointId())||!allowedKps.contains(l.knowledgePointId())||!Set.of("MASTERED","LEARNING","WEAK","UNKNOWN").contains(l.status())||l.confidence()==null||l.confidence().compareTo(BigDecimal.ZERO)<0||l.confidence().compareTo(BigDecimal.ONE)>0||blank(l.evidence())||l.evidence().length()>4000)throw invalid("认知标签不合法");labels.add(l);}
    List<AiLearningDtos.ResourceSuggestion> suggestions=new ArrayList<>();for(var s:r.resourceSuggestions()){if(s==null||blank(s.knowledgePointId())||!allowedKps.contains(s.knowledgePointId())||blank(s.reason())||s.reason().length()>1000||s.preferredResourceTypes()==null||s.preferredResourceTypes().size()>7||new HashSet<>(s.preferredResourceTypes()).size()!=s.preferredResourceTypes().size()||s.preferredResourceTypes().stream().anyMatch(this::blank)||!RESOURCE_TYPES.containsAll(s.preferredResourceTypes()))throw invalid("资源建议不合法");suggestions.add(s);}
    return new Validated(r.summary().trim(),List.copyOf(candidates),List.copyOf(labels),List.copyOf(suggestions));}
  private boolean candidateType(String v){try{return MemoryType.valueOf(v)!=MemoryType.TEACHER_NOTE;}catch(Exception e){return false;}}
  private void fail(Task t,RuntimeException f){int attempts=t.attempts();jdbc.update("""
      UPDATE edu_profile_summary_task SET status=?,next_attempt_at=?,lease_owner=NULL,lease_until=NULL,error_code=?,error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING' AND lease_owner=? AND attempt_count=?
      """,attempts>=5?"FAILED":"PENDING",Timestamp.valueOf(LocalDateTime.now().plusSeconds(Math.min(300,1L<<Math.min(attempts,8)))),f instanceof BusinessException b?b.errorCode().name():"PROFILE_SUMMARY_FAILED",limit(f.getMessage(),1000),t.id(),t.claimToken(),attempts);}
  private Task task(ResultSet rs,int n)throws SQLException{return new Task(rs.getLong("id"),rs.getLong("session_id"),rs.getLong("student_id"),rs.getLong("course_id"),rs.getString("request_id"),rs.getInt("attempt_count"),rs.getString("lease_owner"));}
  private BusinessException invalid(String reason){return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"学习摘要响应未通过校验",Map.of("reason",reason));}private boolean blank(String v){return v==null||v.isBlank();}private String limit(String v,int n){if(v==null)return "";return v.length()<=n?v:v.substring(0,n);}private Instant instant(Timestamp t){return t.toInstant();}
  private record Task(Long id,Long sessionId,Long studentId,Long courseId,String requestId,int attempts,String claimToken){}private record Validated(String summary,List<AiLearningDtos.MemoryCandidate> candidates,List<AiCognitiveLabel> labels,List<AiLearningDtos.ResourceSuggestion> suggestions){}
}
