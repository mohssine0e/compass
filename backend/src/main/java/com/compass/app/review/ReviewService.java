package com.compass.app.review;

import com.compass.app.ai.ReviewAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.review.dto.ReviewResult;
import com.compass.app.roadmap.RoadmapService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Cross-thread depth (Phase 10): notices recurring patterns across separate ideas/roadmaps and
 * takes stock of where everything stands, both in the self-talk voice. Only meaningful once
 * there's some history — otherwise it says so rather than inventing insight.
 */
@Service
public class ReviewService {

  private final EntryRepository repository;
  private final RoadmapService roadmapService;
  private final ReviewAiService reviewAi;

  public ReviewService(EntryRepository repository, RoadmapService roadmapService,
      ReviewAiService reviewAi) {
    this.repository = repository;
    this.roadmapService = roadmapService;
    this.reviewAi = reviewAi;
  }

  @Transactional(readOnly = true)
  public ReviewResult review() {
    List<Entry> all = repository.findAllByOrderByCreatedAtDesc();

    List<Entry> ideas = all.stream()
        .filter(e -> e.getType() == EntryType.IDEA && e.getStatus() != EntryStatus.DROPPED)
        .toList();
    List<RoadmapService.RoadmapWithSteps> roadmaps = roadmapService.listRoadmapsWithSteps();

    boolean enough = ideas.size() + roadmaps.size() >= 2;
    if (!enough || !reviewAi.isAvailable()) {
      return new ReviewResult(List.of(), null, enough);
    }

    String ideasStr = formatIdeas(ideas);
    String roadmapsStr = formatRoadmaps(roadmaps);
    String stalledStr = formatStalledSteps(roadmaps);

    List<String> threads = reviewAi.findThreads(ideasStr, roadmapsStr, stalledStr);
    String summary = reviewAi.weeklyReview(roadmapsStr, ideasStr);
    return new ReviewResult(threads, summary, true);
  }

  private static String formatIdeas(List<Entry> ideas) {
    StringBuilder sb = new StringBuilder();
    for (Entry e : ideas) {
      String text = str(e.getContent(), "text");
      if (text != null) {
        sb.append("- ").append(text).append(" (").append(e.getStatus().getValue()).append(")\n");
      }
    }
    return sb.toString();
  }

  private static String formatRoadmaps(List<RoadmapService.RoadmapWithSteps> roadmaps) {
    StringBuilder sb = new StringBuilder();
    for (RoadmapService.RoadmapWithSteps r : roadmaps) {
      String title = str(r.roadmap().getContent(), "title");
      long done = r.steps().stream().filter(s -> s.getStatus() == EntryStatus.DONE).count();
      Entry current = r.steps().stream()
          .filter(s -> s.getStatus() != EntryStatus.DONE && s.getStatus() != EntryStatus.DROPPED)
          .findFirst().orElse(null);
      sb.append("- ").append(title).append(": ").append(done).append('/').append(r.steps().size())
          .append(" done");
      if (current != null) {
        sb.append(", now on '").append(str(current.getContent(), "text")).append('\'');
      } else {
        sb.append(", complete");
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** Steps that keep stalling — skipped or reformulated repeatedly — across all roadmaps. */
  private static String formatStalledSteps(List<RoadmapService.RoadmapWithSteps> roadmaps) {
    StringBuilder sb = new StringBuilder();
    for (RoadmapService.RoadmapWithSteps r : roadmaps) {
      String title = str(r.roadmap().getContent(), "title");
      for (Entry step : r.steps()) {
        int reformulated = intOf(step.getContent(), "reformulateCount");
        if (step.getSkipCount() >= 2 || reformulated >= 2) {
          sb.append("- ").append(str(step.getContent(), "text"))
              .append(" (in ").append(title).append(")\n");
        }
      }
    }
    return sb.toString();
  }

  private static String str(Map<String, Object> content, String key) {
    Object value = content != null ? content.get(key) : null;
    return value instanceof String s ? s : null;
  }

  private static int intOf(Map<String, Object> content, String key) {
    Object value = content != null ? content.get(key) : null;
    return value instanceof Number n ? n.intValue() : 0;
  }
}
