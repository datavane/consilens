package com.consilens.benchmark.runner;

import com.consilens.benchmark.micro.FileDiffBenchmark;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;

/**
 * Standalone entry point for the local file/Parquet benchmark. Kept separate
 * from {@link BenchmarkSuite} so the database-oriented suite and its baseline
 * stay unchanged.
 */
public final class FileBenchmarkMain {

    private FileBenchmarkMain() {
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(FileDiffBenchmark.class.getName())
                .forks(1)
                .warmupIterations(1)
                .measurementIterations(1)
                .shouldFailOnError(true)
                .build();
        Collection<RunResult> results = new Runner(options).run();
        System.out.println("benchmark,rows,format,diffRatio,score,unit");
        for (RunResult result : results) {
            System.out.printf("%s,%s,%s,%s,%.4f,%s%n",
                    FileDiffBenchmark.class.getSimpleName(),
                    result.getParams().getParam("rows"),
                    result.getParams().getParam("format"),
                    result.getParams().getParam("diffRatio"),
                    result.getPrimaryResult().getScore(),
                    result.getPrimaryResult().getScoreUnit());
        }
    }
}
