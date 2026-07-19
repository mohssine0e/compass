package com.compass.app.ai;

import com.compass.app.events.EventService;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the AI calls that expect a JSON reply (roadmap drafting, resume
 * extraction, self-description interpretation). One place for failover (primary → backup with
 * the larger generation budget), lenient parsing, and brief event logging on failure — so each
 * feature service only writes its prompt and reads the fields it wants.
 *
 * <p>Like the rest of the AI layer these calls are best-effort: {@code generate} returns
 * {@code null} when no provider is configured or the reply can't be used, and the caller
 * surfaces that rather than inventing content.
 */
@Component
public class AiJsonGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiJsonGenerator.class);

    private final AiProperties props;
    private final OpenAiCompatibleChatClient chat;
    private final EventService events;

    // Tolerant parser just for model output: models routinely emit literal newlines inside JSON
    // string values, which strict Jackson rejects. Scoped here so request parsing stays strict.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    public AiJsonGenerator(AiProperties props, OpenAiCompatibleChatClient chat, EventService events) {
        this.props = props;
        this.chat = chat;
        this.events = events;
    }

    /** True when at least one provider could serve a generation request. */
    public boolean isAvailable() {
        return props.getPrimary().isConfigured() || props.getBackup().isConfigured();
    }

    /**
     * Try primary then backup with the generation budget; parse the reply as JSON. Returns
     * {@code null} on any failure, logging a brief event ({@code feature} names which call
     * degraded) when both providers fail or the reply won't parse.
     */
    public JsonNode generate(String feature, String system, String user) {
        String raw = complete(props.getPrimary(), system, user);
        if (raw == null) {
            raw = complete(props.getBackup(), system, user);
        }
        if (raw == null) {
            if (isAvailable()) {
                events.aiWarning("provider_error", "All AI providers failed for " + feature + ".", null);
            }
            return null;
        }
        JsonNode json = parse(raw);
        if (json == null) {
            events.aiWarning("parse_failure", "AI returned unparseable JSON for " + feature + ".", null);
        }
        return json;
    }

    private String complete(AiProperties.Provider provider, String system, String user) {
        if (!provider.isConfigured()) {
            return null;
        }
        try {
            String out = chat.complete(provider, props.getGenerationTimeoutSeconds(),
                    props.getGenerationMaxTokens(), system, user);
            return out == null || out.isBlank() ? null : out;
        } catch (RuntimeException ex) {
            log.warn("AI JSON provider ({}) failed: {}", provider.getModel(), ex.getMessage());
            events.aiWarning(AiFailures.category(ex),
                    provider.getModel() + " failed: " + AiFailures.reason(ex), null);
            return null;
        }
    }

    /** Parse the model's reply into JSON, tolerating ```json fences and surrounding prose. */
    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(extractJson(raw));
        } catch (Exception ex) {
            log.warn("AI returned unparseable JSON: {}", ex.getMessage());
            return null;
        }
    }

    /** Pull the first {...} block out of a reply, stripping any code fences around it. */
    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int open = s.indexOf('{');
        int close = s.lastIndexOf('}');
        return open >= 0 && close > open ? s.substring(open, close + 1) : s;
    }

    /** The non-blank strings of a JSON array node, trimmed; empty list if not an array. */
    public static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String value = text(node);
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    /** A JSON node's text, or {@code null} for missing/null nodes. */
    public static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }
}
