package com.consilens.server.api.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class PlanRequest {

    @NotBlank
    @Size(max = 512)
    private String goal;

    @Valid
    @NotNull
    private Endpoint source;

    @Valid
    @NotNull
    private Endpoint target;

    @NotEmpty
    @Size(max = 64)
    private List<String> keys;

    private Map<String, Object> hints = new LinkedHashMap<>();

    @Data
    public static class Endpoint {
        @NotBlank
        @Size(max = 64)
        private String type;
        @Size(max = 256)
        private String table;
        private String query;
    }
}
