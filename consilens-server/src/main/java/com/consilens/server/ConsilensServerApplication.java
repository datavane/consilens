package com.consilens.server;

import com.consilens.server.boot.ConsilensServerProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.consilens.server")
@EnableConfigurationProperties(ConsilensServerProperties.class)
@EnableScheduling
@MapperScan("com.consilens.server.infrastructure.db.mapper")
public class ConsilensServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsilensServerApplication.class, args);
    }
}
