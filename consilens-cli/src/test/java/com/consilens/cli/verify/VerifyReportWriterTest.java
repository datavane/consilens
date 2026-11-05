package com.consilens.cli.verify;

import com.consilens.sdk.verify.PolicyCheck;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldListWithinToleranceRowsInMarkdownReport() throws Exception {
        VerifyResult result = VerifyResult.builder()
                .verdict(Verdict.PASS)
                .exitCode(0)
                .summary(com.consilens.core.diff.DiffResult.DiffStatistics.builder()
                        .sourceRowCount(1)
                        .targetRowCount(1)
                        .totalDifferences(0)
                        .withinToleranceCount(1)
                        .build())
                .withinToleranceDifferences(List.of(com.consilens.core.diff.DiffRow.withinTolerance(List.of(1), List.of("1", "10.500000"), List.of("1", "10.504000"),
                        List.of("id", "amount"), List.of("id", "amount"),
                        List.of("amount"), List.of("amount"), java.util.Map.of("amount", 0.004))))
                .build();

        List<Path> written = VerifyReportWriter.write(result, List.of("markdown"), tempDir);

        String markdown = Files.readString(written.get(0), StandardCharsets.UTF_8);
        assertThat(markdown).contains("withinToleranceCount: 1");
        assertThat(markdown).contains("容差内（未计入差异）");
        assertThat(markdown).contains("amount");
    }

    @Test
    void shouldEscapePipesAndNewlinesInWithinToleranceTable() throws Exception {
        VerifyResult result = VerifyResult.builder()
                .verdict(Verdict.PASS)
                .exitCode(0)
                .summary(com.consilens.core.diff.DiffResult.DiffStatistics.builder()
                        .sourceRowCount(1)
                        .targetRowCount(1)
                        .totalDifferences(0)
                        .withinToleranceCount(1)
                        .build())
                .withinToleranceDifferences(List.of(com.consilens.core.diff.DiffRow.withinTolerance(
                        List.of("a|b"), List.of("x\ny"), List.of("x\ny"),
                        List.of("amount"), List.of("amount"),
                        List.of("amount"), List.of("amount"), java.util.Map.of("amount", 0.004))))
                .build();

        List<Path> written = VerifyReportWriter.write(result, List.of("markdown"), tempDir);

        String markdown = Files.readString(written.get(0), StandardCharsets.UTF_8);
        assertThat(markdown).contains("a\\|b");
        assertThat(markdown).doesNotContain("a|b");
        // 表格行不能被换行截断：原值里的 \n 必须变成空格
        assertThat(markdown.lines().filter(line -> line.startsWith("| a")).count()).isEqualTo(1);
    }

    @Test
    void shouldRenderInconclusiveChecksAsSkippedWithReason() throws Exception {
        VerifyResult result = VerifyResult.builder()
                .verdict(Verdict.INCONCLUSIVE)
                .exitCode(3)
                .checks(List.of(
                        PolicyCheck.builder().name("anyDifference").passed(true).build(),
                        PolicyCheck.builder().name("maxNewDifferences").passed(false)
                                .actual(2L).limit(0L).build(),
                        PolicyCheck.builder().name("baseline").passed(null)
                                .message("failOn=new requires a baseline file").build()))
                .message("not evaluable: baseline: failOn=new requires a baseline file")
                .build();

        List<Path> written = VerifyReportWriter.write(result, List.of("junit"), tempDir);

        String xml = Files.readString(written.get(0), StandardCharsets.UTF_8);
        // 不可评估不能渲染成通过的 testcase：必须是 skipped 且带原因
        assertThat(xml).contains("<skipped message=\"failOn=new requires a baseline file\"/>");
        assertThat(xml).contains("failures=\"1\"");
        assertThat(xml).contains("skipped=\"1\"");
        assertThat(xml.split("<failure ", -1).length - 1).isEqualTo(1);
    }
}
