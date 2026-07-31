package com.compass.app.assistant.dto;

/**
 * Polled progress of an in-content explain job (V3-10). {@code response} is null until
 * {@code status} is {@code "DONE"}; {@code error} is null unless {@code status} is
 * {@code "FAILED"}.
 */
public record ExplainJobResponse(
        String status,
        String response,
        String error
) {
}
