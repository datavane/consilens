package com.consilens.ai.backend;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM backend implementation that communicates with a local Ollama server.
 */
@Slf4j
public class OllamaBackend implements LLMBackend {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen2.5:7b";

    private final String baseUrl;
    private final String model;
    private final HttpLLMClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaBackend() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    public OllamaBackend(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = new HttpLLMClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            ArrayNode messagesArray = objectMapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                ObjectNode sysMsg = objectMapper.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                messagesArray.add(sysMsg);
            }

            for (ChatMessage msg : messages) {
                ObjectNode msgNode = objectMapper.createObjectNode();
                msgNode.put("role", msg.getRole().name().toLowerCase());
                msgNode.put("content", msg.getContent() != null ? msg.getContent() : "");

                if (msg.getRole() == ChatMessage.Role.TOOL && msg.getToolCallId() != null) {
                    msgNode.put("tool_call_id", msg.getToolCallId());
                }

                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    ArrayNode toolCallsNode = objectMapper.createArrayNode();
                    for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                        ObjectNode tcNode = objectMapper.createObjectNode();
                        tcNode.put("id", tc.getId());
                        tcNode.put("type", "function");
                        ObjectNode funcNode = objectMapper.createObjectNode();
                        funcNode.put("name", tc.getName());
                        funcNode.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
                        tcNode.set("function", funcNode);
                        toolCallsNode.add(tcNode);
                    }
                    msgNode.set("tool_calls", toolCallsNode);
                }

                messagesArray.add(msgNode);
            }
            requestBody.set("messages", messagesArray);

            if (functions != null && !functions.isEmpty()) {
                ArrayNode toolsArray = objectMapper.createArrayNode();
                for (FunctionDefinition fd : functions) {
                    ObjectNode toolNode = objectMapper.createObjectNode();
                    toolNode.put("type", "function");
                    ObjectNode funcDef = objectMapper.createObjectNode();
                    funcDef.put("name", fd.getName());
                    funcDef.put("description", fd.getDescription());
                    if (fd.getParameters() != null) {
                        funcDef.set("parameters", fd.getParameters());
                    }
                    toolNode.set("function", funcDef);
                    toolsArray.add(toolNode);
                }
                requestBody.set("tools", toolsArray);
            }

            JsonNode response = httpClient.post(baseUrl + "/api/chat", requestBody);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Ollama chat request failed: {}", e.getMessage(), e);
            return LLMResponse.builder()
                    .text("Error communicating with Ollama: " + e.getMessage())
                    .finishReason("error")
                    .build();
        }
    }

    @Override
    public String complete(String prompt) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);
            JsonNode response = httpClient.post(baseUrl + "/api/generate", requestBody);
            return response.path("response").asText("No response from Ollama");
        } catch (IOException e) {
            log.error("Ollama completion request failed: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public boolean isAvailable() {
        return httpClient.isReachable(baseUrl + "/api/tags");
    }

    @Override
    public BackendInfo info() {
        return BackendInfo.builder()
                .name("ollama")
                .model(model)
                .version("1.0")
                .supportsFunctionCalling(true)
                .supportsStreaming(false)
                .build();
    }

    private LLMResponse parseResponse(JsonNode response) throws IOException {
        JsonNode message = response.path("message");
        String content = message.path("content").asText(null);
        String finishReason = response.path("done_reason").asText("stop");

        List<ChatMessage.ToolCall> toolCalls = Collections.emptyList();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            toolCalls = new ArrayList<>();
            for (JsonNode tc : toolCallsNode) {
                JsonNode func = tc.path("function");
                String id = tc.path("id").asText("tc_" + System.currentTimeMillis());
                String name = func.path("name").asText();
                Map<String, Object> arguments = new HashMap<>();
                JsonNode argsNode = func.path("arguments");
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(e -> arguments.put(e.getKey(), e.getValue().asText()));
                } else if (argsNode.isTextual()) {
                    try {
                        JsonNode parsed = objectMapper.readTree(argsNode.asText());
                        parsed.fields().forEachRemaining(e -> arguments.put(e.getKey(), e.getValue().asText()));
                    } catch (Exception ignored) {
                    }
                }
                toolCalls.add(ChatMessage.ToolCall.builder().id(id).name(name).arguments(arguments).build());
            }
        }

        return LLMResponse.builder()
                .text(content)
                .toolCalls(toolCalls)
                .finishReason(finishReason)
                .build();
    }
}
