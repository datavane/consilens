package com.consilens.core.diff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-column difference statistics for one comparison run.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code insertCount}: rows only present on the target side (operation SOURCE_MISSING)</li>
 *   <li>{@code updateCount}: MISMATCH rows whose value for this column differs</li>
 *   <li>{@code deleteCount}: rows only present on the source side (operation TARGET_MISSING)</li>
 *   <li>{@code differenceCount}: insert + update + delete</li>
 *   <li>{@code differencePercentage}: updateCount / mismatchRows * 100 (kept for the existing UI)</li>
 *   <li>{@code differenceRate}: differenceCount / totalRows * 100 (new, all operations),
 *       where {@code totalRows} is the <strong>sum of both sides' row counts</strong> — a single
 *       changed row in a 3-row table therefore reads 16.67%, not 33%</li>
 * </ul>
 *
 * <p>{@code aggregatedFromSamples=false} means the counts were accumulated from the
 * complete run result rather than from a bounded sample. When {@code maxDifferences}
 * truncates the result, {@code truncated=true} and the counts cover the retained rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnStats {

    /** Logical column name (source name when available). */
    private String name;

    private String sourceName;

    private String targetName;

    private long insertCount;

    private long updateCount;

    private long deleteCount;

    private long differenceCount;

    private double differencePercentage;

    private double differenceRate;

    private boolean aggregatedFromSamples;

    private boolean truncated;
}
