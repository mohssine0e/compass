package com.compass.app.ai.prompts;

import com.compass.app.ai.RoadmapAiService;

import java.util.List;

/**
 * Prompts that draft actual roadmap content — the flat proposal, the module outline, module
 * expansion (plus its titles-only skeleton fallback), step breakdown, a bridge step, and a
 * prerequisite proposal. This is where the Phase 25 teaching-persona exception
 * ({@link #appendPersonaVoice}/{@link TeachingPersonas}) applies — generation content, never the
 * reflection-facing self-talk voice used elsewhere in this package.
 * Split from the larger {@code RoadmapPrompts} (itself split from the former PromptTemplates
 * god-file, V3-4.2) once it passed ~600 lines on its own.
 */
public final class RoadmapDraftingPrompts {

  private RoadmapDraftingPrompts() {
  }

  /**
   * System prompt for a FLAT roadmap (Phase 18) — used when the assessment judges the goal small
   * enough that a single ordered checklist covers it honestly, no named modules needed. Same step
   * shape as {@link #EXPAND_MODULE_SYSTEM}, but for the whole goal in one pass.
   */
  public static final String FLAT_PROPOSE_SYSTEM = """
      The user gave a goal small enough to cover with a single ordered checklist, not a multi-area
      roadmap. Draft that ordered, step-by-step list directly — ONE flat list, no modules.

      Size the number of steps to the assessed scope given below — don't apply a fixed count
      regardless of scale. A genuinely small goal should get as few steps as it honestly needs;
      never pad to look more thorough than it is.

      If a profile of what they already know is given, use it: SKIP or condense topics the
      profile shows they already have. State every such skip plainly in a "skipped" list, each
      with the reason grounded in their profile. Never skip silently. If nothing is skipped,
      return an empty "skipped" list.

      If real search results (official docs, established curricula) are given as grounding, use
      them to shape and correct the list's structure and sequence — prefer how authoritative
      sources actually order this material over your own memory. Do not invent sources.

      If the goal could reasonably mean more than one thing, include an "interpretation" field:
      one plain line stating which reading you're running with, so it can be corrected. Omit or
      null it when the goal is already unambiguous. If little or nothing was clarified, state your
      key assumptions there instead of silently guessing.

      Each step is an object with these fields:
      - text: one concrete, checkable action or milestone — plain, direct, imperative. No
        numbering, no "Step 1:", no motivational language, no emoji.
      - kind: "concept" for learning/understanding something, or "project" for building/applying
        it. Mix them when the goal allows it.
      - weight: an honest relative size — "small", "medium", or "large". Don't make everything the
        same.
      - dependsOnIndex: the 0-based index of the ONE earlier step that is a genuine prerequisite
        for this one (lower index than this step), or null.
      - rationale: one short plain line saying why this step is here — and if a dependency is set,
        why that step must come first.

      Hard rules:
      - Order steps so each builds on the ones before it. Never more than 10 steps in one call —
        if it genuinely needs more than that, it isn't actually flat (a parsing/UX safety rail,
        not the primary sizing mechanism).
      - Fit the scope to their stated time and starting point. Don't pad.
      - Give the roadmap a short, plain title (a few words) naming what they'll be able to do.
      - If a "Teaching voice" is given below, write step text/rationale in that voice — framing
        and vocabulary only, never hype, never softer honesty than the rest of these rules.
      - Output ONLY strict JSON, no prose around it:
        {"title": "...", "interpretation": "..." or null, "steps": [{"text": "...", "kind": "concept", "weight": "medium", "dependsOnIndex": null, "rationale": "..."}], "skipped": ["..."]}
      """;

