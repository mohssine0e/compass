package com.compass.app.export;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.profile.LearnerProfile;
import com.compass.app.profile.LearnerProfileRepository;
import com.compass.app.topic.CanonicalTopic;
import com.compass.app.topic.CanonicalTopicRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Everything the founder has put into Compass, in one JSON document (V3-0.2).
 *
 * <p>There was no way to get the data out. Every roadmap, idea, and the learner profile lived in
 * exactly one local Postgres with no dump and no export — a disk failure or a bad {@code DROP}
 * was total, unrecoverable loss of months of work. This turns that into a file you can copy.
 *
 * <p>Deliberately excludes {@code system_events}: that's regenerable operational noise, it's
 * already pruned on a schedule, and including it would bury the actual content.
 *
 * <p>There is deliberately no matching import. Export alone makes the data survivable; a correct
 * import is a much larger problem (id remapping across {@code parent_id}/{@code depends_on}
 * chains) and isn't needed for that. The file is plain JSON — readable, and restorable by hand
 * or by a one-off script if it ever actually comes to that.
 */
@RestController
@RequestMapping("/export")
class ExportController {

    private final EntryRepository entries;
    private final LearnerProfileRepository profiles;
    private final CanonicalTopicRepository topics;

    ExportController(EntryRepository entries, LearnerProfileRepository profiles,
                     CanonicalTopicRepository topics) {
        this.entries = entries;
        this.profiles = profiles;
        this.topics = topics;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<ExportDocument> export() {
        List<EntryExport> allEntries = entries.findAllByOrderByCreatedAtDesc().stream()
                .map(EntryExport::of)
                .toList();
        ProfileExport profile = profiles.findFirstByOrderByIdAsc().map(ProfileExport::of).orElse(null);
        List<TopicExport> allTopics = topics.findAll().stream().map(TopicExport::of).toList();

        ExportDocument doc = new ExportDocument(1, Instant.now(), allEntries, profile, allTopics);

        // Content-Disposition so a browser hitting this URL saves a dated file instead of
        // rendering a wall of JSON — the frontend link relies on it for the filename.
        String filename = "compass-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(doc);
    }

    /** {@code schemaVersion} is here so a future reader can tell what shape it's looking at. */
    record ExportDocument(int schemaVersion, Instant exportedAt, List<EntryExport> entries,
                          ProfileExport learnerProfile, List<TopicExport> canonicalTopics) {
    }

    record EntryExport(Long id, String type, String status, String significance, Long parentId,
                       Integer orderIndex, Long dependsOn, Map<String, Object> content,
                       int skipCount, Instant createdAt, Instant updatedAt, Instant lastResurfacedAt) {
        static EntryExport of(Entry e) {
            return new EntryExport(e.getId(),
                    e.getType() == null ? null : e.getType().getValue(),
                    e.getStatus() == null ? null : e.getStatus().getValue(),
                    e.getSignificance() == null ? null : e.getSignificance().getValue(),
                    e.getParentId(), e.getOrderIndex(), e.getDependsOn(), e.getContent(),
                    e.getSkipCount(), e.getCreatedAt(), e.getUpdatedAt(), e.getLastResurfacedAt());
        }
    }

    record ProfileExport(Long id, List<Map<String, Object>> skills,
                         Map<String, Object> resumeExtracted, Map<String, Object> selfDescription,
                         Map<String, Object> formatPreferences, List<String> inferredPreferences,
                         Map<String, Object> learningPreferences,
                         Instant confirmedAt, Instant updatedAt) {
        static ProfileExport of(LearnerProfile p) {
            return new ProfileExport(p.getId(), p.getSkills(), p.getResumeExtracted(),
                    p.getSelfDescription(), p.getFormatPreferences(), p.getInferredPreferences(),
                    p.getLearningPreferences(), p.getConfirmedAt(), p.getUpdatedAt());
        }
    }

    // The embedding vector is deliberately dropped: it's hundreds of floats per topic, it would
    // dwarf everything else in the file, and it's fully regenerable from the canonical name.
    record TopicExport(Long id, String topicId, String canonicalName, List<String> aliases,
                       List<String> subtopics, List<String> prerequisites,
                       List<String> relatedTopics, String createdFrom, Long roadmapEntryId,
                       int usageCount, Instant createdAt, Instant lastUsedAt) {
        static TopicExport of(CanonicalTopic t) {
            return new TopicExport(t.getId(), t.getTopicId(), t.getCanonicalName(), t.getAliases(),
                    t.getSubtopics(), t.getPrerequisites(), t.getRelatedTopics(), t.getCreatedFrom(),
                    t.getRoadmapEntryId(), t.getUsageCount(), t.getCreatedAt(), t.getLastUsedAt());
        }
    }
}
