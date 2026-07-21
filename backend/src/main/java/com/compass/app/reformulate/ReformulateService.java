package com.compass.app.reformulate;

import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import com.compass.app.reformulate.dto.ApplyReformulateRequest;
import com.compass.app.reformulate.dto.ReformulateProposal;
import com.compass.app.roadmap.RoadmapService;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * User-initiated reformulation of a step that feels too hard/big/abstract (Phase 8.5). Reuses
 * the Phase 4 restructuring (break down / add prerequisite) and Phase 7.5 resource discovery
 * (easier resources), but targeted at a specific step the user is looking at — always
 * propose → approve → apply, never a silent edit. Tracks how often a step is reformulated so
 * the self-talk voice can notice a pattern.
 */
@Service
public class ReformulateService {

  private final EntryRepository repository;
  private final RoadmapService roadmapService;
  private final RoadmapAiService roadmapAi;
  private final SearchGroundingService searchGrounding;
  private final ProfileService profileService;

  public ReformulateService(EntryRepository repository, RoadmapService roadmapService,
      RoadmapAiService roadmapAi, SearchGroundingService searchGrounding,
      ProfileService profileService) {
    this.repository = repository;
    this.roadmapService = roadmapService;
    this.roadmapAi = roadmapAi;
    this.searchGrounding = searchGrounding;
    this.profileService = profileService;
  }

  /** Draft a reformulation of a step — not applied. {@code kind} is break_down / add_prerequisite / easier_resources. */
  @Transactional(readOnly = true)
  public ReformulateProposal propose(Long stepId, String kind) {
    Entry step = requireStep(stepId);
    Long roadmapId = step.getParentId();
    String stepText = stringOf(step, "text");
    String roadmapTitle = roadmapId == null ? null
        : repository.findById(roadmapId).map(r -> stringOf(r, "text") != null
            ? stringOf(r, "text") : stringOf(r, "title")).orElse(null);
    String note = reformulateNote(step);

    return switch (kind == null ? "" : kind) {
      case "break_down" -> {
        if (roadmapService.isAtMaxStepDepth(stepId)) {
          throw new IllegalStateException("This is already broken down as far as it goes.");
        }
        String profileContext = profileService.confirmedProfile()
            .map(p -> ProfileContext.forModulePrompt(p, roadmapTitle, stepText))
            .orElse(null);
        SearchGroundingService.Grounding grounding = searchGrounding.ground(stepText);
        String groundingContext = grounding == null ? null : grounding.context();

        List<RoadmapAiService.DraftStep> smaller =
            roadmapAi.breakDownStep(roadmapTitle, stepText, profileContext, groundingContext);
        if (smaller == null) {
          throw new IllegalStateException("Couldn't break this down right now.");
        }
        List<String> smallerTexts = smaller.stream().map(RoadmapAiService.DraftStep::text).toList();
        List<List<RoadmapAiService.Resource>> resources = roadmapAi.suggestResources(
            stepText, smallerTexts, grounding == null ? null : grounding.results(),
            avoidedFormats(), preferredFormats(), roadmapService.usedResourceUrls(roadmapId));
        // Reuses GenerateRoadmapResponse's own step-building logic rather than duplicating it —
        // a break-down proposal is really just a same-batch (no cross-module) module expansion.
        List<GenerateRoadmapResponse.ProposedStep> proposedSteps = GenerateRoadmapResponse.proposal(
                null, null, smaller, resources, List.of(), List.of(), null, Map.of())
            .steps();
        yield ReformulateProposal.breakDown(roadmapId, stepId, stepText, proposedSteps, note);
      }
      case "add_prerequisite" -> {
        RoadmapAiService.Prerequisite p =
            roadmapAi.proposePrerequisite(roadmapTitle, stepText, priorStepsText(roadmapId, step));
        if (p == null) {
          throw new IllegalStateException("Nothing obvious to revisit first — this may just need doing.");
        }
        yield ReformulateProposal.prerequisite(roadmapId, stepId, stepText, p.step(), p.why(), note);
      }
      case "easier_resources" -> {
        List<Map<String, Object>> resources = easierResources(stepText);
        if (resources.isEmpty()) {
          throw new IllegalStateException("Couldn't find gentler resources right now.");
        }
        yield ReformulateProposal.resources(roadmapId, stepId, stepText, resources, note);
      }
      default -> throw new IllegalArgumentException("Unknown reformulation: " + kind);
    };
  }

