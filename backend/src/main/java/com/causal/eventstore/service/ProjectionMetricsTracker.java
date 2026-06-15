package com.causal.eventstore.service;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ProjectionMetricsTracker {

    private static final long FIVE_MINUTES_MS = 5 * 60 * 1000;
    private static final long ONE_HOUR_MS = 60 * 60 * 1000;

    private final ConcurrentHashMap<String, Deque<ProcessingSample>> samples = new ConcurrentHashMap<>();

    public void recordProcessing(String projectionId, long latencyMs, boolean success) {
        Deque<ProcessingSample> deque = samples.computeIfAbsent(projectionId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new ProcessingSample(Instant.now(), latencyMs, success));
        pruneOldSamples(deque);
    }

    public MetricsSnapshot computeMetrics(String projectionId) {
        Deque<ProcessingSample> deque = samples.get(projectionId);
        if (deque == null || deque.isEmpty()) {
            return new MetricsSnapshot(null, null, null);
        }

        pruneOldSamples(deque);

        Instant now = Instant.now();
        Instant fiveMinAgo = now.minusMillis(FIVE_MINUTES_MS);
        Instant oneHourAgo = now.minusMillis(ONE_HOUR_MS);

        long fiveMinCount = 0;
        double fiveMinLatencySum = 0;
        long oneHourTotal = 0;
        long oneHourFailed = 0;

        for (ProcessingSample sample : deque) {
            if (!sample.timestamp.isBefore(fiveMinAgo)) {
                fiveMinCount++;
                fiveMinLatencySum += sample.latencyMs;
            }
            if (!sample.timestamp.isBefore(oneHourAgo)) {
                oneHourTotal++;
                if (!sample.success) oneHourFailed++;
            }
        }

        Double avgLatencyMs = fiveMinCount > 0 ? fiveMinLatencySum / fiveMinCount : null;
        Double throughputPerMin = fiveMinCount > 0 ? fiveMinCount / 5.0 : null;
        Double errorRate = oneHourTotal > 0 ? (double) oneHourFailed / oneHourTotal : null;

        return new MetricsSnapshot(avgLatencyMs, throughputPerMin, errorRate);
    }

    private void pruneOldSamples(Deque<ProcessingSample> deque) {
        Instant cutoff = Instant.now().minusMillis(ONE_HOUR_MS);
        while (!deque.isEmpty() && deque.peekFirst().timestamp.isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    public static class ProcessingSample {
        public final Instant timestamp;
        public final long latencyMs;
        public final boolean success;

        public ProcessingSample(Instant timestamp, long latencyMs, boolean success) {
            this.timestamp = timestamp;
            this.latencyMs = latencyMs;
            this.success = success;
        }
    }

    public static class MetricsSnapshot {
        public final Double avgLatencyMs;
        public final Double throughputPerMin;
        public final Double errorRate;

        public MetricsSnapshot(Double avgLatencyMs, Double throughputPerMin, Double errorRate) {
            this.avgLatencyMs = avgLatencyMs;
            this.throughputPerMin = throughputPerMin;
            this.errorRate = errorRate;
        }
    }
}
