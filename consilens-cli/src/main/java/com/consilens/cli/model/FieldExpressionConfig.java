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
public class FieldExpressionConfig {

    @JsonProperty("column")
    private String column;

    @JsonProperty("expression")
    private String expression;

    @JsonProperty("literal")
    private Object literal;

    @JsonIgnore
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();

    @JsonAnySetter
    public void addAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
