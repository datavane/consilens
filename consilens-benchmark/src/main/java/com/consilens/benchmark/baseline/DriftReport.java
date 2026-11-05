package com.consilens.benchmark.baseline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 漂移判定聚合报告。
 */
@Data
@Builder
public class DriftReport {

    /** 逐条漂移明细。 */
    @Builder.Default
    private List<DriftItem> items = new ArrayList<>();

    /** 漂移状态枚举。 */
    public enum DriftStatus {
        PASS,
        WARN,
        FAIL,
        NEW,
        SKIPPED
    }

    public List<DriftItem> getItems() {
        return items == null ? Collections.emptyList() : items;
    }

    /** 是否存在回归（FAIL）。 */
    public boolean hasRegression() {
        for (DriftItem item : getItems()) {
            if (item.getStatus() == DriftStatus.FAIL) {
                return true;
            }
        }
        return false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriftItem {
        /** 场景编号。 */
        private String key;
        /** 当前值。 */
        private double current;
        /** 基线值，无基线时为 0。 */
        private double baseline;
        /** 相对比值 current/baseline，无基线时为 NaN。 */
        private double ratio;
        /** 判定状态。 */
        private DriftStatus status;
        /** 说明文本。 */
        private String message;
    }
}
