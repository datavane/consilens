package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Paired string configuration for source and target.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class StringPairConfig {

    @JsonProperty("source")
    private String source;

    @JsonProperty("target")
    private String target;

    public void validate(String fieldName) throws ValidationException {
        if (source == null || source.trim().isEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + ".source 不能为空");
        }
        if (target == null || target.trim().isEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + ".target 不能为空");
        }
    }
}
