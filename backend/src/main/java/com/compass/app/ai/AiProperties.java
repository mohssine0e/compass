package com.compass.app.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI configuration (prefix {@code compass.ai}). Two tiers, two OpenAI-compatible providers
 * each, tried in order within a tier on any error/timeout/quota exhaustion (Phase 19):
 * <ul>
 *   <li>{@code fast} — Groq, then Gemini Flash. For tone acknowledgments, clarifying
 *   questions, verification checks: short, frequent, cheap calls where a snappy provider
 *   matters more than depth.</li>
 *   <li>{@code heavy} — Gemini Pro, then NVIDIA NIM. For generation-weight calls (outline
 *   drafting, module expansion, self-critique) that need a stronger model and can tolerate a
 *   longer wait.</li>
 * </ul>
 * Four providers, two tiers is the founder's confirmed ceiling — not six discrete providers
 * across three tiers (see TASKS.md Phase 19). Gemini Flash and Gemini Pro share one API key
 * ({@code GEMINI_API_KEY}) but are genuinely separate quota pools, addressed by model name
 * per provider entry. Only API keys are secret; they come from environment variables so they
 * never live in the codebase.
 */
@Component
@ConfigurationProperties(prefix = "compass.ai")
public class AiProperties {

    private long timeoutSeconds = 6;
    private int maxTokens = 64;
    // Fast-tier JSON calls (clarifying questions, goal assessment, verification checks, ...)
    // need more room than a one-line tone acknowledgment but far less than a full outline —
    // a smaller, quicker budget than the heavy tier's, so the fast chain stays fast in practice
    // and not just in name.
    private long fastJsonTimeoutSeconds = 12;
    private int fastJsonMaxTokens = 500;
    // Roadmap generation/restructuring returns a multi-step payload, so it needs a bigger
    // token budget and a longer timeout than a one-line acknowledgment. A genuinely large,
    // assessed-as-complex goal (Phase 18) can need close to the old fixed 8-module cap's worth of
    // title+scope text in one response; 1200 truncated that JSON mid-array under real testing.
    private long generationTimeoutSeconds = 30;
    private int generationMaxTokens = 2200;
    // A much smaller ask than a full expansion — titles only, no descriptions/resources — used
    // as the emergency skeleton path when the whole heavy chain fails (Phase 19).
    private long skeletonTimeoutSeconds = 10;
    private int skeletonMaxTokens = 300;

    private List<Provider> fast = new ArrayList<>();
    private List<Provider> heavy = new ArrayList<>();

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getFastJsonTimeoutSeconds() {
        return fastJsonTimeoutSeconds;
    }

    public void setFastJsonTimeoutSeconds(long fastJsonTimeoutSeconds) {
        this.fastJsonTimeoutSeconds = fastJsonTimeoutSeconds;
    }

    public int getFastJsonMaxTokens() {
        return fastJsonMaxTokens;
    }

    public void setFastJsonMaxTokens(int fastJsonMaxTokens) {
        this.fastJsonMaxTokens = fastJsonMaxTokens;
    }

    public long getGenerationTimeoutSeconds() {
        return generationTimeoutSeconds;
    }

    public void setGenerationTimeoutSeconds(long generationTimeoutSeconds) {
        this.generationTimeoutSeconds = generationTimeoutSeconds;
    }

    public int getGenerationMaxTokens() {
        return generationMaxTokens;
    }

    public void setGenerationMaxTokens(int generationMaxTokens) {
        this.generationMaxTokens = generationMaxTokens;
    }

    public long getSkeletonTimeoutSeconds() {
        return skeletonTimeoutSeconds;
    }

    public void setSkeletonTimeoutSeconds(long skeletonTimeoutSeconds) {
        this.skeletonTimeoutSeconds = skeletonTimeoutSeconds;
    }

    public int getSkeletonMaxTokens() {
        return skeletonMaxTokens;
    }

    public void setSkeletonMaxTokens(int skeletonMaxTokens) {
        this.skeletonMaxTokens = skeletonMaxTokens;
    }

    public List<Provider> getFast() {
        return fast;
    }

    public void setFast(List<Provider> fast) {
        this.fast = fast;
    }

    public List<Provider> getHeavy() {
        return heavy;
    }

    public void setHeavy(List<Provider> heavy) {
        this.heavy = heavy;
    }

    /** The configured providers for a tier, in failover order — unconfigured ones dropped. */
    public List<Provider> providersFor(AiTier tier) {
        List<Provider> list = tier == AiTier.FAST ? fast : heavy;
        return list.stream().filter(Provider::isConfigured).toList();
    }

    /** True when at least one provider in either tier could serve a call. */
    public boolean anyConfigured() {
        return !providersFor(AiTier.FAST).isEmpty() || !providersFor(AiTier.HEAVY).isEmpty();
    }

    /** One OpenAI-compatible provider (base URL + key + model). */
    public static class Provider {
        private String name = "provider";
        private String baseUrl;
        private String apiKey;
        private String model;
        // Some models (e.g. NVIDIA's Nemotron Super, a reasoning model) emit a lengthy internal
        // "thinking" trace that counts against max_tokens even though only the final JSON
        // matters here — this asks the model to skip it via the chat_template_kwargs the NIM
        // API supports. Left off (false) for providers that don't have/need this.
        private boolean disableThinking = false;
        // A slower free-tier provider (observed: NVIDIA's NIM catalog taking ~40s for a real
        // outline prompt vs. Groq's sub-second responses) needs more patience than the shared
        // generation timeout gives it. Null (the default) means "use the tier's shared timeout."
        private Long timeoutSecondsOverride;

        /** Configured only when both a base URL and an API key are present. */
        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isDisableThinking() {
            return disableThinking;
        }

        public void setDisableThinking(boolean disableThinking) {
            this.disableThinking = disableThinking;
        }

        public Long getTimeoutSecondsOverride() {
            return timeoutSecondsOverride;
        }

        public void setTimeoutSecondsOverride(Long timeoutSecondsOverride) {
            this.timeoutSecondsOverride = timeoutSecondsOverride;
        }
    }
}
