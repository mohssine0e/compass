package com.compass.app.notifications;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, in-memory feed of {@link Notification}s the frontend polls (a global toast, not
 * per-screen) — same in-memory, no-DB-table reasoning as {@link com.compass.app.roadmap.GenerationJobService}:
 * single-user, no auth, and a notification lost on restart is a missed toast, not lost data.
 *
 * <p>A {@link ConcurrentSkipListMap}, not a plain {@code ConcurrentHashMap} like the two
 * existing job stores — {@link #since} needs ordered iteration by id, which a hash map doesn't
 * give.
 */
@Service
public class NotificationService {

    // Long enough to genuinely be "history for later" (the founder can open the panel hours
    // later and still find what they missed), short enough that this stays a recent-signal view
    // rather than a permanent log — same reasoning EventRetentionService already uses for
    // system_events, just a different window since these are read by a human, not an admin.
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int MAX_SIZE = 200;
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 100;

    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentSkipListMap<Long, Notification> notifications = new ConcurrentSkipListMap<>();

    /** Queue an ordinary ("info") notification. Best-effort — never on a path that can't afford it. */
    public void push(String message, Map<String, Object> context) {
        push(message, "info", context);
    }

    /**
     * As {@link #push(String, Map)}, but with an explicit tone ("info" or "danger") for the
     * toast.
     *
     * <p>V4-1: coalesces with the most recently pushed notification when it has the exact same
     * {@code (message, tone)} — the real case this handles is a batch module expansion where
     * several modules fail the same way in quick succession (same message text, a different
     * {@code moduleId} in {@code context} each time); the per-occurrence context isn't shown in
     * the toast text anyway, so a matching {@code (message, tone)} is treated as "the same
     * event happening again," not a distinct one, and {@code context} is simply replaced with
     * the latest occurrence's rather than merged. Coalescing keeps the original {@code id} and
     * bumps {@code createdAt} to now, so a client that already fetched and is still showing that
     * toast won't see the updated count until its next unrelated poll surfaces something new
     * past it — an accepted gap (see {@code TASKS_v4.md} V4-1.1) rather than building a second
     * "update an already-delivered item" protocol for what's fundamentally a burst-arrival
     * problem: the common case this fixes is many duplicates landing before the first poll ever
     * happens, which coalesces perfectly into one entry with the right count.
     *
     * <p>{@code synchronized}, not just relying on the map's own thread safety: coalescing is a
     * check-then-act (look at the last entry, then decide whether to overwrite it or append) —
     * two concurrent pushes of the same message (a real scenario, {@code ModulePrefetchService}
     * runs several modules on separate threads) could otherwise both see "no match yet" and both
     * append, which is exactly the duplication this exists to prevent. Push is infrequent
     * (background job completions), not a hot path, so a coarse lock costs nothing that matters.
     */
    public synchronized void push(String message, String tone, Map<String, Object> context) {
        Map.Entry<Long, Notification> last = notifications.lastEntry();
        if (last != null && last.getValue().message().equals(message) && last.getValue().tone().equals(tone)) {
            Notification bumped = new Notification(last.getKey(), message, tone, Instant.now(), context,
                    last.getValue().count() + 1);
            notifications.put(last.getKey(), bumped);
            return;
        }
        long id = nextId.getAndIncrement();
        notifications.put(id, new Notification(id, message, tone, Instant.now(), context, 1));
        if (notifications.size() > MAX_SIZE) {
            notifications.pollFirstEntry();
        }
    }

    /** Every notification with an id strictly greater than {@code afterId}, oldest first. */
    public List<Notification> since(long afterId) {
        return List.copyOf(notifications.tailMap(afterId, false).values());
    }

    /**
     * The most recent {@code limit} notifications, newest first — independent of any client's
     * own poll cursor. Backs the "past notifications" history panel, so something already
     * consumed off the toast feed (dismissed, auto-expired, or dropped by the 10-toast display
     * cap) is still findable here.
     */
    public List<Notification> recent(Integer limit) {
        int capped = limit == null ? DEFAULT_HISTORY_LIMIT : Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        List<Notification> newestFirst = new ArrayList<>(notifications.descendingMap().values());
        return newestFirst.size() <= capped ? newestFirst : newestFirst.subList(0, capped);
    }

    @Scheduled(fixedRate = 60_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        notifications.values().removeIf(n -> n.createdAt().isBefore(cutoff));
    }
}
