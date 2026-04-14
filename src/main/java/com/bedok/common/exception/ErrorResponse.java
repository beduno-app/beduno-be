package com.bedok.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        List<?> details,
        Instant timestamp,
        String traceId
) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, Instant.now(), null);
    }

    public static ErrorResponse of(String error, String message, List<?> details) {
        return new ErrorResponse(error, message, details, Instant.now(), null);
    }
}
