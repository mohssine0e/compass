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

  /**
   * Proposed theme clusters over a list of idea texts (Phase 14) — for the founder to rename or
   * drop before anything is tagged. Indices are validated against {@code ideaTexts.size()} and
   * deduplicated so no idea appears in two themes; empty list when unavailable or nothing clusters.
   */
  public List<Cluster> clusterIdeas(List<String> ideaTexts) {
    if (ideaTexts == null || ideaTexts.size() < 2) {
      return List.of();
    }
    JsonNode json = ai.generate("idea clustering",
        PromptTemplates.CLUSTER_SYSTEM, PromptTemplates.clusterUser(ideaTexts));
    if (json == null || json.get("themes") == null || !json.get("themes").isArray()) {
      return List.of();
    }
    List<Cluster> clusters = new java.util.ArrayList<>();
    java.util.Set<Integer> claimed = new java.util.HashSet<>();
    for (JsonNode node : json.get("themes")) {
      String label = AiJsonGenerator.text(node.get("label"));
      if (label == null || label.isBlank() || node.get("indices") == null
          || !node.get("indices").isArray()) {
        continue;
      }
      List<Integer> indices = new java.util.ArrayList<>();
      for (JsonNode i : node.get("indices")) {
        if (i.isInt() && i.asInt() >= 0 && i.asInt() < ideaTexts.size() && claimed.add(i.asInt())) {
          indices.add(i.asInt());
        }
      }
      if (indices.size() >= 2) {
        clusters.add(new Cluster(label.trim(), indices));
      }
    }
    return clusters;
  }

  /** A proposed theme: its label and the 0-based indices (into the request list) that fit it. */
  public record Cluster(String label, List<Integer> indices) {
  }
}
