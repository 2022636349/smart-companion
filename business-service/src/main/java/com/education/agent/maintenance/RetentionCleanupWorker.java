package com.education.agent.maintenance;

import java.time.Instant;
import java.util.Map;

public interface RetentionCleanupWorker {
  Map<String,Integer> cleanup(Instant now, LeaseFence fence);

  @FunctionalInterface
  interface LeaseFence { void verify(); }
}
