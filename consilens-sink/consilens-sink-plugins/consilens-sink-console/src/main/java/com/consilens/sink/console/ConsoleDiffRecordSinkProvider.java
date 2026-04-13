package com.consilens.sink.console;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class ConsoleDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "console";
    }

    @Override
    public String getType() {
        return "diff-record";
    }

    @Override
    public Sink create() {
        return new ConsoleDiffRecordSink();
    }
}
