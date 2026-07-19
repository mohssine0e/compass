package com.compass.app.ai;

import java.net.SocketTimeoutException;

/** Small helpers to turn an AI-call failure into a brief event category and reason. */
final class AiFailures {

    private AiFailures() {
    }

    /** A timeout reads differently from a provider error — tell them apart for the log. */
    static String category(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException) {
                return "timeout";
            }
            String m = c.getMessage();
            if (m != null && m.toLowerCase().contains("timed out")) {
                return "timeout";
            }
        }
        return "provider_error";
    }

    /** A short, human reason for the event message (not a stack trace). */
    static String reason(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
