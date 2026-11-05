package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Size;

@Data
public class TaskDefinitionRunRequest {

    @Size(max = ApiValidationRules.ID_MAX_LENGTH)
    private String serialNo;

    @Valid
    private RunRequest.Options options = new RunRequest.Options();
}
