package com.compass.app.entry;

import com.compass.app.entry.dto.CreateEntryRequest;
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

    public EntryController(EntryService service) {
        this.service = service;
    }

    /** Capture an entry. Defaults to an idea; returns the stored entry. */
    @PostMapping
    public ResponseEntity<EntryResponse> create(@RequestBody CreateEntryRequest request) {
        Entry entry = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(entry));
    }

    /** List all entries, newest first. */
    @GetMapping
    public List<EntryResponse> list() {
        return service.listAll().stream().map(EntryResponse::from).toList();
    }

    /** Partial update — self-report completion with {"status":"done"}. */
    @PatchMapping("/{id}")
    public EntryResponse patch(@PathVariable Long id, @RequestBody PatchEntryRequest request) {
        return EntryResponse.from(service.update(id, request));
    }
}
