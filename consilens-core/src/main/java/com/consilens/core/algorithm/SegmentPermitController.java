package com.consilens.core.algorithm;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIFO permit controller for segment-level concurrency.
 *
 * <p>Unlike a plain tryAcquire-based gate, this controller queues requests when the
 * budget is exhausted and grants permits in arrival order as running segments finish.
 * This preserves the configured concurrency cap without forcing callers to degrade
 * to local comparison merely because a burst of sibling segments arrived together.
 */
@Slf4j
final class SegmentPermitController {

    private final int maxPermits;
    private int availablePermits;
    private final Deque<QueuedRequest> waitQueue = new ArrayDeque<>();

    SegmentPermitController(int maxPermits) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("Segment permit budget must be greater than 0");
        }
        this.maxPermits = maxPermits;
        this.availablePermits = maxPermits;
    }

    CompletableFuture<PermitLease> acquire(String requestId) {
        synchronized (this) {
            if (waitQueue.isEmpty() && availablePermits > 0) {
                availablePermits--;
                log.debug("Granted segment permit immediately for {} (availablePermits={}, queueDepth=0)",
                        requestId, availablePermits);
                return CompletableFuture.completedFuture(new PermitLease(this, requestId));
            }

            CompletableFuture<PermitLease> future = new CompletableFuture<>();
            waitQueue.addLast(new QueuedRequest(requestId, future));
            log.debug("Queued segment permit request for {} (availablePermits={}, queueDepth={})",
                    requestId, availablePermits, waitQueue.size());
            return future;
        }
    }

    private void release(String requestId) {
        List<GrantedRequest> grantsToComplete;
        int availableAfterRelease;
        int queueDepthAfterRelease;

        synchronized (this) {
            if (availablePermits >= maxPermits) {
                log.warn("Ignoring over-release of segment permit for {} (availablePermits={}, maxPermits={})",
                        requestId, availablePermits, maxPermits);
                return;
            }

            availablePermits++;
            grantsToComplete = drainQueueLocked();
            availableAfterRelease = availablePermits;
            queueDepthAfterRelease = waitQueue.size();
        }

        for (GrantedRequest grant : grantsToComplete) {
            grant.future.complete(new PermitLease(this, grant.requestId));
        }

        log.debug("Released segment permit for {} (availablePermits={}, queueDepth={})",
                requestId, availableAfterRelease, queueDepthAfterRelease);
    }

    private List<GrantedRequest> drainQueueLocked() {
        List<GrantedRequest> grants = new ArrayList<>();
        while (availablePermits > 0 && !waitQueue.isEmpty()) {
            QueuedRequest next = waitQueue.removeFirst();
            if (next.future.isDone()) {
                continue;
            }
            availablePermits--;
            grants.add(new GrantedRequest(next.requestId, next.future));
        }
        return grants;
    }

    int availablePermits() {
        synchronized (this) {
            return availablePermits;
        }
    }

    int queueDepth() {
        synchronized (this) {
            return waitQueue.size();
        }
    }

    static final class PermitLease implements AutoCloseable {

        private final SegmentPermitController controller;
        private final String requestId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private PermitLease(SegmentPermitController controller, String requestId) {
            this.controller = controller;
            this.requestId = requestId;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                controller.release(requestId);
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    private static final class QueuedRequest {
        private final String requestId;
        private final CompletableFuture<PermitLease> future;

        private QueuedRequest(String requestId, CompletableFuture<PermitLease> future) {
            this.requestId = requestId;
            this.future = future;
        }
    }

    private static final class GrantedRequest {
        private final String requestId;
        private final CompletableFuture<PermitLease> future;

        private GrantedRequest(String requestId, CompletableFuture<PermitLease> future) {
            this.requestId = requestId;
            this.future = future;
        }
    }
}
