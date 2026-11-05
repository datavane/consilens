package com.consilens.sdk.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineDiffReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadCountsAndIgnoreBlankLines() throws Exception {
        Path baseline = tempDir.resolve("baseline.jsonl");
        Files.writeString(baseline, "{\"operation\":\"mismatch\",\"primaryKey\":[1],\"metadata\":{}}\n"
                + "\n"
                + "{\"operation\":\"mismatch\",\"primaryKey\":[1],\"metadata\":{}}\n");

        Map<String, Integer> counts = BaselineDiffReader.readCounts(baseline);

        assertThat(counts).hasSize(1);
        assertThat(counts.values()).containsExactly(2);
    }

    @Test
    void shouldAcceptScalarAndObjectPrimaryKeys() throws Exception {
        // 手写或外部工具产出的 JSONL 可能把主键写成标量/对象；不能因此让整次校验崩掉
        Path baseline = tempDir.resolve("odd.jsonl");
        Files.writeString(baseline, "{\"operation\":\"mismatch\",\"primaryKey\":\"abc\"}\n"
                + "{\"operation\":\"mismatch\",\"primaryKey\":{\"id\":7}}\n"
                + "{\"operation\":\"mismatch\"}\n");

        Map<String, Integer> counts = BaselineDiffReader.readCounts(baseline);

        assertThat(counts).hasSize(3);
        assertThat(counts.keySet()).anySatisfy(key -> assertThat(key).contains("[abc]"));
        assertThat(counts.keySet()).anySatisfy(key -> assertThat(key).contains("id=7"));
    }

    @Test
    void shouldNormalizeOperationNames() throws Exception {
        Path legacy = tempDir.resolve("legacy.jsonl");
        Files.writeString(legacy, "{\"operation\":\"MISMATCH\",\"primaryKey\":[1]}\n");

        assertThat(BaselineDiffReader.readCounts(legacy).keySet())
                .contains("mismatch|[1]|");
    }
}
