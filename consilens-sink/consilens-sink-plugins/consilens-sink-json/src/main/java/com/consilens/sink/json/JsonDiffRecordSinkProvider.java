package com.consilens.sink.json;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class JsonDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "json";
    }

    @Override
    public String getType() {
        return SinkTypes.DIFF_RECORD;
    }

    @Override
    public Sink create() {
        return new JsonDiffRecordSink();
    }
}
