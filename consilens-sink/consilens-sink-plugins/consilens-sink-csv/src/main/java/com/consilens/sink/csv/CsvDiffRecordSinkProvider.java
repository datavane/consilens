package com.consilens.sink.csv;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;
import com.consilens.sink.api.SinkTypes;

public class CsvDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "csv";
    }

    @Override
    public String getType() {
        return SinkTypes.DIFF_RECORD;
    }

    @Override
    public Sink create() {
        return new CsvDiffRecordSink();
    }
}
