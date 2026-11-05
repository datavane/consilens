package com.consilens.connector.api.planner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompareRequestClusterExecutionSpecTest {

    @Test
    void shouldKeepClusterExecutionSpecOptionalForExistingRequestBuilders() {
        CompareRequest existingRequest = CompareRequest.builder().build();

        assertNull(existingRequest.getClusterExecutionSpec());
    }

    @Test
    void shouldExposeTypedClusterExecutionSpecOnCompareRequest() {
        ClusterExecutionSpec executionSpec = ClusterExecutionSpec.builder()
                .executionMode(ExecutionMode.KUBERNETES)
                .parallelism(4)
                .maxAttempts(3)
                .kubernetesNamespace("data-quality")
                .kubernetesImage("registry.example/consilens:phase0")
                .attributes(Map.of("serviceAccount", "consilens-worker"))
                .build();

        CompareRequest request = CompareRequest.builder()
                .clusterExecutionSpec(executionSpec)
                .build();

        assertEquals(executionSpec, request.getClusterExecutionSpec());
    }

    @Test
    void shouldSerializeClusterExecutionSpecForRemoteSubmission() throws Exception {
        ClusterExecutionSpec executionSpec = ClusterExecutionSpec.builder()
                .executionMode(ExecutionMode.YARN)
                .parallelism(2)
                .maxAttempts(2)
                .yarnQueue("quality")
                .attributes(Map.of("applicationTag", "reconciliation"))
                .build();

        assertEquals(executionSpec, deserialize(serialize(executionSpec)));
    }

    private byte[] serialize(ClusterExecutionSpec executionSpec) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(executionSpec);
        }
        return bytes.toByteArray();
    }

    private ClusterExecutionSpec deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ClusterExecutionSpec) input.readObject();
        }
    }
}
