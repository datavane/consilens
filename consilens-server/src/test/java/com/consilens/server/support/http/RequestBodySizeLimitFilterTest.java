package com.consilens.server.support.http;

import com.consilens.server.boot.ConsilensServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySizeLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectApiRequestWhenContentLengthExceedsLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/tasks/execute");
        request.addHeader("Content-Length", "5");
        request.addHeader("X-Trace-Id", "trace-large");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-large\"");
    }

    @Test
    void shouldApplyToApiRootPath() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1");
        request.addHeader("Content-Length", "5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void shouldRejectApiRequestWithMatrixParameterPath() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1;foo/tasks/execute");
        request.addHeader("Content-Length", "5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void shouldRejectApiRequestWhenStreamingBodyExceedsLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = unknownLengthRequest("POST", "/v1/tasks/execute");
        request.setContent("large".getBytes());
        request.addHeader("X-Trace-Id", "trace-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, consumingChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-stream\"");
    }

    @Test
    void shouldRejectApiRequestWhenReaderExceedsLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = unknownLengthRequest("POST", "/v1/tasks/execute");
        request.setContent("large".getBytes());
        request.addHeader("X-Trace-Id", "trace-reader");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, readerConsumingChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-reader\"");
    }

    @Test
    void shouldAllowApiRequestWithinLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(8L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/tasks/execute");
        request.setContent("ok".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, consumingChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldSkipNonApiRequest() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/info");
        request.addHeader("Content-Length", "5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldSkipApiGetRequest() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(properties(4L), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks/task-1");
        request.addHeader("Content-Length", "5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private ConsilensServerProperties properties(long maxRequestBodyBytes) {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getApi().setMaxRequestBodyBytes(maxRequestBodyBytes);
        return properties;
    }

    private MockHttpServletRequest unknownLengthRequest(String method, String requestUri) {
        return new MockHttpServletRequest(method, requestUri) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
    }

    private MockFilterChain consumingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
                ServletInputStream inputStream = request.getInputStream();
                byte[] buffer = new byte[8];
                while (inputStream.read(buffer) != -1) {
                    // drain request body
                }
            }
        };
    }

    private MockFilterChain readerConsumingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
                char[] buffer = new char[8];
                while (request.getReader().read(buffer) != -1) {
                    // drain request body
                }
            }
        };
    }
}
