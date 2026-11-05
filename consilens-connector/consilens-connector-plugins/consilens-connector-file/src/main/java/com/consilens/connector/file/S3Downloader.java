package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Downloads S3 objects into a local temporary directory so the existing
 * CSV/JSONL/Parquet scanners can be reused unchanged.
 *
 * <p>Supported locations:
 * <ul>
 *   <li>{@code s3://bucket/key.csv} — one object;</li>
 *   <li>{@code s3://bucket/prefix/} — all data files under the prefix;</li>
 *   <li>{@code s3://bucket/data/*.parquet} or {@code **} — glob-filtered prefix.</li>
 * </ul>
 */
final class S3Downloader implements AutoCloseable {

    private static final int MAX_OBJECTS = 10_000;

    private final S3Gateway gateway;
    private final long maxObjectBytes;
    private final long maxTotalBytes;

    S3Downloader(S3Gateway gateway) {
        this(gateway, S3Options.DEFAULT_MAX_OBJECT_BYTES, S3Options.DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * @param maxObjectBytes 单个对象上限；0 或负数表示不限
     * @param maxTotalBytes  本次下载总量上限；0 或负数表示不限
     */
    S3Downloader(S3Gateway gateway, long maxObjectBytes, long maxTotalBytes) {
        this.gateway = gateway;
        this.maxObjectBytes = maxObjectBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    List<Path> download(String location, Path targetDir) {
        S3Location parsed = S3Location.parse(location);
        List<String> keys = resolveKeys(parsed);
        if (keys.isEmpty()) {
            throw new ConnectorException("No S3 objects matched " + parsed.display());
        }
        if (keys.size() > MAX_OBJECTS) {
            throw new ConnectorException("S3 location matched more than " + MAX_OBJECTS
                    + " objects; narrow the prefix or glob");
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException exception) {
            throw new ConnectorException("Failed to create S3 temp directory " + targetDir
                    + ": " + exception.getMessage(), exception);
        }

        List<Path> files = new ArrayList<>();
        long totalCopied = 0L;
        int index = 0;
        for (String key : keys) {
            String name = baseName(key);
            if (name == null || name.isBlank()) {
                continue;
            }
            Path target = targetDir.resolve((index++) + "-" + name);
            try {
                totalCopied = copyCapped(parsed, key, target, totalCopied);
            } catch (IOException exception) {
                throw new ConnectorException("Failed to download s3://" + parsed.bucket()
                        + "/" + key + ": " + exception.getMessage(), exception);
            }
            files.add(target);
        }
        if (files.isEmpty()) {
            throw new ConnectorException("No downloadable objects matched " + parsed.display());
        }
        return files;
    }

    /**
     * 边写边计数地把对象落到本地文件。
     *
     * <p>直接 {@code Files.copy} 没有任何上限：一个异常的（或恶意的）超大对象能把磁盘写满，
     * 进而把整个比对拖挂。超限时删掉半成品文件再报错，并说明是哪个对象、哪个上限。
     */
    private long copyCapped(S3Location parsed, String key, Path target, long totalCopied)
            throws IOException {
        long copied = 0L;
        long total = totalCopied;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = gateway.get(parsed.bucket(), key)) {
            if (input == null) {
                throw new ConnectorException("S3 returned no content for s3://"
                        + parsed.bucket() + "/" + key);
            }
            try (java.io.OutputStream output = Files.newOutputStream(target,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    copied += read;
                    total += read;
                    if (maxObjectBytes > 0 && copied > maxObjectBytes) {
                        throw new ConnectorException("s3://" + parsed.bucket() + "/" + key
                                + " exceeds s3MaxObjectBytes=" + maxObjectBytes
                                + " bytes; raise the limit or narrow the location");
                    }
                    if (maxTotalBytes > 0 && total > maxTotalBytes) {
                        throw new ConnectorException("S3 download exceeds s3MaxTotalBytes="
                                + maxTotalBytes + " bytes at s3://" + parsed.bucket() + "/" + key
                                + "; raise the limit or narrow the location");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (RuntimeException | IOException exception) {
            // 半成品文件不能留在临时目录里（后续扫描会把它当成完整文件）
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 删除失败不覆盖原始错误
            }
            if (exception instanceof ConnectorException) {
                throw (ConnectorException) exception;
            }
            throw exception;
        }
        return total;
    }

    private List<String> resolveKeys(S3Location location) {
        String key = location.key();
        if (key.isEmpty() || key.endsWith("/")) {
            return dataFiles(gateway.list(location.bucket(), key));
        }
        if (hasGlob(key)) {
            String prefix = globPrefix(key);
            Pattern pattern = Pattern.compile(globRegex(key));
            List<String> keys = new ArrayList<>();
            for (String candidate : gateway.list(location.bucket(), prefix)) {
                if (pattern.matcher(candidate).matches() && FilePaths.isDataFile(candidate)) {
                    keys.add(candidate);
                }
            }
            keys.sort(Comparator.naturalOrder());
            return keys;
        }
        return List.of(key);
    }

    private static List<String> dataFiles(List<String> keys) {
        List<String> result = new ArrayList<>();
        if (keys == null) {
            return result;
        }
        for (String key : keys) {
            if (key != null && !key.endsWith("/") && FilePaths.isDataFile(key)) {
                result.add(key);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static boolean hasGlob(String key) {
        return key.indexOf('*') >= 0 || key.indexOf('?') >= 0
                || key.indexOf('[') >= 0 || key.indexOf('{') >= 0;
    }

    private static String globPrefix(String key) {
        int wildcard = key.length();
        for (char marker : new char[]{'*', '?', '[', '{'}) {
            int index = key.indexOf(marker);
            if (index >= 0) {
                wildcard = Math.min(wildcard, index);
            }
        }
        return key.substring(0, wildcard);
    }

    private static String globRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            switch (current) {
                case '*':
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                        regex.append(".*");
                        index++;
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '[':
                    int end = glob.indexOf(']', index);
                    if (end > index) {
                        regex.append(glob, index, end + 1);
                        index = end;
                    } else {
                        regex.append("\\[");
                    }
                    break;
                case '{':
                    int close = glob.indexOf('}', index);
                    if (close > index) {
                        String[] options = glob.substring(index + 1, close).split(",");
                        regex.append("(?:");
                        for (int option = 0; option < options.length; option++) {
                            if (option > 0) {
                                regex.append('|');
                            }
                            regex.append(Pattern.quote(options[option]));
                        }
                        regex.append(')');
                        index = close;
                    } else {
                        regex.append("\\{");
                    }
                    break;
                default:
                    if ("\\.[]()+^$|".indexOf(current) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(current);
            }
        }
        return regex.append('$').toString();
    }

    private static String baseName(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    @Override
    public void close() {
        gateway.close();
    }
}
