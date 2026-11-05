package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private final RunTaskQueryService runTaskQueryService;
    private final RunTaskRetryService runTaskRetryService;
    private final RunTaskCancelService runTaskCancelService;
    private final RunTaskSubmissionService runTaskSubmissionService;
    private final DiffReportService diffReportService;

    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<TaskAcceptedResponse>> submit(
            @Valid @RequestBody RunRequest request,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(runTaskSubmissionService.submit(request, traceId), traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TaskSummaryDto>>> listTasks(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize,
            @RequestParam(required = false) List<TaskStatus> status,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = ApiValidationRules.NODE_KEY_PATTERN) String executeNodeKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "false") boolean includeDiffSummary,
            @RequestParam(required = false) Long definitionId,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        PageResponse<TaskSummaryDto> result = runTaskQueryService.listTasks(page,
                pageSize,
                status,
                keyword,
                executeNodeKey,
                startTime,
                endTime,
                includeDiffSummary,
                definitionId,
                traceId);
        return ResponseEntity.ok(ApiResponse.success(result, traceId));
    }

    @GetMapping("/{taskId}/diff-report")
    public ResponseEntity<ApiResponse<DiffReportDto>> getDiffReport(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(diffReportService.getDiffReport(taskId), traceId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskQueryResponse>> getTask(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                                  HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(runTaskQueryService.getTask(taskId, traceId), traceId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                   HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        runTaskRetryService.retry(taskId, traceId);
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
                                                    HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        TaskStatus status = runTaskCancelService.cancel(taskId, traceId);
        if (status == TaskStatus.CANCEL_REQUESTED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(null, traceId));
        }
        return ResponseEntity.ok(ApiResponse.success(null, traceId));
    }
}
