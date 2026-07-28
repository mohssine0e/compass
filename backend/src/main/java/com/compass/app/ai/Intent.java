package com.compass.app.ai;

/**
 * The founder's intent behind a single piece of unified-intake input (RB-5.1) — what RB-2's
 * TASK/MINI/TOPIC/CAREER tier classifier already covers is really just the internals of one of
 * these ten (Learn/Plan a journey/Prepare route into that existing classifier as a sub-step;
 * see {@link com.compass.app.ai.prompts.IntentPrompts#INTENT_CLASSIFY_SYSTEM}). {@code MAINTAIN} is deliberately not a
 * value here — RB-5.1 keeps it out of scope since no destination exists for it yet.
 */
public enum Intent {
    IDEA,
    LEARN,
    DO,
    PLAN_A_JOURNEY,
    PRACTICE,
    REVIEW,
    PREPARE,
    EXPLORE,
    TROUBLESHOOT,
    ASSESS
}
