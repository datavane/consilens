package com.consilens.core.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDiffSinkLimitTest {

    @Test
    void rejectsRowsBeyondTheLimit() {
        InMemoryDiffSink sink = new InMemoryDiffSink();
        sink.setMaxDifferences(2);
        sink.onDiffRow(row("a"));
        sink.onDiffRow(row("b"));

        assertThrows(IllegalStateException.class, () -> sink.onDiffRow(row("c")));
        assertEquals(2, sink.size());
    }

    @Test
    void limitHoldsUnderConcurrentProducers() throws Exception {
        // 旧实现是"先查 size() 再逐条加"：多个分段并发时检查会一起通过，条数可以明显超限
        InMemoryDiffSink sink = new InMemoryDiffSink();
        sink.setMaxDifferences(150);

        int producers = 4;
        int each = 100;
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int producer = 0; producer < producers; producer++) {
            int id = producer;
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < each; i++) {
                    try {
                        sink.onDiffRow(row(id + "-" + i));
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    }
                }
            });
        }
        tasks.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 恰好 150 条：既不能超，也不能因为并发互相踩踏而少收
        assertEquals(150, sink.size());
        assertTrue(rejected.get() > 0, "超出上限的行必须被拒绝");
    }

    @Test
    void nonPositiveLimitMeansUnlimited() {
        InMemoryDiffSink sink = new InMemoryDiffSink();
        sink.setMaxDifferences(0);
        for (int i = 0; i < 1000; i++) {
            sink.onDiffRow(row("k" + i));
        }
        assertEquals(1000, sink.size());
    }

    private static DiffRow row(String key) {
        return DiffRow.builder()
                .operation(DiffOperation.MISMATCH)
                .primaryKey(List.of(key))
                .build();
    }
}
