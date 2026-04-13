package com.consilens.sink.table;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class TableDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "table";
    }

    @Override
    public String getType() {
        return "diff-record";
    }

    @Override
    public Sink create() {
        return new TableDiffRecordSink();
    }
}
