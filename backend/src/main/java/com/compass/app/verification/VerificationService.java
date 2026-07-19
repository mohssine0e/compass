package com.compass.app.verification;

import com.compass.app.ai.VerificationAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Optional understanding-checks before a step counts as done (Phase 8). Verification is a
 * deliberately later phase than self-reporting (CLAUDE.md §2) — a step is only gated when its
 * own {@code verify} mode, or its roadmap's default, is {@code light} or {@code full}.
 */
@Service
public class VerificationService {

  static final Set<String> RIGORS = Set.of("light", "full");

  /** Spaced-retrieval intervals in days — each pass pushes the next recheck further out. */
  static final int[] RECHECK_DAYS = {3, 7, 21, 60};

  private final EntryRepository repository;
  private final VerificationAiService verifyAi;

  public VerificationService(EntryRepository repository, VerificationAiService verifyAi) {
    this.repository = repository;
    this.verifyAi = verifyAi;
  }

  /**
   * Generate (and stash) a fair check for a step. Throws if the step isn't set to be verified,
   * or the AI can't write one right now.
   */
  @Transactional
  public String generateCheck(Long stepId) {
    Entry step = requireStep(stepId);
    String rigor = resolveRigor(step);
    if (rigor == null) {
      throw new IllegalArgumentException("This step isn't set to be verified.");
    }
    if (!verifyAi.isAvailable()) {
      throw new IllegalStateException("Checks are unavailable right now — mark it done yourself.");
    }
    String question = verifyAi.generateCheck(parentTitle(step), textOf(step), rigor);
    if (question == null) {
      throw new IllegalStateException("Couldn't write a check right now — mark it done yourself.");
    }
    Map<String, Object> content = copyContent(step);
    content.put("pendingCheck", question);
    step.setContent(content);
    repository.save(step);
    return question;
  }

  /**
   * Judge the user's answer to the step's pending check. On pass, the step is marked done and
   * stamped verified; otherwise the step stays open and the specific gap is returned.
   */
  @Transactional
  public VerifyResult verify(Long stepId, String answer) {
    if (answer == null || answer.isBlank()) {
      throw new IllegalArgumentException("Write an answer first.");
    }
    Entry step = requireStep(stepId);
    if (!verifyAi.isAvailable()) {
      throw new IllegalStateException("Checks are unavailable right now — mark it done yourself.");
    }
    Map<String, Object> content = copyContent(step);
    String question = content.get("pendingCheck") instanceof String q ? q : null;

    VerificationAiService.Evaluation eval = verifyAi.evaluate(textOf(step), question, answer);
    if (eval == null) {
      throw new IllegalStateException("Couldn't judge that right now — mark it done yourself.");
    }
    if (eval.passed()) {
      content.remove("pendingCheck");
      content.put("verifiedAt", Instant.now().toString());
      scheduleRecheck(content, 0); // first spaced recheck after passing
      step.setContent(content);
      step.setStatus(EntryStatus.DONE);
      Entry saved = repository.save(step);
      touchParent(saved);
    }
    return new VerifyResult(eval.passed(), eval.gap());
  }

  /**
   * Generate a recheck question for a done step (spaced retrieval, Phase 8) and stash it.
   * Rigor falls back to {@code light} when the step/roadmap no longer has a mode set.
   */
  @Transactional
  public String recheckQuestion(Long stepId) {
    Entry step = requireStep(stepId);
    if (!verifyAi.isAvailable()) {
      throw new IllegalStateException("Rechecks are unavailable right now.");
    }
    String rigor = resolveRigor(step);
    String question = verifyAi.generateCheck(parentTitle(step), textOf(step),
        rigor == null ? "light" : rigor);
    if (question == null) {
      throw new IllegalStateException("Couldn't write a recheck right now.");
    }
    Map<String, Object> content = copyContent(step);
    content.put("pendingCheck", question);
    step.setContent(content);
    repository.save(step);
    return question;
  }

  /**
   * Judge a spaced-retrieval answer on a done step. Passing pushes the next recheck further out
   * (the spacing widens); missing it schedules a soon recheck and returns the gap. Either way
   * the step stays done — this reinforces, it doesn't punish.
   */
  @Transactional
  public VerifyResult recheck(Long stepId, String answer) {
    if (answer == null || answer.isBlank()) {
      throw new IllegalArgumentException("Write an answer first.");
    }
    Entry step = requireStep(stepId);
    if (!verifyAi.isAvailable()) {
      throw new IllegalStateException("Rechecks are unavailable right now.");
    }
    Map<String, Object> content = copyContent(step);
    String question = content.get("pendingCheck") instanceof String q ? q : null;

    VerificationAiService.Evaluation eval = verifyAi.evaluate(textOf(step), question, answer);
    if (eval == null) {
      throw new IllegalStateException("Couldn't judge that right now.");
    }
    content.remove("pendingCheck");
    int stage = content.get("recheckStage") instanceof Number n ? n.intValue() : 0;
    // Still solid: widen the spacing. Shaky: bring the next recheck back to the start.
    scheduleRecheck(content, eval.passed() ? stage + 1 : 0);
    step.setContent(content);
    Entry saved = repository.save(step);
    touchParent(saved);
    return new VerifyResult(eval.passed(), eval.gap());
  }

  private static void scheduleRecheck(Map<String, Object> content, int stage) {
    int clamped = Math.max(0, Math.min(stage, RECHECK_DAYS.length - 1));
    content.put("recheckStage", stage);
    content.put("nextRecheckAt",
        Instant.now().plus(RECHECK_DAYS[clamped], ChronoUnit.DAYS).toString());
  }

  /** The step's rigor: its own {@code verify} if set (off → null), else its roadmap's default. */
  private String resolveRigor(Entry step) {
    String stepVerify = stringField(step, "verify");
    if (stepVerify != null) {
      return RIGORS.contains(stepVerify) ? stepVerify : null;
    }
    if (step.getParentId() != null) {
      String roadmapVerify = repository.findById(step.getParentId())
          .map(r -> stringField(r, "verify")).orElse(null);
      return roadmapVerify != null && RIGORS.contains(roadmapVerify) ? roadmapVerify : null;
    }
    return null;
  }

  private Entry requireStep(Long stepId) {
    return repository.findById(stepId)
        .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
        .orElseThrow(() -> new NoSuchElementException("No step " + stepId));
  }

  private String parentTitle(Entry step) {
    return step.getParentId() == null ? null
        : repository.findById(step.getParentId()).map(r -> stringField(r, "title")).orElse(null);
  }

  private void touchParent(Entry step) {
    if (step.getParentId() != null) {
      repository.touchUpdatedAt(step.getParentId(), Instant.now());
    }
  }

  private static Map<String, Object> copyContent(Entry entry) {
    return entry.getContent() != null ? new HashMap<>(entry.getContent()) : new HashMap<>();
  }

  private static String textOf(Entry entry) {
    return stringField(entry, "text");
  }

  private static String stringField(Entry entry, String key) {
    Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
    return value instanceof String s && !s.isBlank() ? s : null;
  }
}
