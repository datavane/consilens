package com.consilens.sdk;

/**
 * Per-run overrides. Fields not set fall back to {@code CompareRequest.executionOptions}.
 *
 * <p>All budgets are optional and positive when set:
 * <ul>
 *   <li>{@code maxDifferences} — stop after this many differences;</li>
 *   <li>{@code maxDurationMs} — wall-clock budget checked at segment/row boundaries;</li>
 *   <li>{@code maxRowsScanned} — cumulative scanned-row budget;</li>
 *   <li>{@code maxBytesFetched} — cumulative fetched-byte budget (JDBC paths).</li>
 * </ul>
 */
public final class RunOptions {

    private static final RunOptions DEFAULTS = builder().build();

    private final Long maxDifferences;
    private final Long maxDurationMs;
    private final Long maxRowsScanned;
    private final Long maxBytesFetched;
    private final RunListener listener;

    private RunOptions(Builder builder) {
        this.maxDifferences = builder.maxDifferences;
        this.maxDurationMs = builder.maxDurationMs;
        this.maxRowsScanned = builder.maxRowsScanned;
        this.maxBytesFetched = builder.maxBytesFetched;
        this.listener = builder.listener;
    }

    public static RunOptions defaults() {
        return DEFAULTS;
    }

    public static RunOptions maxDifferences(long value) {
        return builder().maxDifferences(value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getMaxDifferences() {
        return maxDifferences;
    }

    public Long getMaxDurationMs() {
        return maxDurationMs;
    }

    public Long getMaxRowsScanned() {
        return maxRowsScanned;
    }

    public Long getMaxBytesFetched() {
        return maxBytesFetched;
    }

    public RunListener getListener() {
        return listener;
    }

    public static final class Builder {

        private Long maxDifferences;
        private Long maxDurationMs;
        private Long maxRowsScanned;
        private Long maxBytesFetched;
        private RunListener listener;

        private Builder() {
        }

        public Builder maxDifferences(long value) {
            this.maxDifferences = positive("maxDifferences", value);
            return this;
        }

        public Builder maxDurationMs(long value) {
            this.maxDurationMs = positive("maxDurationMs", value);
            return this;
        }

        public Builder maxRowsScanned(long value) {
            this.maxRowsScanned = positive("maxRowsScanned", value);
            return this;
        }

        public Builder maxBytesFetched(long value) {
            this.maxBytesFetched = positive("maxBytesFetched", value);
            return this;
        }

        /**
         * Coarse-grained lifecycle callbacks; listener exceptions are ignored.
         */
        public Builder listener(RunListener value) {
            this.listener = value;
            return this;
        }

        public RunOptions build() {
            return new RunOptions(this);
        }

        private static long positive(String name, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
