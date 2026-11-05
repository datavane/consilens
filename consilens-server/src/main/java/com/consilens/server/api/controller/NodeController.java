package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.NodeSummaryDto;
import com.consilens.server.application.topology.ServerNodeQueryService;
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
import java.util.List;

@RestController
@RequestMapping("/v1/nodes")
@RequiredArgsConstructor
@Validated
public class NodeController {

    private final ServerNodeQueryService serverNodeQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NodeSummaryDto>>> listNodes(HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(serverNodeQueryService.listNodes(), traceId));
    }

    @GetMapping("/{nodeKey}")
    public ResponseEntity<ApiResponse<NodeSummaryDto>> getNode(
            @PathVariable @Pattern(regexp = ApiValidationRules.NODE_KEY_PATTERN) String nodeKey,
                                                               HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(serverNodeQueryService.getNode(nodeKey), traceId));
    }
}
