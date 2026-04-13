package com.consilens.sink.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level result configuration corresponding to the result node in the config file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ResultConfig {

    @Builder.Default
    private List<SinkConfig> sinks = new ArrayList<>();

    @Builder.Default
    private int parallelism = 4;

    @Builder.Default
    private boolean failOnSinkError = false;
}
