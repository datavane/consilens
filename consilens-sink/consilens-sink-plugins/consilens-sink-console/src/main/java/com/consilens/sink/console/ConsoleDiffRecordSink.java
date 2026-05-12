package com.consilens.sink.console;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.SinkConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Outputs diff records to the console with streaming writes (immediate print per batch).
 */
@Slf4j
public class ConsoleDiffRecordSink implements Sink {

    private ConsoleSinkConfig sinkConfig;
    private long rowCount = 0;
    private boolean truncationNoticePrinted = false;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = ConsoleOutputSupport.parseConfig(config.getProperties());
    }

    @Override
    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        int limit = sinkConfig.getMaxRows();
        for (DiffRow row : rows) {
            if (limit >= 0 && rowCount >= limit) {
                if (!truncationNoticePrinted) {
                    ConsoleOutputSupport.printStdout(ConsoleOutputSupport.truncationPayload(context, limit), sinkConfig);
                    truncationNoticePrinted = true;
                }
                return;
            }
            ConsoleOutputSupport.printStdout(ConsoleOutputSupport.diffRecordPayload(row, context), sinkConfig);
            rowCount++;
        }
    }

    @Override
    public void onSegmentComplete(SegmentResult segmentResult) {
        ConsoleOutputSupport.printStdout(ConsoleOutputSupport.segmentPayload(segmentResult), sinkConfig);
    }
}
