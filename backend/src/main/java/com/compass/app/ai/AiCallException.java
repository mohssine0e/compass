package com.compass.app.ai;

/**
 * A failed AI provider call, carrying enough detail for {@link ProviderHealth} to decide what
 * it means. The distinction matters: a 429 is a duration problem (back off and come back), a
 * 401 is a configuration problem (say it once, stop hammering), a timeout is usually transient,
 * and an unparseable body isn't the provider's fault at all — it's up, our prompt or parser is
 * the problem, so taking it out of rotation would be wrong.
 *
 * <p>Before this existed every failure collapsed into one {@code IllegalStateException} with the
 * status stringified into the message, which made the above impossible to tell apart without
 * matching on text.
 */
public class AiCallException extends RuntimeException {

    /** What went wrong, in the terms the circuit breaker reasons about. */
    public enum Kind {
        /** 429 — quota or rate limit. Back off, escalating on repeats. */
        RATE_LIMITED,
        /** 401/403 — missing, wrong, or revoked key. Long cooldown; worth saying out loud once. */
        AUTH,
        /** Connect/read timeout. Short cooldown; usually transient. */
        TIMEOUT,
        /** 5xx from the provider. Short cooldown. */
        SERVER_ERROR,
        /** Reply arrived but couldn't be used. NOT the provider's fault — never cools it down. */
        BAD_RESPONSE,
        /** Anything else. Short cooldown. */
        OTHER
    }

    private final Kind kind;
    private final Integer status;

    public AiCallException(Kind kind, Integer status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind getKind() {
        return kind;
    }

    /** The HTTP status, or null for failures that never got one (timeouts, parse errors). */
    public Integer getStatus() {
        return status;
    }

    /** Classify an HTTP status into a failure kind. */
    static Kind kindForStatus(int status) {
        if (status == 429) {
            return Kind.RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        if (status >= 500) {
            return Kind.SERVER_ERROR;
        }
        return Kind.OTHER;
    }

    /**
     * The kind for a throwable that isn't already an {@code AiCallException} — falls back to the
     * timeout sniffing {@link AiFailures} already does, so a socket timeout surfacing from deeper
     * in the stack is still recognised.
     */
    static Kind kindOf(Throwable t) {
        if (t instanceof AiCallException ai) {
            return ai.getKind();
        }
        return "timeout".equals(AiFailures.category(t)) ? Kind.TIMEOUT : Kind.OTHER;
    }
}
