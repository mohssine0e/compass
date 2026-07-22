package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI layer for step verification (Phase 8, format variants added Phase 26): generate a fair
 * check for a step, and judge an answer honestly — returning the specific gap in the user's
 * self-talk voice, not a bare pass/fail. Shared JSON plumbing via {@link AiJsonGenerator}.
 * Best-effort: returns {@code null} when the AI can't help, so the caller can surface that rather
 * than block silently.
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

  /**
   * A fair free-text check for a step at the given rigor ("light"/"full") and format
   * ("free_response"/"code_challenge"/"scenario" — Phase 26), or {@code null} on failure.
   */
  public String generateCheck(String roadmapTitle, String stepText, String rigor, String format) {
    JsonNode json = ai.generate(AiTier.FAST, "verification check",
        PromptTemplates.CHECK_SYSTEM, PromptTemplates.checkUser(roadmapTitle, stepText, rigor, format));
    if (json == null) {
      return null;
    }
    String question = AiJsonGenerator.text(json.get("question"));
    return question == null || question.isBlank() ? null : question.trim();
  }

  /**
   * A fair multiple-choice check (Phase 26): exactly 4 options, one correct. {@code null} on
   * failure, or when fewer than 2 usable options come back (unusable as a real MC question).
   */
  public MultipleChoiceCheck generateMultipleChoiceCheck(String roadmapTitle, String stepText, String rigor) {
    JsonNode json = ai.generate(AiTier.FAST, "verification check (multiple choice)",
        PromptTemplates.CHECK_MULTIPLE_CHOICE_SYSTEM,
        PromptTemplates.checkMultipleChoiceUser(roadmapTitle, stepText, rigor));
    if (json == null) {
      return null;
    }
    String question = AiJsonGenerator.text(json.get("question"));
    List<String> options = new ArrayList<>();
    if (json.get("options") != null && json.get("options").isArray()) {
      json.get("options").forEach(n -> {
        String o = AiJsonGenerator.text(n);
        if (o != null && !o.isBlank()) {
          options.add(o.trim());
        }
      });
    }
    JsonNode correctIndexNode = json.get("correctIndex");
    int correctIndex = correctIndexNode != null && correctIndexNode.isInt() ? correctIndexNode.asInt() : -1;
    if (question == null || question.isBlank() || options.size() < 2
        || correctIndex < 0 || correctIndex >= options.size()) {
      return null;
    }
    return new MultipleChoiceCheck(question.trim(), options, correctIndex);
  }

  /** A generated multiple-choice check (Phase 26) — {@code correctIndex} never leaves the server. */
  public record MultipleChoiceCheck(String question, List<String> options, int correctIndex) {
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
