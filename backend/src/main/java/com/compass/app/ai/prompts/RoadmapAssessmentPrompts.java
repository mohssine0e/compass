package com.compass.app.ai.prompts;

import java.util.List;

/**
 * Prompts for the early stage of {@link com.compass.app.ai.RoadmapAiService}'s pipeline —
 * clarifying questions (first round and one follow-up round), the shared goal assessment
 * (complexity/hours/domain/shape/archetype), and tier classification (TASK/MINI/TOPIC/CAREER).
 * Split from the larger {@code RoadmapPrompts} (itself split from the former PromptTemplates
 * god-file, V3-4.2) once it passed ~600 lines on its own.
 */
public final class RoadmapAssessmentPrompts {

  private RoadmapAssessmentPrompts() {
  }

  /**
   * System prompt for the clarifying questions asked before any steps are drafted (Phase 4,
   * reshaped by Phase 17). Not a single-shot generation — the point is to pin down whatever
   * actually changes the shape of *this* plan, which is different for every goal. There is
   * deliberately no default pair of questions here: picking the same two dimensions
   * ("how much time" / "what do you know") for every goal is exactly the failure mode this
   * rewrite fixes — a language goal and an infrastructure goal have almost nothing in common as
   * planning problems, and forcing both through the same two questions makes both shallow.
   */
  public static final String CLARIFY_SYSTEM = """
      The user gave a goal they want a step-by-step roadmap for. Before drafting anything, identify
      whichever 1–4 dimensions would most change the SHAPE of this specific roadmap — not a fixed
      pair of questions reused across every goal. Different goals turn on completely different
      things: a language-learning goal might turn on which languages they already speak and whether
      there's a trip or deadline; an infrastructure goal might turn on target scale and whether a
      codebase already exists; a fitness or creative goal might turn on equipment/access and how
      much of the basics they already have. Those are illustrations of the KIND of specificity
      wanted, not a menu to pick from — read the actual goal and decide what's genuinely load-
      bearing for it. Time-per-week and prior experience are sometimes the right dimensions, but
      treat them as two options among many, never the default.

      Feel free to ask about things beyond raw time/experience when they'd change the plan more:
      a deadline or target date, budget for paid resources, tools or hardware access, the real
      motivation (career change vs. hobby vs. passing a specific exam), whether this has to fit an
      existing team's stack or is fully solo.

      If a confirmed profile is given, do not ask about anything it already answers. Instead of
      re-asking, state your assumption as a plain statement the person can correct (e.g. "Assuming
      ~5–8 hrs/week and solid backend experience from your profile — different for this one?").
      This is a hard rule, not a suggestion: never ask a question whose answer the profile already
      gives.

      If the goal is narrow, specific, and the profile already covers most of what would matter,
      it is completely fine to return zero questions — say so by returning an empty list rather
      than inventing a question just to have one. If the goal is broad or vague, more questions
      (up to four) are warranted precisely because forcing it into fewer would make each one
      shallow.

      Hard rules:
      - 0 to 4 questions. Every one must be genuinely load-bearing for this specific goal — cut
        anything that would apply to a random other goal unchanged.
      - Each question is one plain line, in the user's own clear-headed inner voice — not a
        form field, not a chatbot. No greeting, no preamble, no "let me ask".
      - No praise, no encouragement, no emoji, no exclamation points.
      - Output ONLY strict JSON, no prose around it: {"questions": ["...", "..."]}
      """;

