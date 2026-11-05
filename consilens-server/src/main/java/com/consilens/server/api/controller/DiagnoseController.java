package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.SyncArtifactResponse;
import com.consilens.server.application.capability.SynchronousCapabilityService;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DiagnoseController {

    private final SynchronousCapabilityService synchronousCapabilityService;

    @PostMapping("/diagnose")
    public ResponseEntity<ApiResponse<SyncArtifactResponse>> diagnose(@Valid @RequestBody DiagnoseRequest request,
                                                                      HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(synchronousCapabilityService.diagnose(request, traceId), traceId));
    }
}
