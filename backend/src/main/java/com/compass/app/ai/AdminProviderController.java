package com.compass.app.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Which AI providers are actually working right now (V3-2.3).
 *
 * <p>Answers, in one place, the question that previously needed three separate provider consoles
 * open: is Groq rate-limited, is Gemini's key dry, is anything falling through to the slow NIM
 * last resort — and how fast each one is actually responding. That last part matters because the
 * tier/timeout assignments in {@code application.properties} were set from one-off observations;
 * this is the standing measurement.
 *
 * <p>No auth, same as {@code /admin/events} — single user by design (CLAUDE.md Section 2).
 */
@RestController
@RequestMapping("/admin/providers")
class AdminProviderController {

    private final AiProperties props;
    private final ProviderHealth health;

    AdminProviderController(AiProperties props, ProviderHealth health) {
        this.props = props;
        this.health = health;
    }

    @GetMapping
    List<ProviderHealth.Snapshot> providers() {
        return health.snapshot(props.getFast(), props.getHeavy());
    }
}
