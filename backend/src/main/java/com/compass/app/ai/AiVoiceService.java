package com.compass.app.ai;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
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

    public AiVoiceService(AiProperties props, OpenAiCompatibleChatClient chat) {
        this.props = props;
        this.chat = chat;
    }

    @PostConstruct
    void logConfig() {
        String primary = props.getPrimary().isConfigured()
                ? props.getPrimary().getModel() : "not configured";
        String backup = props.getBackup().isConfigured()
                ? props.getBackup().getModel() : "not configured";
        if (props.getPrimary().isConfigured() || props.getBackup().isConfigured()) {
            log.info("AI acknowledgments: primary={}, backup={}.", primary, backup);
        } else {
            log.info("AI acknowledgments disabled (no provider key set) — capture still works.");
        }
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
        String system = PromptTemplates.ACK_SYSTEM;
        String user = PromptTemplates.ackUser(moment, type, significance, text);

        String line = tryProvider("primary", props.getPrimary(), system, user);
        if (line == null) {
            line = tryProvider("backup", props.getBackup(), system, user);
        }
        return line;
    }

    private String tryProvider(String role, AiProperties.Provider provider, String system, String user) {
        if (!provider.isConfigured()) {
            return null;
        }
        try {
            String line = chat.complete(
                    provider, props.getTimeoutSeconds(), props.getMaxTokens(), system, user);
            if (line == null || line.isBlank()) {
                return null;
            }
            // Models sometimes wrap the line in quotes despite the instruction; strip them.
            return stripQuotes(line.trim());
        } catch (RuntimeException ex) {
            log.warn("AI {} provider ({}) failed: {}", role, provider.getModel(), ex.getMessage());
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
