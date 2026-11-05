package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.RepairRequest;
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
public class RepairController {

    private final SynchronousCapabilityService synchronousCapabilityService;

    @PostMapping("/repair")
    public ResponseEntity<ApiResponse<SyncArtifactResponse>> repair(@Valid @RequestBody RepairRequest request,
                                                                    HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(synchronousCapabilityService.repair(request, traceId), traceId));
    }
}
