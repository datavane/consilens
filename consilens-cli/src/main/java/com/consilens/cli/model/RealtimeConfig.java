package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class RealtimeConfig {

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("updateColumns")
    private StringPairConfig updateColumns;

    @JsonProperty("watermarkDelay")
    private String watermarkDelay;

    @JsonProperty("windowSize")
    private String windowSize;

    @JsonProperty("overlap")
    private String overlap;

    @JsonProperty("checkpointStore")
    private CheckpointStoreConfig checkpointStore;

    @JsonProperty("runOnce")
    private Boolean runOnce;

    @JsonProperty("interval")
    private String interval;

    public void validate(String fieldName) {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        if (updateColumns == null) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + ".updateColumns 配置不能为空");
        }
        updateColumns.validate(fieldName + ".updateColumns");
        parseDuration(fieldName + ".watermarkDelay", watermarkDelay);
        parseDuration(fieldName + ".windowSize", windowSize);
        parseDuration(fieldName + ".overlap", overlap);
        if (interval != null && !interval.isBlank()) {
            parseDuration(fieldName + ".interval", interval);
        }
        if (checkpointStore == null || checkpointStore.getType() == null || checkpointStore.getType().isBlank()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    fieldName + ".checkpointStore.type 不能为空");
        }
        if ("table".equalsIgnoreCase(checkpointStore.getType())
                && (checkpointStore.getName() == null || checkpointStore.getName().isBlank())) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    fieldName + ".checkpointStore.name 不能为空");
        }
    }

    private void parseDuration(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + " 不能为空");
        }
        try {
            Duration.parse(value.trim());
        } catch (Exception e) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + " 不是合法的 ISO-8601 duration");
        }
    }
}
