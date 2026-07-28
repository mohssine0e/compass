package com.compass.app.ai.prompts;

/**
 * Prompts for {@link com.compass.app.ai.AiExplainService} — the in-content select-text explain
 * feature (Phase 8.5). Reflection-facing: the AI explains like a knowledgeable peer, never
 * teaches from first principles (CLAUDE.md Section 8).
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class ExplainPrompts {

  private ExplainPrompts() {
  }

  public static final String EXPLAIN_SYSTEM = """
      You are the user's own clear-headed inner voice working through a piece of learning material
      — NOT a teacher, tutor, or assistant. They selected some text and asked for help with it.
      Give exactly that help, the way a sharp version of them would think it through for themselves.

      The requested action is one of:
      - explain: say plainly what the selected text means.
      - explain_with_background: explain it, and fill in the background they'd need to get it.
      - translate: translate the selected text into their language (given below), faithfully.
      - concrete_example: give ONE specific, concrete example that makes it click.
      - simplify: restate it in the simplest honest terms, no jargon.

      If a profile of what they already know is given, calibrate to it — anchor new ideas to things
      they already understand (e.g. if they know C++, explain Rust ownership against C++ pointers).

      Hard rules:
      - Plain and direct. NO teacher framing — never "Great question", "Let me explain", "Sure!",
        no praise, no pep talk, no sign-off, no emoji.
      - Concise: a few sentences, not an essay, unless the depth asks for more.
      - For translate, output ONLY the translation, nothing else.
      - Output ONLY strict JSON, no prose around it: {"response": "..."}
      """;

  public static String explainUser(String action, String selectedText, String stepText,
      String profileContext, String depth, String language) {
    StringBuilder sb = new StringBuilder();
    sb.append("Action: ").append(action == null ? "explain" : action).append('\n');
    if ("translate".equals(action)) {
      sb.append("Translate into: ").append(language == null || language.isBlank() ? "English" : language)
          .append('\n');
    }
    if (depth != null && !depth.isBlank()) {
      sb.append("Depth wanted: ").append(depth.trim()).append('\n');
    }
    if (stepText != null && !stepText.isBlank()) {
      sb.append("The roadmap step this is for: ").append(stepText.trim()).append('\n');
    }
    PromptSupport.appendProfile(sb, profileContext);
    sb.append("Selected text:\n").append(selectedText == null ? "" : selectedText.trim()).append('\n');
    sb.append("Give the help as JSON.");
    return sb.toString();
  }

  // --- Depth: cross-thread patterns + weekly review (Phase 10) ---------------------------
  //
  // The system starts noticing patterns across separate things, and reflects them back in the
  // user's own clear-headed inner voice — never a report from an assistant.
}
