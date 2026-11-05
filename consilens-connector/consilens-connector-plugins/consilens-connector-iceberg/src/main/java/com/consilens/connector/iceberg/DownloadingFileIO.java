package com.consilens.connector.iceberg;

import com.consilens.connector.file.S3ObjectStore;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * FileIO that downloads S3 metadata/manifest objects into a local temp
 * directory and delegates to {@link LocalFileIO}. Data files are handled by the
 * dataset handle, not this class.
 */
final class DownloadingFileIO implements FileIO {

    private static final long serialVersionUID = 1L;

    private final LocalFileIO local = new LocalFileIO();
    private final S3ObjectStore store;
    private final Path tempDir;

    DownloadingFileIO(S3ObjectStore store, Path tempDir) {
        this.store = store;
        this.tempDir = tempDir;
    }

    @Override
    public InputFile newInputFile(String location) {
        if (!isS3(location)) {
            return local.newInputFile(location);
        }
        Path target = localPath(location);
        if (!Files.isRegularFile(target)) {
            store.download(location, target);
        }
        return local.newInputFile(target.toString());
    }

    @Override
    public OutputFile newOutputFile(String location) {
        if (isS3(location)) {
            throw new UnsupportedOperationException("Writing Iceberg metadata to S3 is not supported");
        }
        return local.newOutputFile(location);
    }

    @Override
    public void deleteFile(String location) {
        if (isS3(location)) {
            throw new UnsupportedOperationException("Deleting S3 Iceberg metadata is not supported");
        }
        local.deleteFile(location);
    }

    private Path localPath(String location) {
        String name = location;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            name = "object";
        }
        return tempDir.resolve(hash(location) + "-" + name);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                builder.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean isS3(String location) {
        if (location == null) {
            return false;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.startsWith("s3://") || lower.startsWith("s3a://");
    }

    @Override
    public void close() {
        store.close();
    }
}
