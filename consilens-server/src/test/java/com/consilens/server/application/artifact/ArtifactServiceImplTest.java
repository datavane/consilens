package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.ArtifactIntegrityException;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.consilens.server.infrastructure.storage.StoredArtifactContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultArtifactServiceTest {

    @Test
    void shouldReadArtifactContentWhenChecksumMatches() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        when(contentStore.read("/tmp/artifact.json")).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(artifactRepository.findById("artifact-1")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("artifact-1")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("/tmp/artifact.json")
                .sha256("4062edaf750fb8074e7e83e0c9028c94e32468a8b6f1614774328ef045150f93")
                .build()));

        ArtifactServiceImpl service = new ArtifactServiceImpl(artifactRepository, contentStore, new com.fasterxml.jackson.databind.ObjectMapper());

        ArtifactContentDto response = service.getArtifactContent("artifact-1");

        assertThat(response.getArtifactId()).isEqualTo("artifact-1");
        assertThat(response.getArtifactType()).isEqualTo("RUN_RESULT");
        assertThat(response.getContent()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void shouldRejectChecksumMismatch() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        when(contentStore.read("/tmp/artifact.json")).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(artifactRepository.findById("artifact-1")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("artifact-1")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("/tmp/artifact.json")
                .sha256("deadbeef")
                .build()));

        ArtifactServiceImpl service = new ArtifactServiceImpl(artifactRepository, contentStore, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> service.getArtifactContent("artifact-1"))
                .isInstanceOf(ArtifactIntegrityException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void shouldWriteArtifactAndReturnReference() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        when(contentStore.write(any(), any(), any(), any())).thenReturn(StoredArtifactContent.builder()
                .storageType("LOCAL_FILE")
                .storageUri("/tmp/artifact-write.json")
                .sha256("sha256")
                .build());
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtifactServiceImpl service = new ArtifactServiceImpl(artifactRepository, contentStore, new com.fasterxml.jackson.databind.ObjectMapper());
        ArtifactRefDto artifact = service.writeArtifact(TaskExecutionContext.builder()
                        .taskId(1L)
                        .instanceKey("task-1")
                        .traceId("trace-1")
                        .nodeKey("node-1")
                        .startTime(Instant.now())
                        .build(),
                ArtifactKind.RUN_RESULT,
                "json",
                Map.of("ok", true),
                Map.of());

        assertThat(artifact.getType()).isEqualTo("RUN_RESULT");
    }
}
