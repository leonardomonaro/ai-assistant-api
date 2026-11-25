package com.leonardomonaro.aiassistant.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ConversationNotFoundException exception,
            HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return handleExceptionInternal(exception, error(status, "Request validation failed",
                ((ServletWebRequest) request).getRequest(), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String message = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for parameter: " + mismatch.getName() : "Invalid request parameter";
        return handleExceptionInternal(exception, error(status, message, ((ServletWebRequest) request).getRequest(),
                Map.of()), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception, error(status, "Request body is missing or invalid JSON",
                ((ServletWebRequest) request).getRequest(), Map.of()), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Object response = body instanceof ApiError ? body : error(status, reasonPhrase(status),
                ((ServletWebRequest) request).getRequest(), Map.of());
        return super.handleExceptionInternal(exception, response, headers, status, request);
    }

    @ExceptionHandler(AiTimeoutException.class)
    public ResponseEntity<ApiError> handleAiTimeout(AiTimeoutException exception, HttpServletRequest request) {
        return build(HttpStatus.GATEWAY_TIMEOUT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiError> handleAiFailure(AiServiceException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected request failure ({})", exception.getClass().getSimpleName());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, Map.of());
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {
        return ResponseEntity.status(status).body(error(status, message, request, validationErrors));
    }

    private ApiError error(HttpStatusCode status, String message, HttpServletRequest request,
                           Map<String, String> validationErrors) {
        return new ApiError(Instant.now(), status.value(), reasonPhrase(status), message, request.getRequestURI(),
                validationErrors);
    }

    private String reasonPhrase(HttpStatusCode status) {
        return status instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : "HTTP error";
    }
}
