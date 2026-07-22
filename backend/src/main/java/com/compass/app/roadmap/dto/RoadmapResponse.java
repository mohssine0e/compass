package com.compass.app.roadmap.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
        String shape,
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
     * @param paceMultiplier (Phase 20) actual session minutes ÷ that session's resource's
     *                       estimated minutes, averaged over every session with both a real
     *                       duration and a parseable estimate on THIS roadmap; null below
     *                       {@value #MIN_PACE_SESSIONS} such sessions — too little signal to
     *                       say anything honest. &gt;1 means slower than estimated, &lt;1 faster.
     * @param paceSessions   how many sessions the pace reading is based on, for an honest caption
     */
    public record Progress(int total, int done, Long currentStepId, String currentStepText,
                           int estimatedTotalMinutes, Double paceMultiplier, int paceSessions) {
    }

    // Below this many paired (real duration + parseable estimate) sessions, a pace reading is
    // noise, not signal — stay silent rather than react to one unusually slow/fast session.
    private static final int MIN_PACE_SESSIONS = 3;

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
                Integer minutes = com.compass.app.roadmap.EstimatedTimeParser.parseMinutes(s);
                if (minutes != null) {
                    sum += minutes;
                }
            }
        }
        return sum;
    }

    /**
     * The actual-vs-estimated pace over every leaf's session history on this roadmap (Phase 20):
     * for each session with both a real {@code durationMinutes} and a {@code resourceUsed} that
     * matches one of that same step's resources with a parseable {@code estimatedTime}, pair the
     * two and accumulate. {@code null} multiplier below {@link #MIN_PACE_SESSIONS} pairs.
     */
    @SuppressWarnings("unchecked")
    private static PaceReading paceReading(List<RoadmapNodeResponse> nodes) {
        int actualMinutes = 0;
        int estimatedMinutes = 0;
        int pairs = 0;
        List<RoadmapNodeResponse> leaves = new ArrayList<>();
        RoadmapNodeResponse.collectLeaves(nodes, leaves);
        for (RoadmapNodeResponse leaf : leaves) {
            Map<String, Object> content = leaf.content();
            if (content == null) {
                continue;
            }
            Map<String, Integer> estimateByResourceId = new java.util.HashMap<>();
            if (content.get("resources") instanceof List<?> resources) {
                for (Object r : resources) {
                    if (r instanceof Map<?, ?> map && map.get("id") instanceof String id
                            && map.get("estimatedTime") instanceof String time) {
                        Integer minutes = com.compass.app.roadmap.EstimatedTimeParser.parseMinutes(time);
                        if (minutes != null) {
                            estimateByResourceId.put(id, minutes);
                        }
                    }
                }
            }
            if (estimateByResourceId.isEmpty() || !(content.get("sessionHistory") instanceof List<?> sessions)) {
                continue;
            }
            for (Object s : sessions) {
                if (!(s instanceof Map<?, ?> session)) {
                    continue;
                }
                Object durationRaw = session.get("durationMinutes");
                Object resourceUsed = session.get("resourceUsed");
                if (!(durationRaw instanceof Number duration) || !(resourceUsed instanceof String rid)) {
                    continue;
                }
                Integer estimate = estimateByResourceId.get(rid);
                if (estimate == null || estimate <= 0) {
                    continue;
                }
                actualMinutes += duration.intValue();
                estimatedMinutes += estimate;
                pairs++;
            }
        }
        if (pairs < MIN_PACE_SESSIONS || estimatedMinutes <= 0) {
            return new PaceReading(null, pairs);
        }
        return new PaceReading((double) actualMinutes / estimatedMinutes, pairs);
    }

    private record PaceReading(Double multiplier, int sessions) {
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
        PaceReading pace = paceReading(children);

        return new RoadmapResponse(
                roadmap.getId(),
                title,
                notes,
                roadmap.getStatus(),
                verify,
                shapeOf(roadmap, children),
                roadmap.getCreatedAt(),
                roadmap.getUpdatedAt(),
                children,
                new Progress(total, done, currentStepId, currentStepText, estimatedTotalMinutes,
                        pace.multiplier(), pace.sessions())
        );
    }

    /**
     * The roadmap's scale (Phase 21 list badge): the Phase 18 assessment's {@code shape} when
     * one was stored; for roadmaps created before assessments, fall back to what the structure
     * says — module children mean nested, only leaf steps mean flat.
     */
    private static String shapeOf(Entry roadmap, List<RoadmapNodeResponse> children) {
        if (roadmap.getContent() != null
                && roadmap.getContent().get("assessment") instanceof Map<?, ?> assessment
                && assessment.get("shape") instanceof String s) {
            return s;
        }
        boolean hasModules = children.stream()
                .anyMatch(c -> c.type() == EntryType.ROADMAP);
        return hasModules ? "nested" : "flat";
    }

    private static String asString(Entry entry, String key) {
        Object value = entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
