package com.ahdyahmed.taskflow.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

@Value
@Builder
public class ApiErrorResponse {
    Instant timestamp;
    int status;
    String error;
    String message;
    String path;
    /** Populated only for bean-validation failures; null otherwise. */
    Map<String, String> fieldErrors;
}
