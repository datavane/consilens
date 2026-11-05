package com.consilens.server.infrastructure.db.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(Environment environment) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(resolveDbType(environment)));
        return interceptor;
    }

    /**
     * 分页方言跟随实际数据源：local profile 使用 H2，其余（含 mysql profile）
     * 默认 MySQL。优先看数据源 URL，避免 profile 名称与 URL 不一致时误判。
     */
    static DbType resolveDbType(Environment environment) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (url.startsWith("jdbc:h2:")) {
            return DbType.H2;
        }
        if (hasProfile(environment.getActiveProfiles(), "mysql")) {
            return DbType.MYSQL;
        }
        if (hasProfile(environment.getActiveProfiles(), "local")
                || hasProfile(environment.getActiveProfiles(), "h2")
                || hasProfile(environment.getDefaultProfiles(), "local")
                || hasProfile(environment.getDefaultProfiles(), "h2")) {
            return DbType.H2;
        }
        return DbType.MYSQL;
    }

    private static boolean hasProfile(String[] profiles, String expected) {
        for (String profile : profiles) {
            if (expected.equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
