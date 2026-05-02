package com.consilens.core.thread;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Concurrency configuration for IO/CPU executor pools.
 */
@Data
@NoArgsConstructor
public class ConcurrencyConfig {

    private PoolConfig io;
    private PoolConfig cpu;

    public ConcurrencyConfig(PoolConfig io, PoolConfig cpu) {
        this.io = io;
        this.cpu = cpu;
        validate();
    }

    public static ConcurrencyConfig defaultConfig() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new ConcurrencyConfig(
                PoolConfig.defaultIo(cores),
                PoolConfig.defaultCpu(cores)
        );
    }

    public void validate() {
        if (io != null) {
            io.validate("io");
        }
        if (cpu != null) {
            cpu.validate("cpu");
        }
    }

    @Data
    @NoArgsConstructor
    public static class PoolConfig {
        private int core;
        private int max;
        private int queueSize;
        private long keepAliveSeconds;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String threadNamePrefix;

        public PoolConfig(int core, int max, int queueSize, long keepAliveSeconds, String threadNamePrefix) {
            this.core = core;
            this.max = max;
            this.queueSize = queueSize;
            this.keepAliveSeconds = keepAliveSeconds;
            this.threadNamePrefix = threadNamePrefix;
            validate("pool");
        }

        public static PoolConfig defaultIo(int cores) {
            int core = Math.max(8, cores * 2);
            int max = Math.max(32, cores * 8);
            return new PoolConfig(core, max, 10000, 60L, "consilens-io-");
        }

        public static PoolConfig defaultCpu(int cores) {
            int core = Math.max(4, cores);
            int max = Math.max(core, cores * 2);
            return new PoolConfig(core, max, 10000, 60L, "consilens-cpu-");
        }

        public void validate(String poolName) {
            if (core <= 0) {
                throw new IllegalArgumentException(poolName + ".core 必须大于 0");
            }
            if (max <= 0) {
                throw new IllegalArgumentException(poolName + ".max 必须大于 0");
            }
            if (core > max) {
                throw new IllegalArgumentException(poolName + ".core 不能大于 max");
            }
            if (queueSize < 0) {
                throw new IllegalArgumentException(poolName + ".queueSize 不能小于 0");
            }
            if (keepAliveSeconds < 0) {
                throw new IllegalArgumentException(poolName + ".keepAliveSeconds 不能小于 0");
            }
        }
    }
}
