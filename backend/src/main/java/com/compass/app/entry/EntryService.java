package com.compass.app.entry;

import com.compass.app.ai.ReviewAiService;
import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.entry.dto.EndSessionRequest;
import com.compass.app.entry.dto.PatchEntryRequest;
import com.compass.app.events.EventService;
import com.compass.app.roadmap.RoadmapDependencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class EntryService {

    private static final int MAX_SESSION_HISTORY = 200;

    private final EntryRepository repository;
    private final ReviewAiService reviewAi;
    private final EventService events;
    private final RoadmapDependencyService dependencies;

    public EntryService(EntryRepository repository, ReviewAiService reviewAi, EventService events,
                        RoadmapDependencyService dependencies) {
        this.repository = repository;
        this.reviewAi = reviewAi;
        this.events = events;
        this.dependencies = dependencies;
    }

    /** All entries, newest first. */
    @Transactional(readOnly = true)
    public List<Entry> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** Every completed roadmap step, most recently updated first (RB-5.3). */
    @Transactional(readOnly = true)
    public List<Entry> completedSteps() {
        return repository.findActiveCompletedSteps();
    }

    /**
     * Propose theme clusters over active, not-yet-themed ideas (Phase 14) — nothing is tagged
     * until the founder confirms via a normal patch ({@code content.theme}). Only ideas without
     * a theme already are offered, so confirming doesn't need to be redone on every call.
     */
    @Transactional(readOnly = true)
    public List<IdeaCluster> clusterIdeas() {
        List<Entry> ideas = repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(e -> e.getType() == EntryType.IDEA)
                .filter(e -> e.getStatus() != EntryStatus.DROPPED && e.getStatus() != EntryStatus.DONE)
                .filter(e -> e.getContent() == null || e.getContent().get("theme") == null)
                .toList();
        List<String> texts = ideas.stream().map(e -> stringOf(e, "text")).toList();
        return reviewAi.clusterIdeas(texts).stream()
                .map(c -> new IdeaCluster(c.label(), c.indices().stream().map(i -> ideas.get(i).getId()).toList()))
                .toList();
    }

    private static String stringOf(Entry entry, String key) {
        Object value = entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : "";
    }

    /** A proposed theme and the real idea ids that fit it — for the founder to confirm/edit. */
    public record IdeaCluster(String label, List<Long> ideaIds) {
    }

    @Transactional(readOnly = true)
    public Entry get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No entry with id " + id));
    }

    /**
     * Apply a partial update. Only non-null fields change. Phase 1's main use is
     * self-reported completion (status → done).
     */
    @Transactional
    public Entry update(Long id, PatchEntryRequest patch) {
        Entry entry = get(id);
        if (patch.expectedUpdatedAt() != null && !patch.expectedUpdatedAt().equals(entry.getUpdatedAt())) {
            throw new com.compass.app.config.ConflictException(
                    "This changed somewhere else. Refresh before saving.");
        }

        if (patch.status() != null) {
            if (patch.status() == EntryStatus.DONE && entry.getType() == EntryType.ROADMAP_STEP) {
                dependencies.requireDone(entry);
            }
            entry.setStatus(patch.status());
        }
        if (patch.significance() != null) {
            entry.setSignificance(patch.significance());
        }
        if (patch.dependsOn() != null) {
            Long prereq = patch.dependsOn() > 0 ? patch.dependsOn() : null;
            if (prereq != null) {
                dependencies.validate(entry, prereq);
            }
            entry.setDependsOn(prereq);
        }
        if (patch.content() != null) {
            entry.setContent(new HashMap<>(patch.content()));
        }
        if (patch.text() != null && !patch.text().isBlank()) {
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent())
                    : new HashMap<>();
            content.put("text", patch.text().trim());
            entry.setContent(content);
        }
        if (patch.title() != null) {
            if (entry.getType() != EntryType.ROADMAP) {
                throw new IllegalArgumentException("Only roadmaps have titles.");
            }
            String title = patch.title().trim();
            if (title.isEmpty()) {
                throw new IllegalArgumentException("A roadmap needs a title.");
            }
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent()) : new HashMap<>();
            content.put("title", title);
            entry.setContent(content);
        }
        if (patch.notes() != null) {
            // Merge notes into content without disturbing the rest (text, resources, …). A blank
            // note clears it.
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent())
                    : new HashMap<>();
            String trimmed = patch.notes().trim();
            if (trimmed.isEmpty()) {
                content.remove("notes");
            } else {
                content.put("notes", trimmed);
            }
            entry.setContent(content);
        }
        if (patch.verify() != null) {
            // Verification mode (Phase 8): off/light/full, merged into content. "off" clears it
            // so a step falls back to its roadmap's default.
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent())
                    : new HashMap<>();
            String mode = patch.verify().trim();
            if (mode.isEmpty() || mode.equals("off")) {
                content.remove("verify");
            } else if (mode.equals("light") || mode.equals("full")) {
                content.put("verify", mode);
            } else {
                throw new IllegalArgumentException("Verify mode must be off, light, or full.");
            }
            entry.setContent(content);
        }
        if (patch.projectUrl() != null) {
            // A project step's public-URL field (Phase 24), merged into content like notes/verify.
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent())
                    : new HashMap<>();
            String trimmed = patch.projectUrl().trim();
            if (trimmed.isEmpty()) {
                content.remove("projectUrl");
            } else {
                content.put("projectUrl", trimmed);
            }
            entry.setContent(content);
        }
        if (patch.collapseOverrides() != null) {
            // The founder's manual collapse/expand choices (RB-4.10), merged into content like
            // notes/verify — whole-map replace, not a per-key merge (see PatchEntryRequest doc).
            Map<String, Object> content = entry.getContent() != null
                    ? new HashMap<>(entry.getContent())
                    : new HashMap<>();
            if (patch.collapseOverrides().isEmpty()) {
                content.remove("collapseOverrides");
            } else {
                content.put("collapseOverrides", patch.collapseOverrides());
            }
            entry.setContent(content);
        }

        Entry saved = repository.save(entry);
        // Working a step counts as touching its roadmap, so an actively-progressing roadmap
        // isn't mistaken for a stalled one by the resurfacing engine.
        if (saved.getParentId() != null) {
            repository.touchUpdatedAt(saved.getParentId(), Instant.now());
        }
        // Brief completion log (RB-4.12) — a sentence, not a stack trace, same discipline as
        // every other system_events entry (CLAUDE.md Section 2). Only roadmap steps, not every
        // entry type, since that's what RB-4.12 actually asks for.
        if (patch.status() == EntryStatus.DONE && saved.getType() == EntryType.ROADMAP_STEP) {
            events.info("step_completed", "step marked done: " + stringOf(saved, "text"),
                    Map.of("stepId", saved.getId()));
        }
        return saved;
    }

    /**
     * Begin a work session on a step (Phase 7.5): append an open session (start time, no
     * duration yet) to the step's {@code sessionHistory}. Lightweight time tracking — no
     * scheduling. If a session is already open, it's left as-is and this is a no-op.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Entry startSession(Long id) {
        Entry entry = get(id);
        Map<String, Object> content = new HashMap<>(entry.getContent() != null ? entry.getContent() : Map.of());
        List<Object> history = content.get("sessionHistory") instanceof List<?> l
                ? new ArrayList<>((List<Object>) l) : new ArrayList<>();

        boolean alreadyOpen = !history.isEmpty()
                && history.get(history.size() - 1) instanceof Map<?, ?> last
                && last.get("durationMinutes") == null;
        if (!alreadyOpen) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("startedAt", Instant.now().toString());
            history.add(session);
            trimOldest(history, MAX_SESSION_HISTORY);
            content.put("sessionHistory", history);
            entry.setContent(content);
            entry = repository.save(entry);
        }
        touchParent(entry);
        return entry;
    }

    /**
     * End the open work session on a step: compute its duration from the start time and record
     * the optional resource used / feedback / completed flag. A no-op if no session is open.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Entry endSession(Long id, EndSessionRequest req) {
        Entry entry = get(id);
        Map<String, Object> content = new HashMap<>(entry.getContent() != null ? entry.getContent() : Map.of());
        List<Object> history = content.get("sessionHistory") instanceof List<?> l
                ? new ArrayList<>((List<Object>) l) : new ArrayList<>();

        if (!history.isEmpty() && history.get(history.size() - 1) instanceof Map<?, ?> lastRaw
                && lastRaw.get("durationMinutes") == null) {
            Map<String, Object> session = new LinkedHashMap<>((Map<String, Object>) lastRaw);
            long minutes = 0;
            if (session.get("startedAt") instanceof String startedAt) {
                minutes = Math.max(0, Duration.between(Instant.parse(startedAt), Instant.now()).toMinutes());
            }
            session.put("durationMinutes", minutes);
            if (req != null && req.resourceUsed() != null && !req.resourceUsed().isBlank()) {
                session.put("resourceUsed", req.resourceUsed().trim());
            }
            if (req != null && req.userFeedback() != null && !req.userFeedback().isBlank()) {
                session.put("userFeedback", req.userFeedback().trim());
            }
            session.put("completed", req != null && Boolean.TRUE.equals(req.completed()));
            history.set(history.size() - 1, session);
            content.put("sessionHistory", history);
            entry.setContent(content);
            entry = repository.save(entry);
        }
        touchParent(entry);
        return entry;
    }

    private void touchParent(Entry entry) {
        if (entry.getParentId() != null) {
            repository.touchUpdatedAt(entry.getParentId(), Instant.now());
        }
    }

    private static void trimOldest(List<?> list, int max) {
        if (list.size() > max) {
            list.subList(0, list.size() - max).clear();
        }
    }

    /**
     * Create an entry from a capture request. Type defaults to idea; new entries start
     * as CAPTURED. Significance is only applied to ideas (see CLAUDE.md Section 4).
     */
    @Transactional
    public Entry create(CreateEntryRequest req) {
        EntryType type = req.type() != null ? req.type() : EntryType.IDEA;

        Map<String, Object> content = req.content() != null
                ? new HashMap<>(req.content())
                : new HashMap<>();
        if (req.text() != null && !req.text().isBlank()) {
            content.put("text", req.text().trim());
        }

        Object text = content.get("text");
        if (!(text instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("An entry needs some text to capture.");
        }

        if (type == EntryType.IDEA) {
            String normalized = s(text).trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            boolean duplicate = repository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(e -> e.getType() == EntryType.IDEA)
                    .map(e -> e.getContent() == null ? null : e.getContent().get("text"))
                    .filter(String.class::isInstance)
                    .map(value -> ((String) value).trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(normalized::equals);
            if (duplicate) content.put("possibleDuplicate", true);
        }

        Entry entry = new Entry();
        entry.setType(type);
        entry.setStatus(EntryStatus.CAPTURED);
        entry.setContent(content);
        entry.setParentId(req.parentId());
        entry.setOrderIndex(req.orderIndex());
        // Significance is meaningful only for ideas; ignore it on other types.
        if (type == EntryType.IDEA) {
            entry.setSignificance(req.significance());
        }

        return repository.save(entry);
    }

    private static String s(Object value) {
        return value instanceof String text ? text : "";
    }
}
