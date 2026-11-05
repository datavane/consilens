package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.application.artifact.ArtifactQueryService;
import com.consilens.server.application.artifact.ArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArtifactQueryControllerTest {

    @Test
    void shouldListArtifactsThroughController() throws Exception {
        ArtifactQueryService queryService = mock(ArtifactQueryService.class);
        when(queryService.listArtifacts(anyInt(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(PageResponse.<ArtifactListDto>builder()
                        .total(1)
                        .page(1)
                        .pageSize(20)
                        .items(List.of(ArtifactListDto.builder()
                                .artifactId("artifact-1")
                                .artifactType("RUN_RESULT")
                                .format("json")
                                .sizeBytes(128L)
                                .build()))
                        .build());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ArtifactController(
                        mock(ArtifactService.class),
                        queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();

        mockMvc.perform(get("/v1/artifacts")
                        .header("X-Trace-Id", "trace-artifacts")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .param("artifactType", "RUN_RESULT")
                        .param("keyword", "artifact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].artifactType").value("RUN_RESULT"));

        verify(queryService).listArtifacts(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(List.of(com.consilens.server.domain.enums.ArtifactKind.RUN_RESULT)),
                org.mockito.ArgumentMatchers.eq("artifact"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("trace-artifacts"));
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
