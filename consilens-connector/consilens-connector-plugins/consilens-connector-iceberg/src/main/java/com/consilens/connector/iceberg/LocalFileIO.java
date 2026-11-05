package com.consilens.connector.iceberg;

import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal local-filesystem FileIO for Iceberg metadata, manifest lists and
 * manifests. Data files are not read through this class; the resolved Parquet
 * paths are handed to the file connector.
 */
final class LocalFileIO implements FileIO {

    private static final long serialVersionUID = 1L;

    @Override
    public InputFile newInputFile(String location) {
        return new LocalInputFile(path(location));
    }

    @Override
    public OutputFile newOutputFile(String location) {
        return new LocalOutputFile(path(location));
    }

    @Override
    public void deleteFile(String location) {
        try {
            Files.deleteIfExists(path(location));
        } catch (IOException exception) {
            throw new UncheckedIOExceptionWrapper(exception);
        }
    }

    static Path path(String location) {
        if (location == null) {
            throw new IllegalArgumentException("location must not be null");
        }
        if (location.startsWith("file:")) {
            return Paths.get(URI.create(location));
        }
        return Paths.get(location);
    }

    private static final class LocalInputFile implements InputFile {

        private final Path path;

        private LocalInputFile(Path path) {
            this.path = path;
        }

        @Override
        public long getLength() {
            try {
                return Files.size(path);
            } catch (IOException exception) {
                return -1L;
            }
        }

        @Override
        public SeekableInputStream newStream() {
            try {
                return new RandomAccessSeekableInputStream(new RandomAccessFile(path.toFile(), "r"));
            } catch (IOException exception) {
                throw new UncheckedIOExceptionWrapper(exception);
            }
        }

        @Override
        public String location() {
            return path.toUri().toString();
        }

        @Override
        public boolean exists() {
            return Files.isRegularFile(path);
        }
    }

    private static final class RandomAccessSeekableInputStream extends SeekableInputStream {

        private final RandomAccessFile file;

        private RandomAccessSeekableInputStream(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public long getPos() throws IOException {
            return file.getFilePointer();
        }

        @Override
        public void seek(long newPos) throws IOException {
            file.seek(newPos);
        }

        @Override
        public int read() throws IOException {
            return file.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return file.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static final class LocalOutputFile implements OutputFile {

        private final Path path;

        private LocalOutputFile(Path path) {
            this.path = path;
        }

        @Override
        public PositionOutputStream create() {
            return open(false);
        }

        @Override
        public PositionOutputStream createOrOverwrite() {
            return open(true);
        }

        @Override
        public String location() {
            return path.toUri().toString();
        }

        @Override
        public InputFile toInputFile() {
            return new LocalInputFile(path);
        }

        private PositionOutputStream open(boolean overwrite) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                OutputStream delegate = overwrite
                        ? Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE)
                        : Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                return new CountingPositionOutputStream(delegate);
            } catch (IOException exception) {
                throw new UncheckedIOExceptionWrapper(exception);
            }
        }
    }

    private static final class CountingPositionOutputStream extends PositionOutputStream {

        private final OutputStream delegate;
        private long position;

        private CountingPositionOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public long getPos() {
            return position;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            position++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            position += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * Iceberg's FileIO methods do not declare checked exceptions; wrap I/O
     * failures so callers see the underlying message.
     */
    static final class UncheckedIOExceptionWrapper extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UncheckedIOExceptionWrapper(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
