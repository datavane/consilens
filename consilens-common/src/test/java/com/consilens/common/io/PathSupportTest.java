package com.consilens.common.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PathSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateNestedDirectories() throws Exception {
        Path nested = tempDir.resolve("a/b/c");

        PathSupport.ensureDirectory(nested);

        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void shouldAcceptSymlinkedDirectory() throws Exception {
        Path link = symlinkToRealDirectory();

        PathSupport.ensureDirectory(link);

        assertTrue(Files.isDirectory(link));
    }

    @Test
    void shouldCreateParentThroughSymlink() throws Exception {
        Path link = symlinkToRealDirectory();
        Path file = link.resolve("nested/out.json");

        PathSupport.ensureParent(file);

        assertTrue(Files.isDirectory(link.resolve("nested")));
        Files.writeString(file, "{}");
        assertEquals("{}", Files.readString(tempDir.resolve("real/nested/out.json")));
    }

    private Path symlinkToRealDirectory() throws IOException {
        Path real = tempDir.resolve("real");
        Files.createDirectories(real);
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are not supported: " + exception.getMessage());
        }
        return link;
    }
}
