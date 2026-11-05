package com.consilens.sink.json;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class JsonResultSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "json";
    }

    @Override
    public String getType() {
        return SinkTypes.RESULT;
    }

    @Override
    public Sink create() {
        return new JsonResultSink();
    }
}
