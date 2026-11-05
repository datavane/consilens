package com.consilens.server.api.controller;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ApiValidationRules;
import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskLogPageDto;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.application.log.RunLogService;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/task-instances")
@RequiredArgsConstructor
@Validated
public class TaskInstanceController {

    private final RunTaskQueryService runTaskQueryService;
    private final RunTaskRetryService runTaskRetryService;
    private final RunTaskCancelService runTaskCancelService;
    private final RunTaskSubmissionService runTaskSubmissionService;
    private final DiffReportService diffReportService;
    private final RunLogService runLogService;

    @PostMapping
    public ResponseEntity<ApiResponse<TaskAcceptedResponse>> submitDirect(
            @Valid @RequestBody RunRequest request,
            HttpServletRequest httpServletRequest) {
        return submit(request, httpServletRequest);
    }

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

    /**
     * 运行实例日志：默认从 offset 顺序读取；传 tailLines 时读取末尾若干行，
     * 并把文件总行数作为 nextOffset 供前端做增量轮询。
     */
    @GetMapping("/{taskId}/logs")
    public ResponseEntity<ApiResponse<TaskLogPageDto>> getLogs(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId,
            @RequestParam(required = false) @Min(0) Long offset,
            @RequestParam(required = false) @Min(1) @Max(20000) Integer tailLines,
            @RequestParam(defaultValue = "2000") @Min(1) @Max(5000) int limit,
            HttpServletRequest httpServletRequest) {
        String traceId = TraceIdSupport.getOrCreateTraceId(httpServletRequest);
        return ResponseEntity.ok(ApiResponse.success(
                runLogService.read(taskId, offset, tailLines, limit), traceId));
    }

    /**
     * 下载完整运行日志文件。
     *
     * <p>用 {@code FileSystemResource} 流式返回：日志文件可能有几十 MB，整份读进堆再去响应
     * 既浪费内存也会把并发下载变成 OOM 的入口。
     */
    @GetMapping("/{taskId}/logs/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadLog(
            @PathVariable @Pattern(regexp = ApiValidationRules.SAFE_ID_PATTERN) String taskId) {
        RunLogService.LogFile file = runLogService.download(taskId);
        org.springframework.core.io.Resource body = file.getPath() == null
                ? new org.springframework.core.io.ByteArrayResource(new byte[0])
                : new org.springframework.core.io.FileSystemResource(file.getPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
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
