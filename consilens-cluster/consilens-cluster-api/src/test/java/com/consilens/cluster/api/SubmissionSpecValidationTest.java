package com.consilens.cluster.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmissionSpecValidationTest {

    @Test
    void tooSmallContainerIsRejectedInsteadOfProducingAWrongHeap() {
        // 128m 容器：堆保底 64m + 非堆预留 160m 已经超了，给任何 -Xmx 都必然 OOMKilled
        assertThrows(IllegalArgumentException.class,
                () -> ClusterSubmissionParameters.coordinatorHeapMb(128));
        assertTrue(ClusterSubmissionParameters.coordinatorHeapMb(256)
                + 160 <= 256);
    }

    @Test
    void coordinatorHeapAlwaysLeavesRoomForNonHeap() {
        // 大内存：70% 生效
        assertEquals(716, ClusterSubmissionParameters.coordinatorHeapMb(1024));
        // 小内存：非堆预留优先，堆不能顶到容器上限
        assertEquals(352, ClusterSubmissionParameters.coordinatorHeapMb(512));
        assertTrue(ClusterSubmissionParameters.coordinatorHeapMb(256) < 256 - 64);
        // 任何情况下都要比容器内存小（128m 太小，已在上面单独覆盖为报错）
        for (int memory : new int[]{256, 512, 1024, 4096}) {
            assertTrue(ClusterSubmissionParameters.coordinatorHeapMb(memory) < memory,
                    "heap must stay below container memory for " + memory);
        }
    }


    @Test
    void shouldAllowLocalYarnArtifactsWithoutStagingUri() {
        // The submitter falls back to the user's HDFS home staging directory,
        // mirroring spark-submit's default spark.yarn.stagingDir; a missing
        // stagingUri is therefore not a spec-level validation error.
        YarnSubmissionSpec spec = YarnSubmissionSpec.builder()
                .runtimeArchiveUri("runtime.zip")
                .descriptorUri("comparison.yaml")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();

        spec.validate();
    }

    @Test
    void shouldRejectInvalidKubernetesEnvironmentNames() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setEnvs(Map.of("invalid-env-name", "value"));

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRejectInvalidImagePullSecretNames() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setImagePullSecrets(List.of("Not_A_Dns_Label"));

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRejectMultipleKubernetesDescriptorFiles() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("source.yaml", "source: {}");
        descriptorData.put("target.yaml", "target: {}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldAcceptJsonDescriptorWithEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json",
                "{\n  \"source\": {\n    \"password\": \"${env.SOURCE_PASSWORD}\"\n  }\n}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    @Test
    void shouldAcceptCompactJsonDescriptorWithEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\"source\":{\"password\":\"${env.SOURCE_PASSWORD}\"}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    @Test
    void shouldAcceptYamlFlowStyleEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "{source: {password: \"${env.SOURCE_PASSWORD}\"}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    private KubernetesSubmissionSpec validKubernetesSpec() {
        return KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens")
                .image("registry.example/consilens:1.0.0")
                .descriptorUri("https://configs.example/consilens/comparison.yaml")
                .coordinatorMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();
    }
}
