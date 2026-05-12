package com.consilens.ai.backend;

import com.consilens.ai.http.HttpLLMClient;
import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base backend for OpenAI-compatible chat completion APIs.
 */
@Slf4j
public abstract class AbstractOpenAICompatibleBackend implements LLMBackend {

    private static final String DEFAULT_CHAT_PATH = "/chat/completions";

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final HttpLLMClient httpClient;
    private final ObjectMapper objectMapper;

    protected AbstractOpenAICompatibleBackend(String baseUrl, String model, String apiKey) {
        this(baseUrl, model, apiKey, null, null, null);
    }

    protected AbstractOpenAICompatibleBackend(String baseUrl, String model, String apiKey,
                                              Duration timeout, Double temperature, Integer maxTokens) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.httpClient = timeout == null ? new HttpLLMClient() : new HttpLLMClient(timeout);
        this.objectMapper = new ObjectMapper();
    }

    protected abstract String backendName();

    protected String chatPath() {
        return DEFAULT_CHAT_PATH;
    }

    protected String completionPath() {
        return DEFAULT_CHAT_PATH;
    }

    protected String healthPath() {
        return "/models";
    }

    protected Map<String, String> headers() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(backendName() + " api key is required");
        }
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    protected abstract boolean supportsToolCalls();

    protected abstract boolean supportsStreaming();

    @Override
    public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);
            applyGenerationOptions(requestBody);

            ArrayNode messagesArray = objectMapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messagesArray.add(buildMessageNode(ChatMessage.Role.SYSTEM, systemPrompt, null, null, null));
            }
            if (messages != null) {
                for (ChatMessage msg : messages) {
                    messagesArray.add(buildMessageNode(msg.getRole(), msg.getContent(), msg.getToolCallId(), msg.getToolCalls(), msg.getName()));
                }
            }
            requestBody.set("messages", messagesArray);

            if (supportsToolCalls() && functions != null && !functions.isEmpty()) {
                ArrayNode toolsArray = objectMapper.createArrayNode();
                for (FunctionDefinition fd : functions) {
                    ObjectNode toolNode = objectMapper.createObjectNode();
                    toolNode.put("type", "function");
                    ObjectNode funcDef = objectMapper.createObjectNode();
                    funcDef.put("name", fd.getName());
                    if (fd.getDescription() != null) {
                        funcDef.put("description", fd.getDescription());
                    }
                    if (fd.getParameters() != null) {
                        funcDef.set("parameters", fd.getParameters());
                    }
                    toolNode.set("function", funcDef);
                    toolsArray.add(toolNode);
                }
                requestBody.set("tools", toolsArray);
                requestBody.put("tool_choice", "auto");
            }

            JsonNode response = httpClient.post(baseUrl + chatPath(), requestBody, headers());
            return parseResponse(response);
        } catch (Exception e) {
            log.error("{} chat request failed: {}", backendName(), e.getMessage(), e);
            return LLMResponse.builder()
                    .text("Error communicating with " + backendName() + ": " + e.getMessage())
                    .finishReason("error")
                    .build();
        }
    }

    @Override
    public String complete(String prompt) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);
            applyGenerationOptions(requestBody);

            ArrayNode messagesArray = objectMapper.createArrayNode();
            messagesArray.add(buildMessageNode(ChatMessage.Role.USER, prompt, null, null, null));
            requestBody.set("messages", messagesArray);

            JsonNode response = httpClient.post(baseUrl + completionPath(), requestBody, headers());
            return extractText(response);
        } catch (Exception e) {
            log.error("{} completion request failed: {}", backendName(), e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return httpClient.isReachable(baseUrl + healthPath(), headers());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public BackendInfo info() {
        return BackendInfo.builder()
                .name(backendName())
                .model(model)
                .version("1.0")
                .supportsFunctionCalling(supportsToolCalls())
                .supportsStreaming(supportsStreaming())
                .build();
    }

    protected LLMResponse parseResponse(JsonNode response) throws IOException {
        JsonNode choice = response.path("choices").isArray() && response.path("choices").size() > 0
                ? response.path("choices").get(0)
                : response;
        JsonNode message = choice.path("message");
        String content = extractMessageContent(choice, message);
        String finishReason = choice.path("finish_reason").asText(response.path("finish_reason").asText("stop"));

        List<ChatMessage.ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        LLMResponse.Usage usage = parseUsage(response.path("usage"));

        return LLMResponse.builder()
                .text(content)
                .toolCalls(toolCalls)
                .finishReason(finishReason)
                .usage(usage)
                .build();
    }

    protected String extractText(JsonNode response) {
        JsonNode choice = response.path("choices").isArray() && response.path("choices").size() > 0
                ? response.path("choices").get(0)
                : response;
        JsonNode message = choice.path("message");
        String content = extractMessageContent(choice, message);
        return content != null ? content : response.path("response").asText("No response from " + backendName());
    }

    protected String extractMessageContent(JsonNode choice, JsonNode message) {
        if (message.isObject()) {
            JsonNode contentNode = message.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                return contentNode.asText(null);
            }
        }
        JsonNode contentNode = choice.get("content");
        return contentNode != null && !contentNode.isNull() ? contentNode.asText(null) : null;
    }

    private void applyGenerationOptions(ObjectNode requestBody) {
        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }
        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(backendName() + " baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private ObjectNode buildMessageNode(ChatMessage.Role role,
                                        String content,
                                        String toolCallId,
                                        List<ChatMessage.ToolCall> toolCalls,
                                        String name) {
        ObjectNode msgNode = objectMapper.createObjectNode();
        msgNode.put("role", role.name().toLowerCase());
        if (content != null) {
            msgNode.put("content", content);
        } else {
            msgNode.putNull("content");
        }
        if (name != null && !name.isEmpty()) {
            msgNode.put("name", name);
        }
        if (toolCallId != null && !toolCallId.isEmpty()) {
            msgNode.put("tool_call_id", toolCallId);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ArrayNode toolCallsNode = objectMapper.createArrayNode();
            for (ChatMessage.ToolCall tc : toolCalls) {
                ObjectNode tcNode = objectMapper.createObjectNode();
                tcNode.put("id", tc.getId());
                tcNode.put("type", "function");
                ObjectNode funcNode = objectMapper.createObjectNode();
                funcNode.put("name", tc.getName());
                funcNode.put("arguments", serializeArguments(tc.getArguments()));
                tcNode.set("function", funcNode);
                toolCallsNode.add(tcNode);
            }
            msgNode.set("tool_calls", toolCallsNode);
        }
        return msgNode;
    }

    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments != null ? arguments : Collections.emptyMap());
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<ChatMessage.ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray() || toolCallsNode.size() == 0) {
            return Collections.emptyList();
        }
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode tc : toolCallsNode) {
            JsonNode func = tc.path("function");
            String id = tc.path("id").asText("tc_" + System.currentTimeMillis());
            String name = func.path("name").asText(null);
            Map<String, Object> arguments = new HashMap<>();
            JsonNode argsNode = func.path("arguments");
            if (argsNode.isObject()) {
                argsNode.fields().forEachRemaining(e -> arguments.put(e.getKey(), toJavaValue(e.getValue())));
            } else if (argsNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(argsNode.asText());
                    parsed.fields().forEachRemaining(e -> arguments.put(e.getKey(), toJavaValue(e.getValue())));
                } catch (Exception ignored) {
                }
            }
            toolCalls.add(ChatMessage.ToolCall.builder()
                    .id(id)
                    .name(name)
                    .arguments(arguments)
                    .build());
        }
        return toolCalls;
    }

    private LLMResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        return LLMResponse.Usage.builder()
                .promptTokens(usageNode.path("prompt_tokens").asInt(0))
                .completionTokens(usageNode.path("completion_tokens").asInt(0))
                .totalTokens(usageNode.path("total_tokens").asInt(0))
                .build();
    }

    private Object toJavaValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return objectMapper.convertValue(value, Object.class);
    }
}
