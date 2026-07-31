package com.compass.app.notifications;

import java.time.Instant;
import java.util.Map;

/**
 * One user-facing "something finished" line (self-talk voice, CLAUDE.md Section 2) — an
 * acknowledgment that was too slow to return synchronously, a roadmap that finished
 * generating, a module that finished drafting, in the background. Distinct from
 * {@code SystemEvent} on purpose: that table is operational/admin noise the founder never
 * captured (CLAUDE.md Section 4); this is content already meant for the founder, just
 * delivered late because it took real AI time to produce.
 *
 * <p>{@code count} (V4-1): when the same {@code (message, tone)} pair arrives again right after
 * the last one (e.g. several modules in a batch each failing to draft), {@link NotificationService}
 * bumps this on the existing entry instead of appending a near-duplicate — ten copies of "Couldn't
 * draft this module in the background" is noise, not signal (CLAUDE.md Section 2's own "brief,
 * for noticing patterns early" reasoning for {@code system_events} applies just as much to a
 * founder-facing toast). {@code 1} for an ordinary, non-repeated notification.
 */
public record Notification(long id, String message, String tone, Instant createdAt, Map<String, Object> context,
                            int count) {
}
