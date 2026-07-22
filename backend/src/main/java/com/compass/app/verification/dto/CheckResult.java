package com.compass.app.verification.dto;

import java.util.List;

/**
 * A generated verification check (Phase 8, format variety added Phase 26). {@code options} is
 * non-null only for {@code format: "multiple_choice"} — the correct index is never sent to the
 * client, only used server-side to grade the answer.
 */
public record CheckResult(String format, String question, List<String> options) {
}
