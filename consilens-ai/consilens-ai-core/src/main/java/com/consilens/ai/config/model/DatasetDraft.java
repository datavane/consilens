package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Source or target dataset draft for AI-assisted config generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetDraft {

    private String type;
    private String name;
    private String jdbcUrl;
    private String usernameEnv;
    private String passwordEnv;
    private String resourceType;
    private String resourceName;
    private String query;
}
