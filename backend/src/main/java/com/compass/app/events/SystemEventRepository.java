package com.compass.app.events;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SystemEventRepository extends JpaRepository<SystemEvent, Long> {

    /**
     * Recent events, newest first, optionally filtered by source and/or severity. A null
     * filter means "any" — so the same query serves the unfiltered list and every combination.
     */
    @Query("""
            SELECT e FROM SystemEvent e
            WHERE (:source IS NULL OR e.source = :source)
              AND (:severity IS NULL OR e.severity = :severity)
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<SystemEvent> findRecent(@Param("source") EventSource source,
                                 @Param("severity") EventSeverity severity,
                                 Pageable pageable);
}
