package com.consilens.server.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定回归测试：application.yaml 的 {@code consilens.server.artifact.*} 必须真的落到
 * 属性对象上（嵌套类需要 setter，Lombok {@code @Data} 丢失时绑定会静默失效，只留代码里的默认值）。
 */
@SpringBootTest(properties = "spring.profiles.active=local")
@TestPropertySource(properties = {
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "consilens.server.artifact.local-base-dir=/tmp/consilens-binding-check",
        "consilens.server.artifact.storage-type=local-file"
})
class ConsilensServerPropertiesBindingTest {

    @Autowired
    private ConsilensServerProperties properties;

    @Test
    void shouldBindArtifactPropertiesFromConfiguration() {
        assertThat(properties.getArtifact().getLocalBaseDir())
                .isEqualTo("/tmp/consilens-binding-check");
        assertThat(properties.getArtifact().getStorageType()).isEqualTo("local-file");
    }
}
