package com.consilens.server.support.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Sha256Support {

    private Sha256Support() {
    }

    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to calculate SHA-256", exception);
        }
    }
}
