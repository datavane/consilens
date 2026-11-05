package com.consilens.cli.model.normalization;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * 容差内差异的处置策略（{@code normalization.tolerance}）。
 *
 * <p>只影响"精确比较不等、但落在 tolerance/relativeTolerance 内"的行：
 * <ul>
 *   <li>{@code ignore}（默认）：判等，不计入 totalDifferences，报告里单独列出；</li>
 *   <li>{@code report}：仍然算差异（旧口径），用于核对容差是否过宽。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TolerancePolicyConfig {

    /** 容差内行是否计入差异：ignore（默认）| report。 */
    @JsonProperty("withinTolerance")
    private String withinTolerance;

    /** 报告里最多列出多少条容差内样本；0 表示只给计数、不列行。 */
    @JsonProperty("reportSamples")
    private Integer reportSamples;

    public static final String IGNORE = "ignore";
    public static final String REPORT = "report";
    public static final int DEFAULT_REPORT_SAMPLES = 100;
    /** 样本上限硬顶：样本是要进内存和报告的行，不设上限等于把"配错一个大数字"变成 OOM。 */
    public static final int MAX_REPORT_SAMPLES = 10_000;

    /**
     * 校验并归一化：拼错的口径值必须在加载配置时就失败，
     * 否则会静默按默认口径跑，用户以为"报了差异"实际没有。
     */
    public void validate() {
        normalizedMode();
        if (reportSamples != null && (reportSamples < 0 || reportSamples > MAX_REPORT_SAMPLES)) {
            throw new IllegalArgumentException("Invalid normalization.tolerance.reportSamples: " + reportSamples
                    + " (must be between 0 and " + MAX_REPORT_SAMPLES + ")");
        }
    }

    /** 归一化后的口径值（小写）；未配置时返回 ignore。 */
    public String normalizedMode() {
        if (withinTolerance == null || withinTolerance.isBlank()) {
            return IGNORE;
        }
        String normalized = withinTolerance.trim().toLowerCase(Locale.ROOT);
        if (!IGNORE.equals(normalized) && !REPORT.equals(normalized)) {
            throw new IllegalArgumentException("Invalid normalization.tolerance.withinTolerance: "
                    + withinTolerance + " (expected ignore|report)");
        }
        return normalized;
    }

    /** 生效的样本上限；未配置时用默认值。 */
    public int effectiveReportSamples() {
        return reportSamples != null ? reportSamples : DEFAULT_REPORT_SAMPLES;
    }
}
