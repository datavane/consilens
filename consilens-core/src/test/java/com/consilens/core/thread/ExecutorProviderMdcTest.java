package com.consilens.core.thread;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutorProviderMdcTest {

    @Test
    void shouldPropagateMdcToPoolThreadsAndRestoreAfterwards() throws Exception {
        ExecutorProvider provider = new ExecutorProvider(ConcurrencyConfig.defaultConfig());
        try {
            MDC.put("runInstanceId", "inst_1");
            String seen = provider.getIoExecutor()
                    .submit(() -> MDC.get("runInstanceId"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("inst_1", seen);

            // 提交线程清空 MDC 后，池线程不应残留上一个任务的上下文
            MDC.clear();
            String after = provider.getIoExecutor()
                    .submit(() -> MDC.get("runInstanceId"))
                    .get(5, TimeUnit.SECONDS);
            assertNull(after);
        } finally {
            MDC.clear();
            provider.shutdown();
        }
    }
}
