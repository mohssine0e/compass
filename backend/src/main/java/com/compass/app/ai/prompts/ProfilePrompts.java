package com.compass.app.ai.prompts;

/**
 * Prompts for {@link com.compass.app.ai.ProfileAiService} — resume extraction and
 * self-description interpretation (CLAUDE.md's "AI interpretations are guesses, not facts":
 * both feed the propose→approve→apply profile-confirmation flow, never applied silently).
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class ProfilePrompts {

  private ProfilePrompts() {
  }

  /** System prompt for pulling structured facts out of resume/CV text. */
  public static final String RESUME_EXTRACT_SYSTEM = """
      Pull structured facts out of the resume/CV text below. Extract only what it actually
      states — do not infer, embellish, or add anything that isn't there. The user will
      review and correct this, so accuracy beats completeness.

      Hard rules:
      - skills: concrete skills/technologies/tools named in the text, as short plain strings.
        Names only, no proficiency guesses.
      - experience: each real role as {title, organization, summary} — summary is one short,
        plain line. Omit any field the text doesn't give.
      - education: each entry as {credential, institution}. Omit what isn't stated.
      - No commentary, no praise, no invented detail. Empty arrays are fine if nothing fits.
      - Output ONLY strict JSON, no prose around it:
        {"skills": ["..."], "experience": [{"title": "...", "organization": "...", "summary": "..."}], "education": [{"credential": "...", "institution": "..."}]}
      """;

  public static String resumeExtractUser(String resumeText) {
    return "Resume text:\n" + (resumeText == null ? "" : resumeText.trim())
        + "\n\nExtract the structured facts as JSON.";
  }

  /**
   * System prompt for interpreting a free-text "how I like to learn" note into a
   * few traits.
   * These are guesses shown back for confirmation, so keep them modest and
   * grounded in the text.
   */
  public static final String SELF_DESCRIPTION_SYSTEM = """
      The user described, in their own words, how they like to learn and think. Turn it into a
      few short, concrete traits a roadmap generator could actually use — grounded in what they
      said, not generic personality labels.

      Hard rules:
      - At most four traits. Fewer if the text is short. Each is one short plain phrase
        (e.g. "prefers concrete examples over theory", "loses interest without a project").
      - Only what the text supports — do not invent traits they didn't imply.
      - Plain and neutral. No praise, no flattery, no diagnosis, no therapy-speak, no emoji.
      - Output ONLY strict JSON, no prose around it: {"traits": ["...", "..."]}
      """;

  public static String selfDescriptionUser(String text) {
    return "What they wrote:\n" + (text == null ? "" : text.trim())
        + "\n\nWrite the traits as JSON.";
  }

  // --- Resource discovery (Phase 7.5)
  // ---------------------------------------------------
  //
  // Attach real learning resources to roadmap steps, drawn only from actual
  // search results so
  // no URL is invented. Respects the founder's format preferences.
}
