package com.compass.app.ai.dto;

/**
 * One row of the RB-1 debug screen's "Run all 26 test goals" table. {@code expectedTier} is
 * null for the deliberately ambiguous/adversarial cases (17-26) — {@code pass} is likewise
 * null for those, since only reasoning quality is judged there, not tier agreement.
 */
public record TierTestCaseResult(String goal, String expectedTier, String actualTier,
                                  double confidence, String reasoning, Boolean pass) {
}
