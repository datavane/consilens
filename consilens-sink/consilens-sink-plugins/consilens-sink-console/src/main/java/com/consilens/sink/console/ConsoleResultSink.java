package com.consilens.sink.console;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.Sink;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Outputs the final aggregated diff result to the console.
 */
public class ConsoleResultSink implements Sink {

    private ConsoleSinkConfig sinkConfig;

    @Override
    public void open(SinkConfig config, DiffContext context) throws IOException {
        sinkConfig = parseConfig(config.getProperties());
    }

    @Override
    public void onResult(DiffResult result, DiffContext context) {
        if (sinkConfig.isShowStatistics()) {
            String summary = result.getSummary();
            System.out.println("[DIFF RESULT] " + summary);
        }
    }

    @Override
    public void onError(DiffContext context, Throwable error) {
        System.err.println("[DIFF ERROR] taskId=" + context.getTaskId() + " error=" + error.getMessage());
    }

    private ConsoleSinkConfig parseConfig(String properties) throws IOException {
        if (properties == null || properties.isBlank()) {
            return new ConsoleSinkConfig();
        }
        return new ObjectMapper().readValue(properties, ConsoleSinkConfig.class);
    }
}
