package com.education.agent.maintenance;

import com.education.agent.common.web.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionMaintenanceCoordinator {
  public static final String JOB="retention-cleanup";
  private final JdbcTemplate jdbc; private final MaintenanceLeaseService leases;
  private final RetentionCleanupWorker worker; private final ObjectMapper json; private final Clock clock;
  private final String owner; private final Duration leaseDuration; private final TransactionTemplate transactions;
  @Autowired public RetentionMaintenanceCoordinator(JdbcTemplate jdbc,MaintenanceLeaseService leases,
      RetentionCleanupWorker worker,ObjectMapper json,PlatformTransactionManager transactionManager,
      @Value("${education.maintenance.instance-id:}") String configuredOwner,
      @Value("${education.maintenance.lease-seconds:120}") long leaseSeconds){
    this(jdbc,leases,worker,json,transactionManager,Clock.systemUTC(),configuredOwner,leaseSeconds);
  }
  public RetentionMaintenanceCoordinator(JdbcTemplate jdbc,MaintenanceLeaseService leases,
      RetentionCleanupWorker worker,ObjectMapper json,PlatformTransactionManager transactionManager,
      Clock clock,String configuredOwner,long leaseSeconds){
    this.jdbc=jdbc;this.leases=leases;this.worker=worker;this.json=json;this.clock=clock;
    this.owner=(configuredOwner==null||configuredOwner.isBlank()?host():configuredOwner)+":"+UUID.randomUUID().toString().substring(0,8);
    this.leaseDuration=Duration.ofSeconds(Math.max(1,leaseSeconds));this.transactions=new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString="${education.maintenance.scan-ms:3600000}",initialDelayString="${education.maintenance.initial-delay-ms:300000}")
  public void scheduled(){run(UUID.randomUUID().toString());}

  public RunResult run(String runId){
    RunResult existing=find(runId);if(existing!=null)return replay(existing);
    Instant now=clock.instant();
    Claim claim=claimRun(runId,now);
    if(!claim.inserted())return replay(claim.result());
    if(!leases.tryAcquire(JOB,owner,now,now.plus(leaseDuration))){
      jdbc.update("""
          UPDATE edu_maintenance_run SET status='SKIPPED',finished_at=?
          WHERE run_id=? AND lease_owner=? AND status='RUNNING'
          """,Timestamp.from(clock.instant()),runId,owner);
      return replay(find(runId));
    }
    try{
      jdbc.update("""
          UPDATE edu_maintenance_run SET status='FAILED',error_code='STALE_RUN_RECOVERED',
            error_message='租约失效后由后续执行恢复',finished_at=?
          WHERE job_name=? AND status='RUNNING' AND run_id<>?
          """,Timestamp.from(now),JOB,runId);
      try{
        executeFenced(runId,now);
      }catch(Exception failure){
        markFailedIfStillOwner(runId,failure);
      }
      return find(runId);
    }finally{leases.release(JOB,owner);}
  }

  private Claim claimRun(String runId,Instant started){
    boolean inserted=true;
    try{insert(runId,"RUNNING",started,null,0,0,null,null,"{}");}
    catch(DataIntegrityViolationException duplicate){inserted=false;}
    RunResult result=find(runId);if(result==null)throw new IllegalStateException("维护执行记录未创建");return new Claim(inserted,result);
  }
  private void insert(String runId,String status,Instant started,Instant finished,long scanned,long affected,String code,String message,String details){jdbc.update("""
      INSERT INTO edu_maintenance_run(job_name,run_id,lease_owner,status,scanned_count,affected_count,error_code,error_message,details_json,request_id,started_at,finished_at,created_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))
      """,JOB,runId,owner,status,scanned,affected,code,message,details,RequestIds.current(),Timestamp.from(started),finished==null?null:Timestamp.from(finished));}
  private void executeFenced(String runId,Instant cutoff){
    transactions.executeWithoutResult(status->{
      leases.lockAndVerify(JOB,owner);
      Map<String,Integer> counts=worker.cleanup(cutoff,()->leases.lockAndVerify(JOB,owner));
      leases.lockAndVerify(JOB,owner);
      long affected=counts.values().stream().mapToLong(Integer::longValue).sum();
      String details;try{details=json.writeValueAsString(counts);}catch(Exception failure){throw new IllegalStateException("维护结果序列化失败",failure);}
      int updated=jdbc.update("""
          UPDATE edu_maintenance_run SET status='SUCCEEDED',scanned_count=?,affected_count=?,
            error_code=NULL,error_message=NULL,details_json=?,finished_at=?
          WHERE run_id=? AND lease_owner=? AND status='RUNNING'
          """,affected,affected,details,Timestamp.from(clock.instant()),runId,owner);
      if(updated!=1)throw new MaintenanceLeaseService.LeaseLostException();
    });
  }
  private void markFailedIfStillOwner(String runId,Exception failure){
    transactions.executeWithoutResult(status->{
      try{leases.lockAndVerify(JOB,owner);}catch(MaintenanceLeaseService.LeaseLostException lost){return;}
      jdbc.update("""
          UPDATE edu_maintenance_run SET status='FAILED',scanned_count=0,affected_count=0,
            error_code='CLEANUP_FAILED',error_message=?,details_json='{}',finished_at=?
          WHERE run_id=? AND lease_owner=? AND status='RUNNING'
          """,safe(failure.getMessage()),Timestamp.from(clock.instant()),runId,owner);
    });
  }
  public RunResult find(String runId){return jdbc.query("""
      SELECT run_id,status,scanned_count,affected_count,error_code,error_message,started_at,finished_at FROM edu_maintenance_run WHERE run_id=?
      """,rs->rs.next()?new RunResult(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getTimestamp(7).toInstant(),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant()):null,runId);}
  private RunResult replay(RunResult initial){
    RunResult current=initial;
    for(int attempt=0;attempt<100&&"RUNNING".equals(current.status());attempt++){
      try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return current;}
      RunResult refreshed=find(current.runId());if(refreshed!=null)current=refreshed;
    }
    return current;
  }
  public String owner(){return owner;}
  private String safe(String value){if(value==null)return "清理执行失败";String clean=value.replaceAll("[\\r\\n\\t]"," ");return clean.substring(0,Math.min(1000,clean.length()));}
  private static String host(){try{return InetAddress.getLocalHost().getHostName();}catch(Exception ignored){return "instance";}}
  private record Claim(boolean inserted,RunResult result){}
  public record RunResult(String runId,String status,long scannedCount,long affectedCount,String errorCode,String errorMessage,Instant startedAt,Instant finishedAt){}
}
