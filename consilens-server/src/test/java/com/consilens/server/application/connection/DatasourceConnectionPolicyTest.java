package com.consilens.server.application.connection;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceConnectionPolicyTest {

    @Test
    void acceptsValidParam() {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("host", "prod-db");
        param.put("port", 3306);
        param.put("database", "shop");
        DatasourceConnectionPolicy.validateParam(param);
    }

    @Test
    void rejectsMissingHostAndBadPort() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateParam(Map.of("database", "x")));
        Map<String, Object> badPort = new LinkedHashMap<>();
        badPort.put("host", "h");
        badPort.put("port", 70000);
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateParam(badPort));
    }

    @Test
    void rejectsSensitivePropertiesKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateProperties("ssl=true&password=hunter2"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateProperties("useSSL=false&user=root"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateProperties("useSSL=true&sslpassword=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateProperties("accessToken=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasourceConnectionPolicy.validateProperties("clientSecret=secret"));
        DatasourceConnectionPolicy.validateProperties("useSSL=true&socketTimeout=10000");
    }

    @Test
    void recognizesDriverSpecificCredentialKeys() {
        assertTrue(DatasourceConnectionPolicy.isSensitivePropertyKey("trustStorePassword"));
        assertTrue(DatasourceConnectionPolicy.isSensitivePropertyKey("accessToken"));
        assertTrue(DatasourceConnectionPolicy.isSensitivePropertyKey("apiKey"));
        assertTrue(DatasourceConnectionPolicy.isSensitivePropertyKey("credential"));
        assertFalse(DatasourceConnectionPolicy.isSensitivePropertyKey("useSSL"));
        assertFalse(DatasourceConnectionPolicy.isSensitivePropertyKey("socketTimeout"));
    }

    @Test
    void whitelistedOptionsDropsUnknownKeys() {
        Map<String, Object> options = Map.of(
                "host", "evil",
                "sid", "ORCL",
                "properties", "ssl=true");
        Map<String, Object> filtered = DatasourceConnectionPolicy.whitelistedOptions(options);
        assertEquals("ORCL", filtered.get("sid"));
        assertEquals("ssl=true", filtered.get("properties"));
        assertNull(filtered.get("host"));
        assertTrue(DatasourceConnectionPolicy.isSensitivePropertyKey("Password"));
    }
}
