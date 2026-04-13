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
@AllArgsConstructor
public class ConcurrencyConfig {

    private PoolConfig io;
    private PoolConfig cpu;

    public static ConcurrencyConfig defaultConfig() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new ConcurrencyConfig(
                PoolConfig.defaultIo(cores),
                PoolConfig.defaultCpu(cores)
        );
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolConfig {
        private int core;
        private int max;
        private int queueSize;
        private long keepAliveSeconds;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String threadNamePrefix;

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
    }
}
