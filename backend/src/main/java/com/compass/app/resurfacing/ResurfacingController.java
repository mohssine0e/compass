package com.compass.app.resurfacing;

import com.compass.app.entry.Entry;
import com.compass.app.entry.dto.EntryResponse;
import com.compass.app.resurfacing.dto.ApplyRestructureRequest;
import com.compass.app.resurfacing.dto.RespondRequest;
import com.compass.app.resurfacing.dto.RestructureProposal;
import com.compass.app.resurfacing.dto.RestructureRequest;
import com.compass.app.resurfacing.dto.ResurfacingPrompt;
import com.compass.app.roadmap.dto.RoadmapResponse;
import com.compass.app.verification.dto.VerifyRequest;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

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
        // A due spaced recheck (Phase 8) comes before the normal stalled-thing resurface.
        Optional<ResurfacingPrompt> recheck = service.nextRecheckPrompt();
        if (recheck.isPresent()) {
            return ResponseEntity.ok(recheck.get());
        }
        return service.nextCandidate()
                .map(entry -> ResponseEntity.ok(ResurfacingPrompt.of(entry, service.question(entry))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Answer a spaced recheck of a done step. Returns {passed, gap}; the step stays done. */
    @PostMapping("/{id}/recheck")
    public VerifyResult recheck(@PathVariable Long id, @RequestBody VerifyRequest request) {
        return service.recheck(id, request.answer());
    }

    /** Record how the user answered the honest question (including a skip). */
    @PostMapping("/{id}/respond")
    public EntryResponse respond(@PathVariable Long id, @RequestBody RespondRequest request) {
        Entry entry = service.respond(id, request.option(), request.text());
        return EntryResponse.from(entry);
    }

    /**
     * Draft a restructuring of a stalled roadmap's current step (break it down / add a
     * prerequisite). Returns a proposal to edit and approve — nothing is changed yet. 503 when
     * the AI can't help right now.
     */
    @PostMapping("/{id}/restructure")
    public RestructureProposal restructure(@PathVariable Long id,
                                           @RequestBody RestructureRequest request) {
        return service.proposeRestructure(id, request.kind());
    }

    /** Apply the user's approved (possibly edited) restructuring; returns the updated roadmap. */
    @PostMapping("/{id}/restructure/apply")
    public RoadmapResponse applyRestructure(@PathVariable Long id,
                                            @RequestBody ApplyRestructureRequest request) {
        return service.applyRestructure(id, request);
    }
}
