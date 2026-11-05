package com.consilens.sink.jsonl;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class JsonlDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "jsonl";
    }

    @Override
    public String getType() {
        return SinkTypes.DIFF_RECORD;
    }

    @Override
    public Sink create() {
        return new JsonlDiffRecordSink();
    }
}
