package com.education.agent.ratelimit;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;

public class RateLimitException extends BusinessException {
  private final long retryAfterSeconds;
  public RateLimitException(long retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }
  public long retryAfterSeconds() { return retryAfterSeconds; }
}
