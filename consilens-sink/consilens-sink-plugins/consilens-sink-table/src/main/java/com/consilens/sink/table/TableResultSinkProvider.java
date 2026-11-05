package com.consilens.sink.table;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class TableResultSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "table";
    }

    @Override
    public String getType() {
        return SinkTypes.RESULT;
    }

    @Override
    public Sink create() {
        return new TableResultSink();
    }
}
