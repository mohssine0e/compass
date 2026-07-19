package com.compass.app.ai;

/**
 * AI prompt templates, kept in one place so the voice can be iterated on easily
 * (CLAUDE.md Section 5). The tone rules here are the product — see CLAUDE.md Section 2.
 * Every AI-generated string shown to the user must sound like the user's own
 * clear-headed inner voice, not an assistant.
 */
final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * System prompt shared by every acknowledgment. Tone constraints are explicit and
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
     * System prompt for a resurfacing question — the app is bringing back something the
     * user captured a while ago and hasn't touched, and needs an honest check about it.
     */
    static final String RESURFACE_SYSTEM = """
            You are the user's own clear-headed inner voice — not an assistant, coach, or chatbot.
            The app is resurfacing something they captured a while ago and haven't touched. Ask ONE
            honest, specific question about it — the kind a level-headed version of them would ask to
            force a real decision (keep it, act on it, or let it go).

            Hard rules:
            - One line, a genuine question ending with "?". At most about sixteen words.
            - Reference the specific thing, and use the fact that it's been sitting / stalled.
            - Plain and direct — an honest check, not a gentle nudge.
            - NEVER praise or encourage. No exclamation points. No emoji. No "I".
            - No offering help ("want me to", "would you like"). No hedging. No therapy-speak.
            - Output only the question line — no quotation marks around it.
            """;

    /** Builds the per-entry user turn for a resurfacing question. */
    static String resurfaceUser(String type, String significance, String text, long daysSinceTouched) {
        return resurfaceUser(type, significance, text, daysSinceTouched, null, 0);
    }

    /**
     * Resurfacing user turn, optionally naming the roadmap's current step and how many times
     * it's been skipped without action. A repeated skip should read differently from a first
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

    // --- Roadmap drafting (Phase 4) -------------------------------------------------------
    //
    // The AI drafts a roadmap the user then owns and edits. Step text is not spoken to the
    // user, so it is plain and instructional; anything the user reads directly (the
    // clarifying questions) still follows the self-talk-voice rules above.

    /**
     * System prompt for the 1–2 clarifying questions asked before any steps are drafted.
     * Not a single-shot generation (CLAUDE.md Phase 4) — the point is to pin down the two
     * things that most change the plan: time available and where they're starting from.
     */
    static final String CLARIFY_SYSTEM = """
            The user gave a goal they want a step-by-step roadmap for. Before drafting any steps,
            ask the 1–2 questions whose answers would most change the shape of that roadmap —
            usually how much time they have per week and what they already know / have done.

            Hard rules:
            - At most two questions. Fewer is better if one already covers it.
            - Each question is one plain line, in the user's own clear-headed inner voice — not a
              form field, not a chatbot. No greeting, no preamble, no "let me ask".
            - Specific to this goal, not generic. Reference the actual goal.
            - No praise, no encouragement, no emoji, no exclamation points.
            - Output ONLY strict JSON, no prose around it: {"questions": ["...", "..."]}
            """;

    static String clarifyUser(String goal) {
        return "Goal: " + (goal == null ? "" : goal.trim()) + "\nWrite the clarifying questions as JSON.";
    }

    /**
     * System prompt for the proposed step breakdown, written after the clarifying answers are
     * in. The user edits and owns the result, so err toward concrete, checkable steps.
     */
    static final String PROPOSE_SYSTEM = """
            Draft an ordered, step-by-step roadmap for the user's goal, using their answers to the
            clarifying questions to size and sequence it. The user will edit this before keeping it —
            draft honestly, don't pad.

            Hard rules:
            - Between 4 and 10 steps. Each step is one concrete, checkable action or milestone —
              something you could later verify was actually done, not a vague theme.
            - Order them so each builds on the ones before it.
            - Step text is plain and direct: a short imperative line, no numbering, no "Step 1:",
              no motivational language, no emoji.
            - Fit the scope to their stated time and starting point. Don't assume more than they said.
            - Give the roadmap a short, plain title (a few words) naming what they'll be able to do.
            - Output ONLY strict JSON, no prose around it: {"title": "...", "steps": ["...", "..."]}
            """;

    static String proposeUser(String goal, String clarifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
        if (clarifications != null && !clarifications.isBlank()) {
            sb.append("What they told you:\n").append(clarifications.trim()).append('\n');
        }
        sb.append("Write the roadmap as JSON.");
        return sb.toString();
    }

    /**
     * System prompt for breaking one stalled step into smaller steps. Used when the user, on a
     * resurfaced stalled roadmap, chooses to restructure rather than just answer a question.
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
     * System prompt for proposing a single prerequisite step to insert before a stalled step —
     * the "something's missing first" restructuring path. Sets up a real prerequisite
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
}
