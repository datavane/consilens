package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceCreateRequest {

    @NotBlank(message = "name is required")
    @Pattern(regexp = ApiValidationRules.NAME_PATTERN, message = "name contains invalid characters")
    private String name;

    @NotBlank(message = "type is required")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "type contains invalid characters")
    private String type;

    /** JSON connection parameters: host / port / database / username / password. */
    private Map<String, Object> param;
}
