package com.compass.app.ai.prompts;

/**
 * Small cross-cutting helpers shared by more than one prompt-family file — currently just the
 * learner-profile context block, appended (when a confirmed profile exists) to
 * {@link com.compass.app.ai.prompts.RoadmapPrompts}, {@link com.compass.app.ai.prompts.ExplainPrompts},
 * and {@link com.compass.app.ai.prompts.IntentPrompts}'s prompts alike. Kept separate from any one
 * feature file specifically because it's genuinely shared, not because everything shared belongs here —
 * see {@code RoadmapPrompts.appendPersonaVoice}, which stays private there since only that file uses it.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class PromptSupport {

  private PromptSupport() {
  }

  /** Append the learner-profile context block to a prompt, if there is one. */
  static void appendProfile(StringBuilder sb, String profileContext) {
    if (profileContext != null && !profileContext.isBlank()) {
      sb.append("What they already know (their confirmed profile):\n")
          .append(profileContext.trim()).append('\n');
    }
  }
}
