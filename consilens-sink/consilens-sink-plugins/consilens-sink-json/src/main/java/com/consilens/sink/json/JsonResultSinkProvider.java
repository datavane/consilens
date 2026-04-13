package com.consilens.sink.json;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class JsonResultSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "json";
    }

    @Override
    public String getType() {
        return "result";
    }

    @Override
    public Sink create() {
        return new JsonResultSink();
    }
}
