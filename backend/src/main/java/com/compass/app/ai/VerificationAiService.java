package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * The AI layer for step verification (Phase 8): generate a fair check for a step, and judge an
 * answer honestly — returning the specific gap in the user's self-talk voice, not a bare
 * pass/fail. Shared JSON plumbing via {@link AiJsonGenerator}. Best-effort: returns {@code null}
 * when the AI can't help, so the caller can surface that rather than block silently.
 */
@Service
public class VerificationAiService {

  private final AiJsonGenerator ai;

  public VerificationAiService(AiJsonGenerator ai) {
    this.ai = ai;
  }

  public boolean isAvailable() {
    return ai.isAvailable();
  }

  /** A fair check for a step at the given rigor ("light"/"full"), or {@code null} on failure. */
  public String generateCheck(String roadmapTitle, String stepText, String rigor) {
    JsonNode json = ai.generate(AiTier.FAST, "verification check",
        PromptTemplates.CHECK_SYSTEM, PromptTemplates.checkUser(roadmapTitle, stepText, rigor));
    if (json == null) {
      return null;
    }
    String question = AiJsonGenerator.text(json.get("question"));
    return question == null || question.isBlank() ? null : question.trim();
  }

  /** Judge an answer to a check, or {@code null} on failure. */
  public Evaluation evaluate(String stepText, String question, String answer) {
    JsonNode json = ai.generate(AiTier.FAST, "verification evaluation",
        PromptTemplates.EVALUATE_SYSTEM, PromptTemplates.evaluateUser(stepText, question, answer));
    if (json == null) {
      return null;
    }
    JsonNode passedNode = json.get("passed");
    boolean passed = passedNode != null && passedNode.asBoolean(false);
    String gap = AiJsonGenerator.text(json.get("gap"));
    return new Evaluation(passed, passed ? null : (gap == null || gap.isBlank() ? null : gap.trim()));
  }

  /** The verdict on an answer: whether it passed, and the specific gap (self-talk voice) if not. */
  public record Evaluation(boolean passed, String gap) {
  }
}
