package com.consilens.benchmark.data;

import java.sql.SQLException;

/** 大规模 benchmark 数据生成入口。 */
public final class BenchmarkDataMain {

    private BenchmarkDataMain() {
    }

    public static void main(String[] args) {
        try {
            BenchmarkDataOptions options = BenchmarkDataOptions.parse(args);
            BenchmarkDataReport report = new BenchmarkDataGenerator().generate(options);
            System.out.printf("benchmark data ready: source=%d, target=%d, mismatch=%d, sourceMissing=%d, targetMissing=%d%n",
                    report.getSourceRows(), report.getTargetRows(), report.getActualMismatchRows(),
                    report.getActualSourceMissingRows(), report.getActualTargetMissingRows());
        } catch (SQLException | RuntimeException e) {
            System.err.println("benchmark data generation failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
