package com.consilens.sink.api;

/**
 * Canonical sink type identifiers shared by {@link SinkManager} and all
 * {@link SinkProvider} implementations.
 */
public final class SinkTypes {

    /** Sink type for row-level diff records. */
    public static final String DIFF_RECORD = "diff-record";

    /** Sink type for the final diff result / run summary. */
    public static final String RESULT = "result";

    private SinkTypes() {
    }
}
