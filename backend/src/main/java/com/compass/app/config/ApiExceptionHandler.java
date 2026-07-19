package com.compass.app.config;

import com.compass.app.events.EventService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Turns common domain failures into clean HTTP responses. Kept minimal on purpose —
 * this is a personal app, not a public API surface. System-side failures (DB errors,
 * unexpected nulls) also record a brief {@code system} event so they show up in the admin
 * view, not just server logs (Phase 5).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final EventService events;

    public ApiExceptionHandler(EventService events) {
        this.events = events;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** AI-backed features that can't run right now (no provider configured, or all failed). */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleUnavailable(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /** A database failure — log it briefly as a system event, then return a plain 500. */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDbError(DataAccessException ex) {
        events.systemError("db_error", "Database error: " + shortReason(ex), null);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong saving that.");
    }

    /** An unexpected null in a critical path — record it so the pattern is visible. */
    @ExceptionHandler(NullPointerException.class)
    public ProblemDetail handleUnexpectedNull(NullPointerException ex) {
        events.systemError("unexpected_null", "Unexpected null: " + shortReason(ex), null);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }

    /** The most specific cause's first line — enough to notice a pattern, never a stack trace. */
    private static String shortReason(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline).strip() : message.strip();
    }
}
