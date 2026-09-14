package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.dto.response.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Shared by {@link RestAuthenticationEntryPoint} (401) and
 * {@link RestAccessDeniedHandler} (403), so both produce exactly the same
 * {@link ApiErrorResponse} JSON shape {@code GlobalExceptionHandler} uses
 * for every other error in the API. Errors thrown inside the Spring
 * Security filter chain never reach {@code @RestControllerAdvice} —
 * without this, 401/403 responses would silently look different from
 * every other error the API returns.
 */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String message) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .fieldErrors(null)
                .build();

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
