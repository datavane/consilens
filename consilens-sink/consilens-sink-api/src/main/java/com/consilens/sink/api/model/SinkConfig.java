package com.consilens.sink.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.Data;

import java.io.IOException;

/**
 * Sink configuration model uniquely identified by format + type.
 * The properties field accepts a YAML object or JSON string, stored internally as JSON.
 * Serialized back to nested object for readable YAML output.
 * Plugins parse it via ObjectMapper.readValue(properties, XxxSinkConfig.class).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class SinkConfig {

    /** Output format: table / json / csv / console. */
    private String format;

    /** Data type: diff-record / result. */
    private String type;

    /** Whether this sink is enabled. */
    private boolean enabled = true;

    /**
     * Plugin-specific properties stored as a JSON string.
     * Accepts YAML object or JSON string; serialized back as a nested object.
     */
    @JsonDeserialize(using = PropertiesDeserializer.class)
    @JsonSerialize(using = PropertiesSerializer.class)
    private String properties;

    /**
     * Deserializes a YAML object node or JSON string into a stored JSON string.
     */
    static class PropertiesDeserializer extends StdDeserializer<String> {

        protected PropertiesDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.getCurrentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_STRING) {
                return p.getValueAsString();
            }
            // YAML object or array node; serialize to compact JSON string.
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            JsonNode node = mapper.readTree(p);
            return node.toString();
        }
    }

    /**
     * Serializes JSON string back to object node for readable nested YAML output.
     */
    static class PropertiesSerializer extends com.fasterxml.jackson.databind.ser.std.StdSerializer<String> {

        protected PropertiesSerializer() {
            super(String.class);
        }

        @Override
        public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator gen,
                com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            try {
                ObjectMapper mapper = (ObjectMapper) gen.getCodec();
                JsonNode node = mapper.readTree(value);
                mapper.writeTree(gen, node);
            } catch (Exception e) {
                // If not a valid JSON object, output raw string.
                gen.writeString(value);
            }
        }
    }
}
