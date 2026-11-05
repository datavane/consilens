package com.consilens.connector.testkit;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reusable contract checks for connector implementations.
 *
 * <p>Usage from a connector's test suite:
 *
 * <pre>
 *   CertificationReport report = ConnectorCertification.verify(provider, config, keySpec, 100);
 *   assertTrue(report.isPassed(), report.failures().toString());
 * </pre>
 *
 * <p>Checks are deliberately shallow: they verify the SPI contract (open,
 * schema, scanner, deterministic scan, close) rather than dialect-specific SQL
 * results, which belong to each connector's integration suite.
 */
public final class ConnectorCertification {

    public static final int DEFAULT_MAX_ROWS = 100;

    private ConnectorCertification() {
    }

    public static CertificationReport verify(ConnectorProvider provider, ConnectorConfig config) {
        return verify(provider, config, null, DEFAULT_MAX_ROWS);
    }

    public static CertificationReport verify(ConnectorProvider provider, ConnectorConfig config,
                                             KeySpec keySpec, int maxRows) {
        CertificationReport report = new CertificationReport(
                provider == null ? null : provider.getType(), resource(config));
        if (provider == null || config == null) {
            report.add("arguments", false, "provider and config are required");
            return report;
        }
        if (config.getType() == null || !config.getType().equalsIgnoreCase(provider.getType())) {
            report.add("provider-type", false, "provider type '" + provider.getType()
                    + "' does not match config type '" + config.getType() + "'");
            return report;
        }
        report.add("provider-type", true, provider.getType());

        ConnectorAdapter adapter = null;
        DatasetHandle dataset = null;
        try {
            try {
                adapter = provider.create(config);
                report.add("adapter.create", adapter != null, adapter == null ? "null" : "ok");
            } catch (Exception | LinkageError error) {
                report.add("adapter.create", false, message(error));
                return report;
            }

            try {
                dataset = adapter.openDataset(config.getResource(), config.getReadOptions());
                report.add("dataset.open", dataset != null, dataset == null ? "null" : "ok");
            } catch (Exception | LinkageError error) {
                report.add("dataset.open", false, message(error));
                return report;
            }

            SchemaDescriptor schema;
            try {
                schema = dataset.getSchema();
            } catch (Exception | LinkageError error) {
                report.add("schema", false, message(error));
                return report;
            }
            if (!schemaValid(schema, report)) {
                return report;
            }

            DatasetMetadata metadata;
            try {
                metadata = dataset.getMetadata();
                report.add("metadata", metadata != null, metadata == null ? "null" : "ok");
            } catch (Exception | LinkageError error) {
                report.add("metadata", false, message(error));
            }

            Optional<RecordScanner> scanner;
            try {
                scanner = dataset.getRecordScanner();
                report.add("record-scanner", scanner != null && scanner.isPresent(),
                        scanner == null || !scanner.isPresent() ? "not provided" : "ok");
            } catch (Exception | LinkageError error) {
                report.add("record-scanner", false, message(error));
                return report;
            }
            if (scanner == null || !scanner.isPresent()) {
                return report;
            }

            List<String> columns = columns(schema);
            long first;
            try {
                first = scan(scanner.get(), config, dataset, schema, keySpec, columns, maxRows);
                report.setRecordsRead(first);
                report.add("scan", true, first + " rows");
            } catch (Exception | LinkageError error) {
                report.add("scan", false, message(error));
                return report;
            }

            try {
                long second = scan(scanner.get(), config, dataset, schema, keySpec, columns, maxRows);
                report.add("scan.deterministic", first == second,
                        "first=" + first + ", second=" + second);
            } catch (Exception | LinkageError error) {
                report.add("scan.deterministic", false, message(error));
            }
        } finally {
            closeQuietly(dataset, report);
            closeQuietly(adapter, report);
        }
        return report;
    }

    private static boolean schemaValid(SchemaDescriptor schema, CertificationReport report) {
        if (schema == null || schema.getFields() == null || schema.getFields().isEmpty()) {
            report.add("schema", false, "empty schema");
            return false;
        }
        Set<Integer> ordinals = new HashSet<>();
        for (FieldDescriptor field : schema.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                report.add("schema", false, "field without name");
                return false;
            }
            if (field.getCanonicalType() == null || field.getCanonicalType().isBlank()) {
                report.add("schema", false, "field '" + field.getName() + "' has no canonical type");
                return false;
            }
            if (field.getOrdinal() != null && !ordinals.add(field.getOrdinal())) {
                report.add("schema", false, "duplicate ordinal " + field.getOrdinal());
                return false;
            }
        }
        report.add("schema", true, schema.getFields().size() + " fields");
        return true;
    }

    private static long scan(RecordScanner scanner, ConnectorConfig config, DatasetHandle dataset,
                             SchemaDescriptor schema, KeySpec keySpec, List<String> columns,
                             int maxRows) throws Exception {
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(config.getResource())
                .keySpec(keySpec)
                .comparisons(ComparisonSpec.builder().fields(columns).build())
                .split(OffsetLimitSplit.builder().offset(0)
                        .limit(maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows).build())
                .schema(schema)
                .side("certification")
                .build();
        long count = 0;
        int limit = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        try (CloseableIterator<CanonicalRecord> iterator = scanner.scan(segment)) {
            if (iterator == null) {
                throw new IllegalStateException("RecordScanner returned null iterator");
            }
            while (iterator.hasNext() && count < limit) {
                CanonicalRecord record = iterator.next();
                if (record == null) {
                    throw new IllegalStateException("RecordScanner returned a null record");
                }
                if (record.getValues() == null || record.getValues().isEmpty()) {
                    throw new IllegalStateException("Record has no values");
                }
                count++;
            }
        }
        return count;
    }

    private static List<String> columns(SchemaDescriptor schema) {
        List<String> columns = new ArrayList<>();
        for (FieldDescriptor field : schema.getFields()) {
            columns.add(field.getName());
        }
        return columns;
    }

    private static String resource(ConnectorConfig config) {
        if (config == null || config.getResource() == null) {
            return null;
        }
        String name = config.getResource().getName();
        return name != null && !name.isBlank() ? name : config.getResource().getPath();
    }

    private static void closeQuietly(AutoCloseable closeable, CertificationReport report) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
            report.add("close." + closeable.getClass().getSimpleName(), true, "ok");
        } catch (Exception | LinkageError error) {
            report.add("close." + closeable.getClass().getSimpleName(), false, message(error));
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
