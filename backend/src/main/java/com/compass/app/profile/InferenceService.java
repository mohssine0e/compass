package com.compass.app.profile;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.dto.InferenceResult;
import com.compass.app.profile.dto.InferenceResult.InferredPreference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Infers learning preferences from behaviour (Phase 9): session feedback, completion patterns,
 * reformulations, and which resource formats actually get used. Deterministic and conservative —
 * it only proposes an observation when there's real signal, and says what it's based on, so a
 * thin history doesn't produce confident-sounding guesses. Everything is a proposal the founder
 * confirms; nothing is trusted until saved to the profile.
 */
@Service
public class InferenceService {

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
    int doneProject = 0;
    int doneConcept = 0;
    long projectMinutes = 0;
    long conceptMinutes = 0;
    boolean videoResourceExists = false;
    int usedVideo = 0;
    int usedWritten = 0;

    // Which resource ids are which format, so a session's resourceUsed can be classified.
    Map<String, String> resourceFormat = new java.util.HashMap<>();
    for (Entry step : steps) {
      for (Object r : asList(step.getContent().get("resources"))) {
        if (r instanceof Map<?, ?> m && m.get("id") instanceof String id) {
          String fmt = m.get("format") instanceof String f ? f : null;
          resourceFormat.put(id, fmt);
          if ("video".equals(fmt)) {
            videoResourceExists = true;
          }
        }
      }
    }

    for (Entry step : steps) {
      Map<String, Object> content = step.getContent();
      if (intOf(content.get("reformulateCount")) >= 1) {
        reformulatedSteps++;
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
          if ("video".equals(fmt)) {
            usedVideo++;
          } else if ("written".equals(fmt)) {
            usedWritten++;
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
          "Several steps have felt too hard — smaller steps tend to fit you better.", null));
    }
    if (reformulatedSteps >= 2) {
      prefs.add(new InferredPreference(
          "You've broken multiple steps down — the default step size may be too big for you.", null));
    }
    if (doneProject >= 2 && doneConcept >= 1 && projectMinutes < conceptMinutes) {
      prefs.add(new InferredPreference(
          "You move faster on hands-on steps than on reading-heavy ones.", null));
    }
    if (videoResourceExists && usedVideo == 0 && usedWritten >= 2) {
      prefs.add(new InferredPreference("You reach for written resources over video.", "video"));
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
