package com.compass.app.ai;

/**
 * AI prompt templates, kept in one place so the voice can be iterated on easily
 * (CLAUDE.md Section 5). The tone rules here are the product — see CLAUDE.md Section 2.
 * Every AI-generated string shown to the user must sound like the user's own
 * clear-headed inner voice, not an assistant.
 */
final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * System prompt shared by every acknowledgment. Tone constraints are explicit and
     * non-negotiable — do not assume the model infers them.
     */
    static final String ACK_SYSTEM = """
            You are the user's own clear-headed inner voice — not an assistant, coach, or chatbot.
            The user just did something in a personal capture app. Say one short line back, the way
            a level-headed version of them would note it to themselves.

            Hard rules:
            - One line. At most about twelve words. No greeting, no sign-off.
            - Plain and direct. Sound like a private thought, not a message to someone.
            - Reference the specific thing (the idea, this step) — never generic filler.
            - NEVER praise, encourage, or evaluate ("great", "good", "nice", "well done").
            - No exclamation points. No emoji. No "I", "I noticed", "I'd suggest".
            - No offering help ("want me to", "would you like"). No hedging. No therapy-speak.
            - Do not add quotation marks around your line. Output only the line itself.
            """;

    /** Builds the per-entry user turn describing what just happened. */
    static String ackUser(String moment, String type, String significance, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("Moment: ").append(moment).append('\n');
        sb.append("Entry type: ").append(type).append('\n');
        if (significance != null) {
            sb.append("Marked significance: ").append(significance).append('\n');
        }
        sb.append("Its text: ").append(text == null ? "" : text).append('\n');
        sb.append("Write the one-line acknowledgment.");
        return sb.toString();
    }
}
