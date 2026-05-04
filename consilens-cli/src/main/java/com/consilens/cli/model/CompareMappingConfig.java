package com.consilens.cli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CompareMappingConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private String type;

    @JsonProperty("source")
    private FieldExpressionConfig source;

    @JsonProperty("target")
    private FieldExpressionConfig target;

    @JsonProperty("key")
    private Boolean key;

    @JsonProperty("compare")
    private Boolean compare;

    @JsonProperty("ordinal")
    private Integer ordinal;
}
