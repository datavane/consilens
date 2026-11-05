package com.consilens.server.application.capability.config;

import com.consilens.server.support.crypto.AesGcmSecretProtector;
import com.consilens.server.support.crypto.SecretProtectorTestKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SinkSecretSupportTest {

    @Test
    void restoresMaskedPasswordBySinkIdentityNotArrayIndex() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String encryptedA = protector.protect("password-a");
        String encryptedB = protector.protect("password-b");
        Map<String, Object> existing = config(
                sink("jdbc:mysql://a/db", "table_a", encryptedA),
                sink("jdbc:mysql://b/db", "table_b", encryptedB));
        Map<String, Object> target = config(
                sink("jdbc:mysql://b/db", "table_b", "******"),
                sink("jdbc:mysql://a/db", "table_a", "******"));

        SinkSecretSupport.restoreMaskedPasswords(target, existing);

        assertEquals(encryptedB, password(target, 0));
        assertEquals(encryptedA, password(target, 1));
    }

    @Test
    void doesNotRestoreWhenSinkIdentityChanged() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        Map<String, Object> existing = config(
                sink("jdbc:mysql://a/db", "table_a", protector.protect("password-a")));
        Map<String, Object> target = config(
                sink("jdbc:mysql://other/db", "table_a", "******"));

        SinkSecretSupport.restoreMaskedPasswords(target, existing);

        assertEquals("******", password(target, 0));
    }

    private Map<String, Object> config(Map<String, Object>... sinks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sinks", new ArrayList<>(List.of(sinks)));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("result", result);
        return config;
    }

    private Map<String, Object> sink(String url, String tableName, String password) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", url);
        properties.put("tableName", tableName);
        properties.put("password", password);
        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("format", "table");
        sink.put("type", "result");
        sink.put("properties", properties);
        return sink;
    }

    @SuppressWarnings("unchecked")
    private String password(Map<String, Object> config, int index) {
        List<Map<String, Object>> sinks =
                (List<Map<String, Object>>) ((Map<String, Object>) config.get("result")).get("sinks");
        Map<String, Object> properties = (Map<String, Object>) sinks.get(index).get("properties");
        return (String) properties.get("password");
    }
}
