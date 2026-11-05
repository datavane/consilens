package com.consilens.server.application.capability.config;

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
public class EndpointConfig {

    private String type;

    private String table;

    private String query;

    private String filter;

    /** Optional reference to a registered data source; used to inject connection params. */
    private Long datasourceId;

    /** Database / schema name (matches the CLI config semantics: the JDBC url must carry it). */
    private String database;

    @Builder.Default
    private Map<String, Object> connection = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> readOptions = new LinkedHashMap<>();
}
