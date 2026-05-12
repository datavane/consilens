package com.consilens.sink.console;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.SinkConfig;

/**
 * Outputs the final aggregated diff result to the console.
 */
public class ConsoleResultSink implements Sink {

    private ConsoleSinkConfig sinkConfig;

    @Override
    public void open(SinkConfig config, DiffContext context) throws Exception {
        sinkConfig = ConsoleOutputSupport.parseConfig(config.getProperties());
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) {
        if (sinkConfig.isShowStatistics()) {
            ConsoleOutputSupport.printStdout(ConsoleOutputSupport.resultPayload(result, context, true), sinkConfig);
        }
    }

    @Override
    public void onError(DiffContext context, Throwable error) {
        ConsoleOutputSupport.printStderr(ConsoleOutputSupport.errorPayload(context, error), sinkConfig);
    }
}
