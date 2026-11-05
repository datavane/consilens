package com.consilens.server.support.trace;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class TraceIdSupport {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = TraceIdSupport.class.getName() + ".traceId";
    private static final int MAX_TRACE_ID_LENGTH = 128;

    private TraceIdSupport() {
    }

    public static String getOrCreateTraceId(HttpServletRequest request) {
        if (request == null) {
            return newTraceId();
        }
        Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existing instanceof String && !((String) existing).isBlank()) {
            return (String) existing;
        }
        String header = request.getHeader(TRACE_ID_HEADER);
        String traceId = normalize(header);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private static String normalize(String header) {
        if (header == null || header.isBlank()) {
            return newTraceId();
        }
        String traceId = header.trim();
        if (traceId.length() > MAX_TRACE_ID_LENGTH || !traceId.matches("[A-Za-z0-9._:-]+")) {
            return newTraceId();
        }
        return traceId;
    }

    private static String newTraceId() {
        return "trace_" + UUID.randomUUID();
    }
}
