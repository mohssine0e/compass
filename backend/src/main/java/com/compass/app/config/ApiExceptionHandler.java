package com.compass.app.config;

import com.compass.app.events.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.NoSuchElementException;

/**
 * Turns common domain failures into clean HTTP responses. Kept minimal on purpose —
 * this is a personal app, not a public API surface. System-side failures (DB errors,
 * unexpected nulls) also record a brief {@code system} event so they show up in the admin
 * view, not just server logs (Phase 5).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} (V4-6.2) rather than adding a bare
 * {@code @ExceptionHandler(Exception.class)} directly: Spring's handler-method resolution picks
 * the most specific matching exception type across a bean's whole class hierarchy, inherited
 * methods included — so {@code ResponseEntityExceptionHandler}'s own well-tested handlers for
 * Spring MVC's request-processing exceptions (a malformed body, a validation failure, a bad
 * path-variable type, wrong HTTP method) still correctly take precedence over this class's own
 * catch-all, instead of a bare {@code Exception.class} handler shadowing all of them and turning
 * what should be 400s into 500s (found the hard way: a request-validation test genuinely
 * regressed to 500 while writing this).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * A request that doesn't fit the current state — already broken down as far as it goes,
     * nothing pending to re-check. Distinct from the 503 below: retrying won't change the
     * answer, so the frontend should say what's true rather than offer to try again.
     */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
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

    /**
     * V4-6.2 (2026-07-30 user audit): every handler above is a specific, expected domain
     * failure — anything else (a library exception, a bug) fell through to Spring Boot's own
     * default error page instead of this app's consistent {@link ProblemDetail} shape. Reached
     * two ways: directly, via {@link #handleUnexpected} below for a genuinely novel exception
     * type; or as the shared final step of every one of {@link ResponseEntityExceptionHandler}'s
     * ~20 built-in handlers for Spring MVC's own request-processing exceptions (a malformed
     * body, a validation failure, a bad path-variable type, wrong HTTP method), each of which
     * already built its own correct status + {@link ProblemDetail} body before calling this.
     *
     * <p>{@code body == null} is exactly how to tell the two apart: Spring's built-in handlers
     * always pass their own body; only the genuinely-unhandled path here passes {@code null}.
     * Only that second case gets this app's own logging/eventing — a validation 400 is an
     * ordinary, expected user-input rejection, not something worth an ERROR-level server log
     * line or a {@code system_events} entry the founder would see in the admin view.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        if (body != null) {
            return super.handleExceptionInternal(ex, body, headers, status, request);
        }
        log.error("Unhandled exception reaching ApiExceptionHandler", ex);
        events.systemError("unhandled_exception", "Unhandled: " + shortReason(ex), null);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(status.value()), "Something went wrong.");
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * The actual catch-all: a genuinely novel exception type matches nothing more specific,
     * neither this class's own handlers above nor any of {@link ResponseEntityExceptionHandler}'s
     * inherited ones (Spring's handler-method resolution always prefers the most specific match
     * across a bean's full class hierarchy) — funnels into {@link #handleExceptionInternal} the
     * same way Spring's own built-in handlers do, so the logging/eventing above applies uniformly.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request);
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
