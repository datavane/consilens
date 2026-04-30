package com.consilens.core.thread;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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

    public ExecutorProvider(ConcurrencyConfig config) {
        ConcurrencyConfig effective = config != null ? config : ConcurrencyConfig.defaultConfig();
        this.ioExecutor = createExecutor(effective.getIo(), "io");
        this.cpuExecutor = createExecutor(effective.getCpu(), "cpu");
    }

    public void shutdown() {
        shutdownExecutor("io", ioExecutor);
        shutdownExecutor("cpu", cpuExecutor);
    }

    public void shutdownNow() {
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
        );
    }

    private void shutdownExecutor(String name, ExecutorService executor) {
        if (executor == null) {
            return;
        }
        log.info("Shutting down {} executor", name);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
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
