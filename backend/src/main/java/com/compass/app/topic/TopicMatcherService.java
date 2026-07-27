package com.compass.app.topic;

import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.TopicAiService;
import com.compass.app.events.EventService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Canonical Topic Matching (RB-3): before generating anything fresh for a MINI/TOPIC/CAREER
 * goal, check whether it matches something the founder already has a roadmap for. Embed the
 * goal, compare by cosine similarity against every stored {@link CanonicalTopic}'s embedding,
 * and only fall back to one AI judgment call (fast tier) when the raw similarity is genuinely
 * ambiguous — a clearly high or clearly low score never needs the AI at all (RB-3.3).
 */
@Service
public class TopicMatcherService {

    // Above this raw cosine similarity, treat it as a match without asking the AI to confirm.
    private static final double HIGH_SIMILARITY = 0.85;
    // Below this, treat it as genuinely a different topic without asking the AI to confirm.
    private static final double LOW_SIMILARITY = 0.35;

    private final CanonicalTopicRepository repository;
    private final EmbeddingService embeddings;
    private final TopicAiService topicAi;
    private final EventService events;

    public TopicMatcherService(CanonicalTopicRepository repository, EmbeddingService embeddings,
                               TopicAiService topicAi, EventService events) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.topicAi = topicAi;
        this.events = events;
    }

    /**
     * Match {@code goal} against every stored canonical topic. {@code null} means no matching
     * could be attempted at all (embeddings unavailable, or no topics stored yet) — the caller
     * treats that exactly like a "new" result and runs the full generation pipeline unchanged.
     */
    public MatchResult match(String goal) {
        List<Double> goalEmbedding = embeddings.embed(goal);
        if (goalEmbedding == null) {
            return null;
        }
        List<CanonicalTopic> topics = repository.findAll();
        if (topics.isEmpty()) {
            return null;
        }

        CanonicalTopic best = null;
        double bestSimilarity = -1.0;
        for (CanonicalTopic topic : topics) {
            double similarity = cosineSimilarity(goalEmbedding, topic.getEmbedding());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = topic;
            }
        }

        String matchType;
        double confidence;
        String reasoning;
        boolean skippedAi;
        if (bestSimilarity >= HIGH_SIMILARITY) {
            matchType = "exact";
            confidence = bestSimilarity;
            reasoning = "Raw embedding similarity was clearly high — no AI judgment needed.";
            skippedAi = true;
        } else if (bestSimilarity <= LOW_SIMILARITY) {
            matchType = "new";
            confidence = 1.0 - bestSimilarity;
            reasoning = "Raw embedding similarity was clearly low — no AI judgment needed.";
            skippedAi = true;
        } else {
            TopicAiService.MatchJudgment judgment = topicAi.classifyMatch(
                    goal, best.getCanonicalName(), best.getAliases(), best.getSubtopics());
            if (judgment == null) {
                // The judgment call itself failed — same as "new," never blocks generation.
                matchType = "new";
                confidence = 0.0;
                reasoning = "Ambiguous similarity, and the AI judgment call failed.";
            } else {
                matchType = judgment.matchType();
                confidence = judgment.confidence();
                reasoning = judgment.reasoning();
            }
            skippedAi = false;
        }

        // Logged so the thresholds above can be tuned from real usage later, not fixed forever
        // (RB-3.3).
        events.info("topic_match", "goal matched against \"" + best.getCanonicalName()
                + "\" — raw similarity " + String.format(java.util.Locale.ROOT, "%.3f", bestSimilarity)
                + (skippedAi ? " (AI skipped)" : " (AI judged)") + " -> " + matchType,
                Map.of("candidateTopicId", best.getId()));

        if (!"new".equals(matchType)) {
            best.setUsageCount(best.getUsageCount() + 1);
            best.setLastUsedAt(java.time.Instant.now());
            repository.save(best);
        }

        return new MatchResult(matchType, confidence, reasoning, best, bestSimilarity);
    }

    /** Standard cosine similarity over two equal-length float vectors; 0 if lengths mismatch. */
    static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * {@code matchType} is exact|subtopic|prerequisite|new; {@code topic} is the best-matching
     * candidate (never null when this record exists); {@code rawSimilarity} is the raw cosine
     * score before any AI judgment, kept for the founder-decision UI's own reference.
     */
    public record MatchResult(String matchType, double confidence, String reasoning,
                              CanonicalTopic topic, double rawSimilarity) {
    }
}
