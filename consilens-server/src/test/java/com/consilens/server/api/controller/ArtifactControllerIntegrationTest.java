package com.consilens.server.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=local")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class ArtifactControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectUnsafeArtifactIdBeforeRepositoryLookup() throws Exception {
        mockMvc.perform(get("/v1/artifacts/{artifactId}", "bad.id")
                        .header("X-Trace-Id", "trace-artifact"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRejectArtifactListWhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(get("/v1/artifacts").param("pageSize", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void shouldListArtifactsWithPageFilters() throws Exception {
        mockMvc.perform(get("/v1/artifacts")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("artifactType", "CONFIG")
                        .param("keyword", "cfg")
                        .header("X-Trace-Id", "trace-artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void shouldRejectTaskListWhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(get("/v1/tasks").param("pageSize", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}
