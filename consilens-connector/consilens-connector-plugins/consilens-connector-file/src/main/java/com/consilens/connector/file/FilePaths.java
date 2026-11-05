package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves a file resource location to one or more local files.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>a regular file;</li>
 *   <li>a directory (recursively collects known data file extensions);</li>
 *   <li>a glob such as {@code data/part-*.parquet} or {@code data/**}{@code /*.csv}.</li>
 * </ul>
 */
final class FilePaths {

    private FilePaths() {
    }

    static List<Path> resolveAll(String location) {
        if (location == null || location.isBlank()) {
            throw new ConnectorException("File resource.path or resource.name is required");
        }
        Path path = Paths.get(location);
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        if (Files.isDirectory(path)) {
            return walk(path, entry -> isDataFile(entry.getFileName().toString()));
        }
        if (hasGlob(location)) {
            return resolveGlob(location);
        }
        throw new ConnectorException("File does not exist: " + location);
    }

    static List<Path> filterByFormat(List<Path> paths, FileFormat format, boolean explicitFile) {
        if (explicitFile) {
            return paths;
        }
        List<Path> filtered = paths.stream()
                .filter(path -> matchesFormat(path.getFileName().toString(), format))
                .sorted(Comparator.comparing(Path::toString))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            throw new ConnectorException("No " + format.name().toLowerCase(Locale.ROOT)
                    + " files found in " + paths.get(0).getParent());
        }
        return filtered;
    }

    /**
     * Infers one format from all resolved files. Mixed directories/globs must
     * declare {@code format} explicitly; silently keeping only one format would
     * drop the other files and report a false match.
     */
    static FileFormat detectCommonFormat(List<Path> paths, String location) {
        Set<FileFormat> formats = new LinkedHashSet<>();
        for (Path path : paths) {
            formats.add(FileFormat.from(null, path.toString()));
        }
        if (formats.size() > 1) {
            throw new ConnectorException("Multiple file formats found under " + location + ": "
                    + formats + "; set resource.format explicitly");
        }
        return formats.iterator().next();
    }

    private static List<Path> resolveGlob(String location) {
        Path pattern = Paths.get(location).toAbsolutePath().normalize();
        Path base = globBase(pattern);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        if (!Files.isDirectory(base)) {
            throw new ConnectorException("File glob base directory does not exist: " + base);
        }
        return walk(base, entry -> matcher.matches(entry.toAbsolutePath().normalize())
                && isDataFile(entry.getFileName().toString()));
    }

    private static Path globBase(Path pattern) {
        Path base = pattern.getRoot() != null ? pattern.getRoot() : Paths.get("").toAbsolutePath();
        for (Path segment : pattern) {
            if (hasGlob(segment.toString())) {
                break;
            }
            base = base.resolve(segment);
        }
        return base;
    }

    private static List<Path> walk(Path root, java.util.function.Predicate<Path> filter) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(filter)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
            if (files.isEmpty()) {
                throw new ConnectorException("No data files found under: " + root);
            }
            return files;
        } catch (IOException exception) {
            throw new ConnectorException("Failed to list files under " + root + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static boolean hasGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }

    static boolean isDataFile(String name) {
        if (name == null || name.startsWith(".") || "_SUCCESS".equals(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".csv.gz")
                || lower.endsWith(".jsonl") || lower.endsWith(".jsonl.gz")
                || lower.endsWith(".ndjson") || lower.endsWith(".ndjson.gz")
                || lower.endsWith(".parquet") || lower.endsWith(".orc");
    }

    private static boolean matchesFormat(String name, FileFormat format) {
        String lower = name.toLowerCase(Locale.ROOT);
        switch (format) {
            case PARQUET:
                return lower.endsWith(".parquet");
            case ORC:
                return lower.endsWith(".orc");
            case JSONL:
                return lower.endsWith(".jsonl") || lower.endsWith(".jsonl.gz")
                        || lower.endsWith(".ndjson") || lower.endsWith(".ndjson.gz");
            case CSV:
            default:
                return lower.endsWith(".csv") || lower.endsWith(".csv.gz");
        }
    }
}
