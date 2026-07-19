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
            WHERE e.status NOT IN ('done', 'dropped')
              AND ( (e.type = 'idea' AND e.significance = 'big') OR e.type = 'roadmap' )
              AND e.updated_at < :staleBefore
              AND (e.last_resurfaced_at IS NULL OR e.last_resurfaced_at < :resurfacedBefore)
            ORDER BY (e.last_resurfaced_at IS NULL) DESC, e.updated_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Entry> findNextResurfaceCandidate(@Param("staleBefore") Instant staleBefore,
                                               @Param("resurfacedBefore") Instant resurfacedBefore);

    /** Bump an entry's updated_at (e.g. a roadmap when one of its steps is worked). */
    @Modifying
    @Query(value = "UPDATE entries SET updated_at = :now WHERE id = :id", nativeQuery = true)
    void touchUpdatedAt(@Param("id") Long id, @Param("now") Instant now);
}