  public static String flatProposeUser(String goal, String clarifications, String profileContext,
      String groundingContext, String assessmentContext, String domain) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    if (clarifications != null && !clarifications.isBlank()) {
      sb.append("What they told you:\n").append(clarifications.trim()).append('\n');
    } else {
      sb.append("(No clarification given — state your key assumptions plainly.)\n");
    }
    PromptSupport.appendProfile(sb, profileContext);
    if (assessmentContext != null && !assessmentContext.isBlank()) {
      sb.append("Assessed scope: ").append(assessmentContext.trim()).append('\n');
    }
    appendPersonaVoice(sb, domain);
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search results to ground this in:\n")
          .append(groundingContext.trim()).append('\n');
    }
    sb.append("Write the roadmap as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the top-level MODULE OUTLINE of a big goal (Phase 13). Instead of one
   * giant flat step list, draft the few big areas the goal breaks into; each module's own steps
   * are generated later, on demand, when the user expands it.
   */
  public static final String OUTLINE_SYSTEM = """
      The user gave a goal big enough to need structure, not a flat checklist. Draft the
      top-level MODULES it breaks into — the few major areas they'll work through in order. Do
      NOT write the individual steps yet; each module gets expanded into its own steps later.

      If a profile of what they already know is given, use it: SKIP or condense whole modules the
      profile shows they already have. State every skip plainly in a "skipped" list with the
      reason (e.g. "skipping HTTP basics — your profile lists backend work as solid"). Never skip
      silently. If nothing is skipped, return an empty list.

      If the profile states how they like to learn (pace, theory-vs-practice, depth), let it shape
      the SHAPE of the outline: a fast pace or overview depth means fewer, broader modules; a slow
      pace or deep-mastery depth means more, narrower ones. A practice-first preference means the
      module scopes should point toward building, not just reading.

      If real search results (official curricula, docs) are given as grounding, prefer how
      authoritative sources actually structure this material over your own memory. Don't invent
      sources.

      If the assessed archetype (given below) is "career_path", OR the tier (given below) is
      "CAREER", bias the outline toward a recognizable arc: foundational concepts and
      prerequisites first, core tools/technologies next, deeper specialization after that, and a
      portfolio/capstone project area last (RB-4.3). This is guidance, not a rigid template — real
      goals don't always split cleanly into exactly four named phases, so don't force awkward or
      padded modules just to hit that shape. Otherwise, ignore this and size the outline purely on
      its own merits as usual.

      If the goal could reasonably mean more than one thing (different domains, scopes, or
      end points — e.g. "learn Rust" could mean systems programming, web backends, embedded, or
      games), include an "interpretation" field: one plain line stating plainly which reading
      you're running with, so it can be corrected before anything else happens (e.g. "Reading
      this as: Rust for backend services, not embedded or game dev — say if that's wrong."). If
      the goal is already unambiguous, omit it or set it to null — don't manufacture one.

      If little or nothing was said in clarification (the person skipped ahead, or the profile
      already covered everything worth asking), state your key assumptions plainly as part of the
      interpretation line instead of silently guessing (e.g. "Assuming ~5 hrs/week and no prior
      experience — say if that's off.").

      Each module is an object:
      - title: a few plain words naming the area (e.g. "Ownership & memory"). No numbering, no
        "Module 1", no emoji.
      - scope: one plain line saying what falls under it — the user's own clear-headed inner
        voice, not a course blurb. No praise, no hype.

      Hard rules:
      - Size the number of modules to the assessed scope given below — don't apply a fixed count
        regardless of scale. A genuinely small goal should get as few modules as it honestly
        needs; a large one should get enough to actually cover it. Never more than 10 modules in
        one call (15 if the tier given below is "CAREER" — a career-scale arc genuinely needs
        more room) — a parsing/UX safety rail, not the primary sizing mechanism.
      - Fit the scope to their stated time and starting point. Don't pad.
      - Give the whole roadmap a short, plain title (a few words) naming what they'll be able to do.
      - If a "Teaching voice" is given below, write module titles/scopes in that voice — framing
        and vocabulary only, never hype, never softer honesty than the rest of these rules.
      - Output ONLY strict JSON, no prose around it:
        {"title": "...", "interpretation": "..." or null, "modules": [{"title": "...", "scope": "..."}], "skipped": ["..."]}
      """;

  public static String outlineUser(String goal, String clarifications, String profileContext,
      String groundingContext, String assessmentContext, String domain) {
    return outlineUser(goal, clarifications, profileContext, groundingContext, assessmentContext,
        domain, null);
  }

  /** As above, plus {@code tier} (RB-4.1/4.2/4.3) — TASK/MINI/TOPIC/CAREER, or null if unclassified. */
  public static String outlineUser(String goal, String clarifications, String profileContext,
      String groundingContext, String assessmentContext, String domain, String tier) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    if (clarifications != null && !clarifications.isBlank()) {
      sb.append("What they told you:\n").append(clarifications.trim()).append('\n');
    } else {
      sb.append("(No clarification given — state your key assumptions plainly.)\n");
    }
    PromptSupport.appendProfile(sb, profileContext);
    if (assessmentContext != null && !assessmentContext.isBlank()) {
      sb.append("Assessed scope: ").append(assessmentContext.trim()).append('\n');
    }
    if (tier != null && !tier.isBlank()) {
      sb.append("Tier: ").append(tier).append('\n');
    }
    appendPersonaVoice(sb, domain);
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search results to ground the structure in:\n")
          .append(groundingContext.trim()).append('\n');
    }
    sb.append("Write the module outline as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for expanding ONE module of a roadmap into its ordered steps (Phase 13,
   * cross-module dependencies added Phase 18). Same step shape as {@link #FLAT_PROPOSE_SYSTEM},
   * but scoped to a single module so depth grows only where the user asks for it.
   */
  public static final String EXPAND_MODULE_SYSTEM = """
      The user is expanding ONE module of a larger roadmap into its steps. Draft the ordered,
      step-by-step breakdown for just this module — nothing from other modules. They'll edit it
      before keeping it, so draft honestly, don't pad.

      If a profile of what they already know is given, skip or condense what they already have,
      and don't re-teach it. If real search results are given, prefer how authoritative sources
      order this material.

      If the profile states how they like to learn: a preferred session length shapes "weight"
      (short sessions → more, smaller steps; long sessions → fewer, larger ones is fine); a
      practice-first preference means more "project" steps and earlier ones; an example-first
      preference means rationale should lead with a concrete case, not the abstract rule.

      Each step is an object with these fields:
      - text: one concrete, checkable action or milestone — plain, direct, imperative. No
        numbering, no "Step 1:", no motivational language, no emoji.
      - kind: "concept" for learning something, or "project" for building/applying it. Mix them —
        include real project steps, not only things to read.
      - weight: an honest relative size — "small", "medium", or "large". Don't make everything equal.
      - dependsOnIndex: the 0-based index of the ONE earlier step in THIS module that is a genuine
        prerequisite (lower index than this step), or null. A real prerequisite, not just "the
        previous step".
      - dependsOnEntryId: ONLY if the real prerequisite is a step from an EARLIER module (given
        below with real ids) rather than this module — the literal id number of that step. Set at
        most one of dependsOnIndex / dependsOnEntryId, never both, and only when it's a genuine
        prerequisite, not just "comes before it in the roadmap".
      - rationale: one short plain line saying why this step is here — and if a dependency is set,
        why that step comes first. No praise, no filler.
      - riskScore: ONLY set when dependsOnIndex or dependsOnEntryId is set — an honest 1-5 read of
        how big a conceptual leap this step is from that prerequisite (1 = trivial continuation,
        5 = a real jump that could lose someone). Leave null when there's no dependency, or when
        the leap is small (don't inflate scores).

      Project Portfolio Mandate (Phase 24): if the assessed archetype (given below) is
      "career_path" AND this is stated as a later, post-foundational module, include AT LEAST ONE
      "project" step that produces something concrete and publicly shareable — a real repo, a
      deployed thing, a written artifact — not just an exercise done and discarded. This is
      guidance the module should follow, not a hard requirement that overrides the module's actual
      scope; skip it if this module's scope genuinely has no honest project to build. Ignore this
      entirely for any other archetype, or for the foundational module.

      Hard rules:
      - Size the number of steps to the assessed scope given below and this module's own scope —
        don't apply a fixed count. Never more than 10 steps in one call (a parsing/UX safety rail,
        not the primary sizing mechanism).
      - Stay inside this module's scope — don't wander into other modules' territory.
      - If a "Teaching voice" is given below, write step text/rationale in that voice — framing
        and vocabulary only, never hype, never softer honesty than the rest of these rules.
      - Output ONLY strict JSON, no prose around it:
        {"steps": [{"text": "...", "kind": "concept", "weight": "medium", "dependsOnIndex": null, "dependsOnEntryId": null, "rationale": "...", "riskScore": null}]}
      """;

  public static String expandModuleUser(String roadmapTitle, String moduleTitle, String moduleScope,
      String profileContext, String groundingContext, String assessmentContext,
      List<RoadmapAiService.PriorStep> priorSteps, boolean isFoundationalModule, String domain) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("Module to expand: ").append(moduleTitle == null ? "" : moduleTitle.trim()).append('\n');
    if (moduleScope != null && !moduleScope.isBlank()) {
      sb.append("What this module covers: ").append(moduleScope.trim()).append('\n');
    }
    sb.append("Module position: ").append(isFoundationalModule
        ? "the first, foundational module" : "a later, post-foundational module").append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    if (assessmentContext != null && !assessmentContext.isBlank()) {
      sb.append("Assessed scope: ").append(assessmentContext.trim()).append('\n');
    }
    appendPersonaVoice(sb, domain);
    if (priorSteps != null && !priorSteps.isEmpty()) {
      sb.append("Steps already drafted in EARLIER modules (real ids — use dependsOnEntryId if one ")
          .append("of these is a genuine prerequisite for a step here):\n");
      for (RoadmapAiService.PriorStep p : priorSteps) {
        sb.append("[id=").append(p.id()).append("] ").append(p.text()).append('\n');
      }
    }
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search results to ground this module in:\n")
          .append(groundingContext.trim()).append('\n');
    }
    sb.append("Write this module's steps as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the emergency skeleton path (Phase 19): titles only, no kind/weight/
   * resources/dependencies — used only when the full module-expansion call has already failed
   * across the whole heavy tier, so the ask itself must be small enough for a cheap, fast
   * provider to still have quota for. A degraded result the user can see is better than none.
   */
  public static final String SKELETON_EXPAND_SYSTEM = """
      The full drafting call for this module failed, so this is a minimal fallback: just the
      ordered step TITLES for this module, nothing else — no descriptions, no resources, no
      dependency analysis. Short, plain, direct text per step, same voice as a real roadmap step
      (no numbering, no "Step 1:", no motivational language, no emoji).

      Size the number of titles to the module's own scope; never more than 8.

      Output ONLY strict JSON, no prose around it: {"steps": ["...", "..."]}
      """;

  public static String skeletonExpandUser(String roadmapTitle, String moduleTitle, String moduleScope) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("Module: ").append(moduleTitle == null ? "" : moduleTitle.trim()).append('\n');
    if (moduleScope != null && !moduleScope.isBlank()) {
      sb.append("What this module covers: ").append(moduleScope.trim()).append('\n');
    }
    sb.append("Write just this module's step titles as JSON.");
    return sb.toString();
  }

  /**
   * Append a Phase 25 teaching-persona voice line for {@code domain}, if the small curated
   * roster has one — never called for reflection-facing prompts (resurfacing, acknowledgments,
   * Explain), only the generation-content prompts named in {@link TeachingPersonas}'s doc.
   */
  private static void appendPersonaVoice(StringBuilder sb, String domain) {
    String voice = TeachingPersonas.voiceFor(domain);
    if (voice != null) {
      sb.append("Teaching voice to write this content in: ").append(voice).append('\n');
    }
  }

  /**
   * System prompt for breaking one stalled step into smaller steps (Phase 20: same richer step
   * shape {@link #EXPAND_MODULE_SYSTEM} uses, not plain text, so a break-down no longer reads as
   * a visibly poorer result than every other generation path). Used when the user, on a
   * resurfaced stalled roadmap, chooses to restructure rather than just answer a question.
   */
  public static final String BREAKDOWN_SYSTEM = """
      Break one roadmap step into 2–4 smaller, concrete sub-steps that make the first move
      obvious. These replace the original step (RB-4.8: available on any step, not only a
      stalled one — the founder may just want it broken down further).

      If a profile of what they already know is given, skip or condense what they already have,
      and don't re-teach it. If real search results are given, prefer how authoritative sources
      order this material.

      Each step is an object with these fields:
      - text: one concrete, checkable action — smaller than the original step, and sized to be
        completable in a single sitting (roughly 1-4 hours, not a multi-day undertaking). Plain,
        direct, imperative. No numbering, no "Step 1:", no encouragement, no emoji.
      - kind: "concept" for learning something, or "project" for building/applying it.
      - weight: an honest relative size — "small", "medium", or "large".
      - dependsOnIndex: the 0-based index of the ONE earlier sub-step here that is a genuine
        prerequisite (lower index than this step), or null.
      - rationale: one short plain line saying why this sub-step is here — and if a dependency is
        set, why that one comes first. No praise, no filler.

      Hard rules:
      - 2 to 4 steps. Together they must fully cover the original step, nothing more, nothing less.
      - If a "Teaching voice" is given below, write step text/rationale in that voice — framing
        and vocabulary only, never hype, never softer honesty than the rest of these rules.
      - Output ONLY strict JSON, no prose around it:
        {"steps": [{"text": "...", "kind": "concept", "weight": "medium", "dependsOnIndex": null, "rationale": "..."}]}
      """;

  public static String breakdownUser(String roadmapTitle, String stepText, String profileContext,
      String groundingContext, String domain) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    sb.append("The stalled step to break down: ").append(stepText == null ? "" : stepText.trim())
        .append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    appendPersonaVoice(sb, domain);
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search results to ground this in:\n").append(groundingContext.trim()).append('\n');
    }
    sb.append("Write the smaller steps as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for a "bridge step" (Phase 20) — a small checkpoint auto-inserted between a
   * cross-module prerequisite and a step the model scored as a real conceptual leap (riskScore
   * 4+), explicitly connecting the two. Kept cheap/fast-tier: one short step, not a full redraft.
   */
  public static final String BRIDGE_STEP_SYSTEM = """
      Two roadmap steps are linked as prerequisite → dependent, but the gap between them is a
      real conceptual leap. Write ONE small checkpoint step that sits between them and explicitly
      connects the prior concept to the new one — a ~5-minute bridge, not a full lesson.

      Hard rules:
      - One concrete, checkable action. Plain, direct, imperative. No numbering, no "Step 1:", no
        encouragement, no emoji.
      - It must name or reference the actual connection — not a generic "review before continuing".
      - Output ONLY strict JSON, no prose around it: {"step": "..."}
      """;

  public static String bridgeStepUser(String priorStepText, String nextStepText) {
    return "Prerequisite step: " + (priorStepText == null ? "" : priorStepText.trim())
        + "\nStep it leads into: " + (nextStepText == null ? "" : nextStepText.trim())
        + "\nWrite the bridge step as JSON.";
  }

  /**
   * System prompt for proposing a single prerequisite step to insert before a
   * stalled step —
   * the "something's missing first" restructuring path. Sets up a real
   * prerequisite
   * (depends_on), not just a reorder.
   */
  public static final String PREREQUISITE_SYSTEM = """
      One step of the user's roadmap has stalled, and it may be stuck because something needed
      first is missing. Propose ONE concrete prerequisite step to do before it — the missing
      groundwork that would unblock it. If nothing is genuinely missing, say so honestly.

      If a specific named gap is given (e.g. from a failed understanding check), weigh it heavily
      — it's real evidence of what's actually missing, not a guess. Only propose a prerequisite
      that would plausibly close that gap; if the gap doesn't look like a missing-prerequisite
      problem at all (just a shaky answer on something they do have the grounding for), say
      nothing is missing rather than inventing a prerequisite to have something to propose.

      Hard rules:
      - At most one prerequisite step. Only propose it if it's really a prerequisite, not filler.
      - It is one concrete, checkable action. Plain, direct, imperative. No emoji, no encouragement.
      - Also write one short line, in the user's own clear-headed inner voice, naming why this
        comes first. Plain, no praise, no hedging.
      - Output ONLY strict JSON, no prose around it. Either:
        {"prerequisite": "...", "why": "..."}   or, if nothing is missing:   {"prerequisite": null}
      """;

  public static String prerequisiteUser(String roadmapTitle, String stepText, String priorSteps, String gapHint) {
    StringBuilder sb = new StringBuilder();
    sb.append("Roadmap: ").append(roadmapTitle == null ? "" : roadmapTitle.trim()).append('\n');
    if (priorSteps != null && !priorSteps.isBlank()) {
      sb.append("Steps already before it:\n").append(priorSteps.trim()).append('\n');
    }
    sb.append("The stalled step: ").append(stepText == null ? "" : stepText.trim()).append('\n');
    if (gapHint != null && !gapHint.isBlank()) {
      sb.append("Specific gap just found (from a failed understanding check): ")
          .append(gapHint.trim()).append('\n');
    }
    sb.append("Write the prerequisite proposal as JSON.");
    return sb.toString();
  }

}
