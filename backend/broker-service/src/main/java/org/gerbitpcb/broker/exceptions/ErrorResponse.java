package org.gerbitpcb.broker.exceptions;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Standard error response format for all broker API errors.
 * 
 * Provides consistent error information:
 * - timestamp: when the error occurred
 * - status: HTTP status code
 * - error: short error type/name
 * - message: detailed error message
 */
public record ErrorResponse(
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("status") int status,
    @JsonProperty("error") String error,
    @JsonProperty("message") String message
) {
    public ErrorResponse(int status, String error, String message) {
        this(Instant.now(), status, error, message);
    }
}

