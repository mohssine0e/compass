package com.compass.app.ai.prompts;

import java.util.List;

/**
 * Prompts for editing an already-drafted roadmap — regenerating one module's scope, inserting a
 * new module, regrouping steps, reordering a CAREER roadmap's arc, replanning what's left,
 * self-critique, and a step's "what this covers" bullets.
 * Split from the larger {@code RoadmapPrompts} (itself split from the former PromptTemplates
 * god-file, V3-4.2) once it passed ~600 lines on its own.
 */
public final class RoadmapEditingPrompts {

  private RoadmapEditingPrompts() {
  }

  /**
   * System prompt for redrafting one module's title/scope in place (Phase 18) — "regenerate this
   * module" when the outline is right but one area isn't. Given the roadmap's other modules as
   * context so the new version doesn't wander into their territory.
   */
  public static final String REGENERATE_MODULE_SYSTEM = """
      Redraft ONE module of an existing roadmap — a new title and scope for it — given the
      roadmap's other modules as context so the new version doesn't duplicate or contradict them.
      The user asked for this because the current version isn't right; give a genuinely different
      or better take, not a reworded copy of the same thing.

      Hard rules:
      - title: a few plain words naming the area. No numbering, no "Module N", no emoji.
      - scope: one plain line saying what falls under it — the user's own clear-headed inner
        voice, not a course blurb. No praise, no hype.
      - Stay distinct from the roadmap's other modules — don't re-cover their territory.
      - Output ONLY strict JSON, no prose around it: {"title": "...", "scope": "..."}
      """;

