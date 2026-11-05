package com.consilens.core.compare;

import java.util.Locale;

/**
 * 容差内（绝对差 ≤ tolerance，或相对差 ≤ relativeTolerance）行的处置口径。
 *
 * <p>只作用于终局本地比较：SQL 侧哈希无法表达容差（值不同哈希必不同），
 * 因此容差列一定会递归收敛到本地逐行比较，由这里决定"算不算差异"。
 */
public enum WithinToleranceMode {

    /**
     * 默认口径：判等。不计入 totalDifferences，也不进入差异 sink；
     * 但会以样本形式随结果返回，报告里单独列出，避免"容差把差异吃掉了却看不见"。
     */
    IGNORE("ignore", "容差内判等，不计入差异，报告单列"),

    /**
     * 审计口径：容差内仍然算差异。用于核对容差是否过宽，或需要保留历史"多报"行为的场景。
     */
    REPORT("report", "容差内仍计入差异");

    private final String code;
    private final String description;

    WithinToleranceMode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 解析配置值；null/空白表示未配置，回落到默认口径 {@link #IGNORE}。
     * 非法值直接抛异常：拼错一个字符就静默按默认口径跑，正是这类开关最危险的失败方式。
     */
    public static WithinToleranceMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return IGNORE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WithinToleranceMode mode : values()) {
            if (mode.code.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown withinTolerance mode: " + value
                + " (expected ignore|report)");
    }
}
