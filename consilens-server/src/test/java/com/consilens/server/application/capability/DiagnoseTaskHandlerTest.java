package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnoseTaskHandlerTest {

    @Test
    void shouldNotCallDryRunZeroDifferencesDataEquality() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("run-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("run-artifact")
                .artifactType(ArtifactKind.RUN_RESULT.name())
                .artifactFormat("json")
                .content("{\"success\":true,\"dryRun\":true}")
                .build());
        when(artifactService.writeArtifact(any(), eq(ArtifactKind.DIAGNOSIS), eq("json"), any(), anyMap()))
                .thenReturn(ArtifactRefDto.builder().id("diag-1").type("DIAGNOSIS").build());
        DiagnoseTaskHandler handler = new DiagnoseTaskHandler(artifactService, new ObjectMapper());
        DiagnoseRequest request = new DiagnoseRequest();
        request.setRunArtifactId("run-artifact");

        handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request);

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(artifactService).writeArtifact(any(), eq(ArtifactKind.DIAGNOSIS), eq("json"),
                content.capture(), anyMap());
        Map<?, ?> diagnosis = (Map<?, ?>) content.getValue();
        assertTrue(String.valueOf(diagnosis.get("findings")).contains("dry run"), diagnosis.toString());
        assertTrue(Boolean.FALSE.equals(diagnosis.get("statisticsAvailable")), diagnosis.toString());
    }

    @Test
    void shouldRejectNonNumericDifferenceCount() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("run-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("run-artifact")
                .artifactType(ArtifactKind.RUN_RESULT.name())
                .artifactFormat("json")
                .content("{\"success\":true,\"differenceCount\":\"bad\"}")
                .build());
        DiagnoseTaskHandler handler = new DiagnoseTaskHandler(artifactService, new ObjectMapper());
        DiagnoseRequest request = new DiagnoseRequest();
        request.setRunArtifactId("run-artifact");

        assertThrows(InvalidInputException.class,
                () -> handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request));
    }
}
