package com.compass.app.entry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    /** All entries, newest first (for the list/capture views). */
    List<Entry> findAllByOrderByCreatedAtDesc();

    /** Steps of a roadmap, in their intended order. */
    List<Entry> findByParentIdOrderByOrderIndexAsc(Long parentId);

    /** All entries of one type, newest first (e.g. roadmaps for the roadmap list). */
    List<Entry> findByTypeOrderByCreatedAtDesc(EntryType type);

    /**
     * Every completed roadmap step, across all roadmaps, most recently updated first (RB-5.3) —
     * feeds the unified intake's PRACTICE/REVIEW picker: "pick a step you've already done, get
     * quizzed on it now" instead of waiting for the resurfacing schedule's single next-candidate.
     */
    List<Entry> findByTypeAndStatusOrderByUpdatedAtDesc(EntryType type, EntryStatus status);

    /**
     * The one entry most worth resurfacing right now, or empty if nothing qualifies.
     *
     * <p>Generic on type/status/last_resurfaced_at (not hardcoded to ideas, per CLAUDE.md
     * Section 4): an unresolved thread that's significant enough to chase — a BIG idea or a
     * roadmap you're mid-way through — that hasn't been touched since {@code staleBefore} and
     * hasn't been resurfaced since {@code resurfacedBefore}. Never-resurfaced items come
     * first, then the most stale.
     */
    @Query(value = """
            SELECT * FROM entries e
            WHERE e.status NOT IN ('done', 'dropped', 'archived')
              AND ( (e.type = 'idea' AND e.significance = 'big')
                    OR (e.type = 'roadmap' AND e.parent_id IS NULL) )
              AND e.updated_at < :staleBefore
              AND (e.last_resurfaced_at IS NULL OR e.last_resurfaced_at < :resurfacedBefore)
            ORDER BY (e.last_resurfaced_at IS NULL) DESC, e.updated_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Entry> findNextResurfaceCandidate(@Param("staleBefore") Instant staleBefore,
                                               @Param("resurfacedBefore") Instant resurfacedBefore);

    /**
     * Every descendant of {@code rootId} — modules, steps, substeps, however deep — in one query,
     * ordered so a parent always precedes its children and siblings keep their {@code order_index}.
     *
     * <p>Replaces the walk-one-node-at-a-time recursion the roadmap tree helpers used to do
     * (V3-3.1): building a leaf-step list for a career roadmap meant one {@code SELECT} per node,
     * 60+ round trips for a tree small enough to load whole in a single pass. The root itself is
     * deliberately excluded — callers that need it already hold it.
     */
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT e.*, ARRAY[COALESCE(e.order_index, 0)::bigint, e.id] AS path
                FROM entries e WHERE e.parent_id = :rootId
                UNION ALL
                SELECT c.*, s.path || ARRAY[COALESCE(c.order_index, 0)::bigint, c.id]
                FROM entries c JOIN subtree s ON c.parent_id = s.id
            )
            SELECT id, type, status, significance, parent_id, order_index, depends_on,
                   content, skip_count, created_at, updated_at, last_resurfaced_at
            FROM subtree ORDER BY path
            """, nativeQuery = true)
    List<Entry> findDescendants(@Param("rootId") Long rootId);

    /**
     * The chain from {@code id} up to its root, nearest ancestor first (the row for {@code id}
     * itself is not included). One query instead of a {@code findById} per level — used for depth
     * checks and for finding the root that carries a roadmap's stored assessment.
     */
    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT e.* , 1 AS lvl FROM entries e
                WHERE e.id = (SELECT parent_id FROM entries WHERE id = :id)
                UNION ALL
                SELECT p.*, a.lvl + 1 FROM entries p JOIN ancestors a ON p.id = a.parent_id
            )
            SELECT id, type, status, significance, parent_id, order_index, depends_on,
                   content, skip_count, created_at, updated_at, last_resurfaced_at
            FROM ancestors ORDER BY lvl
            """, nativeQuery = true)
    List<Entry> findAncestors(@Param("id") Long id);

    /** Bump an entry's updated_at (e.g. a roadmap when one of its steps is worked). */
    @Modifying
    @Query(value = "UPDATE entries SET updated_at = :now WHERE id = :id", nativeQuery = true)
    void touchUpdatedAt(@Param("id") Long id, @Param("now") Instant now);

    /**
     * The distinct parent module ids currently holding any skeleton (titles-only) step (Phase
     * 19) — the emergency fallback used when the whole heavy AI chain failed a module expansion.
     * Read by the background retry sweep to find modules worth re-attempting now that a provider
     * may have recovered.
     */
    @Query(value = """
            SELECT DISTINCT parent_id FROM entries
            WHERE type = 'roadmap_step' AND content->>'skeletonOnly' = 'true' AND parent_id IS NOT NULL
            """, nativeQuery = true)
    List<Long> findModuleIdsWithSkeletonSteps();

    /**
     * A verified-done roadmap step that's due for a spaced recheck (Phase 8): its
     * {@code nextRecheckAt} has passed and it isn't snoozed. Soonest-due first. This is the same
     * resurfacing engine firing on a different trigger — completed + due — not a new system.
     */
    @Query(value = """
            SELECT * FROM entries e
            WHERE e.type = 'roadmap_step' AND e.status = 'done'
              AND e.content->>'nextRecheckAt' IS NOT NULL
              AND (e.content->>'nextRecheckAt')::timestamptz <= :now
              AND (e.last_resurfaced_at IS NULL OR e.last_resurfaced_at < :resurfacedBefore)
            ORDER BY (e.content->>'nextRecheckAt')::timestamptz ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Entry> findNextRecheckCandidate(@Param("now") Instant now,
                                             @Param("resurfacedBefore") Instant resurfacedBefore);
}
