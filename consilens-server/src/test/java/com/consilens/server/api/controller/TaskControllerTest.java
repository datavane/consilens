package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerTest {

    @Test
    void shouldSubmitTaskThroughTaskController() throws Exception {
        RunTaskSubmissionService submissionService = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(submissionService);

        mockMvc.perform(post("/v1/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-task")
                        .content("{\"serialNo\":\"serial-1\",\"configArtifactId\":\"artifact-config\"}"))
                .andExpect(status().isAccepted());

        verify(submissionService).submit(any(), org.mockito.ArgumentMatchers.eq("trace-task"));
    }

    @Test
    void shouldSubmitTaskWithInlineConfigContent() throws Exception {
        RunTaskSubmissionService submissionService = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(submissionService);

        mockMvc.perform(post("/v1/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNo\":\"serial-1\",\"configContent\":\"source:\\n"
                                + "  type: mysql\\n  resource:\\n    type: table\\n    name: orders\"}"))
                .andExpect(status().isAccepted());

        verify(submissionService).submit(any(), any());
    }

    @Test
    void shouldRejectTaskWithoutConfigReference() throws Exception {
        RunTaskSubmissionService submissionService = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(submissionService);

        mockMvc.perform(post("/v1/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNo\":\"serial-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(submissionService, never()).submit(any(), any());
    }

    @Test
    void shouldRejectTaskWhenSerialNoExceedsDatabaseLimit() throws Exception {
        RunTaskSubmissionService submissionService = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(submissionService);

        mockMvc.perform(post("/v1/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNo\":\"" + "s".repeat(129)
                                + "\",\"configArtifactId\":\"artifact-config\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(submissionService, never()).submit(any(), any());
    }

    @Test
    void shouldRejectTaskWhenTimeoutIsNotPositive() throws Exception {
        RunTaskSubmissionService submissionService = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(submissionService);

        mockMvc.perform(post("/v1/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNo\":\"serial-1\",\"configArtifactId\":\"artifact-config\","
                                + "\"options\":{\"timeoutMs\":0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(submissionService, never()).submit(any(), any());
    }

    @Test
    void shouldReturnAcceptedWhenRunningTaskCancellationIsOnlyRequested() throws Exception {
        RunTaskCancelService cancelService = mock(RunTaskCancelService.class);
        when(cancelService.cancel(any(), any())).thenReturn(TaskStatus.CANCEL_REQUESTED);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(
                        mock(RunTaskQueryService.class),
                        mock(RunTaskRetryService.class),
                        cancelService,
                        mock(RunTaskSubmissionService.class),
                        mock(DiffReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();

        mockMvc.perform(post("/v1/tasks/task-running/cancel"))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldListTasksThroughTaskController() throws Exception {
        RunTaskQueryService queryService = mock(RunTaskQueryService.class);
        when(queryService.listTasks(anyInt(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), isNull(), any()))
                .thenReturn(PageResponse.<TaskSummaryDto>builder()
                        .total(1)
                        .page(1)
                        .pageSize(20)
                        .items(List.of(TaskSummaryDto.builder()
                                .taskId("task-1")
                                .serialNo("serial-1")
                                .status("SUCCEEDED")
                                .build()))
                        .build());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(
                        queryService,
                        mock(RunTaskRetryService.class),
                        mock(RunTaskCancelService.class),
                        mock(RunTaskSubmissionService.class),
                        mock(DiffReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();

        mockMvc.perform(get("/v1/tasks")
                        .header("X-Trace-Id", "trace-list")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .param("status", "SUCCEEDED")
                        .param("keyword", "serial-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCEEDED"));

        verify(queryService).listTasks(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(List.of(TaskStatus.SUCCEEDED)),
                org.mockito.ArgumentMatchers.eq("serial-1"),
                isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                isNull(),
                org.mockito.ArgumentMatchers.eq("trace-list"));
    }

    @Test
    void shouldPassIncludeDiffSummaryFlag() throws Exception {
        RunTaskQueryService queryService = mock(RunTaskQueryService.class);
        when(queryService.listTasks(anyInt(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), isNull(), any()))
                .thenReturn(PageResponse.<TaskSummaryDto>builder()
                        .total(1)
                        .page(1)
                        .pageSize(20)
                        .items(List.of(TaskSummaryDto.builder()
                                .taskId("task-1")
                                .status("SUCCEEDED")
                                .diffSummary(com.consilens.server.api.dto.TaskDiffSummaryDto.builder()
                                        .differenceCount(3L)
                                        .differencePercentage(0.03)
                                        .status("GOOD")
                                        .build())
                                .build()))
                        .build());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TaskController(
                        queryService,
                        mock(RunTaskRetryService.class),
                        mock(RunTaskCancelService.class),
                        mock(RunTaskSubmissionService.class),
                        mock(DiffReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();

        mockMvc.perform(get("/v1/tasks")
                        .param("includeDiffSummary", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].diffSummary.differenceCount").value(3))
                .andExpect(jsonPath("$.data.items[0].diffSummary.status").value("GOOD"));

        verify(queryService).listTasks(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq(true),
                isNull(),
                any());
    }

    private MockMvc mockMvc(RunTaskSubmissionService submissionService) {
        return MockMvcBuilders.standaloneSetup(new TaskController(
                        mock(RunTaskQueryService.class),
                        mock(RunTaskRetryService.class),
                        mock(RunTaskCancelService.class),
                        submissionService,
                        mock(DiffReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
