package com.consilens.server.application.connection;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcConnectionSupportTest {

    @Test
    void connectionPropertiesDoNotCarryDriverSpecificTimeoutKeys() {
        Properties properties = JdbcConnectionSupport.connectionProperties(
                "user", "secret", Map.of("properties", "useSSL=false&socketTimeout=10000"));

        assertEquals("user", properties.getProperty("user"));
        assertEquals("secret", properties.getProperty("password"));
        assertEquals("false", properties.getProperty("useSSL"));
        assertEquals("10000", properties.getProperty("socketTimeout"));
        assertFalse(properties.containsKey("connectTimeout"));
        assertFalse(properties.containsKey("loginTimeout"));
    }

    @Test
    void masksCredentialsEmbeddedInJdbcMessages() {
        assertEquals("failed jdbc:mysql://***@db:3306/app?user=***&password=***&useSSL=false",
                JdbcConnectionSupport.maskMessage(
                        "failed jdbc:mysql://scott:tiger@db:3306/app?user=scott&password=tiger&useSSL=false"));
        assertEquals("failed jdbc:oracle:thin:***/***@db:1521/orcl",
                JdbcConnectionSupport.maskMessage(
                        "failed jdbc:oracle:thin:scott/tiger@db:1521/orcl"));
        assertEquals("Access denied for user '***'@'10.0.0.1' (using password: YES)",
                JdbcConnectionSupport.maskMessage(
                        "Access denied for user 'admin'@'10.0.0.1' (using password: YES)"));
    }

    @Test
    void sensitivePropertiesAreNotForwarded() {
        Properties properties = JdbcConnectionSupport.connectionProperties(
                "user", "secret",
                Map.of("properties", "password=other&user=other&sslpassword=other&accessToken=t&ssl=true"));

        assertEquals("user", properties.getProperty("user"));
        assertEquals("secret", properties.getProperty("password"));
        assertEquals("true", properties.getProperty("ssl"));
        assertFalse(properties.containsKey("sslpassword"));
        assertFalse(properties.containsKey("accessToken"));
    }
}
