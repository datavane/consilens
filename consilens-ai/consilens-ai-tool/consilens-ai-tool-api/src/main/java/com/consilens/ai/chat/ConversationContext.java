package com.consilens.ai.chat;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.consilens.ai.model.ChatMessage;
import com.consilens.core.diff.DiffResult;

/**
 * Holds all state for a single user conversation session.
 */
@Data
public class ConversationContext {

    /**
     * Information about a known database connection.
     */
    @Data
    @Builder
    public static class ConnectionInfo {
        private String type;
        private String url;
        private String host;
        private Integer port;
        private String database;
        private String username;
        /** Password is never serialized. */
        private transient String password;
    }

    private final List<ChatMessage> history = new ArrayList<>();

    /** Connections registered in this session, keyed by alias. */
    private final Map<String, ConnectionInfo> connections = new HashMap<>();

    /** Cached table schemas, keyed by "alias.schema.table". */
    private final Map<String, Object> schemaCache = new HashMap<>();

    /** Cached DiffResult objects from previous runs, keyed by result ID. */
    private final Map<String, DiffResult> diffResults = new HashMap<>();

    /** ID of the most recent diff result. */
    private String latestResultId;

    public void addMessage(ChatMessage message) {
        history.add(message);
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public void registerConnection(String alias, ConnectionInfo info) {
        connections.put(alias, info);
    }

    public Optional<ConnectionInfo> getConnection(String alias) {
        return Optional.ofNullable(connections.get(alias));
    }

    public void storeDiffResult(String id, DiffResult result) {
        diffResults.put(id, result);
        latestResultId = id;
    }

    public Optional<DiffResult> getDiffResult(String id) {
        return Optional.ofNullable(diffResults.get(id));
    }

    public Optional<DiffResult> getLatestDiffResult() {
        if (latestResultId == null) {
            return Optional.empty();
        }
        return getDiffResult(latestResultId);
    }
}