  public static String regenerateModuleUser(String roadmapTitle, String moduleTitle, String currentScope,
      String siblingModulesContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("Module to redraft: ").append(moduleTitle == null ? "" : moduleTitle.trim()).append('\n');
    if (currentScope != null && !currentScope.isBlank()) {
      sb.append("Its current scope: ").append(currentScope.trim()).append('\n');
    }
    if (siblingModulesContext != null && !siblingModulesContext.isBlank()) {
      sb.append("The roadmap's other modules (don't duplicate these):\n")
          .append(siblingModulesContext.trim()).append('\n');
    }
    sb.append("Write the redrafted module as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for drafting one new module to insert into an existing outline (Phase 18) —
   * "insert a module here" when the user notices a real gap the outline missed.
   */
  public static final String INSERT_MODULE_SYSTEM = """
      Draft ONE new module to insert into an existing roadmap, given its other modules as
      context — it must add real, distinct coverage the existing modules don't already have.

      If a specific focus is given, the module must be about THAT subtopic specifically — not a
      free choice of whatever gap seems biggest. Without a focus, pick whatever real gap the
      existing modules don't already cover.

      Hard rules:
      - title: a few plain words naming the area. No numbering, no "Module N", no emoji.
      - scope: one plain line saying what falls under it — the user's own clear-headed inner voice.
      - Must be genuinely distinct from every existing module listed — don't re-cover their
        territory.
      - Output ONLY strict JSON, no prose around it: {"title": "...", "scope": "..."}
      """;

  public static String insertModuleUser(String roadmapTitle, String existingModulesContext,
      String assessmentContext) {
    return insertModuleUser(roadmapTitle, existingModulesContext, assessmentContext, null);
  }

  /**
   * As above, plus {@code focusHint} (RB-3.8) — the specific subtopic goal text a confirmed
   * Canonical Topic Match asked for, so the drafted module is about that, not a free choice.
   */
  public static String insertModuleUser(String roadmapTitle, String existingModulesContext,
      String assessmentContext, String focusHint) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    if (existingModulesContext != null && !existingModulesContext.isBlank()) {
      sb.append("Existing modules (don't duplicate these):\n")
          .append(existingModulesContext.trim()).append('\n');
    }
    if (assessmentContext != null && !assessmentContext.isBlank()) {
      sb.append("Assessed scope: ").append(assessmentContext.trim()).append('\n');
    }
    if (focusHint != null && !focusHint.isBlank()) {
      sb.append("Specific focus this module must cover: ").append(focusHint.trim()).append('\n');
    }
    sb.append("Write the new module as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the MINI → TOPIC re-tier path (RB-2.5): the founder decided a flat step
   * list actually deserves named modules. Group the EXISTING steps (given by id) into modules —
   * this reorganizes what's already there, it does not invent new steps or drop any. Fast tier,
   * since this is a structural grouping call, not fresh content generation.
   */
  public static final String REGROUP_STEPS_SYSTEM = """
      A roadmap that was flat (one ordered step list) is being converted to a modules-based
      structure. Group the given existing steps into a small number of named modules — this
      REORGANIZES steps that already exist, it does not invent new step content.

      Hard rules:
      - Every step id given must appear in exactly one group's "stepIds" — don't drop any, don't
        duplicate any, don't invent new ids.
      - Group by real conceptual similarity, in a sensible learning order — not just evenly split.
      - A module's title is a few plain words; scope is one plain line.
      - 2-6 groups depending on how the steps naturally cluster — don't force a fixed count.
      - Output ONLY strict JSON, no prose around it:
        {"groups": [{"title": "...", "scope": "...", "stepIds": [1, 2, 3]}, ...]}
      """;

  public static String regroupStepsUser(String roadmapTitle, List<String> stepIdsAndTexts) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("Existing steps (id: text):\n");
    for (String line : stepIdsAndTexts) {
      sb.append(line).append('\n');
    }
    sb.append("Write the grouping as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the TOPIC → CAREER re-tier path (RB-2.5): propose reordering the existing
   * modules into a Foundations → Core Tooling → Specialization → Portfolio arc. Guidance, not an
   * enforced schema (see RB-4.3) — this only proposes an ORDER for modules that already exist,
   * it does not create a separate stored "phase" entity or rename/restructure anything. Heavy
   * tier, since ordering a whole roadmap's arc benefits from deeper reasoning than a quick call.
   */
  public static final String CAREER_ARC_SYSTEM = """
      A roadmap that was a topic deep-dive is being converted to a career-path roadmap. Propose
      an ORDER for the existing modules (given by id) that reflects a Foundations → Core Tooling →
      Specialization → Portfolio/Capstone arc — this only reorders what already exists, it does
      not rename modules, invent new ones, or drop any.

      Hard rules:
      - Every module id given must appear exactly once, in the proposed order.
      - "phaseLabel" per module is which rough arc stage it now falls under (one of "Foundations",
        "Core Tooling", "Specialization", "Portfolio") — informational only, not a new field to be
        stored structurally.
      - Output ONLY strict JSON, no prose around it:
        {"order": [{"moduleId": 1, "phaseLabel": "Foundations"}, ...]}
      """;

  public static String careerArcUser(String roadmapTitle, List<String> moduleIdsAndTitles) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("Existing modules (id: title — scope):\n");
    for (String line : moduleIdsAndTitles) {
      sb.append(line).append('\n');
    }
    sb.append("Write the proposed arc order as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for redrafting every not-yet-expanded module given real progress so far
   * (Phase 18) — "replan remaining modules" once some are already underway, so the rest of the
   * plan adjusts to where the person actually is instead of a first-pass outline's cold guess.
   */
  public static final String REPLAN_SYSTEM = """
      The user is partway through a roadmap — some modules are already expanded and underway, and
      the rest haven't been touched yet. Redraft the REMAINING, not-yet-started modules, given
      what they've actually done so far as real context — the aim is to adjust the rest of the
      plan to where they actually are now, not repeat what a first-pass outline guessed cold.

      Keep the same NUMBER of remaining modules, in the same order, one redraft per module given —
      do not merge, split, add, or drop any; only redraft each one's title/scope.

      Hard rules:
      - Stay distinct from the already-expanded modules — don't re-cover their territory.
      - title: a few plain words. No numbering, no "Module N", no emoji.
      - scope: one plain line — the user's own clear-headed inner voice, not a course blurb.
      - Output ONLY strict JSON, no prose around it:
        {"modules": [{"title": "...", "scope": "..."}]}
      """;

  public static String replanUser(String roadmapTitle, String doneModulesContext,
      String remainingModulesContext, String assessmentContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    if (assessmentContext != null && !assessmentContext.isBlank()) {
      sb.append("Assessed scope: ").append(assessmentContext.trim()).append('\n');
    }
    sb.append("Already expanded and underway (don't duplicate):\n")
        .append(doneModulesContext == null || doneModulesContext.isBlank()
            ? "(none yet)\n" : doneModulesContext.trim() + "\n");
    sb.append("Remaining modules to redraft, in order:\n")
        .append(remainingModulesContext == null ? "" : remainingModulesContext.trim()).append('\n');
    sb.append("Write the redrafted remaining modules as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for a self-consistency/self-critique pass over a just-drafted step list
   * (Phase 20) — a cheap second look, not a redraft. Checks for steps out of order, unclear
   * descriptions, missing prerequisites, technical inaccuracy, or real gaps against the stated
   * scope. Deliberately narrow: only proposes a concrete {@code suggestedFix} (a corrected
   * step's replacement text) for issues about ONE specific step's wording — ordering/missing-
   * prerequisite/gap issues are named but not auto-fixable, since there's no single unambiguous
   * edit for "insert a step" or "reorder these" that's safe to apply without the founder framing
   * it themselves. An empty list means nothing worth flagging — the common case, and not
   * something to pad for the sake of finding something.
   */
  public static final String CRITIQUE_SYSTEM = """
      A step-by-step plan was just drafted for the goal/scope given below. Take one honest, cheap
      second look — don't redraft it, just check it against itself and the stated scope.

      Look for:
      - Steps genuinely out of order (a later step is actually needed before an earlier one).
      - A step whose wording is unclear or ambiguous enough that someone could misread what to
        actually do.
      - A real missing prerequisite the plan assumes without ever covering.
      - A technically inaccurate claim or instruction.
      - A real gap against the stated scope (something the scope promises that no step covers).

      Only flag something a careful person would genuinely notice — an empty list is the normal,
      good outcome. Don't invent issues to have something to say.

      For each issue:
      - severity: "high", "medium", or "low" — honest, not inflated.
      - message: one plain, direct line naming the actual problem. No praise, no hedging.
      - stepIndex: the 0-based index of the ONE step this issue is about, or null if it's about
        the plan as a whole (ordering across steps, a gap, a missing prerequisite with no single
        step to pin it on).
      - suggestedFix: ONLY when stepIndex is set AND the fix is purely a wording/clarity problem
        with that one step — the corrected replacement text for that step, nothing else. Null for
        every other issue (ordering, missing prerequisite, gaps) — there's no single safe
        auto-edit for those; naming the problem is the point, not guessing a structural fix.

      Output ONLY strict JSON, no prose around it:
      {"issues": [{"severity": "high", "message": "...", "stepIndex": 2, "suggestedFix": "..."}]}
      """;

  public static String critiqueUser(String goal, String scope, List<String> stepTexts) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal/scope: ").append(goal == null ? "" : goal.trim());
    if (scope != null && !scope.isBlank()) {
      sb.append(" — ").append(scope.trim());
    }
    sb.append('\n');
    sb.append("Drafted steps (0-based index):\n");
    for (int i = 0; i < stepTexts.size(); i++) {
      sb.append(i).append(". ").append(stepTexts.get(i)).append('\n');
    }
    sb.append("Write any real issues as JSON.");
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

  /**
   * System prompt for the "what this step covers" bullets shown in a step's deep view
   * (Phase 7.5). Plain and concrete — what you'll actually do or understand, not a pep talk.
   */
  public static final String COVERS_SYSTEM = """
      Given one step of a learning roadmap, list the concrete things it actually covers — what
      you'll do or understand by the end of it. This is a quick orientation, not a lesson.

      Hard rules:
      - 2 to 4 short bullets. Each is one plain, concrete phrase — no full sentences needed.
      - Specific to this step, not the whole roadmap. No praise, no filler, no emoji.
      - Output ONLY strict JSON, no prose: {"covers": ["...", "..."]}
      """;

  public static String coversUser(String roadmapTitle, String stepText) {
    StringBuilder sb = new StringBuilder();
    if (roadmapTitle != null && !roadmapTitle.isBlank()) {
      sb.append("Roadmap: ").append(roadmapTitle.trim()).append('\n');
    }
    sb.append("Step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    sb.append("Write what it covers as JSON.");
    return sb.toString();
  }

  // --- Verification (Phase 8) -----------------------------------------------------------
  //
  // A check the user answers before a step counts as done, and an honest judgment of their
  // answer. The check question is plain; the "gap" on a wrong answer is the user's own
  // clear-headed inner voice — specific, never generic, never harsh.

}
