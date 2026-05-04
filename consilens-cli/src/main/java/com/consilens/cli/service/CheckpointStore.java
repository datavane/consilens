package com.consilens.cli.service;

import java.time.Instant;
import java.util.Optional;

public interface CheckpointStore extends AutoCloseable {

    Optional<CompareCheckpoint> load(String taskId) throws Exception;

    default boolean tryMarkRunning(String taskId, Instant start, Instant end, String owner, Instant leaseUntil) throws Exception {
        markRunning(taskId, start, end);
        return true;
    }

    void markRunning(String taskId, Instant start, Instant end) throws Exception;

    void markSucceeded(String taskId, Instant watermark, Instant start, Instant end) throws Exception;

    void markFailed(String taskId, Instant start, Instant end, Throwable error) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
