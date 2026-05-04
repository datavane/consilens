package com.consilens.cli.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCheckpointStoreTest {

    @Test
    void shouldRespectActiveLeaseAndPreserveWatermarkWhenAcquiring() throws Exception {
        String tableName = "checkpoint_" + System.nanoTime();
        try (JdbcCheckpointStore store = new JdbcCheckpointStore(
                "jdbc:h2:mem:" + tableName + ";DB_CLOSE_DELAY=-1",
                new Properties(),
                tableName,
                "org.h2.Driver")) {
            Instant watermark = Instant.now().minusSeconds(120);
            Instant start = watermark.minusSeconds(60);
            Instant end = watermark;
            store.markSucceeded("task-1", watermark, start, end);

            assertTrue(store.tryMarkRunning(
                    "task-1",
                    Instant.now().minusSeconds(30),
                    Instant.now(),
                    "owner-1",
                    Instant.now().plusSeconds(60)));
            assertFalse(store.tryMarkRunning(
                    "task-1",
                    Instant.now().minusSeconds(30),
                    Instant.now(),
                    "owner-2",
                    Instant.now().plusSeconds(60)));

            CompareCheckpoint running = store.load("task-1").orElseThrow();
            assertEquals(watermark, running.getWatermark());
            assertEquals("owner-1", running.getOwner());
        }
    }
}
