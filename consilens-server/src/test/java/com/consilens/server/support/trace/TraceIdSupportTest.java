package com.consilens.server.support.trace;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdSupportTest {

    @Test
    void shouldUseSafeIncomingTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdSupport.TRACE_ID_HEADER, "trace-test_01:abc.def");

        String traceId = TraceIdSupport.getOrCreateTraceId(request);

        assertThat(traceId).isEqualTo("trace-test_01:abc.def");
    }

    @Test
    void shouldReplaceUnsafeIncomingTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdSupport.TRACE_ID_HEADER, "bad trace\nvalue");

        String traceId = TraceIdSupport.getOrCreateTraceId(request);

        assertThat(traceId).startsWith("trace_");
    }
}