  /** Apply an approved reformulation and record that the step was reformulated. */
  @Transactional
  public void apply(Long stepId, ApplyReformulateRequest req) {
    Entry step = requireStep(stepId);
    Long roadmapId = step.getParentId();

    switch (req.kind() == null ? "" : req.kind()) {
      case "break_down" -> roadmapService.splitStep(roadmapId, stepId, req.draftSteps());
      case "add_prerequisite" -> roadmapService.addPrerequisite(roadmapId, stepId, req.prerequisite());
      case "easier_resources" -> replaceResources(stepId, req.resources());
      default -> throw new IllegalArgumentException("Unknown reformulation: " + req.kind());
    }
    // break_down replaces the step entirely, so only bump the counter when the step survives.
    if (!"break_down".equals(req.kind())) {
      bumpReformulateCount(stepId);
    }
  }

  /** Gentler resources for the step: ground a beginner-leaning search and suggest for it. */
  private List<Map<String, Object>> easierResources(String stepText) {
    if (stepText == null || stepText.isBlank()) {
      return List.of();
    }
    SearchGroundingService.Grounding grounding = searchGrounding.ground(stepText + " beginner tutorial");
    if (grounding == null) {
      return List.of();
    }
    List<List<RoadmapAiService.Resource>> perStep = roadmapAi.suggestResources(
        stepText, List.of(stepText), grounding.results(), avoidedFormats(), preferredFormats(), Set.of());
    List<Map<String, Object>> out = new ArrayList<>();
    if (!perStep.isEmpty()) {
      for (RoadmapAiService.Resource r : perStep.get(0)) {
        out.add(resourceToMap(r));
      }
    }
    return out;
  }

  private void replaceResources(Long stepId, List<Map<String, Object>> resources) {
    Entry step = requireStep(stepId);
    Map<String, Object> content = copyContent(step);
    List<Map<String, Object>> stored = new ArrayList<>();
    if (resources != null) {
      for (Map<String, Object> r : resources) {
        Object title = r.get("title");
        Object url = r.get("url");
        if (!(title instanceof String t) || t.isBlank() || !(url instanceof String u) || u.isBlank()) {
          continue;
        }
        Map<String, Object> map = new LinkedHashMap<>(r);
        map.putIfAbsent("id", UUID.randomUUID().toString());
        map.putIfAbsent("userRating", null);
        stored.add(map);
      }
    }
    content.put("resources", stored);
    step.setContent(content);
    repository.save(step);
    touchParent(step);
  }

  private String reformulateNote(Entry step) {
    int count = intOf(step, "reformulateCount");
    if (count >= 2) {
      return "You've reformulated this step " + count + " times already. Is the topic wrong, or the approach?";
    }
    return null;
  }

  private void bumpReformulateCount(Long stepId) {
    Entry step = requireStep(stepId);
    Map<String, Object> content = copyContent(step);
    content.put("reformulateCount", intOf(step, "reformulateCount") + 1);
    step.setContent(content);
    repository.save(step);
  }

  private List<String> avoidedFormats() {
    return roadmapService.avoidedFormats();
  }

  private List<String> preferredFormats() {
    return roadmapService.preferredFormats();
  }

  private String priorStepsText(Long roadmapId, Entry step) {
    if (roadmapId == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (Entry s : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
      if (s.getId().equals(step.getId())) {
        break;
      }
      String text = stringOf(s, "text");
      if (text != null && !text.isBlank()) {
        sb.append("- ").append(text).append('\n');
      }
    }
    return sb.toString();
  }

  private static Map<String, Object> resourceToMap(RoadmapAiService.Resource r) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("title", r.title());
    map.put("url", r.url());
    if (r.format() != null) {
      map.put("format", r.format());
    }
    if (r.sourceType() != null) {
      map.put("sourceType", r.sourceType());
    }
    if (r.estimatedTime() != null) {
      map.put("estimatedTime", r.estimatedTime());
    }
    if (r.aiGroundingSource() != null) {
      map.put("aiGroundingSource", r.aiGroundingSource());
    }
    return map;
  }

  private Entry requireStep(Long stepId) {
    return repository.findById(stepId)
        .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
        .orElseThrow(() -> new NoSuchElementException("No step " + stepId));
  }

  private void touchParent(Entry step) {
    if (step.getParentId() != null) {
      repository.touchUpdatedAt(step.getParentId(), java.time.Instant.now());
    }
  }

  private static Map<String, Object> copyContent(Entry entry) {
    return entry.getContent() != null ? new HashMap<>(entry.getContent()) : new HashMap<>();
  }

  private static String stringOf(Entry entry, String key) {
    Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
    return value instanceof String s ? s : null;
  }

  private static int intOf(Entry entry, String key) {
    Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
    return value instanceof Number n ? n.intValue() : 0;
  }
}
