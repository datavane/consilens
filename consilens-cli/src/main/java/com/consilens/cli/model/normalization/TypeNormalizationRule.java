package com.consilens.cli.model.normalization;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TypeNormalizationRule {
    
    /**
     * Decimal precision (number of decimal places).
     */
    @JsonProperty("precision")
    private Integer precision;
    
    /**
     * Whether to round (true) or truncate (false).
     */
    @JsonProperty("rounding")
    private Boolean rounding;
    
    /**
     * Formatting pattern.
     */
    @JsonProperty("format")
    private String format;
    
    /**
     * Timezone identifier.
     */
    @JsonProperty("timezone")
    private String timezone;

    /**
     * Temporal comparison mode for date/datetime/timestamp values.
     */
    @JsonProperty("comparisonMode")
    private String comparisonMode;
    
    /**
     * Encoding method (hex/base64).
     */
    @JsonProperty("encoding")
    private String encoding;
    
    /**
     * Whether to use uppercase (for hex encoding).
     */
    @JsonProperty("uppercase")
    private Boolean uppercase;
    
    /**
     * String representation of boolean true.
     */
    @JsonProperty("trueValue")
    private String trueValue;
    
    /**
     * String representation of boolean false.
     */
    @JsonProperty("falseValue")
    private String falseValue;
    
    /**
     * String representation of NULL values.
     */
    @JsonProperty("nullValue")
    private String nullValue;

    /**
     * 数值绝对容差（float/double/decimal）；null 表示不容差。
     * 容差在终局本地比较生效；checksum 候选回查天然兼容。
     */
    @JsonProperty("tolerance")
    private Double tolerance;

    /**
     * 数值相对容差（0..1）；与绝对容差任一满足即判等。
     */
    @JsonProperty("relativeTolerance")
    private Double relativeTolerance;

    /**
     * 会话时区（IANA 名称或 +08:00 偏移）。连接级信息按类型配置语法只能挂在某条规则上，
     * core 侧会把任意规则里的这个参数读成两侧归一化时区。
     */
    @JsonProperty("sessionTimeZone")
    @com.fasterxml.jackson.annotation.JsonAlias("session_time_zone")
    private String sessionTimeZone;
}
