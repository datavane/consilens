package com.consilens.connector.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLocator {

    private String type;

    private String name;

    private String path;

    private Map<String, Object> options;
}
