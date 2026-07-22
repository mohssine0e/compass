package com.compass.app.roadmap.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One node in a roadmap tree (Phase 13). A roadmap is a tree of these via {@code parent_id}:
 * a {@code roadmap} node can hold child {@code roadmap} modules, and a {@code roadmap_step} can
 * hold child {@code roadmap_step} substeps. A node with no children is a <em>leaf</em> — the
 * actual unit of work that progress, sessions, and verification operate on.
 *
 * <p>{@code progress} is filled for container nodes (those with children) by rolling up over
 * their leaf descendants; it's null for leaves, which carry their own {@code status} instead.
 *
 * <p>{@code missingProjectFlag} (Phase 24) is a soft, non-blocking signal set only by
 * {@link RoadmapResponse#of} on a career-scale roadmap's post-foundational top-level modules that
 * have been expanded but contain no {@code kind: "project"} step among their leaves — never
 * computed here, since a single node has no view of the roadmap's overall archetype or its
 * position among sibling modules.
 */
public record RoadmapNodeResponse(
        Long id,
        EntryType type,
        EntryStatus status,
        Integer orderIndex,
        Long dependsOn,
        Map<String, Object> content,
        NodeProgress progress,
        List<RoadmapNodeResponse> children,
        boolean missingProjectFlag
) {
    /** Rolled-up counts over a container's leaf descendants. */
    public record NodeProgress(int total, int done) {
    }

    /** Build a node (and its whole subtree) from an entry, resolving children via {@code childrenOf}. */
    public static RoadmapNodeResponse of(Entry entry, Function<Long, List<Entry>> childrenOf) {
        List<RoadmapNodeResponse> children = childrenOf.apply(entry.getId()).stream()
                .map(child -> of(child, childrenOf))
                .toList();

        NodeProgress progress = null;
        if (!children.isEmpty()) {
            List<RoadmapNodeResponse> leaves = new ArrayList<>();
            collectLeaves(children, leaves);
            int total = leaves.size();
            int done = (int) leaves.stream().filter(l -> l.status() == EntryStatus.DONE).count();
            progress = new NodeProgress(total, done);
        }

        return new RoadmapNodeResponse(
                entry.getId(),
                entry.getType(),
                entry.getStatus(),
                entry.getOrderIndex(),
                entry.getDependsOn(),
                entry.getContent(),
                progress,
                children,
                false
        );
    }

    /** A copy of this node with {@code missingProjectFlag} set — see the class doc. */
    public RoadmapNodeResponse withMissingProjectFlag(boolean flag) {
        return new RoadmapNodeResponse(id, type, status, orderIndex, dependsOn, content, progress,
                children, flag);
    }

    /** Whether any leaf step in this subtree is a {@code kind: "project"} step (Phase 24). */
    public static boolean hasProjectStep(RoadmapNodeResponse node) {
        if (node.children().isEmpty()) {
            return node.type() == EntryType.ROADMAP_STEP
                    && node.content() != null && "project".equals(node.content().get("kind"));
        }
        return node.children().stream().anyMatch(RoadmapNodeResponse::hasProjectStep);
    }

    /** Leaf step descendants of these nodes, in pre-order (the real order of work). */
    public static void collectLeaves(List<RoadmapNodeResponse> nodes, List<RoadmapNodeResponse> out) {
        for (RoadmapNodeResponse n : nodes) {
            if (n.children().isEmpty()) {
                if (n.type() == EntryType.ROADMAP_STEP) {
                    out.add(n);
                }
            } else {
                collectLeaves(n.children(), out);
            }
        }
    }
}
