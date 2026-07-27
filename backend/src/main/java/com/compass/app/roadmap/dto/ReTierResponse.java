package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * The result of a re-tier request (RB-2.5). Exactly one shape is populated, keyed by
 * {@code status}:
 * <ul>
 *   <li>{@code applied} → the change already happened (TASK conversion, flatten to MINI, or a
 *       plain tier relabel) — {@code roadmapId}/{@code taskEntryId} says what to look at now.</li>
 *   <li>{@code proposal} → nothing has changed yet; {@code proposal} holds an AI-drafted grouping
 *       or arc order for the founder to review, edit, and confirm via a second call.</li>
 * </ul>
 */
public record ReTierResponse(
        String status,
        Long roadmapId,
        Long taskEntryId,
        String taskText,
        String acknowledgment,
        Proposal proposal
) {
    /**
     * {@code kind} is {@code "regroup"} (MINI → TOPIC/CAREER: groups reference existing step ids,
     * each becomes a new module) or {@code "arc_order"} (TOPIC → CAREER: an order over existing
     * module ids, {@code phaseLabel} is informational only — see RB-4.3, phases aren't a real
     * stored structural entity).
     */
    public record Proposal(String kind, List<Group> groups) {
    }

    /** One proposed group. For "regroup", {@code entryIds} are step ids and title/scope are set;
     * for "arc_order", {@code entryIds} is a single existing module id and title/scope are null. */
    public record Group(String title, String scope, List<Long> entryIds, String phaseLabel) {
    }

    public static ReTierResponse applied(Long roadmapId) {
        return new ReTierResponse("applied", roadmapId, null, null, null, null);
    }

    public static ReTierResponse taskConversion(Long taskEntryId, String taskText, String acknowledgment) {
        return new ReTierResponse("applied", null, taskEntryId, taskText, acknowledgment, null);
    }

    public static ReTierResponse proposal(String kind, List<Group> groups) {
        return new ReTierResponse("proposal", null, null, null, null, new Proposal(kind, groups));
    }
}
