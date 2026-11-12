package com.education.agent.ratelimit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class DatabaseRateLimiter {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired public DatabaseRateLimiter(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
  public DatabaseRateLimiter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

  public Decision acquire(String bucketKey, int limit, long windowSeconds) {
    Objects.requireNonNull(bucketKey);
    if (limit < 1 || windowSeconds < 1) throw new IllegalArgumentException("invalid rate limit");
    Instant now = clock.instant();
    long startEpoch = Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds;
    Instant started = Instant.ofEpochSecond(startEpoch);
    Instant expires = started.plusSeconds(windowSeconds);
    Timestamp startTimestamp = Timestamp.from(started);
    int updated = jdbc.update("""
        UPDATE edu_rate_limit_bucket SET request_count=request_count+1,expires_at=?
        WHERE bucket_key=? AND window_started_at=? AND request_count<?
        """, Timestamp.from(expires), bucketKey, startTimestamp, limit);
    if (updated == 1) return new Decision(true, remaining(bucketKey,startTimestamp,limit), secondsUntil(expires, now));
    try {
      jdbc.update("""
          INSERT INTO edu_rate_limit_bucket(bucket_key,window_started_at,request_count,expires_at,updated_at)
          VALUES(?,?,1,?,CURRENT_TIMESTAMP(3))
          """, bucketKey, startTimestamp, Timestamp.from(expires));
      return new Decision(true, remaining(bucketKey,startTimestamp,limit), secondsUntil(expires, now));
    } catch (DataIntegrityViolationException duplicate) {
      updated = jdbc.update("""
          UPDATE edu_rate_limit_bucket SET request_count=request_count+1,expires_at=?
          WHERE bucket_key=? AND window_started_at=? AND request_count<?
          """, Timestamp.from(expires), bucketKey, startTimestamp, limit);
      if (updated == 1) return new Decision(true, remaining(bucketKey,startTimestamp,limit), secondsUntil(expires, now));
      return new Decision(false, 0, secondsUntil(expires, now));
    }
  }

  private long secondsUntil(Instant expires, Instant now) {
    long seconds = expires.getEpochSecond() - now.getEpochSecond();
    return Math.max(1, seconds);
  }

  private int remaining(String bucketKey,Timestamp started,int limit){
    Integer count=jdbc.queryForObject("SELECT request_count FROM edu_rate_limit_bucket WHERE bucket_key=? AND window_started_at=?",Integer.class,bucketKey,started);
    return Math.max(0,limit-(count==null?limit:count));
  }

  public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {}
}
