package com.consilens.core.algorithm;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * P1-5 的可观测兜底：两侧同名列一边是按"日期"归一化、另一边是按"日期+时间"归一化时，
 * 每一行都会因为时分秒不同而被判成 MISMATCH（或反过来把真实时点差异抹平），这不是数据差异，
 * 而是归一化口径差异。典型来源：Oracle {@code DATE} 实际含时分秒，而部分消费方按 date-only
 * 渲染（见 {@code OracleDataTypeHandler} 的既有约定）。
 *
 * <p>这里只做显式告警，不自动改口径：改哪一侧都等于替用户决定"时间算不算差异"。
 */
@Slf4j
final class TemporalShapeCompatibility {

    private static final Set<DataType> DATE_ONLY = Set.of(DataType.DATE);
    private static final Set<DataType> WITH_TIME = Set.of(
            DataType.DATETIME,
            DataType.TIMESTAMP,
            DataType.TIMESTAMP_WITH_TIMEZONE);

    private TemporalShapeCompatibility() {
    }

    static void warnIfRisky(TableSegment left, TableSegment right) {
        describeIfRisky(left, right).ifPresent(message -> log.warn(message));
    }

    /**
     * Same detection as {@link #warnIfRisky(TableSegment, TableSegment)} but returns the
     * warning text so callers can also expose it in a structured schema report.
     */
    static Optional<String> describeIfRisky(TableSegment left, TableSegment right) {
        List<String> mismatches = mismatchedTemporalColumns(left, right);
        if (mismatches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                "Temporal shape differs across sides: %s. One side is normalized as date-only "
                        + "(YYYY-MM-DD) while the other keeps time-of-day (YYYY-MM-DD HH:MI:SS), so rows with a "
                        + "non-midnight time will be reported as MISMATCH (or time differences will be truncated). "
                        + "Oracle DATE columns always carry time-of-day; use the same logical type on both sides "
                        + "(or configure the date format) before trusting the result.", mismatches));
    }

    /** @return {@code column(leftType↔rightType)}；只统计两侧同名、且都参与比较的列。 */
    static List<String> mismatchedTemporalColumns(TableSegment left, TableSegment right) {
        if (left == null || right == null
                || left.getSchema() == null || left.getSchema().isEmpty()
                || right.getSchema() == null || right.getSchema().isEmpty()) {
            return List.of();
        }
        List<String> leftColumns = comparedColumns(left);
        List<String> rightColumns = comparedColumns(right);
        List<String> mismatches = new ArrayList<>();
        for (String column : leftColumns) {
            if (!rightColumns.contains(column)) {
                continue;
            }
            Optional<ColumnInfo> leftInfo = left.getSchema().get().getColumn(column);
            Optional<ColumnInfo> rightInfo = right.getSchema().get().getColumn(column);
            if (leftInfo.isEmpty() || rightInfo.isEmpty()) {
                continue;
            }
            DataType leftType = leftInfo.get().getType();
            DataType rightType = rightInfo.get().getType();
            boolean dateOnlyVsDateTime = (DATE_ONLY.contains(leftType) && WITH_TIME.contains(rightType))
                    || (WITH_TIME.contains(leftType) && DATE_ONLY.contains(rightType));
            if (dateOnlyVsDateTime) {
                mismatches.add(column + "(" + leftType + "↔" + rightType + ")");
            }
        }
        return mismatches;
    }

    private static List<String> comparedColumns(TableSegment table) {
        List<String> columns = new ArrayList<>(table.getKeyColumns());
        columns.addAll(table.getExtraColumns());
        return columns;
    }
}
