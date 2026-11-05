package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.ConfigResponse;
import com.consilens.server.application.config.ConfigArtifactService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Pattern;

@RestController
@RequestMapping("/v1/configs")
@RequiredArgsConstructor
@Validated
public class ConfigController {

    private final ConfigArtifactService configArtifactService;

    @GetMapping("/{configId}")
    public ResponseEntity<ApiResponse<ConfigResponse>> getConfig(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String configId,
                                                                 HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(configArtifactService.getConfig(configId), traceId));
    }
}
