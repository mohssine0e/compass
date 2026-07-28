package com.compass.app.ai.prompts;

/**
 * Prompts for {@link com.compass.app.ai.AiVoiceService} — capture acknowledgments and
 * resurfacing questions. Reflection-facing, self-talk voice throughout (CLAUDE.md Section 2);
 * no Phase 25 persona exception applies to anything in this file.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class VoicePrompts {

  private VoicePrompts() {
  }

  /**
   * System prompt shared by every acknowledgment. Tone constraints are explicit
   * and
   * non-negotiable — do not assume the model infers them.
   */
  public static final String ACK_SYSTEM = """
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
  public static String ackUser(String moment, String type, String significance, String text) {
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
   * System prompt for a resurfacing question — the app is bringing back something
   * the
   * user captured a while ago and hasn't touched, and needs an honest check about
   * it.
   */
  public static final String RESURFACE_SYSTEM = """
      You are the user's own clear-headed inner voice — not an assistant, coach, or chatbot.
      The app is resurfacing something they captured a while ago and haven't touched. Ask ONE
      honest, specific question about it — the kind a level-headed version of them would ask to
      force a real decision (keep it, act on it, or let it go).

      Hard rules:
      - One line, a genuine question ending with "?". At most about sixteen words.
      - Reference the specific thing, and use the fact that it's been sitting / stalled.
        If a specific stuck step is given, name that step, not just the roadmap.
      - Plain and direct — an honest check, not a gentle nudge.
      - If they've skipped it repeatedly (a stated pattern, not a first skip), it is fair to
        ask whether it's the wrong next step or whether they're avoiding it — still one line.
      - NEVER praise or encourage. No exclamation points. No emoji. No "I".
      - No offering help ("want me to", "would you like"). No hedging. No therapy-speak.
      - Output only the question line — no quotation marks around it.
      """;

  /** Builds the per-entry user turn for a resurfacing question. */
  public static String resurfaceUser(String type, String significance, String text, long daysSinceTouched) {
    return resurfaceUser(type, significance, text, daysSinceTouched, null, 0);
  }

  /**
   * Resurfacing user turn, optionally naming the roadmap's current step and how
   * many times
   * it's been skipped without action. A repeated skip should read differently
   * from a first
   * one — see CLAUDE.md Section 2 (avoiding it vs. it being the wrong next step).
   */
  public static String resurfaceUser(String type, String significance, String text,
      long daysSinceTouched, String currentStepText, int skipCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("Entry type: ").append(type).append('\n');
    if (significance != null) {
      sb.append("Marked significance: ").append(significance).append('\n');
    }
    sb.append("Its text: ").append(text == null ? "" : text).append('\n');
    if (currentStepText != null && !currentStepText.isBlank()) {
      sb.append("The step they're stuck on right now: ").append(currentStepText).append('\n');
    }
    sb.append("Days since it was last touched: ").append(daysSinceTouched).append('\n');
    if (skipCount == 1) {
      sb.append("They skipped this once already without acting on it.\n");
    } else if (skipCount >= 2) {
      sb.append("They have now skipped this ").append(skipCount)
          .append(" times without acting — this is a pattern, not a one-off. It is fair to")
          .append(" ask whether this is the wrong next step or whether they're avoiding it.\n");
    }
    sb.append("Write the one honest question.");
    return sb.toString();
  }

  // --- Roadmap drafting (Phase 4)
  // -------------------------------------------------------
  //
  // The AI drafts a roadmap the user then owns and edits. Step text is not spoken
  // to the
  // user, so it is plain and instructional; anything the user reads directly (the
  // clarifying questions) still follows the self-talk-voice rules above.
}
