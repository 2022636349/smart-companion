package com.education.agent.maintenance;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;

@Service
public class MaintenanceLeaseService {
  private final JdbcTemplate jdbc;
  public MaintenanceLeaseService(JdbcTemplate jdbc){this.jdbc=jdbc;}

  public boolean tryAcquire(String job,String owner,Instant now,Instant until){
    int updated=jdbc.update("""
        UPDATE edu_maintenance_lease SET lease_owner=?,lease_until=?,heartbeat_at=?,updated_at=CURRENT_TIMESTAMP(3)
        WHERE job_name=? AND (lease_until IS NULL OR lease_until<? OR lease_owner=?)
        """,owner,Timestamp.from(until),Timestamp.from(now),job,Timestamp.from(now),owner);
    if(updated==1)return true;
    try{
      jdbc.update("""
          INSERT INTO edu_maintenance_lease(job_name,lease_owner,lease_until,heartbeat_at,updated_at)
          VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))
          """,job,owner,Timestamp.from(until),Timestamp.from(now));
      return true;
    }catch(DataIntegrityViolationException duplicate){return false;}
  }

  public boolean heartbeat(String job,String owner,Instant now,Instant until){
    return jdbc.update("""
        UPDATE edu_maintenance_lease SET lease_until=?,heartbeat_at=?,updated_at=CURRENT_TIMESTAMP(3)
        WHERE job_name=? AND lease_owner=? AND lease_until>=?
        """,Timestamp.from(until),Timestamp.from(now),job,owner,Timestamp.from(now))==1;
  }
  public void lockAndVerify(String job,String owner){
    LeaseState state=jdbc.query("""
        SELECT lease_owner,lease_until,CURRENT_TIMESTAMP(3) db_now
        FROM edu_maintenance_lease WHERE job_name=? FOR UPDATE
        """,rs->rs.next()?new LeaseState(rs.getString(1),rs.getTimestamp(2),rs.getTimestamp(3)):null,job);
    if(state==null||!owner.equals(state.owner())||state.until()==null||state.now()==null
        ||!state.until().after(state.now()))throw new LeaseLostException();
  }
  public void release(String job,String owner){jdbc.update("""
      UPDATE edu_maintenance_lease SET lease_owner=NULL,lease_until=NULL,heartbeat_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)
      WHERE job_name=? AND lease_owner=?
      """,job,owner);}
  private record LeaseState(String owner,Timestamp until,Timestamp now){}
  public static final class LeaseLostException extends BusinessException {
    public LeaseLostException(){super(ErrorCode.CONCURRENT_MODIFICATION,"维护任务租约已失效");}
  }
}
