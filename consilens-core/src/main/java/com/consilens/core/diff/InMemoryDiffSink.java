package com.consilens.core.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory diff sink for backward compatibility.
 */
public class InMemoryDiffSink implements DiffSink {

    private final List<DiffRow> differences = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onDiffRow(DiffRow diffRow) {
        if (diffRow != null) {
            differences.add(diffRow);
        }
    }

    public List<DiffRow> getDifferences() {
        return new ArrayList<>(differences);
    }
}
