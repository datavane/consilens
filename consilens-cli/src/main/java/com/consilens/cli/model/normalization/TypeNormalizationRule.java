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
}
