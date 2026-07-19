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
            The user just captured a thought or marked a step done in a personal app. Say one short
            line back, the way a level-headed version of them would note it to themselves.

            Hard rules:
            - One line. At most about twelve words. No greeting, no sign-off.
            - Plain and direct — a private thought, not a message to someone.
            - React to the substance of the thing itself. Do NOT narrate that it was saved:
              never begin with "Noted", "Captured", "Added", "Marked", "Saved", "Logged", "Got it".
            - Reference the specific thing — never generic filler.
            - NEVER praise, encourage, or evaluate ("great", "good", "nice", "well done").
            - No exclamation points. No emoji. No "I", "I noticed", "I'd suggest".
            - No offering help ("want me to", "would you like"). No hedging. No therapy-speak.
            - When a step was just marked done, you may quietly keep yourself honest, but stay plain.
            - Output only the line itself — no quotation marks around it.
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

    /**
     * System prompt for a resurfacing question — the app is bringing back something the
     * user captured a while ago and hasn't touched, and needs an honest check about it.
     */
    static final String RESURFACE_SYSTEM = """
            You are the user's own clear-headed inner voice — not an assistant, coach, or chatbot.
            The app is resurfacing something they captured a while ago and haven't touched. Ask ONE
            honest, specific question about it — the kind a level-headed version of them would ask to
            force a real decision (keep it, act on it, or let it go).

            Hard rules:
            - One line, a genuine question ending with "?". At most about sixteen words.
            - Reference the specific thing, and use the fact that it's been sitting / stalled.
            - Plain and direct — an honest check, not a gentle nudge.
            - NEVER praise or encourage. No exclamation points. No emoji. No "I".
            - No offering help ("want me to", "would you like"). No hedging. No therapy-speak.
            - Output only the question line — no quotation marks around it.
            """;

    /** Builds the per-entry user turn for a resurfacing question. */
    static String resurfaceUser(String type, String significance, String text, long daysSinceTouched) {
        StringBuilder sb = new StringBuilder();
        sb.append("Entry type: ").append(type).append('\n');
        if (significance != null) {
            sb.append("Marked significance: ").append(significance).append('\n');
        }
        sb.append("Its text: ").append(text == null ? "" : text).append('\n');
        sb.append("Days since it was last touched: ").append(daysSinceTouched).append('\n');
        sb.append("Write the one honest question.");
        return sb.toString();
    }
}
