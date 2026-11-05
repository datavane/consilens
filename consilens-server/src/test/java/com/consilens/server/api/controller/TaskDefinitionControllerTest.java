package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDefinitionControllerTest {

    private TaskDefinitionService taskDefinitionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskDefinitionService = mock(TaskDefinitionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskDefinitionController(taskDefinitionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    @Test
    void shouldListDefinitions() throws Exception {
        when(taskDefinitionService.list(1, 20, null, null)).thenReturn(
                PageResponse.<TaskDefinitionDto>builder()
                        .total(1).page(1).pageSize(20).items(List.of(dto())).build());

        mockMvc.perform(get("/v1/task-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("orders-check"))
                .andExpect(jsonPath("$.data.items[0].enabled").value(true));
    }

    @Test
    void shouldCreateDefinition() throws Exception {
        when(taskDefinitionService.create(any(TaskDefinitionCreateRequest.class))).thenReturn(dto());

        mockMvc.perform(post("/v1/task-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orders-check\",\"config\":{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("orders-check"));
    }

    @Test
    void shouldRejectInvalidCreateRequest() throws Exception {
        mockMvc.perform(post("/v1/task-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad name!\",\"config\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    void shouldGetDetail() throws Exception {
        when(taskDefinitionService.get(1L)).thenReturn(TaskDefinitionDetailDto.builder()
                .id("1").name("orders-check").enabled(true)
                .config(Map.of("keys", List.of("id")))
                .recentInstances(List.of(TaskDefinitionDetailDto.RecentInstanceDto.builder()
                        .instanceId("inst_1").status("SUCCEEDED").build()))
                .build());

        mockMvc.perform(get("/v1/task-definitions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.config.keys[0]").value("id"))
                .andExpect(jsonPath("$.data.recentInstances[0].instanceId").value("inst_1"));
    }

    @Test
    void shouldReturnNotFound() throws Exception {
        when(taskDefinitionService.get(eq(404L)))
                .thenThrow(new ResourceNotFoundException("task definition not found: 404"));

        mockMvc.perform(get("/v1/task-definitions/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldUpdateDefinition() throws Exception {
        when(taskDefinitionService.update(eq(1L), any(TaskDefinitionCreateRequest.class))).thenReturn(dto());

        mockMvc.perform(put("/v1/task-definitions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orders-check\",\"config\":{\"keys\":[\"id\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("orders-check"));
    }

    @Test
    void shouldDeleteDefinition() throws Exception {
        mockMvc.perform(delete("/v1/task-definitions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shouldRunDefinition() throws Exception {
        when(taskDefinitionService.run(eq(1L), any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        mockMvc.perform(post("/v1/task-definitions/1/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("inst_1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void shouldToggleDefinition() throws Exception {
        TaskDefinitionDto disabled = dto();
        disabled.setEnabled(false);
        when(taskDefinitionService.toggle(1L, false)).thenReturn(disabled);

        mockMvc.perform(post("/v1/task-definitions/1/toggle").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    private TaskDefinitionDto dto() {
        return TaskDefinitionDto.builder()
                .id("1")
                .name("orders-check")
                .taskType("RUN")
                .enabled(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
