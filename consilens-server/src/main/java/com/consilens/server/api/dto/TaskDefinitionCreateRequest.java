package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.Map;

@Data
public class TaskDefinitionCreateRequest {

    @NotBlank(message = "name is required")
    @Pattern(regexp = ApiValidationRules.NAME_PATTERN, message = "name contains invalid characters")
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    /** Full run configuration (ServerCompareConfig JSON). */
    @NotNull(message = "config is required")
    private Map<String, Object> config;
}
