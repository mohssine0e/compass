package com.compass.app.resurfacing;

import com.compass.app.entry.Entry;
import com.compass.app.entry.dto.EntryResponse;
import com.compass.app.resurfacing.dto.RespondRequest;
import com.compass.app.resurfacing.dto.ResurfacingPrompt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/resurfacing")
public class ResurfacingController {

    private final ResurfacingService service;

    public ResurfacingController(ResurfacingService service) {
        this.service = service;
    }

    /**
     * The one thing to resurface before the next capture, with its honest question — or
     * 204 No Content when nothing qualifies.
     */
    @GetMapping("/next")
    public ResponseEntity<ResurfacingPrompt> next() {
        return service.nextCandidate()
                .map(entry -> ResponseEntity.ok(ResurfacingPrompt.of(entry, service.question(entry))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Record how the user answered the honest question (including a skip). */
    @PostMapping("/{id}/respond")
    public EntryResponse respond(@PathVariable Long id, @RequestBody RespondRequest request) {
        Entry entry = service.respond(id, request.option(), request.text());
        return EntryResponse.from(entry);
    }
}
