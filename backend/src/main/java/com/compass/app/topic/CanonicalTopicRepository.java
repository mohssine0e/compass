package com.compass.app.topic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CanonicalTopicRepository extends JpaRepository<CanonicalTopic, Long> {

    Optional<CanonicalTopic> findByTopicId(String topicId);

    /** The canonical topic this roadmap was created as/from (RB-3.10), if it still has one. */
    Optional<CanonicalTopic> findByRoadmapEntryId(Long roadmapEntryId);
}
