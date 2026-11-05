package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class RepairRequest {

    @NotBlank
    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String diagnosisArtifactId;

    @Valid
    private Options options = new Options();

    @Data
    public static class Options {
        private Boolean requireApproval;
    }
}
