package com.compass.app.entry;

import com.compass.app.ai.AiVoiceWorker;
import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.entry.dto.EndSessionRequest;
import com.compass.app.entry.dto.EntryResponse;
import com.compass.app.entry.dto.PatchEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/entries")
public class EntryController {

    private final EntryService service;
    private final AiVoiceWorker aiVoiceWorker;

    public EntryController(EntryService service, AiVoiceWorker aiVoiceWorker) {
        this.service = service;
        this.aiVoiceWorker = aiVoiceWorker;
    }

    /**
     * Capture an entry. Defaults to an idea; returns the stored entry immediately — capture
     * must never wait on an AI call (CLAUDE.md Section 2: low friction beats features). The
     * voice line, when there is one, arrives a few seconds later as a notification instead of
     * blocking this response.
     */
    @PostMapping
    public ResponseEntity<EntryResponse> create(@RequestBody CreateEntryRequest request) {
        Entry entry = service.create(request);
        aiVoiceWorker.acknowledgeAsync(entry);
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.of(entry, null));
    }

    /** List all entries, newest first. */
    @GetMapping
    public List<EntryResponse> list() {
        return service.listAll().stream().map(EntryResponse::from).toList();
    }

    /**
     * Every completed roadmap step, most recently updated first (RB-5.3) — feeds the unified
     * intake's PRACTICE/REVIEW picker.
     */
    @GetMapping("/completed-steps")
    public List<EntryResponse> completedSteps() {
        return service.completedSteps().stream().map(EntryResponse::from).toList();
    }

    /**
     * Propose theme clusters over not-yet-themed ideas (Phase 14). Nothing is tagged — the
     * founder confirms/renames, then tags each accepted idea via the normal patch endpoint.
     */
    @PostMapping("/cluster")
    public List<EntryService.IdeaCluster> cluster() {
        return service.clusterIdeas();
    }

    /**
     * Partial update — self-report completion with {"status":"done"}. Completions are
     * acknowledged in the self-talk voice, same as capture: never synchronously, always as a
     * follow-up notification once the line exists.
     */
    @PatchMapping("/{id}")
    public EntryResponse patch(@PathVariable Long id, @RequestBody PatchEntryRequest request) {
        Entry entry = service.update(id, request);
        if (entry.getStatus() == EntryStatus.DONE) {
            aiVoiceWorker.acknowledgeAsync(entry);
        }
        return EntryResponse.of(entry, null);
    }

    /** Start a lightweight work session on a step (Phase 7.5). */
    @PostMapping("/{id}/sessions/start")
    public EntryResponse startSession(@PathVariable Long id) {
        return EntryResponse.from(service.startSession(id));
    }

    /** End the open work session on a step, recording duration and optional feedback. */
    @PostMapping("/{id}/sessions/end")
    public EntryResponse endSession(@PathVariable Long id, @RequestBody(required = false) EndSessionRequest request) {
        return EntryResponse.from(service.endSession(id, request));
    }
}
