package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ConfigResponse;
import com.consilens.server.application.config.ConfigArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigControllerTest {

    @Test
    void shouldExposeReadOnlyConfigEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(new StubConfigArtifactService()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/v1/configs/config-1").header("X-Trace-Id", "trace-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.content.content").value("{}"));

        mockMvc.perform(post("/v1/configs/config-1/save")
                        .header("X-Trace-Id", "trace-config")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private static class StubConfigArtifactService implements ConfigArtifactService {
        @Override
        public ConfigResponse getConfig(String configId) {
            return ConfigResponse.builder()
                    .artifact(ArtifactRefDto.builder()
                            .id(configId)
                            .type("CONFIG")
                            .format("json")
                            .metadata(Map.of())
                            .build())
                    .content(ArtifactContentDto.builder()
                            .artifactId(configId)
                            .artifactType("CONFIG")
                            .artifactFormat("json")
                            .content("{}")
                            .build())
                    .status("READY")
                    .build();
        }
    }
}
