package com.consilens.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffResultLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldThrowWhenFileNotFound() {
        assertThatThrownBy(() -> DiffResultLoader.fromFile("/no/such/file.json"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldThrowWhenFileIsEmpty(@TempDir Path dir) throws Exception {
        File empty = dir.resolve("empty.json").toFile();
        Files.writeString(empty.toPath(), "");
        assertThatThrownBy(() -> DiffResultLoader.fromFile(empty.getAbsolutePath()))
                .isInstanceOf(java.io.IOException.class);
    }
}
