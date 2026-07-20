package com.compass.app.resurfacing;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.resurfacing.dto.ApplyRestructureRequest;
import com.compass.app.resurfacing.dto.RestructureProposal;
import com.compass.app.resurfacing.dto.ResurfacingPrompt;
import com.compass.app.roadmap.RoadmapService;
import com.compass.app.verification.VerificationService;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The resurfacing engine: decides the one stalled thing worth bringing back before the next
 * capture, and records what the user does about it. Queries {@code entries} generically via
 * the repository (see CLAUDE.md Section 4) — not tied to any single entry type.
 */
@Service
public class ResurfacingService {

    private final EntryRepository repository;
    private final AiVoiceService aiVoice;
    private final RoadmapAiService roadmapAi;
    private final RoadmapService roadmapService;
    private final VerificationService verificationService;
    private final int staleDays;
    private final int snoozeDays;

    public ResurfacingService(EntryRepository repository, AiVoiceService aiVoice,
                              RoadmapAiService roadmapAi, RoadmapService roadmapService,
                              VerificationService verificationService,
                              @Value("${compass.resurfacing.stale-days:3}") int staleDays,
                              @Value("${compass.resurfacing.snooze-days:1}") int snoozeDays) {
        this.repository = repository;
        this.aiVoice = aiVoice;
        this.roadmapAi = roadmapAi;
        this.roadmapService = roadmapService;
        this.verificationService = verificationService;
        this.staleDays = staleDays;
        this.snoozeDays = snoozeDays;
    }

    /**
     * A spaced recheck of a done step that's due, as a resurfacing prompt — checked before the
     * normal stalled-thing resurface so overdue rechecks come first. Empty when none is due.
     */
    @Transactional
    public Optional<ResurfacingPrompt> nextRecheckPrompt() {
        Instant now = Instant.now();
        return repository.findNextRecheckCandidate(now, now.minus(snoozeDays, ChronoUnit.DAYS))
                .map(step -> ResurfacingPrompt.recheck(step,
                        verificationService.recheckQuestion(step.getId())));
    }

    /**
     * Answer a spaced recheck. Delegates the judging/rescheduling to the verification service,
     * and stamps last_resurfaced_at so the same step isn't rechecked again next open.
     */
    @Transactional
    public VerifyResult recheck(Long stepId, String answer) {
        VerifyResult result = verificationService.recheck(stepId, answer);
        repository.findById(stepId).ifPresent(step -> {
            step.setLastResurfacedAt(Instant.now());
            repository.save(step);
        });
        return result;
    }

    /** The next entry worth resurfacing, or empty if nothing qualifies right now. */
    @Transactional(readOnly = true)
    public Optional<Entry> nextCandidate() {
        Instant now = Instant.now();
        return repository.findNextResurfaceCandidate(
                now.minus(staleDays, ChronoUnit.DAYS),
                now.minus(snoozeDays, ChronoUnit.DAYS));
    }

    /** The honest question to ask about an entry, in the self-talk voice (never null). */
    public String question(Entry entry) {
        return aiVoice.resurfaceQuestion(entry, currentStepTextOf(entry), entry.getSkipCount());
    }

