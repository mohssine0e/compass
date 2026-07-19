package com.compass.app.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an event in its own transaction ({@code REQUIRES_NEW}) so operational logging is
 * independent of whatever the caller is doing: it works from a read-only transaction (e.g. the
 * restructure-proposal path) and still commits even if the surrounding request later fails.
 */
@Component
class EventWriter {

    private final SystemEventRepository repository;

    EventWriter(SystemEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(SystemEvent event) {
        repository.save(event);
    }
}
