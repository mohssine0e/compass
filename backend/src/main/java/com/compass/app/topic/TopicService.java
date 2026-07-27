package com.compass.app.topic;

import com.compass.app.ai.TopicAiService;
import com.compass.app.events.EventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Topic evolution (RB-3.10) — a founder-triggered "suggest an addition" to an existing
 * Canonical Topic, same propose→approve→apply pattern used everywhere else in this codebase
 * (CLAUDE.md Section 5). Never automatic.
 */
@Service
public class TopicService {

    private final CanonicalTopicRepository repository;
    private final TopicAiService topicAi;
    private final EventService events;

    public TopicService(CanonicalTopicRepository repository, TopicAiService topicAi, EventService events) {
        this.repository = repository;
        this.topicAi = topicAi;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public CanonicalTopic get(Long topicId) {
        return repository.findById(topicId)
                .orElseThrow(() -> new NoSuchElementException("No canonical topic with id " + topicId));
    }

    /** The canonical topic this roadmap was created as/from, if it still has one (RB-3.10). */
    @Transactional(readOnly = true)
    public CanonicalTopic forRoadmap(Long roadmapEntryId) {
        return repository.findByRoadmapEntryId(roadmapEntryId).orElse(null);
    }

    /**
     * Draft the specific edit a founder's free-text suggestion implies — duplicate/relevance
     * checked, nothing applied yet.
     */
    @Transactional(readOnly = true)
    public TopicAiService.AdditionProposal proposeAddition(Long topicId, String suggestion) {
        CanonicalTopic topic = get(topicId);
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException("Say what you want to add first.");
        }
        TopicAiService.AdditionProposal proposal = topicAi.proposeAddition(topic.getCanonicalName(),
                topic.getAliases(), topic.getSubtopics(), topic.getPrerequisites(), suggestion.trim());
        if (proposal == null) {
            throw new IllegalStateException("Couldn't draft that addition right now — try again shortly.");
        }
        return proposal;
    }

    /**
     * Apply a confirmed addition (RB-3.10) — the founder owns the final value, so this trusts
     * whatever text is given rather than re-deriving it from the proposal. Silently no-ops if
     * the value is already present (case-insensitive), rather than creating a near-duplicate.
     */
    @Transactional
    public CanonicalTopic applyAddition(Long topicId, String field, String value) {
        CanonicalTopic topic = get(topicId);
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Nothing to add.");
        }
        List<String> list = listFor(topic, field);
        boolean alreadyThere = list.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed));
        if (!alreadyThere) {
            List<String> updated = new ArrayList<>(list);
            updated.add(trimmed);
            setListFor(topic, field, updated);
            repository.save(topic);
        }
        events.founderAction("topic_addition", "added \"" + trimmed + "\" to " + field
                + " on canonical topic \"" + topic.getCanonicalName() + "\"",
                Map.of("canonicalTopicId", topic.getId()));
        return topic;
    }

    private static List<String> listFor(CanonicalTopic topic, String field) {
        return switch (field) {
            case "prerequisites" -> topic.getPrerequisites();
            case "aliases" -> topic.getAliases();
            default -> topic.getSubtopics();
        };
    }

    private static void setListFor(CanonicalTopic topic, String field, List<String> value) {
        switch (field) {
            case "prerequisites" -> topic.setPrerequisites(value);
            case "aliases" -> topic.setAliases(value);
            default -> topic.setSubtopics(value);
        }
    }
}
