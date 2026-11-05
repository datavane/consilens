package com.consilens.server.infrastructure.log;

import com.consilens.server.boot.ConsilensServerProperties;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 注册运行实例日志：一个按 instanceKey 落盘的 store，以及挂在业务 logger 上的 appender。
 * 不引入额外的 log4j2 XML，目录直接复用 Spring 配置，便于部署时覆盖。
 */
@Configuration
@RequiredArgsConstructor
public class RunLogConfiguration {

    private static final String APPENDER_NAME = "RunLogAppender";
    private static final String RUN_LOGGER_NAME = "com.consilens";

    private final ConsilensServerProperties properties;

    @Bean
    public RunLogStore runLogStore() {
        ConsilensServerProperties.RunLog config = properties.getRunLog();
        Path directory = Paths.get(config.getDir()).toAbsolutePath().normalize();
        return new RunLogStore(directory, config.isEnabled());
    }

    @Bean
    public RunLogAppender runLogAppender(RunLogStore store) {
        RunLogAppender appender = new RunLogAppender(APPENDER_NAME, RUN_LOGGER_NAME, store);
        if (!store.isEnabled() || !(LogManager.getContext(false) instanceof LoggerContext)) {
            return appender;
        }
        appender.start();
        appender.attach((LoggerContext) LogManager.getContext(false));
        return appender;
    }
}
