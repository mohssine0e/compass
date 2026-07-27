package com.compass.app.entry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place that decides what inside an entry's {@code content} is safe to send to the
 * client.
 *
 * <p>{@code content} is JSONB and every DTO ships it wholesale, which is fine for everything the
 * founder wrote — and not fine for the answer key of a pending multiple-choice check. That's the
 * only server-only field today, but "the DTO forwards the whole map" is the kind of thing that
 * quietly leaks the *next* one too, so it gets a single choke point rather than a rule everyone
 * has to remember.
 */
public final class EntryContent {

    /** Field inside {@code pendingCheck} holding the answer key. Never leaves the server. */
    public static final String CORRECT_INDEX = "correctIndex";

    private EntryContent() {
    }

    /**
     * A copy of {@code content} safe to serialise to the client. Returns the input unchanged when
     * there's nothing to strip, so the common path allocates nothing.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> forClient(Map<String, Object> content) {
        if (content == null || !(content.get("pendingCheck") instanceof Map<?, ?> pending)
                || !pending.containsKey(CORRECT_INDEX)) {
            return content;
        }
        Map<String, Object> safe = new LinkedHashMap<>(content);
        Map<String, Object> scrubbed = new LinkedHashMap<>((Map<String, Object>) pending);
        scrubbed.remove(CORRECT_INDEX);
        safe.put("pendingCheck", scrubbed);
        return safe;
    }
}
