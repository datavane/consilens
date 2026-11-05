package com.consilens.server.support.security;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.support.trace.TraceIdSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Consilens-Api-Key";

    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ConsilensServerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getSecurity().isEnabled()) {
            return true;
        }
        String path = com.consilens.server.support.http.RequestPathSupport.normalizedPath(request);
        if (path == null) {
            return false;
        }
        // 静态 UI 必须在拿到 API key 之前可加载（浏览器无法给 index.html/assets 加请求头）；
        // 只有 /v1/* 业务接口需要鉴权，actuator 除 health 外仍需鉴权。
        if (path.startsWith("/actuator")) {
            return "/actuator/health".equals(path) || path.startsWith("/actuator/health/");
        }
        return !(path.equals("/v1") || path.startsWith("/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String configuredApiKey = properties.getSecurity().getApiKey();
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE",
                    "API key authentication is unavailable");
            return;
        }
        String requestApiKey = request.getHeader(API_KEY_HEADER);
        if (requestApiKey == null || !constantTimeEquals(configuredApiKey.trim(), requestApiKey.trim())) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        HttpStatus status,
                        String errorCode,
                        String error) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(error, errorCode, TraceIdSupport.getOrCreateTraceId(request)));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
