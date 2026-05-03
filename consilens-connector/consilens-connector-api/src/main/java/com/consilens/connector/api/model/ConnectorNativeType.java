package com.consilens.connector.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Connector-native type declaration captured during schema discovery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorNativeType {

    private String connectorType;

    private String name;

    private String declaration;

    private Integer jdbcType;

    private boolean nullable;

    private Map<String, Object> attributes;
}
