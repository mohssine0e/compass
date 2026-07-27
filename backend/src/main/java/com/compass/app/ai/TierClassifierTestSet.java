package com.compass.app.ai;

import java.util.List;

/**
 * The exact 26-goal validation set from {@code TASKS_v2.md} RB-1.3 — use this set, not a
 * different one, every time {@link PromptTemplates#TIER_CLASSIFY_SYSTEM} changes. Cases 1-16
 * have a stated expected tier (clear-cut); cases 17-26 have {@code expectedTier == null}
 * (deliberately ambiguous or adversarial) — for those, only reasoning quality/plausibility is
 * judged, not tier agreement, so no pass/fail is computed for them.
 */
final class TierClassifierTestSet {

    private TierClassifierTestSet() {
    }

    record Case(String goal, Tier expectedTier) {
    }

    static final List<Case> CASES = List.of(
            // Clear-cut TASK
            new Case("Read chapter 3 of the book on my desk this week", Tier.TASK),
            new Case("Watch that conference talk on Rust async I bookmarked", Tier.TASK),
            new Case("Fix the typo in my resume", Tier.TASK),
            new Case("Reply to the email from my advisor about the internship", Tier.TASK),

            // Clear-cut MINI
            new Case("Build a personal budget tracker app", Tier.MINI),
            new Case("Make a simple Discord bot that reminds me to drink water", Tier.MINI),
            new Case("Build a portfolio website for myself", Tier.MINI),
            new Case("Write a short script that backs up my photos automatically", Tier.MINI),

            // Clear-cut TOPIC
            new Case("Learn Docker", Tier.TOPIC),
            new Case("Get good at SQL", Tier.TOPIC),
            new Case("Learn conversational Spanish", Tier.TOPIC),
            new Case("Understand how neural networks actually work", Tier.TOPIC),

            // Clear-cut CAREER
            new Case("Become a DevOps engineer", Tier.CAREER),
            new Case("Transition from frontend to backend engineering as a career", Tier.CAREER),
            new Case("Become a data scientist starting from zero", Tier.CAREER),
            new Case("Pivot into cybersecurity as my next career", Tier.CAREER),

            // Deliberately ambiguous boundary cases — no single "correct" answer
            new Case("Learn Kubernetes", null),
            new Case("Build a small SaaS app", null),
            new Case("Get better at system design for interviews", null),
            new Case("Master Python", null),
            new Case("Learn enough web development to build my own startup's MVP", null),
            new Case("Get certified in AWS", null),

            // Adversarial / edge cases — expect graceful handling, not a confident wrong answer
            new Case("I need to master AI TODAY", null),
            new Case("asdkfj learn stuff", null),
            new Case("Plan my wedding", null),
            new Case("Get better", null));
}
