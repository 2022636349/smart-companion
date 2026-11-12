package com.education.agent.learning.memory;

import com.education.agent.common.error.BusinessException;
import com.education.agent.integration.ai.AiLearningDtos;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.learning.memory.MemoryService.MemoryRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemoryOutboxWorker {
  private static final ZoneId DB_ZONE=ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;private final AiServiceClient ai;private final MemoryService memories;
  private final TransactionTemplate tx;
  public MemoryOutboxWorker(JdbcTemplate jdbc,AiServiceClient ai,MemoryService memories,PlatformTransactionManager manager){this.jdbc=jdbc;this.ai=ai;this.memories=memories;this.tx=new TransactionTemplate(manager);}

  @Scheduled(fixedDelayString="${education.memory.outbox-scan-ms:3000}",initialDelayString="${education.memory.outbox-initial-delay-ms:0}")
  public void scheduled(){try{processOnce(20);}catch(RuntimeException ignored){}}
  public int processOnce(int limit){List<Long> ids=jdbc.queryForList("""
      SELECT id FROM edu_memory_outbox
      WHERE attempt_count<5 AND next_attempt_at<=CURRENT_TIMESTAMP
        AND (status='PENDING' OR (status='PROCESSING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)))
      ORDER BY id LIMIT ?
      """,Long.class,limit);int handled=0;for(Long id:ids){Outbox job=claim(id);if(job==null)continue;handled++;try{deliver(job);success(job);}catch(RuntimeException failure){fail(job,failure);}}return handled;}
  private Outbox claim(Long id){String claimToken=UUID.randomUUID().toString();return tx.execute(s->{int n=jdbc.update("""
      UPDATE edu_memory_outbox SET status='PROCESSING',attempt_count=attempt_count+1,lease_owner=?,lease_until=?,updated_at=CURRENT_TIMESTAMP
      WHERE id=? AND attempt_count<5 AND next_attempt_at<=CURRENT_TIMESTAMP
        AND (status='PENDING' OR (status='PROCESSING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)))
      """,claimToken,Timestamp.valueOf(LocalDateTime.now().plusSeconds(30)),id);if(n==0)return null;return jdbc.queryForObject("SELECT * FROM edu_memory_outbox WHERE id=?",this::outbox,id);});}
  private void deliver(Outbox job){String requestId="memory-outbox-request-"+job.id();MemoryRow memory=find(job.memoryId());
    if(memory==null||memory.indexGeneration()!=job.generation())return;
    boolean preferenceEnabled=memories.preferences(memory.studentId()).longTermMemoryEnabled();
    if("UPSERT".equals(job.eventType())){
      if(!"ACTIVE".equals(memory.status())||memory.version()!=job.version()||!preferenceEnabled)return;
      AiLearningDtos.MemoryUpsertResponse response=ai.upsertMemory(new AiLearningDtos.MemoryUpsertRequest(requestId,memory.id(),memory.studentId(),memory.courseId(),memory.memoryType(),memory.knowledgePointId(),memory.content(),memory.contentHash(),"ACTIVE",memory.version(),memory.indexGeneration(),memory.sourceType(),memory.sourceId(),instant(memory.createdAt())));
      if(response==null||!requestId.equals(response.requestId())||!"learning_memory".equals(response.collection())||!memory.id().equals(response.memoryId())||!Integer.valueOf(memory.indexGeneration()).equals(response.generation())||!Boolean.TRUE.equals(response.indexed()))throw invalid();memories.handleIndexSignal(response.memoryIndexEpoch(),response.memoryRebuildRequired());return;
    }
    if("DEINDEX".equals(job.eventType())){if(!"ACTIVE".equals(memory.status())||preferenceEnabled)return;delete(requestId,job,"DEINDEX");return;}
    if("TERMINAL_DELETE".equals(job.eventType())){if("ACTIVE".equals(memory.status()))return;delete(requestId,job,"TERMINAL_DELETE");return;}
    throw invalid();}
  private void delete(String requestId,Outbox job,String mode){AiLearningDtos.DeleteResponse response=ai.deleteMemory(requestId,job.memoryId(),mode,job.generation());if(response==null||!requestId.equals(response.requestId())||!"learning_memory".equals(response.collection())||!job.memoryId().equals(response.memoryId())||!mode.equals(response.mode())||!Integer.valueOf(job.generation()).equals(response.generation())||response.deletedCount()==null||response.deletedCount()<0)throw invalid();memories.handleIndexSignal(response.memoryIndexEpoch(),response.memoryRebuildRequired());}
  private void success(Outbox job){jdbc.update("UPDATE edu_memory_outbox SET status='SUCCEEDED',lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING' AND lease_owner=? AND attempt_count=?",job.id(),job.claimToken(),job.attempts());memories.refreshRebuild(job.rebuildEpoch());}
  private void fail(Outbox job,RuntimeException failure){int attempts=job.attempts();String status=attempts>=5?"FAILED":"PENDING";long delay=Math.min(300,1L<<Math.min(attempts,8));jdbc.update("""
      UPDATE edu_memory_outbox SET status=?,attempt_count=?,next_attempt_at=?,lease_owner=NULL,
      lease_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING' AND lease_owner=? AND attempt_count=?
      """,status,attempts,Timestamp.valueOf(LocalDateTime.now().plusSeconds(delay)),safe(failure),job.id(),job.claimToken(),attempts);memories.refreshRebuild(job.rebuildEpoch());}
  private MemoryRow find(Long id){List<MemoryRow> rows=jdbc.query("SELECT * FROM edu_learning_memory WHERE id=?",(rs,n)->new MemoryRow(rs.getLong("id"),rs.getLong("student_id"),rs.getLong("course_id"),rs.getString("memory_type"),rs.getString("knowledge_point_id"),rs.getString("title"),rs.getString("content"),rs.getString("content_hash"),rs.getString("source_type"),nullableLong(rs,"source_id"),rs.getBigDecimal("confidence"),rs.getString("status"),rs.getInt("version_no"),rs.getInt("index_generation"),nullableLong(rs,"supersedes_id"),rs.getBoolean("editable"),rs.getBoolean("deletable"),rs.getTimestamp("expires_at"),rs.getTimestamp("created_at"),rs.getTimestamp("updated_at")),id);return rows.isEmpty()?null:rows.get(0);}
  private Outbox outbox(ResultSet rs,int n)throws SQLException{return new Outbox(rs.getLong("id"),rs.getString("event_type"),rs.getLong("memory_id"),rs.getInt("memory_version"),rs.getInt("sync_generation"),rs.getInt("attempt_count"),rs.getString("lease_owner"),rs.getString("rebuild_epoch"));}
  private BusinessException invalid(){return new BusinessException(com.education.agent.common.error.ErrorCode.AI_SERVICE_INVALID_RESPONSE,"记忆索引响应未通过校验");}private String safe(Throwable f){String m=f.getMessage();return (f.getClass().getSimpleName()+":"+(m==null?"":m)).substring(0,Math.min(1000,f.getClass().getSimpleName().length()+1+(m==null?0:m.length())));}private Long nullableLong(ResultSet rs,String c)throws SQLException{long v=rs.getLong(c);return rs.wasNull()?null:v;}private java.time.Instant instant(Timestamp t){return t==null?null:t.toLocalDateTime().atZone(DB_ZONE).toInstant();}
  private record Outbox(Long id,String eventType,Long memoryId,int version,int generation,int attempts,String claimToken,String rebuildEpoch){}
}
