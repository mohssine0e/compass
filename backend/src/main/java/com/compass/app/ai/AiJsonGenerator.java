package com.compass.app.ai;

import com.compass.app.events.EventService;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Shared plumbing for the AI calls that expect a JSON reply (roadmap drafting, resume
 * extraction, self-description interpretation). One place for tiered failover (Phase 19: each
 * {@link AiTier} fails over across its own two providers, fast tier with a smaller budget than
 * heavy), lenient parsing, and brief event logging on failure — so each feature service only
 * writes its prompt, names its tier, and reads the fields it wants.
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
    private final ProviderHealth health;

    // Tolerant parser just for model output, which is JSON-ish rather than JSON: literal
    // newlines inside string values, `//` comments annotating a field (Nemotron does this
    // routinely — it was throwing away entire resource batches), and trailing commas before a
    // closing brace. All three are things a human would read straight past, and none of them
    // change what the model meant. Scoped here so request parsing stays strict.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    public AiJsonGenerator(AiProperties props, OpenAiCompatibleChatClient chat, EventService events,
                           ProviderHealth health) {
        this.props = props;
        this.chat = chat;
        this.events = events;
        this.health = health;
    }

    /** True when at least one provider in either tier could serve a generation request. */
    public boolean isAvailable() {
        return props.anyConfigured();
    }

    /**
     * Try every configured provider in {@code tier}'s failover chain in order, with that tier's
     * budget, until one returns a reply that actually parses as JSON. Returns {@code null} on
     * any failure, logging a brief event ({@code feature} names which call degraded) when every
     * configured provider in the tier fails or no reply could be used.
     *
     * <p>Parsing happens <em>inside</em> the failover loop on purpose. It used to sit after it:
     * the first provider to answer at all won, and if that answer wasn't valid JSON the whole
     * call returned nothing while the remaining providers — which might well have answered
     * cleanly — were never asked. That's how resource discovery ended up empty on almost every
     * step: one weak provider emitting truncated JSON silently cost the feature its entire
     * result. An unusable reply is a failure of that provider, so it fails over like any other.
     */
    public JsonNode generate(AiTier tier, String feature, String system, String user) {
        return generate(tier, feature, system, user, null);
    }

    /**
     * As above, with a token ceiling for this call only. Worth having per-call rather than just
     * raising the tier's budget: Groq's free tier limits *tokens per minute*, and the requested
     * ceiling counts against it whether or not the reply uses it. So a global increase to suit
     * the one big call (resource suggestions) would spend the shared minute-budget on every
     * small one too, and cause rate-limiting elsewhere.
     */
    public JsonNode generate(AiTier tier, String feature, String system, String user,
                             Integer maxTokensOverride) {
        boolean anyReplied = false;
        // Providers currently benched by the circuit breaker are skipped, so a standing 429 or a
        // bad key stops costing a full timeout on every call (see ProviderHealth).
        for (AiProperties.Provider provider : health.callOrder(props.providersFor(tier))) {
            String raw = complete(tier, provider, system, user, maxTokensOverride);
            if (raw == null) {
                continue;
            }
            anyReplied = true;
            JsonNode json = parse(raw);
            if (json != null) {
                return json;
            }
            // Answered, but with something unusable — worth a brief note naming the provider,
            // since "this model can't hold a JSON contract" is exactly the pattern the events
            // log exists to make visible. Not benched: BAD_RESPONSE is a content problem, not
            // an availability one (see ProviderHealth).
            health.recordFailure(provider, new AiCallException(AiCallException.Kind.BAD_RESPONSE,
                    null, provider.getModel() + " returned unparseable JSON", null));
            events.aiWarning("parse_failure",
                    provider.getModel() + " returned unparseable JSON for " + feature + ".", null);
        }
        if (isAvailable()) {
            events.aiWarning("provider_error",
                    (anyReplied ? "No AI provider returned usable JSON for "
                            : "All AI providers failed for ") + feature + ".", null);
        }
        return null;
    }

    /**
     * The emergency skeleton path (Phase 19): a much smaller ask (titles only) tried against the
     * FAST tier when the HEAVY tier's whole chain has already failed for a generation call — a
     * cheap, quick provider may still have quota even when the strong ones don't. {@code null}
     * on failure; callers use this to produce a bare-bones result rather than nothing at all.
     */
    public JsonNode generateSkeleton(String feature, String system, String user) {
        String raw = null;
        for (AiProperties.Provider provider : health.callOrder(props.providersFor(AiTier.FAST))) {
            try {
                long timeout = provider.getTimeoutSecondsOverride() != null
                        ? provider.getTimeoutSecondsOverride() : props.getSkeletonTimeoutSeconds();
                long startedAt = System.currentTimeMillis();
                raw = chat.complete(provider, timeout, props.getSkeletonMaxTokens(), system, user);
                if (raw != null && !raw.isBlank()) {
                    health.recordSuccess(provider, System.currentTimeMillis() - startedAt);
                    break;
                }
                raw = null;
            } catch (RuntimeException ex) {
                health.recordFailure(provider, ex);
                log.warn("AI skeleton provider ({}) failed: {}", provider.getModel(), ex.getMessage());
                events.aiWarning(AiFailures.category(ex),
                        provider.getModel() + " failed: " + AiFailures.reason(ex), null);
            }
        }
        if (raw == null) {
            events.aiWarning("provider_error", "Skeleton fallback also failed for " + feature + ".", null);
            return null;
        }
        return parse(raw);
    }

    private String complete(AiTier tier, AiProperties.Provider provider, String system, String user,
                            Integer maxTokensOverride) {
        if (!provider.isConfigured()) {
            return null;
        }
        try {
            long defaultTimeout = tier == AiTier.FAST
                    ? props.getFastJsonTimeoutSeconds() : props.getGenerationTimeoutSeconds();
            int maxTokens = maxTokensOverride != null ? maxTokensOverride
                    : tier == AiTier.FAST ? props.getFastJsonMaxTokens() : props.getGenerationMaxTokens();
            long timeout = provider.getTimeoutSecondsOverride() != null
                    ? provider.getTimeoutSecondsOverride() : defaultTimeout;
            long startedAt = System.currentTimeMillis();
            String out = chat.complete(provider, timeout, maxTokens, system, user);
            if (out != null && !out.isBlank()) {
                health.recordSuccess(provider, System.currentTimeMillis() - startedAt);
                return out;
            }
            return null;
        } catch (RuntimeException ex) {
            health.recordFailure(provider, ex);
            log.warn("AI JSON provider ({}) failed: {}", provider.getModel(), ex.getMessage());
            events.aiWarning(AiFailures.category(ex),
                    provider.getModel() + " failed: " + AiFailures.reason(ex), null);
            return null;
        }
    }

    /**
     * Parse the model's reply into JSON, tolerating ```json fences and surrounding prose.
     * Package-private rather than private so the parsing/repair rules can be tested directly —
     * they're the part of this class with real logic in them, and they don't need a provider.
     */
    JsonNode parse(String raw) {
        String extracted = extractJson(raw);
        try {
            return mapper.readTree(extracted);
        } catch (Exception ex) {
            JsonNode repaired = parseTruncated(extracted);
            if (repaired != null) {
                log.warn("AI reply was truncated; recovered the complete prefix.");
                return repaired;
            }
            log.warn("AI returned unparseable JSON: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Last-ditch recovery for a reply that ran into the token ceiling mid-JSON. The big
     * generation calls (a module's worth of steps, three resources for each of ten steps) are
     * exactly the ones that get cut off, and losing ten steps' resources because the tenth
     * entry was half-written is a bad trade — nine complete entries are worth keeping.
     *
     * <p>Strictly a salvage of what was already complete: it rewinds to the last element that
     * closed cleanly and shuts the still-open brackets. Nothing is invented, and a reply that
     * wasn't merely truncated (genuine prose, malformed structure) still fails to parse and
     * returns {@code null}.
     */
    private JsonNode parseTruncated(String s) {
        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        int lastComplete = -1; // index just past the last element that closed at depth >= 1

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> open.push(c);
                case '}', ']' -> {
                    if (open.isEmpty()) {
                        return null; // unbalanced the other way — not a truncation
                    }
                    open.pop();
                    if (!open.isEmpty()) {
                        lastComplete = i + 1;
                    }
                }
                default -> { /* ordinary content */ }
            }
        }
        if (open.isEmpty() || lastComplete < 0) {
            return null; // nothing was left open, or nothing complete to salvage
        }

        // Rewind to that boundary, then re-derive which brackets are still open there.
        String prefix = s.substring(0, lastComplete);
        StringBuilder out = new StringBuilder(prefix);
        Deque<Character> stillOpen = new ArrayDeque<>();
        inString = false;
        escaped = false;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stillOpen.push(c);
            } else if (c == '}' || c == ']') {
                stillOpen.pop();
            }
        }
        while (!stillOpen.isEmpty()) {
            out.append(stillOpen.pop() == '{' ? '}' : ']');
        }
        try {
            return mapper.readTree(out.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Reasoning models (e.g. NVIDIA's Nemotron Super) may prefix the reply with a visible
     * "thinking" trace before the actual answer — often in a {@code <think>...</think>} block,
     * sometimes containing stray {@code {}/{}} of its own (code snippets, set notation) that
     * would otherwise confuse the brace-matching below. Strip it first, whether or not the tags
     * are closed, so extraction always works from the real answer onward.
     */
    private static String stripReasoningTrace(String s) {
        int openTag = s.indexOf("<think>");
        if (openTag < 0) {
            return s;
        }
        int closeTag = s.indexOf("</think>", openTag);
        return closeTag >= 0 ? s.substring(closeTag + "</think>".length()).trim()
                : s.substring(0, openTag).trim();
    }

    /** Pull the first {...} block out of a reply, stripping any code fences around it. */
    private static String extractJson(String raw) {
        String s = stripReasoningTrace(raw.trim());
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
