package com.compass.app.resource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResourceEnrichmentRepository extends JpaRepository<ResourceEnrichment, Long> {

    /** The cache-hit check (RES-1's actual cache key): has this url/topic pair already been enriched? */
    Optional<ResourceEnrichment> findByResourceUrlAndTopicKey(String resourceUrl, String topicKey);
}
