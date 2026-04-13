package com.consilens.sink.json;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class JsonDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "json";
    }

    @Override
    public String getType() {
        return "diff-record";
    }

    @Override
    public Sink create() {
        return new JsonDiffRecordSink();
    }
}
