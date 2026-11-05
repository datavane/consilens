package com.consilens.server.support.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void shouldAddSecurityHeadersToEveryResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=()");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    void shouldNotDisableCachingForStaticAssets() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/index.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cache-Control")).isNull();
        assertThat(response.getHeader("Pragma")).isNull();
    }
}
