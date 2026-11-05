package com.consilens.server.application.capability.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个类型的归一化字段，与 CLI 的 {@code TypeNormalizationRule} 保持一致。
 * 字段语义：precision/rounding → format_number；format/timezone/comparisonMode →
 * format_datetime；encoding/uppercase → encode；trueValue/falseValue/nullValue →
 * map_boolean（boolean 类型）或 normalize_string（string 类型）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TypeNormalizationRule {

    private Integer precision;

    private Boolean rounding;

    private String format;

    private String timezone;

    private String comparisonMode;

    private String encoding;

    private Boolean uppercase;

    private String trueValue;

    private String falseValue;

    private String nullValue;

    /** 数值绝对容差（终局本地比较生效）。 */
    private Double tolerance;

    /** 数值相对容差（0..1）。 */
    private Double relativeTolerance;

    /** 会话时区（IANA 名称或 +08:00 偏移），两侧归一化按它统一。 */
    @com.fasterxml.jackson.annotation.JsonAlias("session_time_zone")
    private String sessionTimeZone;
}
