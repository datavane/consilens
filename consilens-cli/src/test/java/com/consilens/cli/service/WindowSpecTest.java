package com.consilens.cli.service;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.connector.api.planner.CompareRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSpecTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCompileWindowIntoBothFilters() throws Exception {
        CliConfiguration config = load(jdbcConfig("table"));

        CompareRequest request = new CompareRequestFactory().create(config,
                WindowSpec.builder()
                        .column("updated_at")
                        .from("2026-01-01 00:00:00")
                        .to("2026-02-01 00:00:00")
                        .build());

        assertThat(request.getSourceFilter().getExpression())
                .isEqualTo("(updated_at >= '2026-01-01 00:00:00'"
                        + " AND updated_at < '2026-02-01 00:00:00')");
        assertThat(request.getTargetFilter().getExpression())
                .isEqualTo("(updated_at >= '2026-01-01 00:00:00'"
                        + " AND updated_at < '2026-02-01 00:00:00')");
    }

    @Test
    void shouldUseSideSpecificColumnsAndMergeExistingFilters() throws Exception {
        CliConfiguration config = load(jdbcConfig("table")
                + "  filters:\n"
                + "    source: tenant_id = 1\n"
                + "    target: tenant_id = 2\n");

        CompareRequest request = new CompareRequestFactory().create(config,
                WindowSpec.builder()
                        .sourceColumn("src_updated_at")
                        .targetColumn("tgt_updated_at")
                        .from("2026-01-01")
                        .to("2026-02-01")
                        .build());

        assertThat(request.getSourceFilter().getExpression())
                .isEqualTo("(tenant_id = 1) AND ((src_updated_at >= '2026-01-01'"
                        + " AND src_updated_at < '2026-02-01'))");
        assertThat(request.getTargetFilter().getExpression())
                .isEqualTo("(tenant_id = 2) AND ((tgt_updated_at >= '2026-01-01'"
                        + " AND tgt_updated_at < '2026-02-01'))");
    }

    @Test
    void shouldRejectFileSources() throws Exception {
        Path source = tempDir.resolve("source.csv");
        Path target = tempDir.resolve("target.csv");
        Files.writeString(source, "id,name\n1,alice\n");
        Files.writeString(target, "id,name\n1,alice\n");
        CliConfiguration config = load("source:\n"
                + "  type: file\n"
                + "  name: source\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    path: " + source + "\n"
                + "target:\n"
                + "  type: file\n"
                + "  name: target\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    path: " + target + "\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name]\n"
                + "    target: [name]\n");

        assertThatThrownBy(() -> new CompareRequestFactory().create(config,
                WindowSpec.builder().column("updated_at").to("2026-02-01").build()))
                .hasMessageContaining("JDBC source");
    }

    @Test
    void shouldValidateBoundsAndColumns() {
        assertThatThrownBy(() -> WindowSpec.builder().column("updated_at").build().validate())
                .hasMessageContaining("window-from");
        assertThatThrownBy(() -> WindowSpec.builder()
                .from("2026-01-01").to("2026-02-01").build().validate())
                .hasMessageContaining("window-column");
    }

    @Test
    void shouldDetectWindowAlreadyCoveredByCheckpoint() {
        assertThat(WindowSpec.builder().from("2026-02-01 00:00:00").to("2026-02-01 00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isTrue();
        assertThat(WindowSpec.builder().from("2026-03-01 00:00:00").to("2026-02-01 00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isTrue();
        assertThat(WindowSpec.builder().from("2026-01-01 00:00:00").to("2026-02-01 00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isFalse();
        assertThat(WindowSpec.builder().to("2026-02-01 00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isFalse();
    }

    @Test
    void shouldCompareWindowBoundsAsTimeNotAsText() {
        // 纯日期 vs 同一时刻的完整时间戳：字典序会把前者判成"未覆盖"，实际窗口是空的
        assertThat(WindowSpec.builder().from("2024-01-02").to("2024-01-02T00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isTrue();
        // 空格分隔的时间戳也要按时间比较
        assertThat(WindowSpec.builder().from("2024-01-02 00:00:00").to("2024-01-02T00:00:00")
                .column("updated_at").build().isAlreadyCovered()).isTrue();
        // 带偏移的同一瞬间
        assertThat(WindowSpec.builder().from("2024-01-02T08:00:00+08:00").to("2024-01-02T00:00:00Z")
                .column("updated_at").build().isAlreadyCovered()).isTrue();
        // 真正未覆盖的窗口不能被误判
        assertThat(WindowSpec.builder().from("2024-01-01T00:00:00").to("2024-01-02T00:00:00Z")
                .column("updated_at").build().isAlreadyCovered()).isFalse();
    }

    @Test
    void shouldRoundTripCheckpointAtomically() throws Exception {
        Path checkpoint = tempDir.resolve("state/watermark.txt");
        assertThat(WindowCheckpoint.read(checkpoint)).isNull();

        WindowCheckpoint.write(checkpoint, "2026-02-01 00:00:00");
        assertThat(WindowCheckpoint.read(checkpoint)).isEqualTo("2026-02-01 00:00:00");

        WindowCheckpoint.write(checkpoint, "2026-03-01 00:00:00");
        assertThat(WindowCheckpoint.read(checkpoint)).isEqualTo("2026-03-01 00:00:00");
    }

    private CliConfiguration load(String yaml) throws Exception {
        Path config = tempDir.resolve("compare.yaml");
        Files.writeString(config, yaml);
        return new ConfigurationManager().loadConfiguration(config.toString());
    }

    private static String jdbcConfig(String resourceType) {
        return "source:\n"
                + "  type: mysql\n"
                + "  name: source\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source_db\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: " + resourceType + "\n"
                + "    name: users\n"
                + "target:\n"
                + "  type: mysql\n"
                + "  name: target\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/target_db\n"
                + "    username: user\n"
                + "    password: pass\n"
                + "  resource:\n"
                + "    type: " + resourceType + "\n"
                + "    name: users\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name]\n"
                + "    target: [name]\n";
    }

    @Test
    void shouldNotTreatMixedTimeShapesAsCovered() {
        // 一侧本地时间、一侧带偏移：按 UTC 硬算会把 08:00 平移掉 8 小时。判不了就退回字符串比较，
        // 即"未覆盖"——多跑一次空窗口可以接受，漏掉差异不行。
        WindowSpec mixed = WindowSpec.builder()
                .from("2024-01-02T00:00:00")
                .to("2024-01-01T16:00:00Z")
                .build();

        assertFalse(mixed.isAlreadyCovered());

        WindowSpec sameShape = WindowSpec.builder()
                .from("2024-01-02T00:00:00Z")
                .to("2024-01-01T16:00:00Z")
                .build();

        assertTrue(sameShape.isAlreadyCovered());
    }
}
