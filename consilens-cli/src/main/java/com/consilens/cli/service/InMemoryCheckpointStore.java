package com.consilens.cli.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, CompareCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Optional<CompareCheckpoint> load(String taskId) {
        return Optional.ofNullable(checkpoints.get(taskId));
    }

    @Override
    public boolean tryMarkRunning(String taskId, Instant start, Instant end, String owner, Instant leaseUntil) {
        CompareCheckpoint existing = checkpoints.get(taskId);
        if (existing != null
                && "running".equalsIgnoreCase(existing.getStatus())
                && existing.getLeaseUntil() != null
                && existing.getLeaseUntil().isAfter(Instant.now())) {
            return false;
        }
        markRunning(taskId, start, end, owner, leaseUntil);
        return true;
    }

    @Override
    public void markRunning(String taskId, Instant start, Instant end) {
        markRunning(taskId, start, end, null, null);
    }

    private void markRunning(String taskId, Instant start, Instant end, String owner, Instant leaseUntil) {
        checkpoints.put(taskId, CompareCheckpoint.builder()
                .taskId(taskId)
                .watermark(checkpoints.containsKey(taskId) ? checkpoints.get(taskId).getWatermark() : null)
                .lastStart(start)
                .lastEnd(end)
                .status("running")
                .owner(owner)
                .leaseUntil(leaseUntil)
                .build());
    }

    @Override
    public void markSucceeded(String taskId, Instant watermark, Instant start, Instant end) {
        checkpoints.put(taskId, CompareCheckpoint.builder()
                .taskId(taskId)
                .watermark(watermark)
                .lastStart(start)
                .lastEnd(end)
                .status("succeeded")
                .build());
    }

    @Override
    public void markFailed(String taskId, Instant start, Instant end, Throwable error) {
        checkpoints.put(taskId, CompareCheckpoint.builder()
                .taskId(taskId)
                .watermark(checkpoints.containsKey(taskId) ? checkpoints.get(taskId).getWatermark() : null)
                .lastStart(start)
                .lastEnd(end)
                .status("failed")
                .build());
    }
}
