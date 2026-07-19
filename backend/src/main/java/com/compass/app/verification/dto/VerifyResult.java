package com.compass.app.verification.dto;

/**
 * The outcome of answering a verification check (Phase 8). On {@code passed}, the step has been
 * marked done. Otherwise {@code gap} is the specific thing that was missed, in the self-talk
 * voice, and the step stays open.
 */
public record VerifyResult(boolean passed, String gap) {
}
