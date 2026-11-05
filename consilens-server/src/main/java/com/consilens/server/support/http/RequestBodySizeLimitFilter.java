package com.consilens.server.support.http;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.exception.RequestBodyTooLargeException;
import com.consilens.server.support.trace.TraceIdSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;

    public RequestBodySizeLimitFilter(ConsilensServerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPathSupport.normalizedPath(request);
        if (path == null || !(path.equals("/v1") || path.startsWith("/v1/"))) {
            return true;
        }
        String method = request.getMethod();
        return !("POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long maxBytes = properties.getApi().getMaxRequestBodyBytes();
        if (contentLength(request) > maxBytes) {
            reject(request, response, maxBytes);
            return;
        }
        try {
            filterChain.doFilter(new SizeLimitedRequest(request, maxBytes), response);
        } catch (RequestBodyTooLargeException exception) {
            reject(request, response, maxBytes);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long maxBytes) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(
                "Request body exceeds max size: " + maxBytes + " bytes",
                "REQUEST_BODY_TOO_LARGE",
                TraceIdSupport.getOrCreateTraceId(request)));
    }

    private long contentLength(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0L) {
            return contentLength;
        }
        String header = request.getHeader("Content-Length");
        if (header == null || header.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static class SizeLimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        SizeLimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new SizeLimitedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static class SizeLimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        SizeLimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                increment(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                increment(count);
            }
            return count;
        }

        private void increment(long count) {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }
    }
}
