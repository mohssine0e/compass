package com.compass.app.ai;

import com.compass.app.events.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The circuit breaker's decisions, which are the whole point of it: which failures bench a
 * provider, for how long, and — most importantly — that a benched chain can still serve a call.
 *
 * <p>Pure in-memory logic, so this needs no Spring context, no database, and no provider key.
 */
class ProviderHealthTest {

    private ProviderHealth health;
    private AiProperties.Provider groq;
    private AiProperties.Provider gemini;

    @BeforeEach
    void setUp() {
        health = new ProviderHealth(Mockito.mock(EventService.class));
        groq = provider("groq", "llama-3.3-70b-versatile");
        gemini = provider("gemini-flash", "gemini-2.0-flash");
    }

    private static AiProperties.Provider provider(String name, String model) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setName(name);
        p.setModel(model);
        p.setBaseUrl("https://example.invalid/v1");
        p.setApiKey("test-key");
        return p;
    }

    private static AiCallException failure(AiCallException.Kind kind, Integer status) {
        return new AiCallException(kind, status, kind + " for test", null);
    }

    @Test
    @DisplayName("a fresh provider is available and in configured order")
    void freshProviderIsAvailable() {
        assertThat(health.isCooling(groq)).isFalse();
        assertThat(health.callOrder(List.of(groq, gemini))).containsExactly(groq, gemini);
    }

    @Test
    @DisplayName("a 429 benches the provider, so the next call skips straight past it")
    void rateLimitBenchesProvider() {
        health.recordFailure(groq, failure(AiCallException.Kind.RATE_LIMITED, 429));

        assertThat(health.isCooling(groq)).isTrue();
        // This is the actual fix: the dead provider is no longer first in line costing a timeout.
        assertThat(health.callOrder(List.of(groq, gemini))).containsExactly(gemini);
    }

    @Test
    @DisplayName("repeated 429s back off further each time, capped at 30 minutes")
    void rateLimitBackoffEscalatesAndCaps() {
        long previous = 0;
        for (int i = 0; i < 10; i++) {
            health.recordFailure(groq, failure(AiCallException.Kind.RATE_LIMITED, 429));
            long remaining = cooldownSecondsOf(groq);
            assertThat(remaining).isGreaterThanOrEqualTo(previous);
            previous = remaining;
        }
        // 1,2,4,8,16,32 minutes -> clamped to the 30-minute ceiling, not growing without bound.
        assertThat(previous).isLessThanOrEqualTo(30 * 60);
        assertThat(previous).isGreaterThan(25 * 60);
    }

    @Test
    @DisplayName("a bad key is reported once, not on every retry")
    void authFailureIsReportedOnce() {
        EventService events = Mockito.mock(EventService.class);
        ProviderHealth h = new ProviderHealth(events);

        h.recordFailure(groq, failure(AiCallException.Kind.AUTH, 401));
        h.recordFailure(groq, failure(AiCallException.Kind.AUTH, 401));
        h.recordFailure(groq, failure(AiCallException.Kind.AUTH, 401));

        // Otherwise a wrong key floods the operational log with the same line all day.
        Mockito.verify(events, Mockito.times(1))
                .systemError(Mockito.eq("provider_auth"), Mockito.anyString(), Mockito.any());
        assertThat(h.isCooling(groq)).isTrue();
    }

    @Test
    @DisplayName("an unusable reply does NOT bench the provider — that's our bug, not its health")
    void badResponseDoesNotBench() {
        health.recordFailure(groq, failure(AiCallException.Kind.BAD_RESPONSE, null));

        assertThat(health.isCooling(groq)).isFalse();
        assertThat(health.callOrder(List.of(groq, gemini))).containsExactly(groq, gemini);
    }

    @Test
    @DisplayName("success clears a cooldown and the failure streak")
    void successClearsCooldown() {
        health.recordFailure(groq, failure(AiCallException.Kind.RATE_LIMITED, 429));
        assertThat(health.isCooling(groq)).isTrue();

        health.recordSuccess(groq, 850);

        assertThat(health.isCooling(groq)).isFalse();
        assertThat(health.callOrder(List.of(groq, gemini))).containsExactly(groq, gemini);
    }

    @Test
    @DisplayName("when every provider is benched the call still goes out — a breaker must never hard-fail")
    void allBenchedStillReturnsWholeChain() {
        health.recordFailure(groq, failure(AiCallException.Kind.RATE_LIMITED, 429));
        health.recordFailure(gemini, failure(AiCallException.Kind.RATE_LIMITED, 429));

        List<AiProperties.Provider> order = health.callOrder(List.of(groq, gemini));

        // Turning a call that might have succeeded into a guaranteed failure would be worse than
        // the latency problem the breaker exists to solve.
        assertThat(order).containsExactlyInAnyOrder(groq, gemini);
    }

    @Test
    @DisplayName("timings and counts are reported per provider for the admin view")
    void snapshotReportsTimingAndCounts() {
        health.recordSuccess(groq, 1000);
        health.recordSuccess(groq, 2000);
        health.recordFailure(gemini, failure(AiCallException.Kind.RATE_LIMITED, 429));

        List<ProviderHealth.Snapshot> snaps = health.snapshot(List.of(groq, gemini), List.of());

        ProviderHealth.Snapshot g = snaps.stream().filter(s -> s.name().equals("groq")).findFirst().orElseThrow();
        assertThat(g.successes()).isEqualTo(2);
        assertThat(g.avgDurationMs()).isEqualTo(1500);
        assertThat(g.coolingDownForSeconds()).isNull();

        ProviderHealth.Snapshot f = snaps.stream().filter(s -> s.name().equals("gemini-flash")).findFirst().orElseThrow();
        assertThat(f.failures()).isEqualTo(1);
        assertThat(f.lastFailureKind()).isEqualTo("RATE_LIMITED");
        assertThat(f.coolingDownForSeconds()).isNotNull();
    }

    @Test
    @DisplayName("providers are tracked per model, since Gemini Flash and Pro are separate quotas")
    void providersAreKeyedByModelNotJustName() {
        AiProperties.Provider flash = provider("gemini", "gemini-2.0-flash");
        AiProperties.Provider pro = provider("gemini", "gemini-2.5-pro");

        health.recordFailure(flash, failure(AiCallException.Kind.RATE_LIMITED, 429));

        assertThat(health.isCooling(flash)).isTrue();
        assertThat(health.isCooling(pro)).isFalse();
    }

    private long cooldownSecondsOf(AiProperties.Provider provider) {
        return health.snapshot(List.of(provider), List.of()).get(0).coolingDownForSeconds();
    }
}
