package com.consilens.core.algorithm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentPermitControllerTest {

    @Test
    void shouldQueueWhenBudgetIsExhausted() {
        SegmentPermitController controller = new SegmentPermitController(1);

        SegmentPermitController.PermitLease firstLease = controller.acquire("segment-1").join();
        CompletableFuture<SegmentPermitController.PermitLease> queued = controller.acquire("segment-2");

        assertFalse(queued.isDone());
        assertEquals(0, controller.availablePermits());
        assertEquals(1, controller.queueDepth());

        firstLease.release();

        assertTrue(queued.isDone());
        assertEquals(0, controller.availablePermits());
        assertEquals(0, controller.queueDepth());

        queued.join().release();
        assertEquals(1, controller.availablePermits());
    }

    @Test
    void shouldGrantQueuedRequestsInFifoOrder() {
        SegmentPermitController controller = new SegmentPermitController(1);

        SegmentPermitController.PermitLease firstLease = controller.acquire("segment-1").join();
        CompletableFuture<SegmentPermitController.PermitLease> second = controller.acquire("segment-2");
        CompletableFuture<SegmentPermitController.PermitLease> third = controller.acquire("segment-3");

        firstLease.release();

        assertTrue(second.isDone());
        assertFalse(third.isDone());

        SegmentPermitController.PermitLease secondLease = second.join();
        secondLease.release();

        assertTrue(third.isDone());

        third.join().release();
        assertEquals(1, controller.availablePermits());
        assertEquals(0, controller.queueDepth());
    }
}
