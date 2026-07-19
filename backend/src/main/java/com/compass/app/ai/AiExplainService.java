package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * In-content help for selected text (Phase 8.5): explain, explain-with-background, translate,
 * concrete example, or simplify — in the user's own self-talk voice, calibrated to their
 * profile, never teacher-mode. Best-effort via {@link AiJsonGenerator}: returns {@code null}
 * when the AI can't help.
 */
@Service
public class AiExplainService {

  /** The supported actions; anything else falls back to a plain explanation. */
  static final Set<String> ACTIONS =
      Set.of("explain", "explain_with_background", "translate", "concrete_example", "simplify");

  private final AiJsonGenerator ai;

  public AiExplainService(AiJsonGenerator ai) {
    this.ai = ai;
  }

  public boolean isAvailable() {
    return ai.isAvailable();
  }

  /** Help for the selected text, or {@code null} when unavailable / unusable. */
  public String help(String action, String selectedText, String stepText,
      String profileContext, String depth, String language) {
    String act = ACTIONS.contains(action) ? action : "explain";
    JsonNode json = ai.generate("in-content " + act, PromptTemplates.EXPLAIN_SYSTEM,
        PromptTemplates.explainUser(act, selectedText, stepText, profileContext, depth, language));
    if (json == null) {
      return null;
    }
    String response = AiJsonGenerator.text(json.get("response"));
    return response == null || response.isBlank() ? null : response.trim();
  }
}
