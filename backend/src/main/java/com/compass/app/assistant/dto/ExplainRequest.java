package com.compass.app.assistant.dto;

/**
 * A request for in-content help on selected text (Phase 8.5). {@code action} is one of
 * explain / explain_with_background / translate / concrete_example / simplify. The context
 * carries where it came from and how to calibrate the answer.
 */
public record ExplainRequest(
        String selectedText,
        ExplainContext context
) {
    public record ExplainContext(
            Long stepId,
            String userSkillLevel,
            String preferredDepth,
            String action,
            String preferredLanguage
    ) {
    }
}
