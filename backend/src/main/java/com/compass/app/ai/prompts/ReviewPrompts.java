package com.compass.app.ai.prompts;

import java.util.List;

/**
 * Prompts for {@link com.compass.app.ai.ReviewAiService} — cross-thread review, idea clustering,
 * and the one-time CAREER completion reflection. All reflection-facing, self-talk voice.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class ReviewPrompts {

  private ReviewPrompts() {
  }

  public static final String THREADS_SYSTEM = """
      Look across the person's captured ideas, roadmaps, and stalled steps below. Name any real
      RECURRING pattern — a topic they keep circling back to, or a kind of step that keeps stalling
      across different roadmaps. Only genuine patterns that show up more than once; if nothing truly
      recurs, return an empty list rather than inventing one.

      Hard rules:
      - Each is one short line in the person's own clear-headed inner voice — a private observation,
        not a report. Reference the specific things ("three of your ideas circle writing").
      - No praise, no advice, no "you should", no emoji. Honest, plain.
      - At most three. Output ONLY strict JSON, no prose: {"threads": ["...", "..."]}
      """;

  public static String threadsUser(String ideas, String roadmaps, String stalled) {
    StringBuilder sb = new StringBuilder();
    if (ideas != null && !ideas.isBlank()) {
      sb.append("Ideas:\n").append(ideas.trim()).append('\n');
    }
    if (roadmaps != null && !roadmaps.isBlank()) {
      sb.append("Roadmaps:\n").append(roadmaps.trim()).append('\n');
    }
    if (stalled != null && !stalled.isBlank()) {
      sb.append("Steps that keep stalling:\n").append(stalled.trim()).append('\n');
    }
    sb.append("Name the recurring patterns as JSON.");
    return sb.toString();
  }

  public static final String REVIEW_SYSTEM = """
      Summarize where things stand across the person's active roadmaps and ideas, in their OWN
      clear-headed inner voice — the way a level-headed version of them would take stock, not a
      status report from an assistant.

      Hard rules:
      - 2-4 short, separate sentences — not one long run-on with clauses stitched together by
        commas. Break at natural pauses: what's moving, what's stalled, the one thing most worth
        attention. Each sentence stands on its own and could be read aloud without a breath.
      - No praise, no pep talk, no "keep it up", no emoji, no greeting or sign-off.
      - Output ONLY strict JSON, no prose around it: {"summary": "..."}
      """;

  public static String reviewUser(String roadmaps, String ideas) {
    StringBuilder sb = new StringBuilder();
    if (roadmaps != null && !roadmaps.isBlank()) {
      sb.append("Active roadmaps:\n").append(roadmaps.trim()).append('\n');
    }
    if (ideas != null && !ideas.isBlank()) {
      sb.append("Ideas:\n").append(ideas.trim()).append('\n');
    }
    sb.append("Write the review as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for a CAREER roadmap's one-time completion reflection (RB-4.7) — same
   * self-talk-voice discipline as {@link #REVIEW_SYSTEM} (a stock-taking, not a status report),
   * triggered once when every step is done instead of on a recurring schedule. Per CLAUDE.md
   * Section 2, this is reflection-facing (self-talk), never the domain-expert generation voice
   * used for step/module content.
   */
  public static final String CAREER_COMPLETION_SYSTEM = """
      The person just finished every step of a career-scale roadmap. Say something honest about
      it in their OWN clear-headed inner voice — the way a level-headed version of them would take
      stock of finishing something real, not a congratulatory message from an assistant.

      Hard rules:
      - 1-3 short, plain sentences. Name the actual thing finished, not a generic "you did it."
      - NEVER praise, hype, or cheerlead ("amazing work", "you should be proud", exclamation
        points). Honest and plain, not celebratory.
      - It's fair to note what's next in an honest, non-pushy way (e.g. "the roadmap's done —
        whether that's enough on its own or the next step is putting it to use is worth a real
        answer, not a reflex yes") — never a hard sell into "what's next."
      - Output ONLY strict JSON, no prose around it: {"reflection": "..."}
      """;

  public static String careerCompletionUser(String roadmapTitle) {
    return "Roadmap just completed: " + (roadmapTitle == null ? "" : roadmapTitle.trim())
        + "\nWrite the one-time completion reflection as JSON.";
  }

  // --- Captures & organization: auto-cluster into themes (Phase 14) ----------------------

  public static final String CLUSTER_SYSTEM = """
      Group the person's captured ideas below into a handful of real THEMES — ideas that are
      genuinely about the same thing, not a forced category for every idea. Ideas that don't fit
      any real theme with at least one other idea should be left out entirely, not stuffed into a
      catch-all group.

      Each theme is an object:
      - label: a few plain words naming the theme (e.g. "side project ideas", "things to read").
        Not a category-speak label like "Miscellaneous" or "Other".
      - indices: the 0-based indices (from the numbered list given) of the ideas in this theme.

      Hard rules:
      - Only real groupings — at least 2 ideas each. Skip ideas that don't cluster with anything.
      - An idea can appear in at most one theme.
      - Output ONLY strict JSON, no prose around it: {"themes": [{"label": "...", "indices": [0, 2]}]}
      """;

  public static String clusterUser(List<String> ideaTexts) {
    StringBuilder sb = new StringBuilder();
    sb.append("Ideas:\n");
    for (int i = 0; i < ideaTexts.size(); i++) {
      sb.append(i).append(". ").append(ideaTexts.get(i)).append('\n');
    }
    sb.append("Group them into themes as JSON.");
    return sb.toString();
  }

  // --- Unified Intake (RB-5) ---------------------------------------------------------------
}
