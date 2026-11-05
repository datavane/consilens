package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.consilens.server.support.trace.TraceIdSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/v1/task-definitions")
public class TaskDefinitionController {

    private final TaskDefinitionService taskDefinitionService;

    public TaskDefinitionController(TaskDefinitionService taskDefinitionService) {
        this.taskDefinitionService = taskDefinitionService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TaskDefinitionDto>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) Boolean enabled,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(
                taskDefinitionService.list(page, pageSize, keyword, enabled), traceId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskDefinitionDto>> create(
            @Valid @RequestBody TaskDefinitionCreateRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(taskDefinitionService.create(request), traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDefinitionDetailDto>> get(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(taskDefinitionService.get(id), traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDefinitionDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody TaskDefinitionCreateRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(taskDefinitionService.update(id, request), traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        taskDefinitionService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ApiResponse<TaskAcceptedResponse>> run(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid TaskDefinitionRunRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(
                taskDefinitionService.run(id, request, traceId), traceId));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<TaskDefinitionDto>> toggle(
            @PathVariable Long id,
            @RequestParam boolean enabled,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(taskDefinitionService.toggle(id, enabled), traceId));
    }
}
