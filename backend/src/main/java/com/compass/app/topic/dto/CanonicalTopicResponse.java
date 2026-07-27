package com.compass.app.topic.dto;

import com.compass.app.topic.CanonicalTopic;

import java.util.List;

public record CanonicalTopicResponse(Long id, String topicId, String canonicalName,
                                     List<String> aliases, List<String> subtopics,
                                     List<String> prerequisites, Long roadmapEntryId) {
    public static CanonicalTopicResponse from(CanonicalTopic t) {
        if (t == null) {
            return null;
        }
        return new CanonicalTopicResponse(t.getId(), t.getTopicId(), t.getCanonicalName(),
                t.getAliases(), t.getSubtopics(), t.getPrerequisites(), t.getRoadmapEntryId());
    }
}
