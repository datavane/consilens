package com.consilens.server.support.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the versioned SecretProtector. A missing key does NOT fall back to
 * plaintext: a failing protector is installed and every protect/reveal call
 * fails loudly. The AI startup validator additionally gates AI startup on the
 * key being present.
 */
@Configuration
public class SecretProtectorConfig {

    /**
     * 仓库里公开的开发密钥（同时是 {@code application.yaml} 的默认值）。
     *
     * <p>任何拿到源码的人都能解开用它加密的口令，所以：生产类 profile 下必须启动失败；
     * 其它 profile 下放行但每次启动都打 WARN（本地/测试/集群联调靠它跑起来）。
     * {@code keyId()} 会返回 {@code dev}，方便在页面上分辨"用的是开发密钥"。
     */
    static final String DEV_KEY_BASE64 = "qL7vN2pX8sKd4Rm9TcW1yHb6Fj3Ge5UaZx0Oi8VbAwM=";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SecretProtectorConfig.class);

    @Bean
    public SecretProtector secretProtector(
            @Value("${consilens.server.datasource.encryption-key:}") String keyBase64,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            return new MissingKeySecretProtector();
        }
        if (DEV_KEY_BASE64.equals(keyBase64.trim())) {
            if (isProductionProfile(activeProfiles)) {
                throw new IllegalStateException(
                        "consilens.server.datasource.encryption-key is still the built-in development key"
                                + " (profiles=" + activeProfiles + "); set CONSILENS_ENCRYPTION_KEY to a"
                                + " private 32-byte Base64 key before deploying");
            }
            LOG.warn("Using the built-in development encryption key (profiles={});"
                    + " anything encrypted with it is readable by anyone with the source tree."
                    + " Set CONSILENS_ENCRYPTION_KEY before exposing this instance.", activeProfiles);
        }
        return new AesGcmSecretProtector(keyBase64, keyBase64.trim().equals(DEV_KEY_BASE64) ? "dev" : "k1");
    }

    /**
     * 生产类 profile 下不允许再用仓库里的开发密钥。
     *
     * <p>本地/测试/集群联调 profile 仍允许：那些环境本来就靠仓库默认值跑起来，直接报错只会让人
     * 把密钥硬写回配置文件。
     */
    static boolean isProductionProfile(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String profile : activeProfiles.split(",")) {
            String name = profile.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("prod") || name.equals("production") || name.equals("staging")
                    || name.equals("preprod") || name.equals("release") || name.equals("online")) {
                return true;
            }
        }
        return false;
    }


    static final class MissingKeySecretProtector implements SecretProtector {
        @Override
        public String protect(String plaintext) {
            throw new IllegalStateException(
                    "datasource encryption key is not configured; plaintext fallback is disabled");
        }

        @Override
        public String reveal(String envelope) {
            throw new IllegalStateException(
                    "datasource encryption key is not configured; plaintext fallback is disabled");
        }

        @Override
        public String keyId() {
            return "missing";
        }
    }
}
