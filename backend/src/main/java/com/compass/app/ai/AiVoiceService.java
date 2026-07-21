package com.compass.app.ai;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.events.EventService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns an entry into a short acknowledgment in the user's self-talk voice.
 *
 * <p>Provider-agnostic with failover: tries the primary provider, then the backup on any
 * error or timeout. Best-effort by design — capture and completion must never fail or stall
 * because the AI is slow or unconfigured. When neither provider is configured, or both fail,
 * this returns {@code null} and the caller falls back to a plain confirmation.
 */
@Service
public class AiVoiceService {

    private static final Logger log = LoggerFactory.getLogger(AiVoiceService.class);

    private final AiProperties props;
    private final OpenAiCompatibleChatClient chat;
    private final EventService events;

    public AiVoiceService(AiProperties props, OpenAiCompatibleChatClient chat, EventService events) {
        this.props = props;
        this.chat = chat;
        this.events = events;
    }

    @PostConstruct
    void logConfig() {
        if (anyConfigured()) {
            String chain = props.providersFor(AiTier.FAST).stream()
                    .map(AiProperties.Provider::getModel)
                    .reduce((a, b) -> a + " -> " + b)
                    .orElse("none");
            log.info("AI acknowledgments (fast tier): {}.", chain);
        } else {
            log.info("AI acknowledgments disabled (no fast-tier provider key set) — capture still works.");
        }
    }

    private boolean anyConfigured() {
        return !props.providersFor(AiTier.FAST).isEmpty();
    }

    /**
     * A short self-talk-voice line acknowledging this entry, or {@code null} if no provider
     * is configured or both providers fail. The moment (just captured vs. marked done) is
     * inferred from the entry's state.
     */
    public String acknowledge(Entry entry) {
        if (entry == null) {
            return null;
        }

        String moment = entry.getStatus() == EntryStatus.DONE
                ? "just marked this done, self-reported"
                : "just captured this";
        String type = entry.getType() != null ? entry.getType().getValue() : "idea";
        String significance = entry.getType() == EntryType.IDEA && entry.getSignificance() != null
                ? entry.getSignificance().getValue()
                : null;
        String text = textOf(entry);
        return generate("acknowledgment", PromptTemplates.ACK_SYSTEM,
                PromptTemplates.ackUser(moment, type, significance, text));
    }

    /**
     * An honest, specific resurfacing question in the user's self-talk voice. Never null —
     * falls back to a plain, still-specific question when no provider is configured or both
     * fail, so the resurfacing prompt always has something honest to ask.
     */
    public String resurfaceQuestion(Entry entry) {
        return resurfaceQuestion(entry, null, entry != null ? entry.getSkipCount() : 0);
    }

    /**
     * As {@link #resurfaceQuestion(Entry)}, but able to name the specific step the user is
     * stuck on (for a roadmap) and to weigh a run of skips — repeated avoidance should read
     * differently from a first skip (CLAUDE.md Section 2). Never null.
     */
    public String resurfaceQuestion(Entry entry, String currentStepText, int skipCount) {
        if (entry == null) {
            return null;
        }
        String type = entry.getType() != null ? entry.getType().getValue() : "idea";
        String significance = entry.getType() == EntryType.IDEA && entry.getSignificance() != null
                ? entry.getSignificance().getValue()
                : null;
        String text = textOf(entry);
        long days = daysSince(entry.getUpdatedAt());

        String q = generate("resurfacing question", PromptTemplates.RESURFACE_SYSTEM,
                PromptTemplates.resurfaceUser(type, significance, text, days, currentStepText, skipCount));
        return q != null ? q : fallbackQuestion(entry, text, currentStepText, skipCount);
    }

    /**
     * Try each configured fast-tier provider in order; null if none is configured or all fail.
     * Each provider failure records a brief event; if all fail (with at least one configured),
     * records the feature-level fall-back to the plain path too.
     */
    private String generate(String feature, String system, String user) {
        for (AiProperties.Provider provider : props.providersFor(AiTier.FAST)) {
            String line = tryProvider(provider, system, user);
            if (line != null) {
                return line;
            }
        }
        if (anyConfigured()) {
            events.aiWarning("fallback",
                    "All AI providers failed for " + feature + "; used the plain fallback.", null);
        }
        return null;
    }

    private static String fallbackQuestion(Entry entry, String text, String currentStepText, int skipCount) {
        // Repeated skips get the honest avoidance question even without a provider.
        if (skipCount >= 2) {
            String step = shorten(currentStepText != null && !currentStepText.isBlank()
                    ? currentStepText : text);
            return "Skipped \"" + step + "\" a few times now. Wrong next step, or avoiding it?";
        }
        if (entry.getType() == EntryType.ROADMAP) {
            String step = currentStepText != null && !currentStepText.isBlank()
                    ? shorten(currentStepText) : shorten(text);
            return "Haven't moved on \"" + step + "\" in a while. Stuck, or done with it?";
        }
        return "\"" + shorten(text) + "\" — still worth it, or let it go?";
    }

    private static String shorten(String s) {
        if (s == null) {
            return "this";
        }
        String t = s.trim();
        return t.length() <= 60 ? t : t.substring(0, 57).trim() + "…";
    }

    private static long daysSince(java.time.Instant instant) {
        if (instant == null) {
            return 0;
        }
        return java.time.Duration.between(instant, java.time.Instant.now()).toDays();
    }

    private String tryProvider(AiProperties.Provider provider, String system, String user) {
        if (!provider.isConfigured()) {
            return null;
        }
        try {
            long timeout = provider.getTimeoutSecondsOverride() != null
                    ? provider.getTimeoutSecondsOverride() : props.getTimeoutSeconds();
            String line = chat.complete(provider, timeout, props.getMaxTokens(), system, user);
            if (line == null || line.isBlank()) {
                return null;
            }
            // Models sometimes wrap the line in quotes despite the instruction; strip them.
            return stripQuotes(line.trim());
        } catch (RuntimeException ex) {
            log.warn("AI provider ({}) failed: {}", provider.getModel(), ex.getMessage());
            events.aiWarning(AiFailures.category(ex),
                    provider.getModel() + " failed: " + AiFailures.reason(ex), null);
            return null;
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && (s.charAt(0) == '"' || s.charAt(0) == '“')
                && (s.charAt(s.length() - 1) == '"' || s.charAt(s.length() - 1) == '”')) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String textOf(Entry entry) {
        Object text = entry.getContent() != null ? entry.getContent().get("text") : null;
        if (text instanceof String s) {
            return s;
        }
        Object title = entry.getContent() != null ? entry.getContent().get("title") : null;
        return title instanceof String s ? s : "";
    }
}
