package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.support.crypto.AesGcmSecretProtector;
import com.consilens.server.support.crypto.SecretProtectorTestKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanTaskHandlerTest {

    @Test
    void shouldPersistConnectionPasswordsEncrypted() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"), any(), anyMap()))
                .thenReturn(ArtifactRefDto.builder().id("artifact-1").type("CONFIG").format("json")
                        .metadata(Map.of()).build());
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        PlanTaskHandler handler = new PlanTaskHandler(artifactService,
                new ServerCompareConfigService(artifactService, new ObjectMapper()), protector);

        handler.handle(TaskExecutionContext.builder().instanceKey("instance-1").build(), request());

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(artifactService).writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"),
                content.capture(), anyMap());
        ServerCompareConfig config = (ServerCompareConfig) content.getValue();

        String endpointPassword = String.valueOf(config.getSource().getConnection().get("password"));
        assertTrue(endpointPassword.startsWith("v2:"), endpointPassword);
        assertEquals("source-secret", protector.reveal(endpointPassword));

        String hintPassword = String.valueOf(config.getHints().get("sourcePassword"));
        assertTrue(hintPassword.startsWith("v2:"), hintPassword);
        assertEquals("source-secret", protector.reveal(hintPassword));

        assertEquals("target-secret", protector.reveal(
                String.valueOf(config.getTarget().getConnection().get("password"))));
    }

    @Test
    void shouldEncryptEveryCredentialShapedConnectionKey() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"), any(), anyMap()))
                .thenReturn(ArtifactRefDto.builder().id("artifact-1").type("CONFIG").format("json")
                        .metadata(Map.of()).build());
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        PlanTaskHandler handler = new PlanTaskHandler(artifactService,
                new ServerCompareConfigService(artifactService, new ObjectMapper()), protector);

        PlanRequest request = request();
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = new LinkedHashMap<>(
                (Map<String, Object>) request.getHints().get("sourceConnection"));
        // 这些键同样出现在 JDBC 连接参数里，只加密 password 会让它们以明文进 CONFIG artifact
        connection.put("sslPassword", "ssl-secret");
        connection.put("accessToken", "token-secret");
        request.getHints().put("sourceConnection", connection);

        handler.handle(TaskExecutionContext.builder().instanceKey("instance-1").build(), request);

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(artifactService).writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"),
                content.capture(), anyMap());
        ServerCompareConfig config = (ServerCompareConfig) content.getValue();

        Map<?, ?> persisted = config.getSource().getConnection();
        assertEquals("ssl-secret", protector.reveal(String.valueOf(persisted.get("sslPassword"))));
        assertEquals("token-secret", protector.reveal(String.valueOf(persisted.get("accessToken"))));
        // 非凭据字段保持原样
        assertEquals("jdbc:mysql://localhost:3306/source", persisted.get("url"));
    }

    @Test
    void shouldNotDoubleEncryptAlreadyProtectedPasswords() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"), any(), anyMap()))
                .thenReturn(ArtifactRefDto.builder().id("artifact-1").type("CONFIG").format("json")
                        .metadata(Map.of()).build());
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        PlanTaskHandler handler = new PlanTaskHandler(artifactService,
                new ServerCompareConfigService(artifactService, new ObjectMapper()), protector);
        PlanRequest request = request();
        String protectedPassword = protector.protect("source-secret");
        request.getHints().put("sourcePassword", protectedPassword);

        handler.handle(TaskExecutionContext.builder().instanceKey("instance-1").build(), request);

        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(artifactService).writeArtifact(any(), eq(ArtifactKind.CONFIG), eq("json"),
                content.capture(), anyMap());
        ServerCompareConfig config = (ServerCompareConfig) content.getValue();
        // endpoint 的 connection 仍来自明文 hint，因此会被加密一次；已加密的 hint 保持原样
        assertEquals(protectedPassword, config.getHints().get("sourcePassword"));
    }

    @Test
    void shouldRejectSensitivePropertiesInPlanConnections() {
        ArtifactService artifactService = mock(ArtifactService.class);
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        PlanTaskHandler handler = new PlanTaskHandler(artifactService,
                new ServerCompareConfigService(artifactService, new ObjectMapper()), protector);
        PlanRequest request = request();
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceConnection =
                (Map<String, Object>) request.getHints().get("sourceConnection");
        sourceConnection.put("properties", "useSSL=false&password=source-secret");

        assertThrows(InvalidInputException.class,
                () -> handler.handle(TaskExecutionContext.builder().instanceKey("instance-1").build(), request));
        verify(artifactService, org.mockito.Mockito.never())
                .writeArtifact(any(), any(), any(), any(), anyMap());
    }

    private PlanRequest request() {
        PlanRequest request = new PlanRequest();
        request.setGoal("compare orders");
        request.setSource(endpoint("mysql", "orders"));
        request.setTarget(endpoint("mysql", "orders_copy"));
        request.setKeys(List.of("id"));
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("sourcePassword", "source-secret");
        hints.put("targetPassword", "target-secret");
        Map<String, Object> sourceConnection = new LinkedHashMap<>();
        sourceConnection.put("url", "jdbc:mysql://localhost:3306/source");
        sourceConnection.put("username", "reader");
        sourceConnection.put("password", "source-secret");
        hints.put("sourceConnection", sourceConnection);
        Map<String, Object> targetConnection = new LinkedHashMap<>();
        targetConnection.put("url", "jdbc:mysql://localhost:3306/target");
        targetConnection.put("username", "reader");
        targetConnection.put("password", "target-secret");
        hints.put("targetConnection", targetConnection);
        request.setHints(hints);
        return request;
    }

    private PlanRequest.Endpoint endpoint(String type, String table) {
        PlanRequest.Endpoint endpoint = new PlanRequest.Endpoint();
        endpoint.setType(type);
        endpoint.setTable(table);
        return endpoint;
    }
}
