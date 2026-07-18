package com.compass.app.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * The only place Claude is called (CLAUDE.md Section 5). Turns an entry into a short
 * acknowledgment in the user's self-talk voice.
 *
 * <p>Best-effort by design: capture and completion must never fail or stall because the
 * AI is slow or unconfigured. When no API key is set, or the call errors or times out,
 * this returns {@code null} and the caller falls back to a plain confirmation.
 */
@Service
public class AiVoiceService {

    private static final Logger log = LoggerFactory.getLogger(AiVoiceService.class);

    private final String model;
    private final AnthropicClient client; // null when no API key is configured

    public AiVoiceService(
            @Value("${compass.ai.model:claude-opus-4-8}") String model,
            @Value("${ANTHROPIC_API_KEY:}") String apiKey,
            @Value("${compass.ai.timeout-seconds:6}") long timeoutSeconds) {
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            log.info("ANTHROPIC_API_KEY not set — AI acknowledgments disabled (capture still works).");
        } else {
            this.client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
        }
    }

    public boolean isEnabled() {
        return client != null;
    }

    /**
     * A short self-talk-voice line acknowledging this entry. The moment (just captured vs.
     * marked done) is inferred from the entry's state. Returns {@code null} on any failure
     * so the caller can fall back to a plain confirmation.
     */
    public String acknowledge(Entry entry) {
        if (client == null || entry == null) {
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

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(64L)
                    .system(PromptTemplates.ACK_SYSTEM)
                    .addUserMessage(PromptTemplates.ackUser(moment, type, significance, text))
                    .build();

            Message response = client.messages().create(params);
            String line = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat)
                    .trim();
            return line.isEmpty() ? null : line;
        } catch (RuntimeException ex) {
            // Never let an AI hiccup break the capture/complete flow.
            log.warn("AI acknowledgment failed ({}); continuing without it.", ex.getMessage());
            return null;
        }
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
