package com.compass.app.roadmap.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A roadmap plus its tree of nodes and a "where am I" summary (Phase 13). A simple roadmap is a
 * tree one level deep (its steps are the children) and reads as a plain linear list; a big one
 * nests modules and substeps. Progress and "current step" are computed over leaf steps, wherever
 * they sit in the tree, so a parent's numbers always reflect real units of work.
 */
public record RoadmapResponse(
        Long id,
        String title,
        String notes,
        EntryStatus status,
        String verify,
        Instant createdAt,
        Instant updatedAt,
        List<RoadmapNodeResponse> children,
        Progress progress
) {
    /**
     * @param total          number of leaf steps
     * @param done           leaf steps marked done
     * @param currentStepId  id of the first not-done/dropped leaf in tree order — where you
     *                       actually are; null when nothing is left
     * @param currentStepText that leaf's text, for the list view; null when complete
     * @param estimatedTotalMinutes sum of every attached resource's parseable estimated time
     *                       across the whole tree (Phase 18) — free-text estimates that don't
     *                       match a strict "~Nh"/"~N min" pattern are silently dropped rather
     *                       than guessed at; 0 when nothing parseable is attached
     */
    public record Progress(int total, int done, Long currentStepId, String currentStepText,
                           int estimatedTotalMinutes) {
    }

    // Matches the estimated-time formats generation actually produces (e.g. "~30 min", "~1h",
    // "~2 hours"); anything else (e.g. "a few hours") is intentionally left unparsed.
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^~?\\s*(\\d+)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)\\.?$",
            Pattern.CASE_INSENSITIVE);

    private static int estimatedTotalMinutes(List<RoadmapNodeResponse> nodes) {
        int total = 0;
        for (RoadmapNodeResponse n : nodes) {
            total += n.children().isEmpty()
                    ? minutesFromResources(n.content()) : estimatedTotalMinutes(n.children());
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static int minutesFromResources(Map<String, Object> content) {
        if (content == null || !(content.get("resources") instanceof List<?> list)) {
            return 0;
        }
        int sum = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("estimatedTime") instanceof String s) {
                Integer minutes = parseMinutes(s);
                if (minutes != null) {
                    sum += minutes;
                }
            }
        }
        return sum;
    }

    private static Integer parseMinutes(String s) {
        Matcher m = TIME_PATTERN.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        int value = Integer.parseInt(m.group(1));
        return m.group(2).toLowerCase().startsWith("h") ? value * 60 : value;
    }

    public static RoadmapResponse of(Entry roadmap, Function<Long, List<Entry>> childrenOf) {
        String title = asString(roadmap, "title");
        String notes = asString(roadmap, "notes");
        String verify = asString(roadmap, "verify"); // roadmap-wide default: null/light/full

        List<RoadmapNodeResponse> children = childrenOf.apply(roadmap.getId()).stream()
                .map(child -> RoadmapNodeResponse.of(child, childrenOf))
                .toList();

        List<RoadmapNodeResponse> leaves = new ArrayList<>();
        RoadmapNodeResponse.collectLeaves(children, leaves);

        int total = leaves.size();
        int done = (int) leaves.stream().filter(l -> l.status() == EntryStatus.DONE).count();
        RoadmapNodeResponse current = leaves.stream()
                .filter(l -> l.status() != EntryStatus.DONE && l.status() != EntryStatus.DROPPED)
                .findFirst()
                .orElse(null);
        Long currentStepId = current == null ? null : current.id();
        String currentStepText = current == null ? null
                : (current.content() != null && current.content().get("text") instanceof String s ? s : null);
        int estimatedTotalMinutes = estimatedTotalMinutes(children);

        return new RoadmapResponse(
                roadmap.getId(),
                title,
                notes,
                roadmap.getStatus(),
                verify,
                roadmap.getCreatedAt(),
                roadmap.getUpdatedAt(),
                children,
                new Progress(total, done, currentStepId, currentStepText, estimatedTotalMinutes)
        );
    }

    private static String asString(Entry entry, String key) {
        Object value = entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
