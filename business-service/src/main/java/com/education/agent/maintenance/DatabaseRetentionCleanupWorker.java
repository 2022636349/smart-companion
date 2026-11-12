package com.education.agent.maintenance;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseRetentionCleanupWorker implements RetentionCleanupWorker {
  public static final int MIN_RETENTION_DAYS=1;
  public static final int MAX_RETENTION_DAYS=36_500;
  private final JdbcTemplate jdbc;
  private final int auditDays, telemetryDays, notificationDays, terminalDays;
  public DatabaseRetentionCleanupWorker(JdbcTemplate jdbc,
      @Value("${education.maintenance.audit-retention-days:365}") int auditDays,
      @Value("${education.maintenance.telemetry-retention-days:365}") int telemetryDays,
      @Value("${education.maintenance.notification-retention-days:365}") int notificationDays,
      @Value("${education.maintenance.terminal-retention-days:30}") int terminalDays){
    validateDays("audit-retention-days",auditDays);
    validateDays("telemetry-retention-days",telemetryDays);
    validateDays("notification-retention-days",notificationDays);
    validateDays("terminal-retention-days",terminalDays);
    this.jdbc=jdbc;this.auditDays=auditDays;this.telemetryDays=telemetryDays;
    this.notificationDays=notificationDays;this.terminalDays=terminalDays;
  }

  private static void validateDays(String property,int value){
    if(value<MIN_RETENTION_DAYS||value>MAX_RETENTION_DAYS)throw new IllegalArgumentException(
        "education.maintenance."+property+" must be between "+MIN_RETENTION_DAYS+" and "+MAX_RETENTION_DAYS+" days");
  }

  @Override @Transactional
  public Map<String,Integer> cleanup(Instant now,LeaseFence fence){
    Map<String,Integer> affected=new LinkedHashMap<>();
    Timestamp current=Timestamp.from(now);
    Timestamp terminal=Timestamp.from(now.minus(terminalDays,ChronoUnit.DAYS));
    fence.verify();
    affected.put("rateBuckets",jdbc.update("DELETE FROM edu_rate_limit_bucket WHERE expires_at<?",current));
    fence.verify();
    affected.put("notificationPublications",jdbc.update("DELETE FROM edu_notification_publication WHERE expires_at<?",current));
    fence.verify();
    affected.put("idempotencyRecords",jdbc.update("DELETE FROM edu_idempotency_record WHERE expires_at<? AND response_json IS NOT NULL",current));
    fence.verify();
    affected.put("revokedRefreshTokens",jdbc.update("DELETE FROM edu_refresh_token WHERE revoked_at IS NOT NULL AND expires_at<?",terminal));
    fence.verify();
    affected.put("readNotifications",jdbc.update("DELETE FROM edu_notification WHERE is_read=TRUE AND created_at<?",Timestamp.from(now.minus(notificationDays,ChronoUnit.DAYS))));
    fence.verify();
    affected.put("modelTelemetry",jdbc.update("DELETE FROM edu_model_call_log WHERE COALESCE(occurred_at,created_at)<?",Timestamp.from(now.minus(telemetryDays,ChronoUnit.DAYS))));
    fence.verify();
    affected.put("auditLogs",jdbc.update("DELETE FROM edu_audit_log WHERE created_at<?",Timestamp.from(now.minus(auditDays,ChronoUnit.DAYS))));
    fence.verify();
    affected.put("publishedOutbox",jdbc.update("DELETE FROM edu_outbox_event WHERE status='PUBLISHED' AND published_at<?",terminal));
    fence.verify();
    affected.put("succeededMemoryOutbox",jdbc.update("DELETE FROM edu_memory_outbox WHERE status='SUCCEEDED' AND updated_at<?",terminal));
    fence.verify();
    return Map.copyOf(affected);
  }
}
