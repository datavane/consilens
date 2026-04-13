package com.consilens.sink.console;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * Outputs diff records to the console with streaming writes (immediate print per batch).
 */
@Slf4j
public class ConsoleDiffRecordSink implements Sink {

    private ConsoleSinkConfig sinkConfig;
    private long rowCount = 0;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        sinkConfig = parseConfig(config.getProperties());
        System.out.println("[ConsoleDiffRecordSink] taskId=" + context.getTaskId() + " opened");
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        int limit = sinkConfig.getMaxRows();
        for (DiffRow row : rows) {
            if (limit >= 0 && rowCount >= limit) {
                System.out.println("[ConsoleDiffRecordSink] maxRows=" + limit + " reached, further rows suppressed");
                return;
            }
            System.out.println("[DIFF] " + row.getDescription());
            rowCount++;
        }
    }

    @Override
    public void onSegmentComplete(SegmentResult segmentResult) {
        System.out.println("[ConsoleDiffRecordSink] segment=" + segmentResult.getSegmentIndex()
                + " differencesFound=" + segmentResult.getDifferencesFound());
    }

    private ConsoleSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new ConsoleSinkConfig();
        }
        return new ObjectMapper().readValue(properties, ConsoleSinkConfig.class);
    }
}
