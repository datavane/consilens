package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.capability.CapabilitySet;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * DatasetHandle for one or more local CSV/JSONL/Parquet files. A resource path
 * may point to a file, a directory, a glob or an {@code s3://}/{@code s3a://}
 * location. S3 objects are downloaded to a temporary directory and deleted when
 * the handle is closed.
 */
public class FileDatasetHandle implements DatasetHandle {

    private final ResourceLocator resource;
    private final ReadOptions readOptions;
    private final String location;
    private final List<Path> paths;
    private final Path tempDir;
    private final FileOptions options;
    private volatile SchemaDescriptor schema;

    public FileDatasetHandle(ResourceLocator resource, ReadOptions readOptions) {
        this(resource, readOptions, null);
    }

    /**
     * @param gateway optional S3 gateway for tests; production callers pass null
     */
    FileDatasetHandle(ResourceLocator resource, ReadOptions readOptions, S3Gateway gateway) {
        if (resource == null) {
            throw new ConnectorException("File resource is required");
        }
        String location = locate(resource);
        if (location == null || location.isBlank()) {
            throw new ConnectorException("File resource.path or resource.name is required");
        }
        Path temp = null;
        List<Path> resolved;
        try {
            if (S3Location.isS3(location)) {
                temp = Files.createTempDirectory("consilens-s3-");
                S3Gateway actual = gateway != null ? gateway : AwsS3Gateway.from(resource);
                try (S3Downloader downloader = new S3Downloader(actual,
                        S3Options.maxObjectBytes(resource), S3Options.maxTotalBytes(resource))) {
                    resolved = downloader.download(location, temp);
                }
            } else {
                resolved = FilePaths.resolveAll(location);
            }
        } catch (IOException exception) {
            deleteRecursively(temp);
            throw new ConnectorException("Failed to prepare S3 temp directory: "
                    + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            deleteRecursively(temp);
            throw exception;
        }
        this.resource = resource;
        this.readOptions = readOptions;
        this.location = location;
        this.tempDir = temp;
        try {
            FileOptions resolvedOptions = FileOptions.from(resource, readOptions,
                    resolved.get(0).toString());
            if (!resolvedOptions.explicitFormat()) {
                resolvedOptions = resolvedOptions.withFormat(
                        FilePaths.detectCommonFormat(resolved, location));
            }
            this.options = resolvedOptions;
            boolean explicitFile = S3Location.isS3(location)
                    ? S3Location.parse(location).isSingleObject()
                    : Files.isRegularFile(java.nio.file.Paths.get(location));
            this.paths = FilePaths.filterByFormat(resolved, options.format(), explicitFile);
        } catch (RuntimeException exception) {
            // 下载已经发生：后续的 option 解析/格式探测/格式过滤失败时必须回收临时目录，
            // 否则每次失败都在 /tmp 下留一份完整的 S3 数据。
            deleteRecursively(temp);
            throw exception;
        }
    }

    private static String locate(ResourceLocator resource) {
        if (resource.getPath() != null && !resource.getPath().isBlank()) {
            return resource.getPath();
        }
        return resource.getName();
    }

    @Override
    public ResourceLocator getResource() {
        return resource;
    }

    @Override
    public DatasetMetadata getMetadata() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("format", options.format().name());
        attributes.put("path", location);
        attributes.put("fileCount", paths.size());
        return DatasetMetadata.builder()
                .logicalName(paths.get(0).getFileName() == null ? location
                        : paths.get(0).getFileName().toString())
                .capabilities(new CapabilitySet(EnumSet.of(
                        ConnectorCapability.SCHEMA_DISCOVERY,
                        ConnectorCapability.CURSOR_SPLIT,
                        ConnectorCapability.ORDERED_SCAN,
                        ConnectorCapability.STREAM_SCAN,
                        ConnectorCapability.PROJECTION_PUSHDOWN)))
                .attributes(attributes)
                .build();
    }

    @Override
    public SchemaDescriptor getSchema() {
        SchemaDescriptor current = schema;
        if (current == null) {
            synchronized (this) {
                if (schema == null) {
                    schema = FileSchemaInferrer.infer(paths, options);
                }
                current = schema;
            }
        }
        return current;
    }

    @Override
    public Optional<RecordScanner> getRecordScanner() {
        return Optional.of(new FileRecordScanner(paths, options, this::getSchema));
    }

    @Override
    public Optional<SplitPlanner> getSplitPlanner() {
        return Optional.of(new FileSplitPlanner());
    }

    @Override
    public Optional<HashProvider> getHashProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<KeyLookupProvider> getKeyLookupProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<SnapshotProvider> getSnapshotProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<FilterPushdownProvider> getFilterPushdownProvider() {
        return Optional.empty();
    }

    @Override
    public void close() {
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    Path getPath() {
        return paths.get(0);
    }

    FileOptions getOptions() {
        return options;
    }

    ReadOptions getReadOptions() {
        return readOptions;
    }
}
