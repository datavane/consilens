package com.consilens.server.support.security;

import com.consilens.server.boot.ConsilensServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectApiRequestWhenApiKeyIsMissing() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks/task-1");
        request.addHeader("X-Trace-Id", "trace-auth");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"UNAUTHORIZED\"");
        assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-auth\"");
    }

    @Test
    void shouldRejectApiRootWhenApiKeyIsMissing() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/v1"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectMatrixParameterBypass() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1;foo/tasks/task-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectEncodedMatrixParameterBypass() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1%3Bfoo/tasks/task-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectHealthTraversalBypass() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/../env");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldAllowApiRequestWithConfiguredApiKey() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks/task-1");
        request.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldAllowHealthCheckWithoutApiKey() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectPathThatOnlyStartsWithHealthCheckPath() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/healthz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldAllowHealthCheckSubPathWithoutApiKey() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/liveness");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldAllowStaticUiAssetsWithoutApiKey() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectNonHealthActuatorWithoutApiKey() throws Exception {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldFailClosedWhenSecurityEnabledWithoutConfiguredApiKey() throws Exception {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(new ConsilensServerProperties(), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks/task-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"AUTHENTICATION_UNAVAILABLE\"");
    }
}