    /**
     * A self-talk note drawing on THIS thread's own history (per-topic, not global, per
     * CLAUDE.md), or {@code null} when there's no prior history — which is itself the signal
     * that the prompt is in generic mode. Kept template-based and specific to the last response.
     */
    @SuppressWarnings("unchecked")
    public String historyNote(Entry entry) {
        Object log = entry.getContent() != null ? entry.getContent().get("resurfaceLog") : null;
        if (!(log instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        int times = list.size();
        Object lastRaw = list.get(list.size() - 1);
        String last = lastRaw instanceof Map<?, ?> m && m.get("response") instanceof String r ? r : null;
        String phrase = switch (last == null ? "" : last) {
            case "still_relevant" -> "you said it still mattered";
            case "stuck" -> "you said you were stuck";
            case "skip" -> "you skipped it";
            case "something_else" -> "you gave it a next step";
            default -> null;
        };
        if (phrase == null) {
            return times == 1 ? "This has come up once before." : "This has come up " + times + " times before.";
        }
        return times == 1
                ? "Last time this came up, " + phrase + "."
                : "This has come up " + times + " times — last time, " + phrase + ".";
    }

    /** The text of the step a stalled roadmap is currently on, or null (not a roadmap / none left). */
    String currentStepTextOf(Entry entry) {
        Entry step = currentStepOf(entry);
        if (step == null) {
            return null;
        }
        Object text = step.getContent() != null ? step.getContent().get("text") : null;
        return text instanceof String s ? s : null;
    }

    /** The first not-done, not-dropped step of a roadmap — where you actually are — or null. */
    Entry currentStepOf(Entry entry) {
        if (entry == null || entry.getType() != com.compass.app.entry.EntryType.ROADMAP) {
            return null;
        }
        return repository.findByParentIdOrderByOrderIndexAsc(entry.getId()).stream()
                .filter(s -> s.getStatus() != EntryStatus.DONE && s.getStatus() != EntryStatus.DROPPED)
                .findFirst()
                .orElse(null);
    }

    /**
     * Record the user's response to a resurfacing prompt. Every response — including a skip —
     * stamps {@code last_resurfaced_at} so the same item isn't shown again next open; the
     * option chosen may also change the entry's state (lost interest → dropped; engaging →
     * developing). Free text / voice arrives as {@code something_else} with {@code text}.
     */
    @Transactional
    public Entry respond(Long id, String option, String text) {
        Entry entry = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No entry with id " + id));

        String choice = option == null || option.isBlank() ? "something_else" : option.trim();
        String note = text != null ? text.trim() : null;

        Map<String, Object> content = entry.getContent() != null
                ? new HashMap<>(entry.getContent())
                : new HashMap<>();

        switch (choice) {
            case "lost_interest" -> entry.setStatus(EntryStatus.DROPPED);
            case "something_else" -> {
                // Engaging with it, in their own words — nudge it forward and keep the note.
                if (entry.getStatus() == EntryStatus.CAPTURED) {
                    entry.setStatus(EntryStatus.DEVELOPING);
                }
                // The next step they named becomes its own trackable task (Phase 9).
                if (note != null && !note.isBlank()) {
                    createNextStepTask(entry, note.trim());
                }
            }
            // still_relevant / stuck / skip: no state change, just the resurface stamp below.
            default -> {
            }
        }

        // Track a *current streak* of avoidance: a skip adds to it; any real engagement clears
        // it, so the self-talk voice can tell one skip from a pattern (CLAUDE.md Section 2).
        if ("skip".equals(choice)) {
            entry.setSkipCount(entry.getSkipCount() + 1);
        } else {
            entry.setSkipCount(0);
        }

        appendResurfaceLog(content, choice, note);
        entry.setContent(content);
        entry.setLastResurfacedAt(Instant.now());
        return repository.save(entry);
    }

    /**
     * Draft a restructuring of the roadmap's current step — never applied here (CLAUDE.md
     * Phase 4: propose, the user approves, only then apply). {@code kind} is {@code break_down}
     * or {@code add_prerequisite}. Throws {@link IllegalStateException} when the AI can't help
     * so the caller surfaces it rather than inventing an edit.
     */
    @Transactional(readOnly = true)
    public RestructureProposal proposeRestructure(Long id, String kind) {
        Entry roadmap = requireRoadmap(id);
        Entry step = currentStepOf(roadmap);
        if (step == null) {
            throw new IllegalArgumentException("No step left on this roadmap to restructure.");
        }
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException("Restructuring is unavailable right now — edit it yourself.");
        }

        String title = stringField(roadmap, "title");
        String stepText = stringField(step, "text");

        return switch (kind == null ? "" : kind) {
            case "break_down" -> {
                List<String> smaller = roadmapAi.breakDownStep(title, stepText);
                if (smaller == null) {
                    throw new IllegalStateException("Couldn't draft smaller steps right now.");
                }
                yield RestructureProposal.breakDown(roadmap.getId(), step.getId(), stepText, smaller);
            }
            case "add_prerequisite" -> {
                RoadmapAiService.Prerequisite p =
                        roadmapAi.proposePrerequisite(title, stepText, priorStepsText(roadmap.getId(), step));
                if (p == null) {
                    throw new IllegalStateException("Nothing obvious is missing first — this may just need doing.");
                }
                yield RestructureProposal.prerequisite(
                        roadmap.getId(), step.getId(), stepText, p.step(), p.why());
            }
            default -> throw new IllegalArgumentException("Unknown restructuring: " + kind);
        };
    }

    /**
     * Apply an approved restructuring, then treat it as real engagement with the roadmap:
     * clear the avoidance streak and stamp {@code last_resurfaced_at} so it isn't shown again
     * next open. Returns the roadmap with its (now edited) steps.
     */
    @Transactional
    public com.compass.app.roadmap.dto.RoadmapResponse applyRestructure(Long id, ApplyRestructureRequest req) {
        Entry roadmap = requireRoadmap(id);

        switch (req.kind() == null ? "" : req.kind()) {
            case "break_down" -> roadmapService.splitStep(id, req.targetStepId(), req.steps());
            case "add_prerequisite" -> roadmapService.addPrerequisite(id, req.targetStepId(), req.prerequisite());
            default -> throw new IllegalArgumentException("Unknown restructuring: " + req.kind());
        }

        Map<String, Object> content = roadmap.getContent() != null
                ? new HashMap<>(roadmap.getContent())
                : new HashMap<>();
        appendResurfaceLog(content, "restructured_" + req.kind(), null);
        roadmap.setContent(content);
        roadmap.setSkipCount(0);
        roadmap.setLastResurfacedAt(Instant.now());
        repository.save(roadmap);

        return com.compass.app.roadmap.dto.RoadmapResponse.of(roadmap, roadmapService::stepsOf);
    }

    private Entry requireRoadmap(Long id) {
        Entry entry = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No entry with id " + id));
        if (entry.getType() != EntryType.ROADMAP) {
            throw new IllegalArgumentException("Only a roadmap can be restructured.");
        }
        return entry;
    }

    /** The texts of the steps that come before {@code step}, as one block for the prompt. */
    private String priorStepsText(Long roadmapId, Entry step) {
        StringBuilder sb = new StringBuilder();
        for (Entry s : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
            if (s.getId().equals(step.getId())) {
                break;
            }
            String text = stringField(s, "text");
            if (text != null && !text.isBlank()) {
                sb.append("- ").append(text).append('\n');
            }
        }
        return sb.toString();
    }

    private static String stringField(Entry entry, String key) {
        Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }

    /** Turn a named next step into its own trackable task, linked to what it came from. */
    private void createNextStepTask(Entry parent, String text) {
        Entry task = new Entry();
        task.setType(EntryType.TASK);
        task.setStatus(EntryStatus.CAPTURED);
        task.setParentId(parent.getId());
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        task.setContent(content);
        repository.save(task);
    }

    @SuppressWarnings("unchecked")
    private static void appendResurfaceLog(Map<String, Object> content, String choice, String note) {
        Object existing = content.get("resurfaceLog");
        List<Object> log = existing instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("response", choice);
        if (note != null && !note.isBlank()) {
            entry.put("note", note);
        }
        log.add(entry);
        content.put("resurfaceLog", log);
    }
}
