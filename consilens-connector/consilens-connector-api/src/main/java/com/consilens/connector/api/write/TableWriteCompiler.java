package com.consilens.connector.api.write;

public interface TableWriteCompiler {

    TableWritePlan compile(TableWriteCompileRequest request);

    PreparedWriteRow prepareRow(TypedOutputRow row, TableWritePlan plan);
}
