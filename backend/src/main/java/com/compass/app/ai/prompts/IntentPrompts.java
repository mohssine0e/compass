package com.compass.app.ai.prompts;

/**
 * Prompts for {@link com.compass.app.ai.IntentAiService} — the unified intake's first step,
 * classifying what one piece of input actually wants (RB-5.1/5.2). Internal routing signal,
 * never shown to the founder — the self-talk-voice rules don't apply here.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class IntentPrompts {

  private IntentPrompts() {
  }

  /**
   * System prompt for the unified intake's intent classifier (RB-5.1) — the very first thing
   * any input goes through now that Capture and Draft-with-AI are one input. Never shown to the
   * founder directly, so plain and analytical, same discipline as {@link #ASSESS_SYSTEM}. Ten
   * intents, concrete definitions with examples at every boundary — ambiguity in the
   * definitions, not raw model capability, is the likely failure mode (same lesson as RB-1's
   * tier classifier).
   */
  public static final String INTENT_CLASSIFY_SYSTEM = """
      Classify what the person actually wants from this one piece of input into exactly one of
      ten intents. This is never shown to them directly — an internal routing signal, so be
      plain, analytical, and honest rather than hedged.

      The ten intents:
      - IDEA: a fleeting thought worth holding onto, not asking the system to do anything with it
        right now. "Build a CLI tool for tracking habits", "we should redesign the onboarding".
      - LEARN: open-ended skill/knowledge acquisition, a hobby or capability upgrade, no career
        framing. "Learn Docker", "get good at SQL".
      - DO: a single action item, no learning curve — something to check off, not get better at.
        "Reply to the email from my advisor", "fix the typo in my resume".
      - PLAN_A_JOURNEY: a bounded project OR an explicit career/identity change goal — anything
        that needs a real multi-step roadmap beyond open-ended topic learning. "Build a portfolio
        website", "become a DevOps engineer".
      - PRACTICE: wants to actively rehearse/apply something they already have some grounding in,
        right now — not learn it fresh. "Let me practice some SQL queries."
      - REVIEW: wants a check-in on how something already learned/done is holding up. "Quiz me on
        what I covered in the Docker module", "how sharp am I still on closures?"
      - PREPARE: getting ready for a specific known event/deadline (exam, interview, presentation)
        — similar shape to PLAN_A_JOURNEY but explicitly framed around preparing for a fixed
        target. "Get ready for my AWS certification exam next month."
      - EXPLORE: open-ended browsing/discovery with no specific goal yet — wants to see what's out
        there, not commit to a plan. "What should I learn next in backend development?"
      - TROUBLESHOOT: a specific problem or confusion right now, wants it explained/resolved, not
        a learning plan. "Why does my Rust borrow checker error happen here?"
      - ASSESS: wants to know their own current level/standing in something, not to learn or plan.
        "How good is my Python actually?"

      What distinguishes the close pairs:
      - LEARN vs. PLAN_A_JOURNEY: LEARN is open-ended ("learn X"), no artifact/career outcome;
        PLAN_A_JOURNEY has a concrete finish line (a built thing) or an identity/career change.
      - PRACTICE vs. REVIEW: PRACTICE is forward-looking rehearsal of a skill; REVIEW is
        specifically checking retention of something already covered.
      - EXPLORE vs. LEARN: EXPLORE has no specific topic committed to yet; LEARN already names one.
      - TROUBLESHOOT vs. DO: TROUBLESHOOT is a confusion/problem to resolve by understanding it;
        DO is a plain action item with nothing to figure out.

      Hard rules:
      - intent is exactly one of: IDEA, LEARN, DO, PLAN_A_JOURNEY, PRACTICE, REVIEW, PREPARE,
        EXPLORE, TROUBLESHOOT, ASSESS. Always pick one, never invent another value.
      - confidence is 0.0-1.0 — reserve above 0.8 for genuinely clear-cut cases.
      - reasoning is 1-2 plain sentences on what specifically drove the call.
      - Output ONLY strict JSON, no prose around it:
        {"intent": "LEARN", "confidence": 0.9, "reasoning": "..."}
      """;

  public static String intentClassifyUser(String input, String profileContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("Input: ").append(input == null ? "" : input.trim()).append('\n');
    PromptSupport.appendProfile(sb, profileContext);
    sb.append("Write the intent classification as JSON.");
    return sb.toString();
  }

  // --- Canonical Topic Matching (RB-3) ----------------------------------------------------
}
