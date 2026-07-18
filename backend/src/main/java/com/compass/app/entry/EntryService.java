package com.compass.app.entry;

import com.compass.app.entry.dto.CreateEntryRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class EntryService {

    private final EntryRepository repository;

    public EntryService(EntryRepository repository) {
        this.repository = repository;
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
