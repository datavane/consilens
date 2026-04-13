package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects time drift patterns where timestamps differ by a consistent offset.
 */
public class TimeDriftPattern implements DiffPattern {

    @Override
    public String getName() {
        return "TIME_DRIFT";
    }

    @Override
    public String getDescription() {
        return "Detects consistent timestamp differences that indicate time zone or clock drift issues";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        long timeDriftCount = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasTimestampColumns)
                .count();

        if (timeDriftCount == 0) {
            return Optional.empty();
        }

        List<String> affectedCols = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .filter(this::hasTimestampColumns)
                .flatMap(r -> r.getColumnNames1().stream())
                .filter(c -> c.toLowerCase().contains("time") || c.toLowerCase().contains("date") || c.toLowerCase().contains("ts"))
                .distinct()
                .collect(Collectors.toList());

        double confidence = Math.min(0.9, 0.5 + (timeDriftCount * 0.1));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("TIME_DRIFT")
                .description("Detected " + timeDriftCount + " rows with potential time drift in timestamp columns")
                .affectedRows((int) timeDriftCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Check time zone settings and synchronize clocks between source and target databases")
                .build());
    }

    private boolean hasTimestampColumns(DiffRow row) {
        return row.getColumnNames1().stream()
                .anyMatch(c -> c.toLowerCase().contains("time")
                        || c.toLowerCase().contains("date")
                        || c.toLowerCase().contains("ts")
                        || c.toLowerCase().contains("created")
                        || c.toLowerCase().contains("updated"));
    }
}
