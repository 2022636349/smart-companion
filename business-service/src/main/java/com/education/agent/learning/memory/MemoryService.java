package com.education.agent.learning.memory;

import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.*;
import com.education.agent.course.CourseAccessService;
import com.education.agent.learning.memory.MemoryDtos.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemoryService {
  private static final ZoneId DB_ZONE=ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc; private final ObjectMapper json; private final TransactionTemplate tx;
  private final IdempotencyRecordMapper idempotency; private final AuditLogService audit;
  private final CourseAccessService courses;
  public MemoryService(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,
      IdempotencyRecordMapper idempotency,AuditLogService audit,CourseAccessService courses){this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);this.idempotency=idempotency;this.audit=audit;this.courses=courses;}

  public PageData<MemoryView> list(AuthenticatedUser user,Long courseId,long page,long size,MemoryType type,String kp,MemoryStatus status){
    requireStudent(user);courses.requireView(user,courseId);StringBuilder where=new StringBuilder(" WHERE student_id=? AND course_id=?");List<Object> args=new ArrayList<>(List.of(user.id(),courseId));
    if(type!=null){where.append(" AND memory_type=?");args.add(type.name());}if(kp!=null&&!kp.isBlank()){where.append(" AND knowledge_point_id=?");args.add(kp.trim());}
    if(status!=null){where.append(" AND status=?");args.add(status.name());}
    long total=jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_memory"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
    List<MemoryView> items=jdbc.query("SELECT * FROM edu_learning_memory"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
    return new PageData<>(items,page,size,total,(total+size-1)/size);
  }
  public Preferences preferences(AuthenticatedUser user){requireStudent(user);return preferences(user.id());}
  public Preferences preferences(Long studentId){
    List<Preferences> rows=jdbc.query("SELECT * FROM edu_memory_preference WHERE student_id=?",(rs,n)->new Preferences(rs.getBoolean("long_term_memory_enabled"),rs.getBoolean("tutor_personalization_enabled"),rs.getBoolean("allow_weakness_memory"),rs.getBoolean("allow_preference_memory"),rs.getInt("retention_days")),studentId);
    return rows.isEmpty()?new Preferences(true,true,true,true,365):rows.get(0);
  }
  public Preferences updatePreferences(AuthenticatedUser user,Preferences request){requireStudent(user);
    tx.executeWithoutResult(s->{lockStudent(user.id());Preferences previous=preferencesForUpdate(user.id());int n=jdbc.update("""
        UPDATE edu_memory_preference SET long_term_memory_enabled=?,tutor_personalization_enabled=?,
        allow_weakness_memory=?,allow_preference_memory=?,retention_days=?,updated_at=CURRENT_TIMESTAMP
        WHERE student_id=?
        """,request.longTermMemoryEnabled(),request.tutorPersonalizationEnabled(),request.allowWeaknessMemory(),request.allowPreferenceMemory(),request.retentionDays(),user.id());
      if(n==0)jdbc.update("""
          INSERT INTO edu_memory_preference(student_id,long_term_memory_enabled,tutor_personalization_enabled,
          allow_weakness_memory,allow_preference_memory,retention_days) VALUES(?,?,?,?,?,?)
          """,user.id(),request.longTermMemoryEnabled(),request.tutorPersonalizationEnabled(),request.allowWeaknessMemory(),request.allowPreferenceMemory(),request.retentionDays());
      if(!request.longTermMemoryEnabled().equals(previous.longTermMemoryEnabled())){
        String action=request.longTermMemoryEnabled()?"UPSERT":"DEINDEX";
        activeRowsForUpdate(user.id()).forEach(r->advance(r,action,null));
      }
    });audit.record("MEMORY_PREFERENCES_UPDATED","STUDENT",user.id().toString(),user.id(),true,Map.of("longTermMemoryEnabled",request.longTermMemoryEnabled()));return preferences(user.id());}

  public void clear(AuthenticatedUser user,String key,ClearRequest request){requireStudent(user);if(!Boolean.TRUE.equals(request.confirm()))throw new BusinessException(ErrorCode.VALIDATION_ERROR,"必须明确确认清除记忆");
    String hash=sha256(normalizedClear(request));IdempotencyRecordEntity old=find(user.id(),"CLEAR_MEMORIES",key);if(old!=null){verifyHash(old,hash);if(old.getResponseJson()!=null)return;}
    tx.executeWithoutResult(s->{lockStudent(user.id());MemoryClaim acquired=claimAfterLock(user.id(),"CLEAR_MEMORIES",key,hash);IdempotencyRecordEntity claim=acquired.record();if(!acquired.owned()){if(claim.getResponseJson()!=null)return;throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"相同幂等请求仍在处理中");}
      StringBuilder sql=new StringBuilder("SELECT * FROM edu_learning_memory WHERE student_id=? AND status='ACTIVE' AND deletable=TRUE AND memory_type<>'TEACHER_NOTE'");List<Object> args=new ArrayList<>(List.of(user.id()));
      if(request.memoryTypes()!=null&&!request.memoryTypes().isEmpty()){sql.append(" AND memory_type IN (").append(String.join(",",Collections.nCopies(request.memoryTypes().size(),"?"))).append(")");request.memoryTypes().forEach(t->args.add(t.name()));}
      if(request.knowledgePointId()!=null&&!request.knowledgePointId().isBlank()){sql.append(" AND knowledge_point_id=?");args.add(request.knowledgePointId().trim());}
      if(request.before()!=null){sql.append(" AND created_at<?");args.add(Timestamp.from(request.before()));}sql.append(" FOR UPDATE");
      List<MemoryRow> rows=jdbc.query(sql.toString(),this::row,args.toArray());rows.forEach(r->advance(r,"TERMINAL_DELETE","DELETED"));complete(claim,"{\"success\":true}");
    });audit.record("MEMORIES_CLEARED","STUDENT",user.id().toString(),user.id(),true,Map.of());}

  public MemoryView revise(AuthenticatedUser user,Long memoryId,String key,RevisionRequest request){requireStudent(user);String hash=sha256(memoryId+"\n"+request.title()+"\n"+request.content().trim()+"\n"+request.revisionReason().trim());String op="REVISE_MEMORY:"+memoryId;
    requireOwnedCourse(user,memoryId);
    IdempotencyRecordEntity old=find(user.id(),op,key);if(old!=null){verifyHash(old,hash);if(old.getResponseJson()!=null)try{return json.readValue(old.getResponseJson(),MemoryView.class);}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"幂等响应损坏");}}
    MemoryView result=tx.execute(s->{lockStudent(user.id());MemoryClaim acquired=claimAfterLock(user.id(),op,key,hash);IdempotencyRecordEntity claim=acquired.record();if(!acquired.owned()){if(claim.getResponseJson()!=null)return readMemoryView(claim.getResponseJson());throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"相同幂等请求仍在处理中");}MemoryRow prior=requireOwnedForUpdate(user.id(),memoryId);
      if(!"ACTIVE".equals(prior.status())||!prior.editable())throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE,"该记忆不能修订");
      advance(prior,"TERMINAL_DELETE","SUPERSEDED");
      courses.requireView(user,prior.courseId());
      String content=request.content().trim();Long id=insertMemory(prior.studentId(),prior.courseId(),prior.memoryType(),prior.knowledgePointId(),trim(request.title()),content,
          "STUDENT",prior.id(),prior.confidence(),prior.version()+1,prior.id(),request.revisionReason().trim(),prior.editable(),prior.deletable(),prior.expiresAt());
      MemoryRow created=requireOwnedForUpdate(user.id(),id);created=advance(created,preferences(user.id()).longTermMemoryEnabled()?"UPSERT":"DEINDEX",null);MemoryView view=view(created);try{complete(claim,json.writeValueAsString(view));}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"幂等响应序列化失败");}return view;});
    audit.record("MEMORY_REVISED","LEARNING_MEMORY",memoryId.toString(),user.id(),true,Map.of("newMemoryId",result.id()));return result;}

  public void delete(AuthenticatedUser user,Long memoryId){requireStudent(user);tx.executeWithoutResult(s->{lockStudent(user.id());MemoryRow row=requireOwnedForUpdate(user.id(),memoryId);courses.requireView(user,row.courseId());if("DELETED".equals(row.status()))return;if(!row.deletable()||"TEACHER_NOTE".equals(row.memoryType()))throw new BusinessException(ErrorCode.FORBIDDEN,"该记忆不可删除");advance(row,"TERMINAL_DELETE","DELETED");});audit.record("MEMORY_DELETED","LEARNING_MEMORY",memoryId.toString(),user.id(),true,Map.of());}

  public boolean personalizationEnabled(Long studentId){Preferences p=preferences(studentId);if(!p.longTermMemoryEnabled()||!p.tutorPersonalizationEnabled())return false;
    Integer rebuilding=jdbc.queryForObject("SELECT COUNT(*) FROM edu_memory_index_rebuild WHERE status IN ('RUNNING','FAILED')",Integer.class);if(rebuilding!=null&&rebuilding>0)return false;
    Integer unsafeDeletes=jdbc.queryForObject("""
        SELECT COUNT(*) FROM edu_memory_outbox o JOIN edu_learning_memory m ON m.id=o.memory_id
        WHERE o.student_id=? AND o.event_type IN ('DEINDEX','TERMINAL_DELETE')
          AND o.sync_generation=m.index_generation AND o.status<>'SUCCEEDED'
        """,Integer.class,studentId);
    // Fail closed before calling D: a deleted/superseded body may still exist in Chroma
    // until its durable DELETE has been acknowledged.
    return unsafeDeletes==null||unsafeDeletes==0;}
  public void validateRecall(Long studentId,Long courseId,List<Long> ids,boolean enabled){if(ids==null||ids.isEmpty())return;if(!enabled)throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"个性化关闭时 AI 不得使用记忆");if(ids.size()>10||new HashSet<>(ids).size()!=ids.size())throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"AI 返回的记忆引用无效");String marks=String.join(",",Collections.nCopies(ids.size(),"?"));List<Object> args=new ArrayList<>(List.of(studentId,courseId));args.addAll(ids);Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM edu_learning_memory WHERE student_id=? AND course_id=? AND status='ACTIVE' AND id IN ("+marks+")",Integer.class,args.toArray());if(count==null||count!=ids.size())throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"AI 引用了不可用、跨学生或跨课程记忆");}
  public void validateRecallAtCommit(Long studentId,Long courseId,List<Long> ids){if(ids==null||ids.isEmpty())return;lockStudent(studentId);if(!personalizationEnabled(studentId))throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"记忆偏好或索引状态已变化，请重试");try{validateRecall(studentId,courseId,ids,true);}catch(BusinessException invalid){throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"记忆状态已变化，请重试");}}
  @Transactional
  public MemoryView createCandidate(Long studentId,Long courseId,MemoryType type,String kp,String title,String content,String sourceType,Long sourceId,java.math.BigDecimal confidence,boolean editable,boolean deletable){
    courses.requireCourse(courseId);
    lockStudent(studentId);
    Preferences current=preferences(studentId);if(!candidateAllowed(current,type))return null;
    String normalized=content.trim();Long id=insertMemory(studentId,courseId,type.name(),kp,trim(title),normalized,sourceType,sourceId,confidence,1,null,null,editable,deletable&&type!=MemoryType.TEACHER_NOTE,null);
    MemoryRow row=jdbc.queryForObject("SELECT * FROM edu_learning_memory WHERE id=? FOR UPDATE",this::row,id);row=advance(row,"UPSERT",null);return view(row);
  }
  public synchronized void rebuildAll(){startRebuild("manual-memory-rebuild-"+UUID.randomUUID());}
  public synchronized boolean handleIndexSignal(String epoch,Boolean required){
    if(required==null||epoch==null||epoch.length()<16||epoch.length()>64)throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,"记忆索引恢复信号无效");
    List<String> states=jdbc.queryForList("SELECT status FROM edu_memory_index_rebuild WHERE epoch=?",String.class,epoch);
    boolean reconciledBefore=!states.isEmpty()&&"SUCCEEDED".equals(states.get(0));
    if(!reconciledBefore)startRebuild(epoch);
    return reconciledBefore;
  }
  public void refreshRebuild(String epoch){if(epoch==null)return;Integer failed=jdbc.queryForObject("SELECT COUNT(*) FROM edu_memory_outbox WHERE rebuild_epoch=? AND status='FAILED'",Integer.class,epoch);Integer remaining=jdbc.queryForObject("SELECT COUNT(*) FROM edu_memory_outbox WHERE rebuild_epoch=? AND status<>'SUCCEEDED'",Integer.class,epoch);if(failed!=null&&failed>0)jdbc.update("UPDATE edu_memory_index_rebuild SET status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE epoch=? AND status='RUNNING'",epoch);else if(remaining!=null&&remaining==0)jdbc.update("UPDATE edu_memory_index_rebuild SET status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP WHERE epoch=? AND status='RUNNING'",epoch);}
  private void startRebuild(String epoch){tx.executeWithoutResult(s->{Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM edu_memory_index_rebuild WHERE epoch=?",Integer.class,epoch);if(exists!=null&&exists>0)return;jdbc.update("UPDATE edu_memory_index_rebuild SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE status IN ('RUNNING','FAILED')");jdbc.update("INSERT INTO edu_memory_index_rebuild(epoch,status,job_count) VALUES(?,'RUNNING',0)",epoch);List<MemoryRow> rows=allRowsForUpdate();for(MemoryRow r:rows){String action="ACTIVE".equals(r.status())?(preferences(r.studentId()).longTermMemoryEnabled()?"UPSERT":"DEINDEX"):"TERMINAL_DELETE";advance(r,action,null,epoch);}jdbc.update("UPDATE edu_memory_index_rebuild SET job_count=?,status=? WHERE epoch=?",rows.size(),rows.isEmpty()?"SUCCEEDED":"RUNNING",epoch);});}
  @EventListener(ApplicationReadyEvent.class)
  public void rebuildOnStartup(){try{startRebuild("startup-memory-rebuild-"+UUID.randomUUID());}catch(RuntimeException ignored){/* startup recovery must not block MySQL-backed business */}}
  private List<MemoryRow> activeRowsForUpdate(Long studentId){return jdbc.query("SELECT * FROM edu_learning_memory WHERE status='ACTIVE' AND student_id=? ORDER BY id FOR UPDATE",this::row,studentId);}
  private Preferences preferencesForUpdate(Long studentId){List<Preferences> rows=jdbc.query("SELECT * FROM edu_memory_preference WHERE student_id=? FOR UPDATE",(rs,n)->new Preferences(rs.getBoolean("long_term_memory_enabled"),rs.getBoolean("tutor_personalization_enabled"),rs.getBoolean("allow_weakness_memory"),rs.getBoolean("allow_preference_memory"),rs.getInt("retention_days")),studentId);return rows.isEmpty()?new Preferences(true,true,true,true,365):rows.get(0);}
  private List<MemoryRow> allRowsForUpdate(){return jdbc.query("SELECT * FROM edu_learning_memory ORDER BY id FOR UPDATE",this::row);}
  private MemoryRow advance(MemoryRow r,String action,String newStatus){return advance(r,action,newStatus,null);}
  private MemoryRow advance(MemoryRow r,String action,String newStatus,String rebuildEpoch){int generation=Math.addExact(r.indexGeneration(),1);int changed=jdbc.update("""
      UPDATE edu_learning_memory SET index_generation=?,status=COALESCE(?,status),updated_at=CURRENT_TIMESTAMP
      WHERE id=? AND index_generation=?
      """,generation,newStatus,r.id(),r.indexGeneration());if(changed!=1)throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"记忆索引代次并发冲突");MemoryRow next=jdbc.queryForObject("SELECT * FROM edu_learning_memory WHERE id=?",this::row,r.id());enqueue(next,action,rebuildEpoch);return next;}
  private void enqueue(MemoryRow r,String action,String rebuildEpoch){String key="MEMORY:"+r.id()+":"+r.indexGeneration()+":"+action;String payload="UPSERT".equals(action)?payload(r):null;try{jdbc.update("""
      INSERT INTO edu_memory_outbox(dedupe_key,event_type,memory_id,student_id,memory_version,sync_generation,rebuild_epoch,payload_json,status,next_attempt_at)
      VALUES(?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP)
      """,key,action,r.id(),r.studentId(),r.version(),r.indexGeneration(),rebuildEpoch,payload);}catch(DuplicateKeyException duplicate){/* exact transition replay */}}
  private Long insertMemory(Long studentId,Long courseId,String type,String kp,String title,String content,String sourceType,
      Long sourceId,java.math.BigDecimal confidence,int version,Long supersedesId,String reason,
      boolean editable,boolean deletable,Timestamp expiresAt){KeyHolder keys=new GeneratedKeyHolder();jdbc.update(connection->{var ps=connection.prepareStatement("""
        INSERT INTO edu_learning_memory(student_id,course_id,memory_type,knowledge_point_id,title,content,content_hash,
        source_type,source_id,confidence,status,version_no,supersedes_id,revision_reason,editable,deletable,expires_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?)
        """,new String[]{"id"});ps.setLong(1,studentId);ps.setLong(2,courseId);ps.setString(3,type);ps.setString(4,kp);ps.setString(5,title);ps.setString(6,content);ps.setString(7,sha256(content));ps.setString(8,sourceType);if(sourceId==null)ps.setNull(9,Types.BIGINT);else ps.setLong(9,sourceId);ps.setBigDecimal(10,confidence);ps.setInt(11,version);if(supersedesId==null)ps.setNull(12,Types.BIGINT);else ps.setLong(12,supersedesId);ps.setString(13,reason);ps.setBoolean(14,editable);ps.setBoolean(15,deletable);ps.setTimestamp(16,expiresAt);return ps;},keys);Number key=keys.getKey();if(key==null)throw new BusinessException(ErrorCode.INTERNAL_ERROR,"创建记忆后未取得主键");return key.longValue();}
  private String payload(MemoryRow r){try{return json.writeValueAsString(Map.of("contentHash",r.contentHash()));}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"记忆 Outbox 序列化失败");}}
  private MemoryRow requireOwnedForUpdate(Long studentId,Long id){List<MemoryRow> rows=jdbc.query("SELECT * FROM edu_learning_memory WHERE id=? AND student_id=? FOR UPDATE",this::row,id,studentId);if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"伴学记忆不存在");return rows.get(0);}
  private MemoryRow row(ResultSet rs,int n)throws SQLException{return new MemoryRow(rs.getLong("id"),rs.getLong("student_id"),rs.getLong("course_id"),rs.getString("memory_type"),rs.getString("knowledge_point_id"),rs.getString("title"),rs.getString("content"),rs.getString("content_hash"),rs.getString("source_type"),nullableLong(rs,"source_id"),rs.getBigDecimal("confidence"),rs.getString("status"),rs.getInt("version_no"),rs.getInt("index_generation"),nullableLong(rs,"supersedes_id"),rs.getBoolean("editable"),rs.getBoolean("deletable"),rs.getTimestamp("expires_at"),rs.getTimestamp("created_at"),rs.getTimestamp("updated_at"));}
  private MemoryView map(ResultSet rs,int n)throws SQLException{return view(row(rs,n));}
  private MemoryView view(MemoryRow r){String name=r.knowledgePointId()==null?null:jdbc.query("SELECT MAX(knowledge_point_name) FROM edu_knowledge_mastery WHERE student_id=? AND course_id=? AND knowledge_point_id=?",rs->rs.next()?rs.getString(1):null,r.studentId(),r.courseId(),r.knowledgePointId());return new MemoryView(r.id(),r.studentId(),r.courseId(),MemoryType.valueOf(r.memoryType()),r.knowledgePointId(),name,r.title(),r.content(),r.sourceType(),r.sourceId(),r.confidence(),MemoryStatus.valueOf(r.status()),r.version(),r.supersedesId(),r.editable(),r.deletable(),instant(r.expiresAt()),instant(r.createdAt()),instant(r.updatedAt()));}
  private void requireOwnedCourse(AuthenticatedUser user,Long memoryId){List<Long> ids=jdbc.queryForList("SELECT course_id FROM edu_learning_memory WHERE id=? AND student_id=?",Long.class,memoryId,user.id());if(ids.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"伴学记忆不存在");courses.requireView(user,ids.get(0));}
  private MemoryClaim claimAfterLock(Long user,String op,String key,String hash){IdempotencyRecordEntity current=find(user,op,key);if(current!=null){verifyHash(current,hash);return new MemoryClaim(current,false);}IdempotencyRecordEntity r=new IdempotencyRecordEntity();r.setUserId(user);r.setOperationId(op);r.setIdempotencyKey(key);r.setRequestHash(hash);r.setExpiresAt(LocalDateTime.now().plusHours(24));try{idempotency.insert(r);return new MemoryClaim(r,true);}catch(DuplicateKeyException e){current=find(user,op,key);verifyHash(current,hash);return new MemoryClaim(current,false);}}
  private IdempotencyRecordEntity find(Long user,String op,String key){return idempotency.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IdempotencyRecordEntity>().eq(IdempotencyRecordEntity::getUserId,user).eq(IdempotencyRecordEntity::getOperationId,op).eq(IdempotencyRecordEntity::getIdempotencyKey,key));}
  private void verifyHash(IdempotencyRecordEntity r,String hash){if(r==null||!hash.equals(r.getRequestHash()))throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"幂等键已用于不同请求");}
  private void complete(IdempotencyRecordEntity r,String response){r.setResponseStatus(200);r.setResponseJson(response);idempotency.updateById(r);}
  private MemoryView readMemoryView(String value){try{return json.readValue(value,MemoryView.class);}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"幂等响应损坏");}}
  private String normalizedClear(ClearRequest r){List<String> t=r.memoryTypes()==null?List.of():r.memoryTypes().stream().map(Enum::name).sorted().toList();return t+"|"+trim(r.knowledgePointId())+"|"+r.before()+"|"+r.confirm();}
  private void lockStudent(Long studentId){List<Long> found=jdbc.queryForList("SELECT id FROM edu_user WHERE id=? FOR UPDATE",Long.class,studentId);if(found.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"学生不存在");}
  private void requireStudent(AuthenticatedUser u){if(u.role()!=UserRole.STUDENT)throw new BusinessException(ErrorCode.FORBIDDEN,"伴学记忆仅对学生本人开放");}
  private boolean candidateAllowed(Preferences p,MemoryType type){if(!p.longTermMemoryEnabled())return false;if((type==MemoryType.MISCONCEPTION||type==MemoryType.WEAK_EVIDENCE)&&!p.allowWeaknessMemory())return false;return type!=MemoryType.LEARNING_PREFERENCE||p.allowPreferenceMemory();}
  private String sha256(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private String trim(String v){return v==null||v.isBlank()?null:v.trim();}private Long nullableLong(ResultSet rs,String c)throws SQLException{long v=rs.getLong(c);return rs.wasNull()?null:v;}private Instant instant(Timestamp t){return t==null?null:t.toLocalDateTime().atZone(DB_ZONE).toInstant();}
  public record MemoryRow(Long id,Long studentId,Long courseId,String memoryType,String knowledgePointId,String title,String content,String contentHash,String sourceType,Long sourceId,java.math.BigDecimal confidence,String status,int version,int indexGeneration,Long supersedesId,boolean editable,boolean deletable,Timestamp expiresAt,Timestamp createdAt,Timestamp updatedAt){}
  private record MemoryClaim(IdempotencyRecordEntity record,boolean owned){}
}
