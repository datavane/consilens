package com.consilens.benchmark.data;

import com.consilens.common.io.PathSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** 数据生成与校验报告。 */
@Getter
public final class BenchmarkDataReport {

    private final String generatedAt;
    private final long requestedRows;
    private final long sourceRows;
    private final long targetRows;
    private final long actualMismatchRows;
    private final long actualSourceMissingRows;
    private final long actualTargetMissingRows;
    private final double diffRatio;
    private final double sourceMissingRatio;
    private final double targetMissingRatio;
    private final long seed;
    private String keyDistribution;

    public BenchmarkDataReport(long requestedRows, long sourceRows, long targetRows,
                               long actualMismatchRows, long actualSourceMissingRows,
                               long actualTargetMissingRows, double diffRatio,
                               double sourceMissingRatio, double targetMissingRatio, long seed) {
        this.generatedAt = Instant.now().toString();
        this.requestedRows = requestedRows;
        this.sourceRows = sourceRows;
        this.targetRows = targetRows;
        this.actualMismatchRows = actualMismatchRows;
        this.actualSourceMissingRows = actualSourceMissingRows;
        this.actualTargetMissingRows = actualTargetMissingRows;
        this.diffRatio = diffRatio;
        this.sourceMissingRatio = sourceMissingRatio;
        this.targetMissingRatio = targetMissingRatio;
        this.seed = seed;
    }

    public void write(Path path) {
        try {
            PathSupport.ensureParent(path);
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write data report: " + path, e);
        }
    }

    public long getActualTotalDifferences() {
        return actualMismatchRows + actualSourceMissingRows + actualTargetMissingRows;
    }

    public void setKeyDistribution(String keyDistribution) { this.keyDistribution = keyDistribution; }
}