  public static String clarifyUser(String goal, String profileContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    sb.append("Write the clarifying questions as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for an optional single follow-up round (Phase 17), conditioned on the first
   * round's actual answers — a genuine follow-up, not a second generic pass. Returning nothing
   * is the expected common case; this must not become a de facto third hoop for every goal.
   */
  public static final String FOLLOWUP_CLARIFY_SYSTEM = """
      The user already answered a first round of clarifying questions about their roadmap goal.
      Look at what they actually said. Only if one of their answers raises a real, more specific
      follow-up question worth asking before drafting — ask it now. If their answers were already
      clear and specific enough to draft from, return an empty list; that should be the common
      outcome, not the exception.

      Example of a genuine follow-up: they said "a few months, part-time" for a deadline question
      — worth pinning down roughly how many hours a week that means. Example of a NON-follow-up:
      they gave a clear, specific answer already — do not ask a rephrased version of the same
      question, and do not ask something new just to fill a slot.

      Hard rules:
      - 0 to 2 questions. Zero is the expected common case.
      - Each question is one plain line, self-talk voice, no greeting, no "thanks for answering".
      - Output ONLY strict JSON, no prose around it: {"questions": ["...", "..."]}
      """;

  public static String followUpClarifyUser(String goal, String firstRoundQa, String profileContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    sb.append("First round of questions and answers:\n")
        .append(firstRoundQa == null ? "" : firstRoundQa.trim()).append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    sb.append("Write any genuine follow-up questions as JSON (usually none).");
    return sb.toString();
  }

  /**
   * System prompt for the assessment pass (Phase 18) — one shared, structured read of how big
   * and complex a goal actually is, so the outline/expand/flat prompts all read the same numbers
   * instead of independently re-guessing scope from raw text. Never shown to the user, so the
   * self-talk-voice rules don't apply here — this is an internal signal, plain and analytical.
   */
  public static final String ASSESS_SYSTEM = """
      Assess a learning/goal-planning request before any structure is drafted for it. This output
      is never shown to the user directly — it's an internal sizing signal other prompts read, so
      be plain, analytical, and precise rather than encouraging or hedged.

      Judge:
      - complexity: 1-5, how much genuine structure this goal needs. 1 is a single afternoon task
        needing a short flat checklist; 5 is a multi-month, multi-domain undertaking needing deep
        nested structure (named modules, each broken into its own steps later).
      - estimatedTotalHours: your best honest estimate of total hours to reach the stated depth,
        given their stated experience/time — a plain integer, or null if genuinely unknowable from
        what's given.
      - domain: a couple of words naming the general field (e.g. "systems programming", "language
        learning", "fitness", "cooking").
      - priorLevel: a couple of words on their apparent starting point for THIS goal specifically
        (e.g. "complete beginner", "some adjacent experience", "returning after a break") — read
        from their answers/profile, don't invent detail they didn't give.
      - shape: "flat" when complexity is low enough that a single ordered checklist of steps
        covers it honestly (no need for named modules/areas); "nested" when it genuinely breaks
        into distinct major areas that each deserve their own expansion. Most small, narrow goals
        are flat; most "learn X" or "become able to Y" goals spanning weeks/months are nested.
      - archetype: a coarse read of what KIND of goal this is, since complexity/shape alone can't
        tell a deep single-topic dive apart from a career pivot at the same scale:
        - "quick_task": a short, bounded thing — done in a session or two, no real learning arc.
        - "topic_deep_dive": genuinely learning or mastering one subject/skill area, however deep —
          no implication of a job/portfolio outcome (e.g. "understand distributed systems deeply",
          "get fluent in Spanish").
        - "career_path": the goal is explicitly about becoming employable/job-ready in a role or
          switching careers (e.g. "become a DevOps engineer", "break into data science") — implies
          a recognizable arc (foundations → tools → specialization → a portfolio/proof of work) and
          real, shareable projects, not just conceptual mastery. Only use this when the goal itself
          is about the career outcome, not merely a topic that happens to be used professionally.

      Hard rules:
      - Be honest and specific, not deferential — a goal that is actually small gets a low
        complexity and "flat", even if the wording sounds ambitious.
      - Output ONLY strict JSON, no prose around it:
        {"complexity": 3, "estimatedTotalHours": 40, "domain": "...", "priorLevel": "...", "shape": "nested", "archetype": "topic_deep_dive"}
      """;

  public static String assessUser(String goal, String clarifications, String profileContext,
      String groundingContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    if (clarifications != null && !clarifications.isBlank()) {
      sb.append("What they told you:\n").append(clarifications.trim()).append('\n');
    }
    PromptSupport.appendProfile(sb, profileContext);
    if (groundingContext != null && !groundingContext.isBlank()) {
      sb.append("Real search context (for scale/scope only):\n")
          .append(groundingContext.trim()).append('\n');
    }
    sb.append("Write the assessment as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for the Roadmap Brain tier classifier (RB-1) — a standalone, isolated
   * function not yet wired into any real generation path. Its whole job is to sort a goal into
   * TASK / MINI / TOPIC / CAREER before anything else happens. This output is never shown to
   * the founder directly at this stage, so plain and analytical, same discipline as
   * {@link #ASSESS_SYSTEM}. Ambiguity in these tier definitions — not raw model capability — is
   * the most likely cause of unreliable classification, so the definitions below are written to
   * be as concrete as possible, with worked examples at every boundary.
   */
  public static final String TIER_CLASSIFY_SYSTEM = """
      Classify a goal into exactly one of four tiers: TASK, MINI, TOPIC, or CAREER. This output
      is never shown to the person directly — it's an internal routing signal, so be plain,
      analytical, and honest rather than hedged or generous.

      The four tiers, in order of scale, with what separates each one from its neighbor:

      TASK — a single action item with no learning curve. It is something to DO, not something
      to GET BETTER AT. Reading one chapter, watching one talk, fixing one typo, sending one
      email. There is no skill being built here, no depth to reach — just an item to check off.
      Examples: "Read chapter 3 of the book on my desk this week", "Reply to the email from my
      advisor", "Watch that conference talk I bookmarked", "Fix the typo in my resume".

      MINI — a bounded, single project with a clear finish line. Building it requires applying
      skills (possibly picking up a few small new ones along the way), but the goal is the
      finished artifact, not open-ended mastery of a subject. You'd know when it's done — it
      either exists and works, or it doesn't. What separates MINI from TASK: a MINI has real
      internal structure (multiple steps, some sequencing, actual building) even though the
      overall scope is still small. What separates MINI from TOPIC: a MINI is scoped to "build
      this one thing," not "get good at this skill area" — the skill is a means, the project is
      the end. Examples: "Build a personal budget tracker app", "Make a Discord bot that reminds
      me to drink water", "Build a portfolio website for myself", "Write a script that backs up
      my photos automatically".

      TOPIC — open-ended skill or knowledge acquisition in one subject area, with no single
      finish line and no implied job/career outcome. The goal is understanding or capability
      itself, at whatever depth the person wants to take it — "learn X" or "get good at Y" for
      its own sake, a hobby, curiosity, or a general capability upgrade. What separates TOPIC
      from MINI: there's no single artifact that marks "done" — depth is genuinely open-ended
      and could keep growing. What separates TOPIC from CAREER: nothing here implies becoming
      employable in a new role or switching what the person does for a living — it's learning
      IN a domain, not transitioning INTO one professionally. Examples: "Learn Docker", "Get
      good at SQL", "Learn conversational Spanish", "Understand how neural networks actually
      work".

      CAREER — the goal is explicitly about an identity or role change: becoming employable in a
      new role, or switching what the person does for work. This is the largest tier — it
      typically spans months, not weeks, and implies a recognizable arc (foundations, core
      tooling, specialization, portfolio/proof of work), not just conceptual mastery of one
      topic. What separates CAREER from TOPIC: the goal is stated in terms of the career/role
      outcome itself ("become a...", "transition into...", "break into..."), not merely a topic
      that happens to be useful professionally. A goal about a topic that's often used
      professionally (e.g. "learn Kubernetes") stays TOPIC unless the person frames it as part of
      becoming something ("become a DevOps engineer" is CAREER; "learn Kubernetes" alone is
      TOPIC). Examples: "Become a DevOps engineer", "Transition from frontend to backend
      engineering as a career", "Become a data scientist starting from zero", "Pivot into
      cybersecurity as my next career".

      Ambiguous goals are expected and normal — when a goal genuinely sits on a boundary (e.g. it
      could be read as TOPIC or as CAREER depending on unstated context), pick the more likely
      reading, give it a lower confidence score, and say in your reasoning exactly what the
      ambiguity is and which reading you chose. Do not force artificial certainty.

      Some goals are not a clean fit for any tier at all — garbage/unparseable input, a goal with
      no real object ("get better" — better at what?), or something that isn't a learning/growth
      goal in the first place (e.g. "plan my wedding"). For these, still return your best-guess
      tier (never leave it blank), but give it genuinely low confidence and say plainly in the
      reasoning that this doesn't fit cleanly and why — a confident wrong answer here is worse
      than an honest low-confidence one. The same applies to unrealistic urgency framing (e.g.
      "master AI today") — the actual scope of the content still drives the tier, an unrealistic
      deadline does not shrink a TOPIC/CAREER-scale goal down to TASK.

      Hard rules:
      - tier is exactly one of "TASK", "MINI", "TOPIC", "CAREER" — always pick one, never leave
        it null or invent a fifth value.
      - confidence is a number from 0.0 to 1.0 — reserve anything above 0.8 for genuinely
        clear-cut cases; ambiguous or poor-fit goals should score meaningfully lower, not a
        token deduction.
      - reasoning is 1-3 plain sentences stating what specifically about the goal's wording
        drove the tier choice — not a restatement of the tier's definition.
      - Output ONLY strict JSON, no prose around it:
        {"tier": "TOPIC", "confidence": 0.9, "reasoning": "..."}
      """;

  public static String tierClassifyUser(String goal, String profileContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    sb.append("Write the tier classification as JSON.");
    return sb.toString();
  }

}
