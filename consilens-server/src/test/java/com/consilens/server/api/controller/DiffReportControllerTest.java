package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiffReportControllerTest {

    private DiffReportService diffReportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        diffReportService = mock(DiffReportService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new TaskController(mock(com.consilens.server.application.task.RunTaskQueryService.class),
                                mock(com.consilens.server.application.task.RunTaskRetryService.class),
                                mock(com.consilens.server.application.task.RunTaskCancelService.class),
                                mock(com.consilens.server.application.task.RunTaskSubmissionService.class),
                                diffReportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    @Test
    void shouldReturnDiffReport() throws Exception {
        when(diffReportService.getDiffReport(eq("task-1"))).thenReturn(DiffReportDto.builder()
                .taskId("task-1")
                .status("GOOD")
                .matchScore(97.0)
                .statistics(Map.of("totalDifferences", 3))
                .columns(List.of(DiffReportDto.ColumnStat.builder()
                        .name("price")
                        .updateCount(1L)
                        .differenceCount(1L)
                        .differencePercentage(50.0)
                        .aggregatedFromSamples(true)
                        .build()))
                .samples(List.of(DiffReportDto.SampleDiff.builder()
                        .operation("MISMATCH")
                        .primaryKey(List.of("1001"))
                        .changedColumns(List.of("price"))
                        .metadata(Map.of())
                        .build()))
                .sampleSize(3)
                .totalDifferenceCount(3L)
                .sampleTruncated(false)
                .timeline(List.of())
                .build());

        mockMvc.perform(get("/v1/tasks/task-1/diff-report")
                        .header("X-Trace-Id", "trace-diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("GOOD"))
                .andExpect(jsonPath("$.data.matchScore").value(97.0))
                .andExpect(jsonPath("$.data.totalDifferenceCount").value(3))
                .andExpect(jsonPath("$.data.sampleSize").value(3))
                .andExpect(jsonPath("$.data.sampleTruncated").value(false))
                .andExpect(jsonPath("$.data.columns[0].name").value("price"))
                .andExpect(jsonPath("$.data.columns[0].updateCount").value(1))
                .andExpect(jsonPath("$.data.samples[0].operation").value("MISMATCH"))
                .andExpect(jsonPath("$.data.samples[0].primaryKey[0]").value("1001"));
    }

    @Test
    void shouldReturnNoneReportForNonSucceededTask() throws Exception {
        when(diffReportService.getDiffReport(eq("task-running"))).thenReturn(DiffReportDto.builder()
                .taskId("task-running")
                .status("NONE")
                .columns(List.of())
                .samples(List.of())
                .sampleTruncated(false)
                .timeline(List.of())
                .build());

        mockMvc.perform(get("/v1/tasks/task-running/diff-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.matchScore").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundForUnknownTask() throws Exception {
        when(diffReportService.getDiffReport(eq("task-404")))
                .thenThrow(new ResourceNotFoundException("Task not found: task-404"));

        mockMvc.perform(get("/v1/tasks/task-404/diff-report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
