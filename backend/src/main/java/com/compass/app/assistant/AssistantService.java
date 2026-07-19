package com.compass.app.assistant;

import com.compass.app.ai.AiExplainService;
import com.compass.app.assistant.dto.ExplainRequest;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates in-content help (Phase 8.5): pulls the step context and the confirmed learner
 * profile so the AI can calibrate, then asks {@link AiExplainService}. Never replaces the
 * original text — it only returns help to show alongside it.
 */
@Service
public class AssistantService {

  private final AiExplainService aiExplain;
  private final EntryRepository entries;
  private final ProfileService profileService;

  public AssistantService(AiExplainService aiExplain, EntryRepository entries,
      ProfileService profileService) {
    this.aiExplain = aiExplain;
    this.entries = entries;
    this.profileService = profileService;
  }

  @Transactional(readOnly = true)
  public String explain(ExplainRequest req) {
    String selected = req.selectedText() != null ? req.selectedText().trim() : "";
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("Select some text first.");
    }
    if (!aiExplain.isAvailable()) {
      throw new IllegalStateException("Help is unavailable right now.");
    }

    ExplainRequest.ExplainContext ctx = req.context();
    String action = ctx != null ? ctx.action() : "explain";
    String depth = ctx != null ? ctx.preferredDepth() : null;
    String language = ctx != null ? ctx.preferredLanguage() : null;
    String stepText = ctx != null ? stepTextOf(ctx.stepId()) : null;
    String profileContext = profileService.confirmedProfile()
        .map(ProfileContext::forPrompt)
        .orElse(null);

    String response = aiExplain.help(action, selected, stepText, profileContext, depth, language);
    if (response == null) {
      throw new IllegalStateException("Couldn't help with that right now.");
    }
    return response;
  }

  private String stepTextOf(Long stepId) {
    if (stepId == null) {
      return null;
    }
    return entries.findById(stepId)
        .map(Entry::getContent)
        .map(c -> c.get("text"))
        .filter(t -> t instanceof String)
        .map(Object::toString)
        .orElse(null);
  }
}
