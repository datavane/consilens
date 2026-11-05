package com.consilens.server.application.diff;

/**
 * Shared match-score / status derivation for diff summaries and diff reports.
 */
public final class DiffReportSupport {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_EXCELLENT = "EXCELLENT";
    public static final String STATUS_GOOD = "GOOD";
    public static final String STATUS_POOR = "POOR";

    private static final double EXCELLENT_THRESHOLD = 99.9;
    private static final double GOOD_THRESHOLD = 95.0;

    private DiffReportSupport() {
    }

    /**
     * matchScore = round(100 * (1 - differencePercentage / 100), 1)。
     * differencePercentage 与生成侧（ChecksumDiffer/JoinDiffer）一致为 0..100 语义。
     * 缺失百分比且零差异时视为 100.0；否则 null。
     */
    public static Double matchScoreOf(Double differencePercentage, long totalDifferenceCount) {
        if (differencePercentage != null) {
            double score = 100.0 * (1.0 - differencePercentage / 100.0);
            return round1(Math.max(0.0, Math.min(100.0, score)));
        }
        if (totalDifferenceCount == 0) {
            return 100.0;
        }
        return null;
    }

    /**
     * 回退百分比（与生成侧口径一致，0..100）：totalDifferences / max(sourceRowCount, targetRowCount) * 100。
     */
    public static Double percentageFromCounts(long totalDifferenceCount,
                                              long sourceRowCount,
                                              long targetRowCount) {
        long totalRows = sourceRowCount + targetRowCount;
        if (totalRows <= 0) {
            return 0.0;
        }
        double percentage = (double) totalDifferenceCount / totalRows * 100.0;
        return Math.max(0.0, Math.min(100.0, percentage));
    }

    /**
     * EXCELLENT when no differences or score >= 99.9; GOOD when >= 95; POOR otherwise.
     * A null score yields NONE.
     */
    public static String statusOf(Double matchScore, long totalDifferenceCount) {
        if (matchScore == null) {
            return STATUS_NONE;
        }
        if (totalDifferenceCount == 0 || matchScore >= EXCELLENT_THRESHOLD) {
            return STATUS_EXCELLENT;
        }
        if (matchScore >= GOOD_THRESHOLD) {
            return STATUS_GOOD;
        }
        return STATUS_POOR;
    }

    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
