package com.compass.app.profile;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.dto.InferenceResult;
import com.compass.app.profile.dto.InferenceResult.InferredPreference;
import com.compass.app.roadmap.EstimatedTimeParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers learning preferences from behaviour (Phase 9, extended Phase 20): session feedback,
 * completion patterns, reformulations, which resource formats actually get used, and pace
 * (actual session time vs. what a resource said it'd take). Deterministic and conservative — it
 * only proposes an observation when there's real signal, and says what it's based on, so a thin
 * history doesn't produce confident-sounding guesses. Everything is a proposal the founder
 * confirms; nothing is trusted until saved to the profile — once confirmed into
 * {@code inferredPreferences}, {@link ProfileContext#forPrompt} already threads it into every
 * generation prompt, and a confirmed {@code preferFormat}/{@code avoidFormat} threads into
 * {@code resourceSuggestUser} the same way an avoid preference already does. This is deliberate,
 * per CLAUDE.md: behavioral inference is still a guess about the founder, so it earns the same
 * confirm-before-trust gate as any other inference, not a silent bias the moment history exists.
 */
@Service
public class InferenceService {

  // Below this many paired (real duration + parseable resource estimate) sessions, a pace
  // reading is noise, not signal — matches RoadmapResponse's per-roadmap threshold.
  private static final int MIN_PACE_SESSIONS = 3;

  private final EntryRepository repository;

  public InferenceService(EntryRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public InferenceResult infer() {
    List<Entry> steps = repository.findAll().stream()
        .filter(e -> e.getType() == EntryType.ROADMAP_STEP && e.getContent() != null)
        .toList();

    int tooHard = 0;
    int sessions = 0;
    int reformulatedSteps = 0;
    int repeatedlySkippedSteps = 0;
    int doneProject = 0;
    int doneConcept = 0;
    long projectMinutes = 0;
    long conceptMinutes = 0;
    long actualMinutes = 0;
    long estimatedMinutes = 0;
    int pacedSessions = 0;

    // Which resource ids exist, in which format, with what parseable estimate — so a session's
    // resourceUsed can be classified for both the format-usage and pace reads.
    Map<String, String> resourceFormat = new HashMap<>();
    Map<String, Integer> resourceEstimateMinutes = new HashMap<>();
    Map<String, Integer> formatAttached = new HashMap<>();
    for (Entry step : steps) {
      for (Object r : asList(step.getContent().get("resources"))) {
        if (r instanceof Map<?, ?> m && m.get("id") instanceof String id) {
          String fmt = m.get("format") instanceof String f ? f : null;
          resourceFormat.put(id, fmt);
          if (fmt != null) {
            formatAttached.merge(fmt, 1, Integer::sum);
          }
          if (m.get("estimatedTime") instanceof String time) {
            Integer minutes = EstimatedTimeParser.parseMinutes(time);
            if (minutes != null) {
              resourceEstimateMinutes.put(id, minutes);
            }
          }
        }
      }
    }

    Map<String, Integer> formatUsed = new HashMap<>();
    for (Entry step : steps) {
      Map<String, Object> content = step.getContent();
      if (intOf(content.get("reformulateCount")) >= 1) {
        reformulatedSteps++;
      }
      if (step.getSkipCount() >= 2) {
        repeatedlySkippedSteps++;
      }
      boolean done = step.getStatus() == EntryStatus.DONE;
      String kind = content.get("kind") instanceof String k ? k : null;

      for (Object s : asList(content.get("sessionHistory"))) {
        if (!(s instanceof Map<?, ?> m)) {
          continue;
        }
        sessions++;
        if ("too_hard".equals(m.get("userFeedback"))) {
          tooHard++;
        }
        long minutes = intOf(m.get("durationMinutes"));
        if ("project".equals(kind)) {
          projectMinutes += minutes;
        } else if ("concept".equals(kind)) {
          conceptMinutes += minutes;
        }
        if (m.get("resourceUsed") instanceof String rid) {
          String fmt = resourceFormat.get(rid);
          if (fmt != null) {
            formatUsed.merge(fmt, 1, Integer::sum);
          }
          Integer estimate = resourceEstimateMinutes.get(rid);
          if (estimate != null && estimate > 0 && minutes > 0) {
            actualMinutes += minutes;
            estimatedMinutes += estimate;
            pacedSessions++;
          }
        }
      }
      if (done && "project".equals(kind)) {
        doneProject++;
      } else if (done && "concept".equals(kind)) {
        doneConcept++;
      }
    }

    List<InferredPreference> prefs = new ArrayList<>();
    if (tooHard >= 2) {
      prefs.add(new InferredPreference(
          "Several steps have felt too hard — smaller steps tend to fit you better.", null, null));
    }
    if (reformulatedSteps >= 2) {
      prefs.add(new InferredPreference(
          "You've broken multiple steps down — the default step size may be too big for you.",
          null, null));
    }
    if (repeatedlySkippedSteps >= 2) {
      prefs.add(new InferredPreference(
          "Several steps have been skipped repeatedly when resurfaced — the step itself, or its "
              + "timing, might be off rather than you.",
          null, null));
    }
    if (doneProject >= 2 && doneConcept >= 1 && projectMinutes < conceptMinutes) {
      prefs.add(new InferredPreference(
          "You move faster on hands-on steps than on reading-heavy ones.", null, null));
    }

    // Format bias: one format clearly used, another clearly attached-but-ignored. Simple binary
    // read on purpose — with a single founder's realistic session volume, ranking every format
    // pair would just be noise dressed up as precision.
    String mostUsedFormat = formatUsed.entrySet().stream()
        .filter(e -> e.getValue() >= 2)
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(null);
    String ignoredFormat = formatAttached.entrySet().stream()
        .filter(e -> !e.getKey().equals(mostUsedFormat) && e.getValue() >= 2
            && formatUsed.getOrDefault(e.getKey(), 0) == 0)
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(null);
    if (mostUsedFormat != null && ignoredFormat != null) {
      prefs.add(new InferredPreference(
          "You reach for " + mostUsedFormat + " resources over " + ignoredFormat + ".",
          ignoredFormat, mostUsedFormat));
    }

    // Pace: actual session time vs. what the resource said it'd take, averaged over enough
    // sessions to mean something. Feeds step sizing in future generation once confirmed — never
    // applied silently, and this alone doesn't touch an in-progress roadmap (see the per-roadmap
    // pace reading surfaced directly on the roadmap page instead).
    if (pacedSessions >= MIN_PACE_SESSIONS && estimatedMinutes > 0) {
      double multiplier = (double) actualMinutes / estimatedMinutes;
      if (multiplier > 2.0) {
        prefs.add(new InferredPreference(
            "You've been taking roughly " + Math.round(multiplier * 10) / 10.0
                + "x longer than steps estimate — sized-up, less granular steps might fit better.",
            null, null));
      } else if (multiplier < 0.5) {
        prefs.add(new InferredPreference(
            "You've been moving roughly " + Math.round((1 / multiplier) * 10) / 10.0
                + "x faster than steps estimate — more depth per step might fit better.",
            null, null));
      }
    }

    String basis = sessions == 0
        ? "No sessions logged yet — nothing to go on."
        : "Based on " + sessions + " session" + (sessions == 1 ? "" : "s")
            + " across " + steps.size() + " steps.";
    return new InferenceResult(prefs, basis);
  }

  private static List<?> asList(Object value) {
    return value instanceof List<?> l ? l : List.of();
  }

  private static int intOf(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }
}
