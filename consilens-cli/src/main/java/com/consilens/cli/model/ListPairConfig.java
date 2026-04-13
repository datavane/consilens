package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paired list configuration for source and target.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ListPairConfig {

    @JsonProperty("source")
    private List<String> source;

    @JsonProperty("target")
    private List<String> target;

    public void validate(String fieldName) throws ValidationException {
        if (isBothEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + " 不能为空");
        }
        if ((source == null || source.isEmpty()) != (target == null || target.isEmpty())) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + " 需要同时配置 source 和 target");
        }
    }

    @JsonIgnore
    public boolean isBothEmpty() {
        return (source == null || source.isEmpty()) && (target == null || target.isEmpty());
    }

    @JsonIgnore
    public boolean hasMismatchedSize() {
        if (source == null || target == null) {
            return false;
        }
        return source.size() != target.size();
    }
}
