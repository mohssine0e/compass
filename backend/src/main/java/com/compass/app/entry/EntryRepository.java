package com.compass.app.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    /** All entries, newest first (for the list/capture views). */
    List<Entry> findAllByOrderByCreatedAtDesc();

    /** Steps of a roadmap, in their intended order. */
    List<Entry> findByParentIdOrderByOrderIndexAsc(Long parentId);

    /** All entries of one type, newest first (e.g. roadmaps for the roadmap list). */
    List<Entry> findByTypeOrderByCreatedAtDesc(EntryType type);
}
