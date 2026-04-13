package com.consilens.cli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves environment variable placeholders in configuration trees.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code ${env.VAR_NAME}}</li>
 *   <li>{@code ${env.VAR_NAME:defaultValue}}</li>
 * </ul>
 */
public class EnvironmentPlaceholderResolver {

    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private final Map<String, String> environment;

    public EnvironmentPlaceholderResolver(Map<String, String> environment) {
        this.environment = environment;
    }

    public JsonNode resolve(JsonNode node) throws ConfigurationException {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode resolvedObject = JsonNodeFactory.instance.objectNode();
            Iterator<Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> field = fields.next();
                resolvedObject.set(field.getKey(), resolve(field.getValue()));
            }
            return resolvedObject;
        }
        if (node.isArray()) {
            ArrayNode resolvedArray = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                resolvedArray.add(resolve(item));
            }
            return resolvedArray;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(resolveText(node.asText()));
        }
        return node;
    }

    private String resolveText(String text) throws ConfigurationException {
        Matcher matcher = ENV_PLACEHOLDER.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String envValue = environment.get(variableName);
            if (envValue == null) {
                if (defaultValue == null) {
                    throw new ConfigurationException("Missing required environment variable: " + variableName);
                }
                envValue = defaultValue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
