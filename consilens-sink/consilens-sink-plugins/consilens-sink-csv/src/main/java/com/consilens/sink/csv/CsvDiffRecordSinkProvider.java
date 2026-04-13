package com.consilens.sink.csv;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class CsvDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "csv";
    }

    @Override
    public String getType() {
        return "diff-record";
    }

    @Override
    public Sink create() {
        return new CsvDiffRecordSink();
    }
}
