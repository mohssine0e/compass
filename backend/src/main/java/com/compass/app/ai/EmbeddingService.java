package com.compass.app.ai;

import com.compass.app.events.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Text embeddings via Gemini (RB-3), for Canonical Topic Matching's cosine-similarity search.
 * Best-effort like every other AI call in this codebase: {@code null} when unconfigured or the
 * call fails, so the caller (the topic matcher) falls back to treating the goal as having no
 * match rather than blocking generation on it.
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final AiProperties props;
    private final EmbeddingClient client;
    private final EventService events;

    public EmbeddingService(AiProperties props, EmbeddingClient client, EventService events) {
        this.props = props;
        this.client = client;
        this.events = events;
    }

    public boolean isAvailable() {
        return props.embeddingConfigured();
    }

    /** The embedding vector for {@code text}, or {@code null} if unconfigured or the call fails. */
    public List<Double> embed(String text) {
        if (!props.embeddingConfigured()) {
            return null;
        }
        try {
            return client.embed(props, text);
        } catch (RuntimeException ex) {
            log.warn("Embedding call failed: {}", ex.getMessage());
            events.aiWarning("provider_error", "Embedding call failed: " + AiFailures.reason(ex), null);
            return null;
        }
    }
}
