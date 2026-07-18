package com.compass.app.entry;

import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.entry.dto.EntryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
