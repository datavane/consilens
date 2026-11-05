package com.consilens.benchmark.baseline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaselineStoreTest {

    @Test
    void missingFileReturnsEmptyBaseline(@TempDir Path tmp) {
        Baseline baseline = BaselineStore.load(tmp.resolve("missing.json"));
        assertThat(baseline.getEntries()).isEmpty();
        assertThat(baseline.getVersion()).isEqualTo(1);
    }

    @Test
    void corruptFileFailsLoudly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("baseline.json");
        Files.writeString(file, "not-json");

        assertThatThrownBy(() -> BaselineStore.load(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to read baseline");
    }

    @Test
    void nullJsonBaselineFailsLoudly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("baseline-null.json");
        Files.writeString(file, "null");

        assertThatThrownBy(() -> BaselineStore.load(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed to read baseline");
    }

    @Test
    void roundTripPersistsEntries(@TempDir Path tmp) {
        Path file = tmp.resolve("baseline.json");

        Map<String, BaselineEntry> entries = new LinkedHashMap<>();
        entries.put("M01.100000", BaselineEntry.builder()
                .key("M01.100000").score(1234.5).unit("ops/s").threshold(0.10).build());
        entries.put("E02", BaselineEntry.builder()
                .key("E02").score(5600.0).unit("ms").threshold(0.20).build());
        Baseline baseline = new Baseline();
        baseline.setVersion(1);
        baseline.setCreatedAt("2026-08-07T10:00:00+08:00");
        baseline.setJdk("11");
        baseline.setEntries(entries);
        BaselineStore.store(baseline, file);

        Baseline loaded = BaselineStore.load(file);
        assertThat(loaded.getEntries()).hasSize(2);
        BaselineEntry m01 = loaded.getEntries().get("M01.100000");
        assertThat(m01.getScore()).isEqualTo(1234.5);
        assertThat(m01.getUnit()).isEqualTo("ops/s");
        assertThat(m01.getThreshold()).isEqualTo(0.10);
        assertThat(loaded.getJdk()).isEqualTo("11");
    }

    @Test
    void atomicWriteReplacesExisting(@TempDir Path tmp) {
        Path file = tmp.resolve("baseline.json");
        storeEntries(file, "A", 1.0);
        storeEntries(file, "B", 2.0);
        Baseline loaded = BaselineStore.load(file);
        assertThat(loaded.getEntries()).containsOnlyKeys("B");
    }

    private void storeEntries(Path file, String key, double score) {
        Baseline b = new Baseline();
        b.setVersion(1);
        b.setEntries(new LinkedHashMap<>());
        b.getEntries().put(key, BaselineEntry.builder()
                .key(key).score(score).unit("x").threshold(0.1).build());
        BaselineStore.store(b, file);
    }
}
