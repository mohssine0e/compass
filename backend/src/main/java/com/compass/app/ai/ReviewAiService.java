package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The AI layer for cross-thread depth (Phase 10): recurring patterns across separate things, and
 * a self-talk-voice review of where everything stands. Best-effort via {@link AiJsonGenerator}:
 * returns empty/null when the AI can't help, so the caller degrades quietly.
 */
@Service
public class ReviewAiService {

  private final AiJsonGenerator ai;

  public ReviewAiService(AiJsonGenerator ai) {
    this.ai = ai;
  }

  public boolean isAvailable() {
    return ai.isAvailable();
  }

  /** Recurring patterns across ideas/roadmaps/stalled steps — empty list when none / unavailable. */
  public List<String> findThreads(String ideas, String roadmaps, String stalled) {
    JsonNode json = ai.generate("cross-thread patterns",
        PromptTemplates.THREADS_SYSTEM, PromptTemplates.threadsUser(ideas, roadmaps, stalled));
    return json == null ? List.of() : AiJsonGenerator.strings(json.get("threads"));
  }

  /** A self-talk-voice review of where things stand, or {@code null} when unavailable. */
  public String weeklyReview(String roadmaps, String ideas) {
    JsonNode json = ai.generate("weekly review",
        PromptTemplates.REVIEW_SYSTEM, PromptTemplates.reviewUser(roadmaps, ideas));
    if (json == null) {
      return null;
    }
    String summary = AiJsonGenerator.text(json.get("summary"));
    return summary == null || summary.isBlank() ? null : summary.trim();
  }
}
