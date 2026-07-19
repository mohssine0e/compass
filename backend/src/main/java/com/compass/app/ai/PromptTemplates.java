package com.compass.app.ai;

import java.util.List;

/**
 * AI prompt templates, kept in one place so the voice can be iterated on easily
 * (CLAUDE.md Section 5). The tone rules here are the product — see CLAUDE.md
 * Section 2.
 * Every AI-generated string shown to the user must sound like the user's own
 * clear-headed inner voice, not an assistant.
 */
final class PromptTemplates {

  private PromptTemplates() {
  }

  /**
   * System prompt shared by every acknowledgment. Tone constraints are explicit
   * and
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
   * System prompt for a resurfacing question — the app is bringing back something
   * the
   * user captured a while ago and hasn't touched, and needs an honest check about
   * it.
   */
  static final String RESURFACE_SYSTEM = """
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
  static String resurfaceUser(String type, String significance, String text, long daysSinceTouched) {
    return resurfaceUser(type, significance, text, daysSinceTouched, null, 0);
  }

  /**
   * Resurfacing user turn, optionally naming the roadmap's current step and how
   * many times
   * it's been skipped without action. A repeated skip should read differently
   * from a first
   * one — see CLAUDE.md Section 2 (avoiding it vs. it being the wrong next step).
   */
  static String resurfaceUser(String type, String significance, String text,
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

  /**
   * System prompt for the 1–2 clarifying questions asked before any steps are
   * drafted.
   * Not a single-shot generation (CLAUDE.md Phase 4) — the point is to pin down
   * the two
   * things that most change the plan: time available and where they're starting
   * from.
   */
  static final String CLARIFY_SYSTEM = """
      The user gave a goal they want a step-by-step roadmap for. Before drafting any steps,
      ask the 1–2 questions whose answers would most change the shape of that roadmap —
      usually how much time they have per week and what they already know / have done.

