package com.consilens.ai.tool;

import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * Utility class for loading {@link DiffResult} objects from files or JSON nodes.
 */
@Slf4j
public final class DiffResultLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DiffResultLoader() {
    }

    /**
     * Loads a {@link DiffResult} from a JSON file.
     *
     * @param filePath path to the JSON file
     * @return the loaded DiffResult
     * @throws IOException if the file cannot be read or parsed
     */
    public static DiffResult fromFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }
        return OBJECT_MAPPER.readValue(file, DiffResult.class);
    }

    /**
     * Deserializes a {@link DiffResult} from a {@link JsonNode}.
     *
     * @param node the JSON node
     * @return the deserialized DiffResult
     * @throws IOException if deserialization fails
     */
    public static DiffResult fromJsonNode(JsonNode node) throws IOException {
        return OBJECT_MAPPER.treeToValue(node, DiffResult.class);
    }
}
