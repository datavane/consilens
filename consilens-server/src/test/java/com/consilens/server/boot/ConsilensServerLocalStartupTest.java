package com.consilens.server.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "consilens.server.scheduler.enabled=false")
// 默认 profile 已改为 mysql（本机开发库）；本测试验证的是 local profile 的内嵌库默认值，
// 因此显式激活，不再依赖 spring.profiles.default。
@ActiveProfiles("local")
class ConsilensServerLocalStartupTest {

    @Autowired
    private Environment environment;

    @Test
    void shouldUseLocalProfileDefaultsForStartup() {
        assertThat(environment.getActiveProfiles()).contains("local");
        assertThat(environment.getProperty("consilens.server.database.allow-embedded")).isEqualTo("true");
        assertThat(environment.getProperty("consilens.server.security.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("always");
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }
}
