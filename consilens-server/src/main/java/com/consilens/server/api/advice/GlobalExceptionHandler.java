package com.consilens.server.api.advice;

import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.domain.exception.ArtifactIntegrityException;
import com.consilens.server.domain.exception.ConflictException;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.exception.RequestBodyTooLargeException;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.support.trace.TraceIdSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception,
                                                              HttpServletRequest request) {
        String error = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error, "INVALID_INPUT", TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(exception.getMessage(), "NOT_FOUND",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(exception.getMessage(), exception.getErrorCode(),
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidInput(InvalidInputException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(exception.getMessage(), exception.getErrorCode(),
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(ArtifactIntegrityException.class)
    public ResponseEntity<ApiResponse<Void>> handleArtifactIntegrity(ArtifactIntegrityException exception,
                                                                     HttpServletRequest request) {
        String traceId = TraceIdSupport.getOrCreateTraceId(request);
        log.error("Artifact integrity check failed, traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Artifact content integrity check failed", exception.getErrorCode(), traceId));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception, HttpServletRequest request) {
        RequestBodyTooLargeException tooLarge = findCause(exception, RequestBodyTooLargeException.class);
        if (tooLarge != null) {
            return handleBodyTooLarge(tooLarge, request);
        }
        // 参数错误的具体原因必须回显：否则校验失败只剩"Invalid request"，用户无从知道哪个字段错了。
        // 框架解析类异常（JSON 反序列化失败等）保留笼统文案，避免把内部结构暴露出去。
        String message = "Invalid request";
        if (exception instanceof IllegalArgumentException
                && exception.getMessage() != null
                && !exception.getMessage().isBlank()) {
            message = exception.getMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "INVALID_REQUEST",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                                    HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("Method not allowed", "METHOD_NOT_ALLOWED",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("Unsupported media type", "UNSUPPORTED_MEDIA_TYPE",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception,
                                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(ApiResponse.error("Not acceptable", "NOT_ACCEPTABLE",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    /**
     * 流式请求体超限时异常在 Jackson 读取阶段抛出，MVC 已接管响应，
     * filter 的 catch 看不到，必须在这里映射成 413。
     */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyTooLarge(RequestBodyTooLargeException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(exception.getMessage(), "REQUEST_BODY_TOO_LARGE",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    /** 保留显式 HTTP 状态（如 SSE 并发上限的 503），避免被兜底 handler 吞成 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception,
                                                                  HttpServletRequest request) {
        String reason = exception.getReason() == null ? "request rejected" : exception.getReason();
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.error(reason, "REQUEST_REJECTED",
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception exception, HttpServletRequest request) {
        String traceId = TraceIdSupport.getOrCreateTraceId(request);
        log.error("Unhandled server error, traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error", "INTERNAL_ERROR", traceId));
    }

    private String toMessage(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
