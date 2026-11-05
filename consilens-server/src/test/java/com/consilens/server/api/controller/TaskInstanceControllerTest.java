package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskLogPageDto;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.application.log.RunLogService;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskInstanceControllerTest {

    private MockMvc mockMvc;
    private RunLogService runLogService;
    private RunTaskQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(RunTaskQueryService.class);
        runLogService = mock(RunLogService.class);
        when(queryService.listTasks(anyInt(), anyInt(), any(), any(), any(), any(), any(),
                anyBoolean(), isNull(), any())).thenReturn(
                PageResponse.<TaskSummaryDto>builder()
                        .total(1).page(1).pageSize(20)
                        .items(List.of(TaskSummaryDto.builder()
                                .taskId("inst_1")
                                .serialNo("s-1")
                                .status("SUCCEEDED")
                                .definitionId("1")
                                .definitionName("orders-check")
                                .build()))
                        .build());
        when(queryService.getTask(eq("inst_1"), any())).thenReturn(TaskQueryResponse.builder()
                .taskId("inst_1")
                .serialNo("s-1")
                .status("SUCCEEDED")
                .definitionId("1")
                .definitionName("orders-check")
                .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskInstanceController(
                        queryService,
                        mock(RunTaskRetryService.class),
                        mock(RunTaskCancelService.class),
                        mock(RunTaskSubmissionService.class),
                        mock(DiffReportService.class),
                        runLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    @Test
    void shouldListInstancesWithDefinitionInfo() throws Exception {
        mockMvc.perform(get("/v1/task-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value("inst_1"))
                .andExpect(jsonPath("$.data.items[0].definitionId").value("1"))
                .andExpect(jsonPath("$.data.items[0].definitionName").value("orders-check"));
    }

    @Test
    void shouldGetInstanceDetailWithDefinitionInfo() throws Exception {
        mockMvc.perform(get("/v1/task-instances/inst_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitionId").value("1"))
                .andExpect(jsonPath("$.data.definitionName").value("orders-check"));
    }

    @Test
    void shouldExposeFailureReasonInInstanceDetail() throws Exception {
        when(queryService.getTask(eq("inst_failed"), any())).thenReturn(TaskQueryResponse.builder()
                .taskId("inst_failed")
                .status("FAILED")
                .errorCode("CLUSTER_EXECUTION_FAILED")
                .errorMessage("cluster application default/job finished with FAILED: Connection refused")
                .build());

        mockMvc.perform(get("/v1/task-instances/inst_failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorCode").value("CLUSTER_EXECUTION_FAILED"))
                .andExpect(jsonPath("$.data.errorMessage")
                        .value("cluster application default/job finished with FAILED: Connection refused"));
    }

    @Test
    void shouldSubmitExternalRun() throws Exception {
        mockMvc.perform(post("/v1/task-instances")
                        .contentType("application/json")
                        .content("{\"serialNo\":\"ext-1\",\"configContent\":{\"keys\":[\"id\"]}}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldReturnRunLogPage() throws Exception {
        when(runLogService.read("inst_1", null, 100, 2000)).thenReturn(TaskLogPageDto.builder()
                .available(true)
                .content("line-1\nline-2\n")
                .nextOffset(2L)
                .hasMore(false)
                .fileSize(24L)
                .build());

        mockMvc.perform(get("/v1/task-instances/inst_1/logs").param("tailLines", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.content").value("line-1\nline-2\n"))
                .andExpect(jsonPath("$.data.nextOffset").value(2));
    }

    @Test
    void shouldDownloadRunLog() throws Exception {
        java.nio.file.Path logFile = java.nio.file.Files.createTempFile("inst_1", ".log");
        java.nio.file.Files.write(logFile, "run log".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RunLogService.LogFile file = mock(RunLogService.LogFile.class);
        when(file.getFileName()).thenReturn("inst_1.log");
        when(file.getPath()).thenReturn(logFile);
        when(runLogService.download("inst_1")).thenReturn(file);

        mockMvc.perform(get("/v1/task-instances/inst_1/logs/download"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals(
                        "run log", result.getResponse().getContentAsString()));
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
