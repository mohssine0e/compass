package com.compass.app.ai.prompts;

/**
 * Prompts for {@link com.compass.app.ai.VerificationAiService} — generating a fair check
 * (free-response and multiple-choice) and evaluating an answer honestly, in the self-talk voice.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class VerificationPrompts {

  private VerificationPrompts() {
  }

  public static final String CHECK_SYSTEM = """
      Write ONE fair check for a single step of a learning roadmap — something a person who
      actually did the step could answer, but someone who only skimmed could not. The point is
      honest self-knowledge, not a gotcha.

      Rigor:
      - "light": one short recall/understanding question.
      - "full": a harder question, or a small concrete task ("write a function that…", "explain
        why X happens when Y"). Still answerable in a few sentences or a short snippet.

      Format (Phase 26, given below alongside rigor):
      - "code_challenge": ask for actual code (or precise pseudocode) that does something concrete
        tied to the step — not "explain how you'd do X", but "write X". Still answerable in a
        short snippet, not a real assignment.
      - "scenario": pose a short, concrete design/judgment situation and ask what they'd do and
        why — a real decision with tradeoffs, not a definition question.
      - "free_response" (the default when neither of the above applies): today's plain recall/
        understanding question or small task, exactly as rigor describes above.

      Hard rules:
      - Exactly one check. Specific to THIS step, not the whole roadmap. Plain and direct.
      - No multiple-choice, no trivia, no praise, no preamble, no emoji.
      - Output ONLY strict JSON, no prose: {"question": "..."}
      """;

  public static String checkUser(String roadmapTitle, String stepText, String rigor, String format) {
    StringBuilder sb = new StringBuilder();
    if (roadmapTitle != null && !roadmapTitle.isBlank()) {
      sb.append("Roadmap: ").append(roadmapTitle.trim()).append('\n');
    }
    sb.append("Step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("Rigor: ").append("full".equals(rigor) ? "full" : "light").append('\n');
    sb.append("Format: ").append(format == null ? "free_response" : format).append('\n');
    sb.append("Write the check as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for a multiple-choice check (Phase 26) — a distinct JSON contract from the
   * free-text formats above (options + which one is correct), so it gets its own system prompt
   * rather than branching {@link #CHECK_SYSTEM}'s single-question shape.
   */
  public static final String CHECK_MULTIPLE_CHOICE_SYSTEM = """
      Write ONE fair multiple-choice check for a single step of a learning roadmap — something a
      person who actually did the step could answer, but someone who only skimmed could not.

      Rigor:
      - "light": tests plain recall/understanding of the core idea.
      - "full": tests applying the idea, not just recalling it — a "what happens if…" or "which
        approach is right here" question, not a bigger question in disguise (still one question).

      Exactly 4 options, exactly one correct. Distractors must be genuinely plausible — real
      misconceptions or near-misses a person could actually hold, never a joke option or something
      obviously wrong just to pad the count.

      Hard rules:
      - Specific to THIS step, not the whole roadmap. Plain and direct, no praise, no preamble, no
        emoji.
      - correctIndex is 0-based, indexing into "options".
      - Output ONLY strict JSON, no prose:
        {"question": "...", "options": ["...", "...", "...", "..."], "correctIndex": 0}
      """;

  public static String checkMultipleChoiceUser(String roadmapTitle, String stepText, String rigor) {
    StringBuilder sb = new StringBuilder();
    if (roadmapTitle != null && !roadmapTitle.isBlank()) {
      sb.append("Roadmap: ").append(roadmapTitle.trim()).append('\n');
    }
    sb.append("Step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("Rigor: ").append("full".equals(rigor) ? "full" : "light").append('\n');
    sb.append("Write the check as JSON.");
    return sb.toString();
  }

  public static final String EVALUATE_SYSTEM = """
      Judge whether the user's answer to a check shows they actually understand the step. Be fair
      and honest — a right answer in their own words passes even if imperfectly worded; a vague,
      hand-wavy, or wrong answer does not.

      When it does NOT pass, write a "gap": one or two lines in the user's OWN clear-headed inner
      voice naming the SPECIFIC thing they got wrong or missed — the actual concept, not "not
      quite" or "review this more". It should read like they caught the hole themselves.

      Hard rules:
      - Judge understanding, not phrasing or spelling.
      - gap (only when not passed): specific and plain. No praise, no scolding, no emoji, no
        "you should". When passed, gap is null.
      - Output ONLY strict JSON, no prose: {"passed": true, "gap": null}
      """;

  public static String evaluateUser(String stepText, String question, String answer) {
    StringBuilder sb = new StringBuilder();
    sb.append("Step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("The check they were asked: ").append(question == null ? "" : question.trim()).append('\n');
    sb.append("Their answer: ").append(answer == null ? "" : answer.trim()).append('\n');
    sb.append("Judge it as JSON.");
    return sb.toString();
  }

  // --- In-content help (Phase 8.5) ------------------------------------------------------
  //
  // The user selected some text in a resource and wants help with it. The voice stays the
  // user's own clear-headed inner voice working through the material — never teacher-mode.
}
