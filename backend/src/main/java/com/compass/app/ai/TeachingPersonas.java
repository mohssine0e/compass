package com.compass.app.ai;

/**
 * A small, curated domain-expert voice roster for generation/teaching content (Phase 25) — the
 * step text, rationale, and module scope drafted during outline generation, module expansion,
 * and step breakdown. Deliberately scoped down from the suggestions doc's full nine-persona list
 * to only the domains actually generated for so far; add another entry only once a real roadmap
 * domain genuinely doesn't fit an existing one, not speculatively.
 *
 * <p>This voice is distinct from — and never applied to — the self-talk voice that governs every
 * reflection-facing surface (resurfacing questions, capture acknowledgments, verification
 * check-ins, the Explain feature). See CLAUDE.md Section 2's scoped exception. A persona changes
 * framing and vocabulary; the "no empty hype" rule still applies in full regardless of persona.
 */
final class TeachingPersonas {

  private TeachingPersonas() {
  }

  private static final String SOFTWARE_ENGINEERING = """
      an experienced software engineer explaining to a capable junior colleague — direct and \
      concrete, uses real terminology without over-explaining basics that don't need it, \
      references the kind of gotchas that only show up in real practice (not textbook trivia)""";

  private static final String LANGUAGE_LEARNING = """
      a fluent, practical language tutor — plain and grounded in "this is what actually gets \
      used", not textbook-abstract; ties grammar and vocabulary to real usage and real \
      conversations rather than rules recited for their own sake""";

  /**
   * A short third-person voice description for {@code domain} (free text from the Phase 18
   * assessment, e.g. "systems programming", "language learning", "fitness"), or {@code null} if
   * no persona in the current small roster genuinely fits — callers fall back to the existing
   * plain drafting voice (today's default, still governed by "no empty hype"), never to
   * self-talk.
   */
  static String voiceFor(String domain) {
    if (domain == null || domain.isBlank()) {
      return null;
    }
    String d = domain.toLowerCase();
    if (d.contains("program") || d.contains("software") || d.contains("engineer")
        || d.contains("devops") || d.contains("computer science") || d.contains(" code")
        || d.startsWith("code") || d.contains("coding") || d.contains("web dev")
        || d.contains("backend") || d.contains("frontend") || d.contains("systems")) {
      return SOFTWARE_ENGINEERING;
    }
    if (d.contains("language") || d.contains("linguistic") || d.contains("fluency")) {
      return LANGUAGE_LEARNING;
    }
    return null;
  }
}
