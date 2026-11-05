package com.consilens.server.infrastructure.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.springframework.beans.factory.DisposableBean;

/**
 * 把带运行实例 MDC 的日志转发到 {@link RunLogStore}，由 store 决定写入哪个实例文件。
 * 未处于运行实例上下文的事件会被 store 丢弃，因此不会产生额外文件。
 *
 * <p>挂在 {@code com.consilens} logger 上而不是 root：项目 log4j2 配置里该 logger
 * 的 additivity=false，挂 root 收不到业务日志。
 */
public class RunLogAppender extends AbstractAppender implements DisposableBean {

    private final RunLogStore store;
    private final String loggerName;
    private String attachedLoggerName;

    public RunLogAppender(String name, String loggerName, RunLogStore store) {
        super(name, null, null, true, null);
        this.loggerName = loggerName;
        this.store = store;
    }

    @Override
    public void append(LogEvent event) {
        store.append(event);
    }

    public void attach(LoggerContext context) {
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = configuration.getLoggerConfig(loggerName);
        attachedLoggerName = loggerConfig.getName();
        loggerConfig.addAppender(this, Level.ALL, null);
        context.updateLoggers();
    }

    @Override
    public void destroy() {
        if (attachedLoggerName != null && LogManager.getContext(false) instanceof LoggerContext) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            LoggerConfig loggerConfig = configuration.getLoggerConfig(attachedLoggerName);
            if (loggerConfig != null) {
                loggerConfig.removeAppender(getName());
                context.updateLoggers();
            }
        }
        stop();
        store.closeAll();
    }
}
