package com.consilens.server.api.advice;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.domain.exception.ArtifactIntegrityException;
import com.consilens.server.domain.exception.RequestBodyTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnBadRequestForMalformedJson() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])),
                request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void shouldKeepActionableMessageForIllegalArgument() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new IllegalArgumentException("config.comparison.keys must not be empty"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 只回 "Invalid request" 等于把校验原因藏起来
        assertThat(response.getBody().getError()).contains("keys must not be empty");
    }

    @Test
    void shouldNotEchoFrameworkParseMessages() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("JSON parse error: unexpected token at [Source: ...]",
                        new MockHttpInputMessage(new byte[0])),
                request());

        assertThat(response.getBody().getError()).isEqualTo("Invalid request");
    }

    @Test
    void shouldReturnPayloadTooLargeWhenJacksonWrapsBodyLimitException() {
        HttpMessageNotReadableException wrapped = new HttpMessageNotReadableException(
                "too large", new RequestBodyTooLargeException(1024),
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(wrapped, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("REQUEST_BODY_TOO_LARGE");
    }

    @Test
    void shouldReturnMethodNotAllowedForUnsupportedMethod() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH"),
                request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void shouldReturnInternalErrorForArtifactIntegrityFailure() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleArtifactIntegrity(
                new ArtifactIntegrityException("checksum mismatch"),
                request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("ARTIFACT_INTEGRITY_ERROR");
        assertThat(response.getBody().getError()).isEqualTo("Artifact content integrity check failed");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-test");
        return request;
    }
}
