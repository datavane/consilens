package com.consilens.connector.api.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadOptions {

    private String consistency;

    private Integer batchSize;

    private Integer fetchSize;

    private Map<String, Object> options;
}
