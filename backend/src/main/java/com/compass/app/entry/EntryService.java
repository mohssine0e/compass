package com.compass.app.entry;

import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.entry.dto.PatchEntryRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
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

        Entry saved = repository.save(entry);
        // Working a step counts as touching its roadmap, so an actively-progressing roadmap
        // isn't mistaken for a stalled one by the resurfacing engine.
        if (saved.getParentId() != null) {
            repository.touchUpdatedAt(saved.getParentId(), Instant.now());
        }
        return saved;
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
