package com.compass.app.ai;

import com.compass.app.events.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers which providers are currently failing, so a dead one stops costing a full timeout
 * on every single call.
 *
 * <p>The problem this solves is concrete and was already documented in
 * {@code application.properties} from live observation: Gemini Flash can sit on a standing
 * {@code 429 limit:0} for an account, and Groq's free tier hits per-minute token limits under a
 * burst. Without any memory of that, every fast-tier call walked the chain from index 0 — paying
 * Groq's timeout, then Gemini's, before reaching a NIM fallback at a 45s override. A tier
 * classification that should take under a second took most of a minute, all day long.
 *
 * <p>Cooldowns never hard-fail a request. If every provider in a tier is cooling down,
 * {@link #callOrder} still returns all of them, ordered by which is closest to recovery — a
 * breaker that could turn a working call into a failure would be worse than the problem.
 *
 * <p>In-memory and per-instance by design, same reasoning as {@code GenerationJobService}: single
 * user, single instance, and the worst case on restart is re-learning within one call per provider.
 */
@Component
public class ProviderHealth {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealth.class);

    // Rate limits are a duration problem: back off harder each time it keeps saying no, but cap
    // it so a provider that recovered at noon isn't still benched at dinner.
    private static final Duration RATE_LIMIT_BASE = Duration.ofMinutes(1);
    private static final Duration RATE_LIMIT_MAX = Duration.ofMinutes(30);
    // A bad key won't fix itself in 30 seconds. Long enough to stop the hammering, short enough
    // that fixing .env and carrying on doesn't need a restart.
    private static final Duration AUTH_COOLDOWN = Duration.ofMinutes(15);
    // Timeouts and 5xx are usually a blip.
    private static final Duration TRANSIENT_COOLDOWN = Duration.ofSeconds(30);

    private final EventService events;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ProviderHealth(EventService events) {
        this.events = events;
    }

    /**
     * The order to try {@code chain} in right now: every provider not currently cooling down,
     * in their configured failover order. If they're all cooling down, the whole chain is
     * returned instead, soonest-to-recover first — see the class note on never hard-failing.
     */
    public List<AiProperties.Provider> callOrder(List<AiProperties.Provider> chain) {
        Instant now = Instant.now();
        List<AiProperties.Provider> available = chain.stream()
                .filter(p -> !isCooling(p, now))
                .toList();
        if (!available.isEmpty()) {
            return available;
        }
        return chain.stream()
                .sorted(Comparator.comparing(p -> stateOf(p).cooldownUntil, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** True when this provider is benched right now. */
    public boolean isCooling(AiProperties.Provider provider) {
        return isCooling(provider, Instant.now());
    }

    private boolean isCooling(AiProperties.Provider provider, Instant now) {
        Instant until = stateOf(provider).cooldownUntil;
        return until != null && until.isAfter(now);
    }

    /** A call came back usable. Clears any cooldown and the failure streak. */
    public void recordSuccess(AiProperties.Provider provider, long durationMs) {
        State s = stateOf(provider);
        boolean wasCooling = s.cooldownUntil != null && s.cooldownUntil.isAfter(Instant.now());
        s.lastSuccessAt = Instant.now();
        s.consecutiveFailures = 0;
        s.cooldownUntil = null;
        s.lastFailureKind = null;
        s.successes.incrementAndGet();
        s.totalDurationMs.addAndGet(durationMs);
        s.lastDurationMs = durationMs;
        if (wasCooling) {
            log.info("AI provider {} recovered.", provider.getName());
        }
    }

    /**
     * A call failed. Sets a cooldown sized to what actually went wrong — except for
     * {@link AiCallException.Kind#BAD_RESPONSE}, which records the failure but leaves the
     * provider in rotation, because an unusable reply means the prompt or parser is at fault,
     * not the provider's availability.
     */
    public void recordFailure(AiProperties.Provider provider, Throwable failure) {
        State s = stateOf(provider);
        AiCallException.Kind kind = AiCallException.kindOf(failure);
        s.lastFailureAt = Instant.now();
        s.lastFailureKind = kind;
        s.lastFailureMessage = AiFailures.reason(failure);
        s.failures.incrementAndGet();

        if (kind == AiCallException.Kind.BAD_RESPONSE) {
            s.consecutiveFailures = 0;
            return;
        }
        s.consecutiveFailures++;

        Duration cooldown = switch (kind) {
            case RATE_LIMITED -> backoff(s.consecutiveFailures);
            case AUTH -> AUTH_COOLDOWN;
            default -> TRANSIENT_COOLDOWN;
        };
        s.cooldownUntil = Instant.now().plus(cooldown);

        // A bad key is a configuration mistake the founder has to act on, and it looks identical
        // to an outage in the logs unless it's named. Say it once per cooldown, at error
        // severity, rather than emitting the same warning on every retry for the rest of the day.
        if (kind == AiCallException.Kind.AUTH && !s.authReported) {
            s.authReported = true;
            events.systemError("provider_auth",
                    provider.getName() + " rejected the API key — check its env var.",
                    Map.of("provider", provider.getName()));
        } else if (kind != AiCallException.Kind.AUTH) {
            s.authReported = false;
        }
        log.info("AI provider {} benched for {}s after {}.",
                provider.getName(), cooldown.toSeconds(), kind);
    }

    private static Duration backoff(int consecutiveFailures) {
        long minutes = 1L << Math.min(consecutiveFailures - 1, 5); // 1, 2, 4, 8, 16, 32
        Duration d = RATE_LIMIT_BASE.multipliedBy(minutes);
        return d.compareTo(RATE_LIMIT_MAX) > 0 ? RATE_LIMIT_MAX : d;
    }

    /** Current state of every provider that has been called at least once, plus the given chain. */
    public List<Snapshot> snapshot(List<AiProperties.Provider> fast, List<AiProperties.Provider> heavy) {
        List<Snapshot> out = new ArrayList<>();
        fast.forEach(p -> out.add(snapshotOf("fast", p)));
        heavy.forEach(p -> out.add(snapshotOf("heavy", p)));
        return out;
    }

    private Snapshot snapshotOf(String tier, AiProperties.Provider provider) {
        State s = stateOf(provider);
        long successes = s.successes.get();
        Long avg = successes == 0 ? null : s.totalDurationMs.get() / successes;
        Instant until = s.cooldownUntil;
        Long coolingFor = until == null || !until.isAfter(Instant.now())
                ? null : Duration.between(Instant.now(), until).toSeconds();
        return new Snapshot(provider.getName(), tier, provider.getModel(), provider.isConfigured(),
                s.lastSuccessAt, s.lastFailureAt,
                s.lastFailureKind == null ? null : s.lastFailureKind.name(),
                s.lastFailureMessage, coolingFor, successes, s.failures.get(), avg, s.lastDurationMs);
    }

    private State stateOf(AiProperties.Provider provider) {
        return states.computeIfAbsent(key(provider), k -> new State());
    }

    // Name alone isn't unique enough — the same provider name can be pointed at a different
    // model by config, and those are separate quota pools in practice (Gemini Flash vs Pro).
    private static String key(AiProperties.Provider provider) {
        return provider.getName() + "|" + provider.getModel();
    }

    /** One provider's current state, for the admin view. */
    public record Snapshot(String name, String tier, String model, boolean configured,
                           Instant lastSuccessAt, Instant lastFailureAt, String lastFailureKind,
                           String lastFailureMessage, Long coolingDownForSeconds,
                           long successes, long failures, Long avgDurationMs, Long lastDurationMs) {
    }

    private static final class State {
        volatile Instant lastSuccessAt;
        volatile Instant lastFailureAt;
        volatile AiCallException.Kind lastFailureKind;
        volatile String lastFailureMessage;
        volatile Instant cooldownUntil;
        volatile int consecutiveFailures;
        volatile boolean authReported;
        volatile Long lastDurationMs;
        final AtomicLong successes = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong totalDurationMs = new AtomicLong();
    }
}
