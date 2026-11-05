package com.consilens.core.thread;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides IO/CPU executor pools with unified configuration.
 */
@Getter
@Slf4j
public class ExecutorProvider {

    private final ExecutorService ioExecutor;
    private final ExecutorService cpuExecutor;
    private final boolean managesExecutors;

    public ExecutorProvider(ConcurrencyConfig config) {
        ConcurrencyConfig effective = config != null ? config : ConcurrencyConfig.defaultConfig();
        this.ioExecutor = createExecutor(effective.getIo(), "io");
        this.cpuExecutor = createExecutor(effective.getCpu(), "cpu");
        this.managesExecutors = true;
    }

    public ExecutorProvider(ExecutorService ioExecutor, ExecutorService cpuExecutor) {
        this(ioExecutor, cpuExecutor, false);
    }

    public ExecutorProvider(ExecutorService ioExecutor, ExecutorService cpuExecutor, boolean managesExecutors) {
        this.ioExecutor = ioExecutor;
        this.cpuExecutor = cpuExecutor;
        this.managesExecutors = managesExecutors;
    }

    public void shutdown() {
        if (!managesExecutors) {
            log.debug("Skipping executor shutdown because ExecutorProvider does not own the executors");
            return;
        }
        shutdownExecutor("io", ioExecutor);
        shutdownExecutor("cpu", cpuExecutor);
    }

    public void shutdownNow() {
        if (!managesExecutors) {
            log.debug("Skipping immediate executor shutdown because ExecutorProvider does not own the executors");
            return;
        }
        shutdownExecutorNow("io", ioExecutor);
        shutdownExecutorNow("cpu", cpuExecutor);
    }

    private ExecutorService createExecutor(ConcurrencyConfig.PoolConfig poolConfig, String name) {
        ConcurrencyConfig.PoolConfig effective = poolConfig != null
                ? poolConfig
                : ("io".equals(name) ? ConcurrencyConfig.PoolConfig.defaultIo(1)
                                     : ConcurrencyConfig.PoolConfig.defaultCpu(1));
        effective.validate(name);
        ThreadFactory factory = new NamedThreadFactory(
                effective.getThreadNamePrefix() != null ? effective.getThreadNamePrefix() : "consilens-" + name + "-"
        );
        return new ThreadPoolExecutor(
                effective.getCore(),
                effective.getMax(),
                effective.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, effective.getQueueSize())),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        ) {
            /**
             * 提交时捕获 MDC 并在执行线程上恢复：分片比对跑在池线程里，
             * 若不做传播，运行实例日志只能拿到调度线程的少数几行。
             */
            @Override
            public void execute(Runnable command) {
                Map<String, String> context = MDC.getCopyOfContextMap();
                if (context == null || context.isEmpty()) {
                    super.execute(command);
                    return;
                }
                super.execute(() -> {
                    Map<String, String> previous = MDC.getCopyOfContextMap();
                    MDC.setContextMap(context);
                    try {
                        command.run();
                    } finally {
                        if (previous == null || previous.isEmpty()) {
                            MDC.clear();
                        } else {
                            MDC.setContextMap(previous);
                        }
                    }
                });
            }
        };
    }

    private static final long SHUTDOWN_WAIT_SECONDS = 12L;

    private void shutdownExecutor(String name, ExecutorService executor) {
        if (executor == null) {
            return;
        }
        log.info("Shutting down {} executor", name);
        executor.shutdown();
        try {
            // 12s 而不是 60s：收尾时所有任务都已经结束（毫秒级退出），而失败路径上可能还有卡在
            // 长查询里的任务——等满 60 秒只会让整个比对迟迟不返回。超过 12 秒一律强制中断。
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate in time, forcing shutdown", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while shutting down {} executor", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownExecutorNow(String name, ExecutorService executor) {
        if (executor == null) {
            return;
        }
        log.info("Shutting down {} executor immediately", name);
        executor.shutdownNow();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        private NamedThreadFactory(String namePrefix) {
            SecurityManager s = System.getSecurityManager();
            this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
}
