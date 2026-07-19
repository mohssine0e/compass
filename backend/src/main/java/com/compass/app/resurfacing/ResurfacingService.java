package com.compass.app.resurfacing;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
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
    private final int staleDays;
    private final int snoozeDays;

    public ResurfacingService(EntryRepository repository, AiVoiceService aiVoice,
                              @Value("${compass.resurfacing.stale-days:3}") int staleDays,
                              @Value("${compass.resurfacing.snooze-days:1}") int snoozeDays) {
        this.repository = repository;
        this.aiVoice = aiVoice;
        this.staleDays = staleDays;
        this.snoozeDays = snoozeDays;
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
        return aiVoice.resurfaceQuestion(entry);
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
            }
            // still_relevant / stuck / skip: no state change, just the resurface stamp below.
            default -> {
            }
        }

        appendResurfaceLog(content, choice, note);
        entry.setContent(content);
        entry.setLastResurfacedAt(Instant.now());
        return repository.save(entry);
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
