package com.compass.app.verification;

import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.VerificationAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.verification.dto.CheckResult;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional understanding-checks before a step counts as done (Phase 8, format variety added
 * Phase 26). Verification is a deliberately later phase than self-reporting (CLAUDE.md §2) — a
 * step is only gated when its own {@code verify} mode, or its roadmap's default, is {@code light}
 * or {@code full}.
 *
 * <p>Three check formats, all optional variants of this same engine, never a second system:
 * {@code multiple_choice} (auto-graded, no AI call needed to judge it), {@code code_challenge}
 * and {@code scenario} (free-text, judged by the same {@link VerificationAiService#evaluate}
 * used since Phase 8). A step's {@code kind} suggests a default format; the founder can always
 * override it per check.
 */
@Service
public class VerificationService {

  static final Set<String> RIGORS = Set.of("light", "full");
  static final Set<String> FORMATS = Set.of("multiple_choice", "code_challenge", "scenario", "free_response");

  /** Spaced-retrieval intervals in days — each pass pushes the next recheck further out. */
  static final int[] RECHECK_DAYS = {3, 7, 21, 60};

  private final EntryRepository repository;
  private final VerificationAiService verifyAi;
  private final RoadmapAiService roadmapAi;

  // The correct option for a pending multiple-choice check, kept out of persisted content so it
  // never round-trips through the roadmap tree endpoint (Phase 26) — transient by design, same
  // as any other in-memory-only server state in this app. Lost on restart, same tradeoff as any
  // other un-answered pending check would have anyway.
  private final Map<Long, Integer> pendingCorrectIndex = new ConcurrentHashMap<>();

  public VerificationService(EntryRepository repository, VerificationAiService verifyAi,
                             RoadmapAiService roadmapAi) {
    this.repository = repository;
    this.verifyAi = verifyAi;
    this.roadmapAi = roadmapAi;
  }

  /** A step's default check format (Phase 26) from its {@code kind} — always overridable. */
  public String defaultFormat(Long stepId) {
    Entry step = requireStep(stepId);
    return defaultFormatFor(step);
  }

  private static String defaultFormatFor(Entry step) {
    return "project".equals(stringField(step, "kind")) ? "code_challenge" : "multiple_choice";
  }

  /**
   * Generate (and stash) a fair check for a step, in {@code formatOverride} if given and valid,
   * else the step's auto-detected default. Throws if the step isn't set to be verified, the
   * format override is unrecognized, or the AI can't write one right now.
   */
  @Transactional
  public CheckResult generateCheck(Long stepId, String formatOverride) {
    Entry step = requireStep(stepId);
    String rigor = resolveRigor(step);
    if (rigor == null) {
      throw new IllegalArgumentException("This step isn't set to be verified.");
    }
    if (formatOverride != null && !FORMATS.contains(formatOverride)) {
      throw new IllegalArgumentException("Unrecognized check format.");
    }
    String format = formatOverride != null ? formatOverride : defaultFormatFor(step);
    if (!verifyAi.isAvailable()) {
      throw new IllegalStateException("Checks are unavailable right now — mark it done yourself.");
    }
    return "multiple_choice".equals(format)
        ? generateMultipleChoice(step, rigor)
        : generateFreeText(step, rigor, format);
  }

  private CheckResult generateMultipleChoice(Entry step, String rigor) {
    VerificationAiService.MultipleChoiceCheck mc =
        verifyAi.generateMultipleChoiceCheck(parentTitle(step), textOf(step), rigor);
    if (mc == null) {
      throw new IllegalStateException("Couldn't write a check right now — mark it done yourself.");
    }
    Map<String, Object> content = copyContent(step);
    Map<String, Object> pending = new HashMap<>();
    pending.put("format", "multiple_choice");
    pending.put("question", mc.question());
    pending.put("options", mc.options());
    content.put("pendingCheck", pending);
    step.setContent(content);
    repository.save(step);
    pendingCorrectIndex.put(step.getId(), mc.correctIndex());
    return new CheckResult("multiple_choice", mc.question(), mc.options());
  }

  private CheckResult generateFreeText(Entry step, String rigor, String format) {
    String question = verifyAi.generateCheck(parentTitle(step), textOf(step), rigor, format);
    if (question == null) {
      throw new IllegalStateException("Couldn't write a check right now — mark it done yourself.");
    }
    Map<String, Object> content = copyContent(step);
    Map<String, Object> pending = new HashMap<>();
    pending.put("format", format);
    pending.put("question", question);
    content.put("pendingCheck", pending);
    step.setContent(content);
    repository.save(step);
    pendingCorrectIndex.remove(step.getId());
    return new CheckResult(format, question, null);
  }

  /**
   * Judge the user's answer to the step's pending check. On pass, the step is marked done and
   * stamped verified; otherwise the step stays open and the specific gap is returned.
   * {@code selectedIndex} is only read for a pending multiple-choice check; {@code answer} is
   * only read for the free-text formats.
   */
  @Transactional
  public VerifyResult verify(Long stepId, String answer, Integer selectedIndex) {
    Entry step = requireStep(stepId);
    Map<String, Object> content = copyContent(step);
    PendingCheck pending = pendingCheckOf(content);
    if (pending == null) {
      throw new IllegalStateException("No pending check on this step — ask for one first.");
    }

    VerificationAiService.Evaluation eval;
    if ("multiple_choice".equals(pending.format())) {
      eval = gradeMultipleChoice(stepId, selectedIndex, pending);
    } else {
      if (answer == null || answer.isBlank()) {
        throw new IllegalArgumentException("Write an answer first.");
      }
      if (!verifyAi.isAvailable()) {
        throw new IllegalStateException("Checks are unavailable right now — mark it done yourself.");
      }
      eval = verifyAi.evaluate(textOf(step), pending.question(), answer);
      if (eval == null) {
        throw new IllegalStateException("Couldn't judge that right now — mark it done yourself.");
      }
    }

    if (eval.passed()) {
      pendingCorrectIndex.remove(stepId);
      content.remove("pendingCheck");
      content.put("verifiedAt", Instant.now().toString());
      scheduleRecheck(content, 0); // first spaced recheck after passing
      step.setContent(content);
      step.setStatus(EntryStatus.DONE);
      Entry saved = repository.save(step);
      touchParent(saved);
      return new VerifyResult(true, null, null, null);
    }
    return withSuggestedPrerequisite(step, eval.gap());
  }

  /**
   * Deterministic multiple-choice grading (Phase 26) — no AI call needed. The gap message names
   * the actually-correct option plainly, in the same self-talk voice as an AI-judged gap, since
   * there's no ambiguity to explain, just the fact of what was missed.
   */
  private VerificationAiService.Evaluation gradeMultipleChoice(Long stepId, Integer selectedIndex,
                                                                PendingCheck pending) {
    if (selectedIndex == null) {
      throw new IllegalArgumentException("Pick an option first.");
    }
    Integer correctIndex = pendingCorrectIndex.get(stepId);
    if (correctIndex == null) {
      throw new IllegalStateException("That check expired — ask for a new one.");
    }
    if (selectedIndex.equals(correctIndex)) {
      return new VerificationAiService.Evaluation(true, null);
    }
    String correctOption = correctIndex >= 0 && correctIndex < pending.options().size()
        ? pending.options().get(correctIndex) : null;
    String gap = correctOption == null ? "Not that one."
        : "Not that one. The one that holds up: \"" + correctOption + "\".";
    return new VerificationAiService.Evaluation(false, gap);
  }

  /**
   * On a failed check (Phase 20), see whether the named gap plausibly maps to a missing
   * prerequisite — real evidence from what was actually missed, not a guess from the step's text
   * alone. Best-effort: a failed/unavailable proposal just means the gap shows with no
   * suggestion, same as before this existed.
   */
  private VerifyResult withSuggestedPrerequisite(Entry step, String gap) {
    RoadmapAiService.Prerequisite prereq = roadmapAi.isAvailable()
        ? roadmapAi.proposePrerequisite(parentTitle(step), textOf(step), priorStepsText(step), gap)
        : null;
    return new VerifyResult(false, gap,
        prereq == null ? null : prereq.step(), prereq == null ? null : prereq.why());
  }

  /** Step texts already before this one under the same parent, earliest first. */
  private String priorStepsText(Entry step) {
    if (step.getParentId() == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (Entry s : repository.findByParentIdOrderByOrderIndexAsc(step.getParentId())) {
      if (s.getId().equals(step.getId())) {
        break;
      }
      String text = stringField(s, "text");
      if (text != null) {
        sb.append("- ").append(text).append('\n');
      }
    }
    return sb.toString();
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
    // Spaced retrieval always uses the plain free-text format — the Phase 26 format picker is a
    // founder-initiated per-check choice, not something a background recheck should surface.
    String question = verifyAi.generateCheck(parentTitle(step), textOf(step),
        rigor == null ? "light" : rigor, "free_response");
    if (question == null) {
      throw new IllegalStateException("Couldn't write a recheck right now.");
    }
    Map<String, Object> content = copyContent(step);
    Map<String, Object> pending = new HashMap<>();
    pending.put("format", "free_response");
    pending.put("question", question);
    content.put("pendingCheck", pending);
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
    PendingCheck pending = pendingCheckOf(content);
    String question = pending == null ? null : pending.question();

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
    return eval.passed() ? new VerifyResult(true, null, null, null)
        : withSuggestedPrerequisite(saved, eval.gap());
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

  /** A step's in-progress pending check, however it's shaped. */
  private record PendingCheck(String format, String question, List<String> options) {
  }

  /**
   * Reads {@code content.pendingCheck}, accepting both the Phase 26 map shape
   * ({@code {format, question, options?}}) and the plain-string shape every check used before
   * this phase (and still used nowhere new — kept only so a check pending from before this
   * change doesn't strand the step). {@code null} if there's no pending check at all.
   */
  @SuppressWarnings("unchecked")
  private static PendingCheck pendingCheckOf(Map<String, Object> content) {
    Object raw = content.get("pendingCheck");
    if (raw instanceof String s) {
      return s.isBlank() ? null : new PendingCheck("free_response", s, null);
    }
    if (raw instanceof Map<?, ?> map) {
      String format = map.get("format") instanceof String f ? f : "free_response";
      String question = map.get("question") instanceof String q ? q : null;
      List<String> options = map.get("options") instanceof List<?> list
          ? (List<String>) list : null;
      return question == null ? null : new PendingCheck(format, question, options);
    }
    return null;
  }
}
