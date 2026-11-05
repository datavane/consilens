package com.consilens.connector.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Opens local text files, transparently decompressing {@code .gz}.
 */
final class FileReaders {

    private FileReaders() {
    }

    static BufferedReader newBufferedReader(Path path, Charset charset) throws IOException {
        InputStream input = Files.newInputStream(path);
        if (isGzip(path)) {
            input = new GZIPInputStream(input);
        }
        return stripBom(new BufferedReader(new InputStreamReader(input, charset)));
    }

    /**
     * A UTF-8 BOM decodes to {@code \uFEFF}; keeping it turns the first header
     * name into "\uFEFFid" and makes key lookups silently fail.
     */
    private static BufferedReader stripBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }

    static boolean isGzip(Path path) {
        return path != null && path.toString().toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    /** Removes a trailing .gz so the remaining extension can select the format. */
    static String stripGzip(String path) {
        if (path == null) {
            return null;
        }
        return path.toLowerCase(Locale.ROOT).endsWith(".gz")
                ? path.substring(0, path.length() - 3) : path;
    }
}
