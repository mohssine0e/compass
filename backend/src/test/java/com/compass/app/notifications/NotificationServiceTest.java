package com.compass.app.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure logic, no Spring context, no DB — same style as {@code EventServiceTest}. Covers the one
 * thing that matters for a polling feed: {@code since(afterId)} returns exactly what a client
 * hasn't seen yet, in order, and the store never grows unbounded.
 */
class NotificationServiceTest {

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
    }

    @Test
    @DisplayName("since(0) returns everything pushed so far, oldest first")
    void sinceZeroReturnsEverything() {
        service.push("Held.", null);
        service.push("Roadmap ready to review.", null);

        List<Notification> all = service.since(0);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).message()).isEqualTo("Held.");
        assertThat(all.get(1).message()).isEqualTo("Roadmap ready to review.");
    }

    @Test
    @DisplayName("since(afterId) excludes everything up to and including that id")
    void sinceExcludesAlreadySeenNotifications() {
        service.push("first", null);
        long secondId = service.since(0).get(0).id();
        service.push("second", null);
        service.push("third", null);

        List<Notification> unseen = service.since(secondId);

        assertThat(unseen).extracting(Notification::message).containsExactly("second", "third");
    }

    @Test
    @DisplayName("since(afterId) with no new notifications returns an empty list, not null")
    void sinceWithNothingNewReturnsEmpty() {
        service.push("only one", null);
        long lastId = service.since(0).get(0).id();

        assertThat(service.since(lastId)).isEmpty();
    }

    @Test
    @DisplayName("context is carried through unchanged")
    void contextIsCarriedThrough() {
        service.push("A module finished drafting.", Map.of("moduleId", 42L));

        assertThat(service.since(0).get(0).context()).containsEntry("moduleId", 42L);
    }

    @Test
    @DisplayName("the plain push() defaults to an 'info' tone; the explicit overload can say 'danger'")
    void toneDefaultsToInfoUnlessGivenExplicitly() {
        service.push("Roadmap ready to review.", null);
        service.push("Couldn't draft this module in the background.", "danger", null);

        List<Notification> all = service.since(0);
        assertThat(all.get(0).tone()).isEqualTo("info");
        assertThat(all.get(1).tone()).isEqualTo("danger");
    }

    @Test
    @DisplayName("ids are strictly increasing, so a client cursor never goes backwards")
    void idsAreStrictlyIncreasing() {
        service.push("a", null);
        service.push("b", null);
        service.push("c", null);

        List<Long> ids = service.since(0).stream().map(Notification::id).toList();

        assertThat(ids).isSorted();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("pushing past the cap drops the oldest rather than growing unbounded")
    void pushingPastCapDropsOldest() {
        for (int i = 0; i < 201; i++) {
            service.push("message " + i, null);
        }

        List<Notification> all = service.since(0);

        assertThat(all).hasSize(200);
        assertThat(all.get(0).message()).isEqualTo("message 1");
        assertThat(all.get(all.size() - 1).message()).isEqualTo("message 200");
    }

    @Test
    @DisplayName("sweeping fresh notifications removes nothing — only the 24-hour-stale ones are pruned")
    void sweepDoesNotRemoveFreshNotifications() {
        service.push("just now", null);

        service.sweep();

        assertThat(service.since(0)).hasSize(1);
    }

    @Test
    @DisplayName("recent() returns newest first, capped at the default limit")
    void recentReturnsNewestFirstCappedAtDefault() {
        for (int i = 0; i < 25; i++) {
            service.push("message " + i, null);
        }

        List<Notification> history = service.recent(null);

        assertThat(history).hasSize(20);
        assertThat(history.get(0).message()).isEqualTo("message 24");
        assertThat(history.get(history.size() - 1).message()).isEqualTo("message 5");
    }

    @Test
    @DisplayName("recent(limit) honors a caller-supplied limit, clamped to a sane range")
    void recentHonorsExplicitLimit() {
        service.push("a", null);
        service.push("b", null);
        service.push("c", null);

        assertThat(service.recent(2)).extracting(Notification::message).containsExactly("c", "b");
        // Clamped rather than trusted verbatim — a caller passing 0 or a negative number still
        // gets at least one, and an absurdly large number is capped, not passed through to grow
        // an unbounded response.
        assertThat(service.recent(0)).hasSize(1);
        assertThat(service.recent(10_000)).hasSize(3);
    }

    @Test
    @DisplayName("a fresh notification starts at count 1")
    void freshNotificationStartsAtCountOne() {
        service.push("Roadmap ready to review.", null);

        assertThat(service.since(0).get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("repeating the same (message, tone) right after coalesces into one entry with a rising count")
    void repeatedMessageCoalescesInsteadOfAppending() {
        String message = "Couldn't draft this module in the background — expand it yourself.";
        service.push(message, "danger", Map.of("moduleId", 1L));
        service.push(message, "danger", Map.of("moduleId", 2L));
        service.push(message, "danger", Map.of("moduleId", 3L));

        List<Notification> all = service.since(0);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).count()).isEqualTo(3);
        // Latest occurrence's context wins — see the coalescing javadoc on push() for why this
        // isn't merged across occurrences.
        assertThat(all.get(0).context()).containsEntry("moduleId", 3L);
    }

    @Test
    @DisplayName("coalescing keeps the original id, so an already-seen cursor doesn't replay it")
    void coalescingKeepsTheOriginalId() {
        service.push("same message", null);
        long firstId = service.since(0).get(0).id();

        service.push("same message", null);
        service.push("same message", null);

        assertThat(service.since(0)).hasSize(1);
        assertThat(service.since(0).get(0).id()).isEqualTo(firstId);
    }

    @Test
    @DisplayName("a different tone breaks the coalescing streak even with identical message text")
    void differentToneDoesNotCoalesce() {
        service.push("Couldn't draft this module.", "danger", null);
        service.push("Couldn't draft this module.", "info", null);

        assertThat(service.since(0)).hasSize(2);
    }

    @Test
    @DisplayName("a different message in between breaks the coalescing streak")
    void interveningDifferentMessageBreaksCoalescing() {
        service.push("first", null);
        service.push("interruption", null);
        service.push("first", null);

        List<Notification> all = service.since(0);
        assertThat(all).extracting(Notification::message).containsExactly("first", "interruption", "first");
        assertThat(all).allMatch(n -> n.count() == 1);
    }
}
