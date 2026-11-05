package com.consilens.benchmark.runner;

/**
 * consilens-benchmark 套件入口。解析命令行为 {@link BenchmarkOptions}，
 * 构造 {@link BenchmarkSuite} 按选定模式运行，退出码反映是否回归。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        BenchmarkOptions options = BenchmarkOptions.parse(args);
        System.out.println("benchmark: " + options);
        BenchmarkSuite suite = new BenchmarkSuite(options);
        try {
            int exitCode = suite.run();
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception e) {
            System.err.println("benchmark: execution failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
