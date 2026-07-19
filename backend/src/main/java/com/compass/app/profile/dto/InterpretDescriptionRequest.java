package com.compass.app.profile.dto;

/**
 * A free-text "how I like to learn / think" note to interpret into a few traits (Phase 6).
 * The interpretation is a guess shown back for confirmation, never saved from here.
 */
public record InterpretDescriptionRequest(String text) {
}