      If a profile of what they already know is given, use it to make the questions SHARPER,
      not generic: don't ask about something the profile already answers — instead ask the
      follow-up it raises (e.g. "you've already covered ownership — skip it here, or want a
      quick refresher step?" rather than "how much Rust do you know?").

      Hard rules:
      - At most two questions. Fewer is better if one already covers it.
      - Each question is one plain line, in the user's own clear-headed inner voice — not a
        form field, not a chatbot. No greeting, no preamble, no "let me ask".
      - Specific to this goal (and their profile, if given), not generic.
      - No praise, no encouragement, no emoji, no exclamation points.
      - Output ONLY strict JSON, no prose around it: {"questions": ["...", "..."]}
      """;

  static String clarifyUser(String goal, String profileContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    appendProfile(sb, profileContext);
    sb.append("Write the clarifying questions as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the proposed step breakdown, written after the clarifying
   * answers are
   * in. The user edits and owns the result, so err toward concrete, checkable
   * steps.
   */
  static final String PROPOSE_SYSTEM = """
      Draft an ordered, step-by-step roadmap for the user's goal, using their answers to the
      clarifying questions to size and sequence it. The user will edit this before keeping it —
      draft honestly, don't pad.

      If a profile of what they already know is given, use it: SKIP or condense topics the
      profile shows they already have, and don't re-teach them. State every such skip plainly
      in a "skipped" list, each with the reason grounded in their profile (e.g. "skipping basic
      syntax — your profile lists C++ as solid"). Never skip silently. If nothing is skipped,
      return an empty "skipped" list.

      If real search results (official docs, established curricula) are given as grounding, use
      them to shape and CORRECT the roadmap's structure and sequence — prefer how authoritative
      sources actually order this material over your own memory. Do not invent sources; only the
      given ones are real.

      Each step is an object with these fields:
      - text: one concrete, checkable action or milestone — plain, direct, imperative. No
        numbering, no "Step 1:", no motivational language, no emoji.
      - kind: "concept" for learning/understanding something, or "project" for building/applying
        it. Mix them — a good roadmap is not just topics to read, it includes real project steps
        (e.g. "build a small CLI tool using traits and generics"), not only "read about traits".
      - weight: an honest relative size — "small", "medium", or "large". Do NOT make everything
        the same; a big topic and a tiny one must not look equal.
      - dependsOn: the 0-based index of the ONE earlier step that is a genuine prerequisite for
        this one (must be a lower index than this step), or null if none. This is a real
        prerequisite, not just "the previous step" — only set it when the step truly cannot be
        done without that other one.
      - rationale: one short plain line saying why this step is here — and if dependsOn is set,
        why that step must come first. No praise, no filler.

      Hard rules:
      - Between 4 and 10 steps. Order them so each builds on the ones before it.
      - Fit the scope to their stated time and starting point. Don't assume more than they said.
      - Give the roadmap a short, plain title (a few words) naming what they'll be able to do.
      - Output ONLY strict JSON, no prose around it:
        {"title": "...", "steps": [{"text": "...", "kind": "concept", "weight": "medium", "dependsOn": null, "rationale": "..."}], "skipped": ["..."]}
      """;

  static String proposeUser(String goal, String clarifications, String profileContext,
      String groundingContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    if (clarifications != null && !clarifications.isBlank()) {
      sb.append("What they told you:\n").append(clarifications.trim()).append('\n');
    }
    appendProfile(sb, profileContext);
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search results to ground the structure in:\n")
          .append(groundingContext.trim()).append('\n');
    }
    sb.append("Write the roadmap as JSON.");
    return sb.toString();
  }

  /** Append the learner-profile context block to a prompt, if there is one. */
  private static void appendProfile(StringBuilder sb, String profileContext) {
    if (profileContext != null && !profileContext.isBlank()) {
      sb.append("What they already know (their confirmed profile):\n")
          .append(profileContext.trim()).append('\n');
    }
  }

  /**
   * System prompt for breaking one stalled step into smaller steps. Used when the
   * user, on a
   * resurfaced stalled roadmap, chooses to restructure rather than just answer a
   * question.
   */
  static final String BREAKDOWN_SYSTEM = """
      One step of the user's roadmap has stalled. Break just that step into 2–4 smaller,
      concrete sub-steps that make the first move obvious. These replace the stalled step.

      Hard rules:
      - 2 to 4 steps. Each is one concrete, checkable action — smaller than the original.
      - Together they must fully cover the original step, nothing more, nothing less.
      - Plain, direct, imperative lines. No numbering, no "Step 1:", no encouragement, no emoji.
      - Output ONLY strict JSON, no prose around it: {"steps": ["...", "..."]}
      """;

  static String breakdownUser(String roadmapTitle, String stepText) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("The stalled step to break down: ").append(stepText == null ? "" : stepText.trim())
        .append('\n');
    sb.append("Write the smaller steps as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for proposing a single prerequisite step to insert before a
   * stalled step —
   * the "something's missing first" restructuring path. Sets up a real
   * prerequisite
   * (depends_on), not just a reorder.
   */
  static final String PREREQUISITE_SYSTEM = """
      One step of the user's roadmap has stalled, and it may be stuck because something needed
      first is missing. Propose ONE concrete prerequisite step to do before it — the missing
      groundwork that would unblock it. If nothing is genuinely missing, say so honestly.

      Hard rules:
      - At most one prerequisite step. Only propose it if it's really a prerequisite, not filler.
      - It is one concrete, checkable action. Plain, direct, imperative. No emoji, no encouragement.
      - Also write one short line, in the user's own clear-headed inner voice, naming why this
        comes first. Plain, no praise, no hedging.
      - Output ONLY strict JSON, no prose around it. Either:
        {"prerequisite": "...", "why": "..."}   or, if nothing is missing:   {"prerequisite": null}
      """;

  static String prerequisiteUser(String roadmapTitle, String stepText, String priorSteps) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    if (priorSteps != null && !priorSteps.isBlank()) {
      sb.append("Steps already before it:\n").append(priorSteps.trim()).append('\n');
    }
    sb.append("The stalled step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("Write the prerequisite proposal as JSON.");
    return sb.toString();
  }

  // --- Learner profile (Phase 6)
  // --------------------------------------------------------
  //
  // Both of these interpret the founder's own material and are shown back for
  // confirmation
  // (CLAUDE.md: AI interpretations of the person are guesses, not facts). Extract
  // only what's
  // actually stated — don't embellish, don't infer beyond the text.

  /** System prompt for pulling structured facts out of resume/CV text. */
  static final String RESUME_EXTRACT_SYSTEM = """
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

  static String resumeExtractUser(String resumeText) {
    return "Resume text:\n" + (resumeText == null ? "" : resumeText.trim())
        + "\n\nExtract the structured facts as JSON.";
  }

  /**
   * System prompt for interpreting a free-text "how I like to learn" note into a
   * few traits.
   * These are guesses shown back for confirmation, so keep them modest and
   * grounded in the text.
   */
  static final String SELF_DESCRIPTION_SYSTEM = """
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

  static String selfDescriptionUser(String text) {
    return "What they wrote:\n" + (text == null ? "" : text.trim())
        + "\n\nWrite the traits as JSON.";
  }

  // --- Resource discovery (Phase 7.5)
  // ---------------------------------------------------
  //
  // Attach real learning resources to roadmap steps, drawn only from actual
  // search results so
  // no URL is invented. Respects the founder's format preferences.

  static final String RESOURCE_SUGGEST_SYSTEM = """
      Attach up to 3 learning resources to each roadmap step, chosen ONLY from the real search
      results provided. This is grounding, not memory: every resource MUST use a url copied
      exactly from one of the given results — never invent, guess, or modify a url. If no given
      result fits a step, that step gets no resources. Quality and honesty beat coverage.

      For each resource:
      - title: the result's title (or a clearer short version of it).
      - url: copied exactly from the matching search result.
      - format: one of written, video, interactive, repo, book_chapter — your best read of what
        the result actually is.
      - source_type: one of official_docs, community, tutorial.
      - estimated_time: a short human estimate to get through it (e.g. "~30 min", "a few hours").
      - ai_grounding_source: the title of the search result this came from.

      Hard rules:
      - NEVER suggest a resource in a format the user avoids (given below). Drop it entirely.
      - Match resources to the step they actually help with; don't pad every step.
      - Output ONLY strict JSON, no prose: {"steps": [{"index": 0, "resources": [{"title": "...",
        "url": "...", "format": "written", "source_type": "official_docs",
        "estimated_time": "~1h", "ai_grounding_source": "..."}]}]}
      """;

  static String resourceSuggestUser(String goal, List<String> stepTexts,
      String searchResults, List<String> avoidFormats) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    sb.append("Steps (0-based index):\n");
    for (int i = 0; i < stepTexts.size(); i++) {
      sb.append(i).append(". ").append(stepTexts.get(i)).append('\n');
    }
    if (avoidFormats != null && !avoidFormats.isEmpty()) {
      sb.append("Formats the user AVOIDS (never suggest these): ")
          .append(String.join(", ", avoidFormats)).append('\n');
    }
    sb.append("Real search results (use only these urls):\n")
        .append(searchResults == null ? "" : searchResults.trim()).append('\n');
    sb.append("Write the per-step resources as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the "what this step covers" bullets shown in a step's deep view
   * (Phase 7.5). Plain and concrete — what you'll actually do or understand, not a pep talk.
   */
  static final String COVERS_SYSTEM = """
      Given one step of a learning roadmap, list the concrete things it actually covers — what
      you'll do or understand by the end of it. This is a quick orientation, not a lesson.

      Hard rules:
      - 2 to 4 short bullets. Each is one plain, concrete phrase — no full sentences needed.
      - Specific to this step, not the whole roadmap. No praise, no filler, no emoji.
      - Output ONLY strict JSON, no prose: {"covers": ["...", "..."]}
      """;

  static String coversUser(String roadmapTitle, String stepText) {
    StringBuilder sb = new StringBuilder();
    if (roadmapTitle != null && !roadmapTitle.isBlank()) {
      sb.append("Roadmap: ").append(roadmapTitle.trim()).append('\n');
    }
    sb.append("Step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("Write what it covers as JSON.");
    return sb.toString();
  }
}
