package com.education.agent.submission.service;

/** Optional test hook; production uses the no-op implementation. */
@FunctionalInterface
public interface GradingFailureHook {
  void afterClaim(Long submissionId);
}
