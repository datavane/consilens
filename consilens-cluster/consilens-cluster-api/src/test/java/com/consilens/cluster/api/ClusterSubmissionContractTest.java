package com.consilens.cluster.api;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.connector.api.planner.KeyRangeSplit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterSubmissionContractTest {

    @Test
    void shouldSerializeSubmissionReceiptAcrossProcessBoundary() throws Exception {
        ClusterSubmission submission = ClusterSubmission.builder()
                .submissionId("submission-42")
                .executionMode(ExecutionMode.LOCAL)
                .submittedAt(Instant.parse("2026-08-23T00:00:00Z"))
                .splitCount(3)
                .build();

        assertEquals(submission, deserialize(serialize(submission)));
    }

    @Test
    void shouldRoundTripRedactedSubmissionRequestWithDecimalOpenKeyRangeSplit() throws Exception {
        String sourcePassword = "source-password-must-not-leak";
        String executionAttributeSecret = "execution-attribute-must-not-leak";
        CompareRequest compareRequest = CompareRequest.builder()
                .source(ConnectorConfig.builder().connection(Map.of("password", sourcePassword)).build())
                .clusterExecutionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.KUBERNETES)
                        .parallelism(4)
                        .maxAttempts(3)
                        .kubernetesNamespace("data-quality")
                        .kubernetesImage("registry.example/consilens:phase0")
                        .attributes(Map.of("token", executionAttributeSecret))
                        .build())
                .build();
        ClusterSubmitRequest request = ClusterSubmitRequest.from(
                "submission-43",
                compareRequest,
                ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build());

        byte[] payload = serialize(request);
        ClusterSubmitRequest restored = deserializeRequest(payload);

        assertEquals(request, restored);
        restored.validate();
        assertFalse(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1).contains(sourcePassword));
        assertFalse(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(executionAttributeSecret));
    }

    @Test
    void shouldRoundTripPlannedKeyRangeSplitSeparatelyFromSubmission() throws Exception {
        ClusterPlannedSplit plannedSplit = ClusterPlannedSplit.builder()
                .submissionId("submission-43")
                .keyRangeSplit(ClusterKeyRangeSplit.from("key-range-0", KeyRangeSplit.builder()
                        .startKey(List.of(new BigDecimal("100.125")))
                        .endKey(null)
                        .build()))
                .build();

        ClusterPlannedSplit restored = deserializePlannedSplit(serialize(plannedSplit));

        assertEquals(plannedSplit, restored);
        restored.validate();
        assertEquals(List.of(new BigDecimal("100.125")), restored.getKeyRangeSplit().toKeyRangeSplit().getStartKey());
        assertNull(restored.getKeyRangeSplit().toKeyRangeSplit().getEndKey());
    }

    @Test
    void shouldRejectInvalidOrCompositeKeyRanges() {
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.from("range-0", KeyRangeSplit.builder()
                .startKey(null)
                .endKey(null)
                .build()));
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.from("range-1", KeyRangeSplit.builder()
                .startKey(List.of(1L, 2L))
                .endKey(List.of(3L))
                .build()));
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.from("range-2", KeyRangeSplit.builder()
                .startKey(List.of(1L))
                .endKey(List.of("2"))
                .build()));
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.builder()
                .endKey(new BigDecimal("200"))
                .build()
                .toKeyRangeSplit());
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.from("range-3", KeyRangeSplit.builder()
                .startKey(List.of(new BigDecimal("100")))
                .endKey(List.of(new BigDecimal("100")))
                .build()));
        assertThrows(IllegalArgumentException.class, () -> ClusterKeyRangeSplit.builder()
                .startKey(new BigDecimal("100"))
                .endKey(new BigDecimal("99.999"))
                .build()
                .toKeyRangeSplit());
    }

    @Test
    void shouldAllowOpenEndedKeyRange() {
        KeyRangeSplit split = ClusterKeyRangeSplit.builder()
                .startKey(new BigDecimal("100"))
                .endKey(null)
                .build()
                .toKeyRangeSplit();

        assertEquals(List.of(new BigDecimal("100")), split.getStartKey());
        assertNull(split.getEndKey());
    }

    @Test
    void shouldAllowSubmitterToReceivePortableSubmissionRequest() {
        ClusterSubmitter submitter = new ClusterSubmitter() {
            @Override
            public ClusterSubmission submit(ClusterSubmitRequest request) {
                request.validate();
                return ClusterSubmission.builder()
                        .submissionId("local-1")
                        .executionMode(ExecutionMode.LOCAL)
                        .splitCount(0)
                        .build();
            }
        };

        ClusterSubmission submission = submitter.submit(ClusterSubmitRequest.builder()
                .submissionId("local-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build())
                .execution(ClusterExecutionDescription.builder().executionMode(ExecutionMode.LOCAL).build())
                .build());

        assertEquals(0, submission.getSplitCount());
    }

    private byte[] serialize(ClusterSubmission submission) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(submission);
        }
        return bytes.toByteArray();
    }

    private ClusterSubmission deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ClusterSubmission) input.readObject();
        }
    }

    private byte[] serialize(ClusterSubmitRequest request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(request);
        }
        return bytes.toByteArray();
    }

    private ClusterSubmitRequest deserializeRequest(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ClusterSubmitRequest) input.readObject();
        }
    }

    private byte[] serialize(ClusterPlannedSplit plannedSplit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(plannedSplit);
        }
        return bytes.toByteArray();
    }

    private ClusterPlannedSplit deserializePlannedSplit(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ClusterPlannedSplit) input.readObject();
        }
    }
}
