package com.consilens.cli.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CheckpointStoreConfig {

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonIgnore
    @Builder.Default
    private Map<String, Object> options = new LinkedHashMap<>();

    @JsonAnySetter
    public void putOption(String key, Object value) {
        options.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getOptions() {
        return options;
    }
}
