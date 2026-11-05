package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RepairRequest;
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

class RepairTaskHandlerTest {

    @Test
    void shouldNotProposeNoChangeWhenDiagnosisHasNoStatistics() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("diagnosis-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("diagnosis-artifact")
                .artifactType(ArtifactKind.DIAGNOSIS.name())
                .artifactFormat("json")
                .content("{\"statisticsAvailable\":false,\"differenceCount\":0}")
                .build());
        when(artifactService.writeArtifact(any(), eq(ArtifactKind.REPAIR_CONFIG), eq("json"), any(), anyMap()))
                .thenReturn(ArtifactRefDto.builder().id("repair-1").type("REPAIR_CONFIG").build());
        RepairTaskHandler handler = new RepairTaskHandler(artifactService, new ObjectMapper());
        RepairRequest request = new RepairRequest();
        request.setDiagnosisArtifactId("diagnosis-artifact");

        handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request);

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(artifactService).writeArtifact(any(), eq(ArtifactKind.REPAIR_CONFIG), eq("json"),
                content.capture(), anyMap());
        Map<?, ?> repair = (Map<?, ?>) content.getValue();
        assertTrue(String.valueOf(repair.get("proposals")).contains("no comparison statistics"),
                repair.toString());
    }

    @Test
    void shouldRejectNonNumericDifferenceCount() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("diagnosis-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("diagnosis-artifact")
                .artifactType(ArtifactKind.DIAGNOSIS.name())
                .artifactFormat("json")
                .content("{\"differenceCount\":\"bad\"}")
                .build());
        RepairTaskHandler handler = new RepairTaskHandler(artifactService, new ObjectMapper());
        RepairRequest request = new RepairRequest();
        request.setDiagnosisArtifactId("diagnosis-artifact");

        assertThrows(InvalidInputException.class,
                () -> handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request));
    }
}
