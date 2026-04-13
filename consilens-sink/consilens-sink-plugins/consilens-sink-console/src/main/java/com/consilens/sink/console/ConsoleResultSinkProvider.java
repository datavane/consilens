package com.consilens.sink.console;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class ConsoleResultSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "console";
    }

    @Override
    public String getType() {
        return "result";
    }

    @Override
    public Sink create() {
        return new ConsoleResultSink();
    }
}
