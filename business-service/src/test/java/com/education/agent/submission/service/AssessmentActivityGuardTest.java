package com.education.agent.submission.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AssessmentActivityGuardTest {
  @Test
  void closeWaitsForSameAssessmentButDifferentAssessmentDoesNotBlockAndEntriesAreRemoved()
      throws Exception {
    AssessmentActivityGuard guard = new AssessmentActivityGuard();
    CountDownLatch activityEntered = new CountDownLatch(1);
    CountDownLatch finishActivity = new CountDownLatch(1);

    CompletableFuture<Void> activity =
        CompletableFuture.runAsync(
            () ->
                guard.withActivity(
                    10L,
                    () -> {
                      activityEntered.countDown();
                      await(finishActivity);
                      return null;
                    }));
    assertThat(activityEntered.await(2, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Void> sameAssessmentClose =
        CompletableFuture.runAsync(() -> guard.withClose(10L, () -> {}));
    CompletableFuture<Void> otherAssessmentClose =
        CompletableFuture.runAsync(() -> guard.withClose(11L, () -> {}));

    otherAssessmentClose.get(2, TimeUnit.SECONDS);
    assertThat(sameAssessmentClose.isDone()).isFalse();
    finishActivity.countDown();
    activity.get(2, TimeUnit.SECONDS);
    sameAssessmentClose.get(2, TimeUnit.SECONDS);
    assertThat(guard.retainedAssessmentCount()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
