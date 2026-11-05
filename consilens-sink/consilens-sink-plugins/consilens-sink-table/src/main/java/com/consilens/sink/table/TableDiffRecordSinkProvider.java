package com.consilens.sink.table;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class TableDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "table";
    }

    @Override
    public String getType() {
        return SinkTypes.DIFF_RECORD;
    }

    @Override
    public Sink create() {
        return new TableDiffRecordSink();
    }
}
