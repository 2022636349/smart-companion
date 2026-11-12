package com.education.agent.submission.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Single-instance linearization boundary for long-running assessment activity.
 *
 * <p>Database row locks remain the authority for short mutations. A public sandbox run cannot keep
 * a database transaction open while it calls the AI service, so it holds an assessment read lock
 * instead. Closing the same assessment takes the write lock until the authoritative CLOSED state
 * and exam drain are complete. Entries are reference-counted and removed once the last holder or
 * waiter leaves, so the registry does not grow with historical assessments.
 */
@Component
public class AssessmentActivityGuard {
  private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

  public <T> T withActivity(Long assessmentId, Supplier<T> action) {
    Entry entry = retain(assessmentId);
    var lock = entry.lock.readLock();
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
      release(assessmentId, entry);
    }
  }

  public void withClose(Long assessmentId, Runnable action) {
    Entry entry = retain(assessmentId);
    var lock = entry.lock.writeLock();
    lock.lock();
    try {
      action.run();
    } finally {
      lock.unlock();
      release(assessmentId, entry);
    }
  }

  private Entry retain(Long assessmentId) {
    if (assessmentId == null) throw new IllegalArgumentException("assessmentId is required");
    return entries.compute(
        assessmentId,
        (ignored, current) -> {
          Entry selected = current == null ? new Entry() : current;
          selected.references.incrementAndGet();
          return selected;
        });
  }

  private void release(Long assessmentId, Entry expected) {
    entries.computeIfPresent(
        assessmentId,
        (ignored, current) -> {
          if (current != expected) return current;
          return current.references.decrementAndGet() == 0 ? null : current;
        });
  }

  int retainedAssessmentCount() {
    return entries.size();
  }

  private static final class Entry {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicInteger references = new AtomicInteger();
  }
}
