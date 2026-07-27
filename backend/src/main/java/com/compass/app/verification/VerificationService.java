package com.compass.app.verification;

import com.compass.app.config.ConflictException;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.VerificationAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryContent;
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
  private final com.compass.app.roadmap.RoadmapService roadmapService;

  public VerificationService(EntryRepository repository, VerificationAiService verifyAi,
                             RoadmapAiService roadmapAi,
                             @org.springframework.context.annotation.Lazy
                             com.compass.app.roadmap.RoadmapService roadmapService) {
    this.repository = repository;
    this.verifyAi = verifyAi;
    this.roadmapAi = roadmapAi;
    // @Lazy: RoadmapService -> ... -> VerificationService closes a constructor cycle otherwise.
    this.roadmapService = roadmapService;
  }

  /** A step's default check format (Phase 26) from its {@code kind} — always overridable. */
  public String defaultFormat(Long stepId) {
    Entry step = requireStep(stepId);
    return defaultFormatFor(step);
  }

  /**
   * Pick the format that fits what the step actually asks of you. {@code scenario} was accepted
   * by the API and supported by the prompts but was never selectable automatically — only a
   * project step got anything other than multiple choice, so design/architecture steps were
   * checked with trivia when a judgement question was the whole point.
   */
  private static String defaultFormatFor(Entry step) {
    if ("project".equals(stringField(step, "kind"))) {
      return "code_challenge";
    }
    String text = textOf(step);
    String haystack = text == null ? "" : text.toLowerCase();
    for (String cue : SCENARIO_CUES) {
      if (haystack.contains(cue)) {
        return "scenario";
      }
    }
    for (String cue : CODE_CUES) {
      if (haystack.contains(cue)) {
        return "code_challenge";
      }
    }
    return "multiple_choice";
  }

  // Deliberately a small, readable cue list rather than an AI call: picking a default format is
  // not worth a round trip, and the founder can override it on any check anyway.
  private static final List<String> SCENARIO_CUES =
      List.of("design", "architect", "choose", "trade-off", "tradeoff", "strategy", "plan ",
          "decide", "evaluate", "compare");
  private static final List<String> CODE_CUES =
      List.of("build", "implement", "write ", "code", "script", "configure", "deploy", "automate");

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
    // Persisted, not held in a server-side map: the question and options are already in the DB,
    // so keeping the answer key in memory meant any backend restart left a pending check that
    // could never be answered ("That check expired") — and multiple_choice is the DEFAULT format,
    // so that was the common path. EntryContent.forClient strips this before it reaches the
    // browser, which is what the in-memory map was really protecting against.
    pending.put(EntryContent.CORRECT_INDEX, mc.correctIndex());
    content.put("pendingCheck", pending);
    step.setContent(content);
    repository.save(step);
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
      throw new ConflictException("No pending check on this step — ask for one first.");
    }

    VerificationAiService.Evaluation eval;
    if ("multiple_choice".equals(pending.format())) {
      eval = gradeMultipleChoice(selectedIndex, pending);
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
      content.remove("pendingCheck");
      content.put("verifiedAt", Instant.now().toString());
      scheduleRecheck(content, 0); // first spaced recheck after passing
      step.setContent(content);
      step.setStatus(EntryStatus.DONE);
      Entry saved = repository.save(step);
      touchParent(saved);
      return new VerifyResult(true, null, null, null);
    }

    // A missed check is spent. It has to be, for multiple choice: the gap deliberately names the
    // option that holds up (missing something and being told what you missed is the point), and
    // leaving the same question pending meant you could immediately resubmit the revealed answer
    // and pass. That is not verification. The gap still teaches; passing now costs a fresh check.
    content.remove("pendingCheck");
    step.setContent(content);
    Entry saved = repository.save(step);
    return withSuggestedPrerequisite(saved, eval.gap());
  }

  /**
   * Deterministic multiple-choice grading (Phase 26) — no AI call needed. The gap message names
   * the actually-correct option plainly, in the same self-talk voice as an AI-judged gap, since
   * there's no ambiguity to explain, just the fact of what was missed.
   */
  private VerificationAiService.Evaluation gradeMultipleChoice(Integer selectedIndex,
                                                                PendingCheck pending) {
    if (selectedIndex == null) {
      throw new IllegalArgumentException("Pick an option first.");
    }
    Integer correctIndex = pending.correctIndex();
    if (correctIndex == null) {
      throw new ConflictException("That check expired — ask for a new one.");
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

  /**
   * Step texts already covered before this one, earliest first — across the whole roadmap, not
   * just the current module. Scoped to the immediate parent, a nested step's "prior steps" began
   * again at each module boundary, so a prerequisite suggestion for a step in module 5 couldn't
   * see anything taught in modules 1-4 and would happily propose re-learning it.
   */
  private String priorStepsText(Entry step) {
    List<Entry> ancestors = repository.findAncestors(step.getId());
    if (ancestors.isEmpty()) {
      return null;
    }
    Long rootId = ancestors.get(ancestors.size() - 1).getId();
    StringBuilder sb = new StringBuilder();
    for (Entry s : roadmapService.leafStepsOf(rootId)) {
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
    if (pending == null) {
      // Without this the answer was evaluated against a null question — a meaningless verdict
      // returned as if it meant something. verify() has always guarded this; recheck() didn't.
      throw new ConflictException("No recheck pending on this step — ask for one first.");
    }

    VerificationAiService.Evaluation eval =
        verifyAi.evaluate(textOf(step), pending.question(), answer);
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
    // Store the clamped stage: the raw one kept incrementing past the end of RECHECK_DAYS, so a
    // long-retained step's recorded stage drifted away from any interval it actually maps to.
    content.put("recheckStage", clamped);
    content.put("nextRecheckAt",
        Instant.now().plus(RECHECK_DAYS[clamped], ChronoUnit.DAYS).toString());
  }

  /**
   * The step's rigor: its own {@code verify} if set (off → null), else the ROOT roadmap's default.
   *
   * <p>This walks all the way up rather than checking one parent. It used to check only
   * {@code step.getParentId()}, which for a nested roadmap is the step's <em>module</em> — and
   * modules never carry {@code verify}, only the root roadmap does. The effect was that
   * verification silently could not be used at all on any TOPIC or CAREER roadmap: the roadmap
   * said {@code verify: light}, the UI offered the button (it reads the same fallback correctly),
   * and the server answered "This step isn't set to be verified." Only flat MINI roadmaps ever
   * worked. Confirmed by the data: zero steps had ever reached {@code verifiedAt}.
   */
  private String resolveRigor(Entry step) {
    String stepVerify = stringField(step, "verify");
    if (stepVerify != null) {
      return RIGORS.contains(stepVerify) ? stepVerify : null;
    }
    // Nearest ancestor that actually states a mode wins, so a per-module override stays possible.
    for (Entry ancestor : repository.findAncestors(step.getId())) {
      String verify = stringField(ancestor, "verify");
      if (verify != null) {
        return RIGORS.contains(verify) ? verify : null;
      }
    }
    return null;
  }

  private Entry requireStep(Long stepId) {
    return repository.findById(stepId)
        .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
        .orElseThrow(() -> new NoSuchElementException("No step " + stepId));
  }

  /**
   * The title of the roadmap this step belongs to — the ROOT, not the immediate parent. For a
   * nested step the immediate parent is a module, and modules carry a {@code title} too, so the
   * old one-level lookup returned a module name while the prompt labelled it as the roadmap. The
   * check was written against the wrong scope without anything looking wrong.
   */
  private String parentTitle(Entry step) {
    List<Entry> ancestors = repository.findAncestors(step.getId());
    return ancestors.isEmpty() ? null : stringField(ancestors.get(ancestors.size() - 1), "title");
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
  private record PendingCheck(String format, String question, List<String> options,
                              Integer correctIndex) {
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
      return s.isBlank() ? null : new PendingCheck("free_response", s, null, null);
    }
    if (raw instanceof Map<?, ?> map) {
      String format = map.get("format") instanceof String f ? f : "free_response";
      String question = map.get("question") instanceof String q ? q : null;
      List<String> options = map.get("options") instanceof List<?> list
          ? (List<String>) list : null;
      Integer correctIndex = map.get(EntryContent.CORRECT_INDEX) instanceof Number n
          ? n.intValue() : null;
      return question == null ? null : new PendingCheck(format, question, options, correctIndex);
    }
    return null;
  }
}
