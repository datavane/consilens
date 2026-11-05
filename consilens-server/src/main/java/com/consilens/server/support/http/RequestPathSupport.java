package com.consilens.server.support.http;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器共用的请求路径规范化：Tomcat 路由前会剥掉矩阵参数（;path-param）
 * 并规范化 . / ..，但 {@code getRequestURI()} 保留原始形态。任何基于前缀的
 * 安全检查都必须先走这里，否则 {@code /v1;foo/...} 能绕过只看原始字符串的过滤器。
 */
public final class RequestPathSupport {

    private RequestPathSupport() {
    }

    public static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        try {
            path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // 非法编码保留原串，后续仍按非 /v1/ 处理（会要求鉴权/限制）
        }
        path = path.replaceAll(";[^/]*", "");
        try {
            String normalized = java.net.URI.create(path).normalize().getPath();
            if (normalized != null) {
                path = normalized;
            }
        } catch (IllegalArgumentException ignored) {
            // 非法 URI 保留解码后的路径
        }
        return path;
    }
}
