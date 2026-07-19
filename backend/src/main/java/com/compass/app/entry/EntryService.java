package com.compass.app.entry;

import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.entry.dto.EndSessionRequest;
import com.compass.app.entry.dto.PatchEntryRequest;
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

    private final EntryRepository repository;

    public EntryService(EntryRepository repository) {
        this.repository = repository;
    }

    /** All entries, newest first. */
    @Transactional(readOnly = true)
    public List<Entry> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
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

        if (patch.status() != null) {
            entry.setStatus(patch.status());
        }
        if (patch.significance() != null) {
            entry.setSignificance(patch.significance());
        }
        if (patch.dependsOn() != null) {
            // Non-positive id is the documented "clear the prerequisite" sentinel, since a
            // plain null here means "leave unchanged".
            Long prereq = patch.dependsOn() > 0 ? patch.dependsOn() : null;
            if (prereq != null && prereq.equals(entry.getId())) {
                throw new IllegalArgumentException("A step can't be its own prerequisite.");
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

        Entry saved = repository.save(entry);
        // Working a step counts as touching its roadmap, so an actively-progressing roadmap
        // isn't mistaken for a stalled one by the resurfacing engine.
        if (saved.getParentId() != null) {
            repository.touchUpdatedAt(saved.getParentId(), Instant.now());
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
}
