package com.education.agent.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DatabaseRetentionCleanupWorkerTest {
  @Test
  void rejectsZeroNegativeAndAboveMaximumBeforeAnySqlCanRun(){
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    assertInvalid(jdbc,0,365,365,30,"audit-retention-days");
    assertInvalid(jdbc,365,-1,365,30,"telemetry-retention-days");
    assertInvalid(jdbc,365,365,DatabaseRetentionCleanupWorker.MAX_RETENTION_DAYS+1,30,"notification-retention-days");
    assertInvalid(jdbc,365,365,365,0,"terminal-retention-days");
    verifyNoInteractions(jdbc);
  }

  @Test
  void acceptsInclusiveRetentionBoundariesWithoutExecutingCleanup(){
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    assertThatCode(()->new DatabaseRetentionCleanupWorker(jdbc,
        DatabaseRetentionCleanupWorker.MIN_RETENTION_DAYS,
        DatabaseRetentionCleanupWorker.MAX_RETENTION_DAYS,
        DatabaseRetentionCleanupWorker.MIN_RETENTION_DAYS,
        DatabaseRetentionCleanupWorker.MAX_RETENTION_DAYS)).doesNotThrowAnyException();
    verifyNoInteractions(jdbc);
  }

  private void assertInvalid(JdbcTemplate jdbc,int audit,int telemetry,int notification,int terminal,String property){
    assertThatThrownBy(()->new DatabaseRetentionCleanupWorker(jdbc,audit,telemetry,notification,terminal))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("education.maintenance."+property)
        .hasMessageContaining("between 1 and 36500 days");
  }
}
