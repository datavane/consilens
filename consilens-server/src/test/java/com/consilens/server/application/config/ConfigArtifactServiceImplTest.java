package com.consilens.server.application.config;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConfigArtifactServiceTest {

    @Test
    void shouldReadConfigArtifactWithContent() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifact("artifact-config")).thenReturn(ArtifactRefDto.builder()
                .id("artifact-config")
                .type("CONFIG")
                .format("json")
                .metadata(Map.of())
                .build());
        when(artifactService.getArtifactContent("artifact-config")).thenReturn(ArtifactContentDto.builder()
                .artifactId("artifact-config")
                .artifactType("CONFIG")
                .artifactFormat("json")
                .content("{}")
                .build());

        var response = new ConfigArtifactServiceImpl(artifactService).getConfig("artifact-config");

        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getContent().getContent()).isEqualTo("{}");
    }

    @Test
    void shouldMaskSensitiveKeysInConfigContent() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifact("artifact-config")).thenReturn(ArtifactRefDto.builder()
                .id("artifact-config").type("CONFIG").format("json").metadata(Map.of()).build());
        when(artifactService.getArtifactContent("artifact-config")).thenReturn(ArtifactContentDto.builder()
                .artifactId("artifact-config").artifactType("CONFIG").artifactFormat("json")
                .content("{\"source\":{\"type\":\"mysql\",\"connection\":{"
                        + "\"url\":\"jdbc:mysql://db/app\",\"password\":\"source-secret\","
                        + "\"properties\":\"useSSL=false&password=prop-secret&token=tok\"}},"
                        + "\"hints\":{\"sourcePassword\":\"hint-secret\"},"
                        + "\"result\":{\"sinks\":[{\"properties\":{\"password\":\"sink-secret\"}}]}}")
                .build());

        var response = new ConfigArtifactServiceImpl(artifactService).getConfig("artifact-config");

        String content = response.getContent().getContent();
        assertThat(content).doesNotContain("source-secret", "prop-secret", "hint-secret", "sink-secret", "token=tok");
        assertThat(content).contains("\"password\":\"******\"");
        assertThat(content).contains("password=******");
        assertThat(content).contains("jdbc:mysql://db/app");
    }

    @Test
    void shouldRejectNonConfigArtifact() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifact("artifact-run")).thenReturn(ArtifactRefDto.builder()
                .id("artifact-run")
                .type("RUN_RESULT")
                .format("json")
                .metadata(Map.of())
                .build());

        assertThatThrownBy(() -> new ConfigArtifactServiceImpl(artifactService).getConfig("artifact-run"))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("CONFIG or REPAIR_CONFIG");
    }

    @Test
    void shouldRejectBlankConfigIdBeforeCallingArtifactService() {
        ArtifactService artifactService = mock(ArtifactService.class);

        assertThatThrownBy(() -> new ConfigArtifactServiceImpl(artifactService).getConfig(" "))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("configId is required");
        verify(artifactService, never()).getArtifact(" ");
    }
}
