package com.consilens.cli.model;

import com.consilens.common.enums.LocalCompareMode;
import com.consilens.core.validation.ValidationException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local comparison configuration for terminal checksum segments.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalCompareConfig {

    @Builder.Default
    @JsonProperty("mode")
    private String mode = "full";

    @JsonIgnore
    public LocalCompareMode getModeEnum() {
        return LocalCompareMode.fromString(mode);
    }

    public void validate() throws ValidationException {
        String normalizedMode = mode == null ? null : mode.trim().toLowerCase();
        if (!"full".equals(normalizedMode) && !"row-hash".equals(normalizedMode)) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    "strategy.localCompare.mode 必须是 full 或 row-hash");
        }
    }
}
