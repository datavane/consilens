package com.consilens.connector.api.config;

import com.consilens.connector.api.model.ResourceLocator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorConfig {

    private String type;

    private String name;

    private Map<String, Object> connection;

    private ResourceLocator resource;

    private ReadOptions readOptions;
}
