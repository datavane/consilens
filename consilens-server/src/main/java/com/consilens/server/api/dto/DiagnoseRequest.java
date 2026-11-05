package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DiagnoseRequest {

    @NotBlank
    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String runArtifactId;

    private Map<String, Object> options = new LinkedHashMap<>();
}
