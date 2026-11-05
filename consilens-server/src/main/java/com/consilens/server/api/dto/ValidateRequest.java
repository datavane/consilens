package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ValidateRequest {

    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String configArtifactId;
    private Object configContent;
    private Map<String, Object> options = new LinkedHashMap<>();

    @AssertTrue(message = "configArtifactId and configContent must contain exactly one value")
    public boolean isConfigReferenceValid() {
        boolean hasArtifact = configArtifactId != null && !configArtifactId.isBlank();
        boolean hasContent = configContent != null && !(configContent instanceof String && ((String) configContent).isBlank());
        return hasArtifact ^ hasContent;
    }
}
