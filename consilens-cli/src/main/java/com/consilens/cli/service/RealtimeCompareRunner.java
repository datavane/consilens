package com.consilens.cli.service;

import com.consilens.cli.model.CheckpointStoreConfig;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.StringPairConfig;
import com.consilens.cli.model.TableMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Slf4j
public class RealtimeCompareRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQL_UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DiffService diffService;
    private final CompareRequestFactory requestFactory;
    private final Clock clock;
    private final CheckpointStoreFactory checkpointStoreFactory;

    public RealtimeCompareRunner() {
        this(new DiffService(), new CompareRequestFactory(), Clock.systemUTC(), null);
    }

    RealtimeCompareRunner(DiffService diffService, CompareRequestFactory requestFactory, Clock clock) {
        this(diffService, requestFactory, clock, null);
    }

    RealtimeCompareRunner(DiffService diffService,
                          CompareRequestFactory requestFactory,
                          Clock clock,
                          CheckpointStoreFactory checkpointStoreFactory) {
        this.diffService = diffService;
        this.requestFactory = requestFactory;
        this.clock = clock;
        this.checkpointStoreFactory = checkpointStoreFactory != null ? checkpointStoreFactory : this::createCheckpointStoreInternal;
    }

    public CliDiffResult runLoop(CliConfiguration config) throws Exception {
        if (config.getRealtime() == null || !Boolean.TRUE.equals(config.getRealtime().getEnabled())) {
            throw new IllegalStateException("realtime.enabled must be true");
        }
        Duration interval = config.getRealtime().getInterval() != null && !config.getRealtime().getInterval().isBlank()
                ? Duration.parse(config.getRealtime().getInterval())
                : Duration.parse(config.getRealtime().getWindowSize());
        CliDiffResult lastResult = null;
        try (CheckpointStore checkpointStore = checkpointStoreFactory.create(config)) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    lastResult = runIteration(config, checkpointStore);
                } catch (Exception e) {
                    log.error("Realtime compare iteration failed; next iteration will continue after interval", e);
                }
                try {
                    sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResult != null ? lastResult : emptyResult(config);
    }

    private CliDiffResult runIteration(CliConfiguration config, CheckpointStore checkpointStore) throws Exception {
        if (config.getRealtime() == null || !Boolean.TRUE.equals(config.getRealtime().getEnabled())) {
            throw new IllegalStateException("realtime.enabled must be true");
        }

        Duration watermarkDelay = Duration.parse(config.getRealtime().getWatermarkDelay());
        Duration windowSize = Duration.parse(config.getRealtime().getWindowSize());
        Duration overlap = Duration.parse(config.getRealtime().getOverlap());
        String taskId = requestFactory.create(config).getRealtimeSpec().getTaskId();
        String owner = UUID.randomUUID().toString();
        Instant leaseUntil = clock.instant().plus(maxDuration(windowSize, watermarkDelay));

        Optional<CompareCheckpoint> checkpoint = checkpointStore.load(taskId);
        Instant safeEnd = clock.instant().minus(watermarkDelay);
        Instant start = checkpoint.map(value -> value.getWatermark().minus(overlap)).orElse(safeEnd.minus(windowSize));
        Instant end = safeEnd;
        if (!end.isAfter(start)) {
            return emptyResult(config);
        }

        if (!checkpointStore.tryMarkRunning(taskId, start, end, owner, leaseUntil)) {
            return emptyResult(config);
        }
        try {
            CliConfiguration effectiveConfig = cloneConfig(config);
            applyWindow(effectiveConfig, start, end);
            CliDiffResult result = diffService.performDiff(effectiveConfig);
            checkpointStore.markSucceeded(taskId, end, start, end);
            return result;
        } catch (Exception e) {
            checkpointStore.markFailed(taskId, start, end, e);
            throw e;
        }
    }

    private CheckpointStore createCheckpointStoreInternal(CliConfiguration config) {
        CheckpointStoreConfig checkpointStore = config.getRealtime().getCheckpointStore();
        if ("memory".equalsIgnoreCase(checkpointStore.getType())) {
            return new InMemoryCheckpointStore();
        }
        if (!"table".equalsIgnoreCase(checkpointStore.getType())) {
            throw new IllegalStateException("Unsupported checkpointStore.type: " + checkpointStore.getType());
        }
        Map<String, Object> options = checkpointStore.getOptions() != null
                ? new LinkedHashMap<>(checkpointStore.getOptions())
                : new LinkedHashMap<>();
        String jdbcUrl = stringValue(options.remove("url"));
        String username = stringValue(options.remove("username"));
        String password = stringValue(options.remove("password"));
        String driver = stringValue(options.remove("driver"));
        if (jdbcUrl == null) {
            jdbcUrl = config.getTarget().getUrl();
        }
        if (username == null) {
            username = config.getTarget().getUsername();
        }
        if (password == null) {
            password = config.getTarget().getPassword();
        }
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (entry.getValue() != null) {
                properties.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return new JdbcCheckpointStore(jdbcUrl, properties, checkpointStore.getName(), driver);
    }

    private void applyWindow(CliConfiguration config, Instant start, Instant end) {
        ComparisonConfig comparison = config.getComparison();
        String sourceWindow = mergeWindowPredicate(
                comparison.getFilters() != null ? comparison.getFilters().getSource() : null,
                config.getRealtime().getUpdateColumns().getSource(),
                start,
                end);
        String targetWindow = mergeWindowPredicate(
                comparison.getFilters() != null ? comparison.getFilters().getTarget() : null,
                config.getRealtime().getUpdateColumns().getTarget(),
                start,
                end);
        comparison.setFilters(StringPairConfig.builder()
                .source(sourceWindow)
                .target(targetWindow)
                .build());
    }

    private String mergeWindowPredicate(String baseFilter, String updateColumn, Instant start, Instant end) {
        String window = updateColumn + " >= '" + SQL_UTC_FORMATTER.format(start) + "' AND "
                + updateColumn + " < '" + SQL_UTC_FORMATTER.format(end) + "'";
        if (baseFilter == null || baseFilter.isBlank()) {
            return window;
        }
        return "(" + baseFilter.trim() + ") AND (" + window + ")";
    }

    private Duration maxDuration(Duration left, Duration right) {
        Duration max = left.compareTo(right) >= 0 ? left : right;
        return max.multipliedBy(2);
    }

    private void sleep(Duration interval) throws InterruptedException {
        long millis = Math.max(1000L, interval.toMillis());
        Thread.sleep(millis);
    }

    private CliConfiguration cloneConfig(CliConfiguration config) {
        return OBJECT_MAPPER.convertValue(config, CliConfiguration.class);
    }

    private CliDiffResult emptyResult(CliConfiguration config) {
        return CliDiffResult.builder()
                .strategy(config.getStrategyMode())
                .sourceMissingCount(0)
                .targetMissingCount(0)
                .mismatchCount(0)
                .totalDifferences(0)
                .sourceRowCount(0)
                .targetRowCount(0)
                .differences(new java.util.ArrayList<>())
                .tableMetadata(TableMetadata.builder()
                        .sourceTable(resourceDisplay(config.getSource()))
                        .targetTable(resourceDisplay(config.getTarget()))
                        .sourceColumns(requestFactory.sourceColumns(config))
                        .targetColumns(requestFactory.targetColumns(config))
                        .build())
                .build();
    }

    private String resourceDisplay(com.consilens.cli.model.ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "";
        }
        com.consilens.cli.model.ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        if ("table".equalsIgnoreCase(resource.getType())) {
            return resource.getName() != null ? resource.getName() : "";
        }
        return resource.getPath() != null ? resource.getPath() : "";
    }

    private String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    @FunctionalInterface
    interface CheckpointStoreFactory {
        CheckpointStore create(CliConfiguration config) throws Exception;
    }
}
