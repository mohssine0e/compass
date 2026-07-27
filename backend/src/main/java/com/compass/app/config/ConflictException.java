package com.compass.app.config;

/**
 * The request can't apply to the current state of things — and won't on a retry either.
 *
 * <p>These used to be thrown as {@code IllegalStateException}, which the handler maps to 503
 * "service unavailable". That's the right answer for "no AI provider could serve this", and the
 * wrong one for "this step is already broken down as far as it goes" — a permanent, perfectly
 * ordinary answer that the frontend was presenting as an outage the founder should wait out.
 * Same distinction as 409 vs 503 in HTTP: a conflict with reality, not a failure of the system.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
