package com.compass.app.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI configuration (prefix {@code compass.ai}). Three OpenAI-compatible providers with
 * failover, tried in order — primary, then backup, then tertiary — so one provider's quota
 * running dry (a real, recurring issue on free tiers under real testing/usage volume) cascades
 * to the next instead of failing the whole call. Only the API keys are secret; they come from
 * environment variables so they never live in the codebase.
 */
@Component
@ConfigurationProperties(prefix = "compass.ai")
public class AiProperties {

    private long timeoutSeconds = 6;
    private int maxTokens = 64;
    // Roadmap generation/restructuring returns a multi-step payload, so it needs a bigger
    // token budget and a longer timeout than a one-line acknowledgment. A genuinely large,
    // assessed-as-complex goal (Phase 18) can need close to the old fixed 8-module cap's worth of
    // title+scope text in one response; 1200 truncated that JSON mid-array under real testing.
    private long generationTimeoutSeconds = 30;
    private int generationMaxTokens = 2200;
    private Provider primary = new Provider();
    private Provider backup = new Provider();
    // A third, independent quota pool (e.g. NVIDIA's build.nvidia.com free NIM catalog) tried
    // only once both primary and backup fail — same model class, not a quality downgrade, just
    // more headroom before the caller has to degrade to a plain-text fallback.
    private Provider tertiary = new Provider();

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

    public Provider getPrimary() {
        return primary;
    }

    public void setPrimary(Provider primary) {
        this.primary = primary;
    }

    public Provider getBackup() {
        return backup;
    }

    public void setBackup(Provider backup) {
        this.backup = backup;
    }

    public Provider getTertiary() {
        return tertiary;
    }

    public void setTertiary(Provider tertiary) {
        this.tertiary = tertiary;
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
        // generation timeout gives it. Null (the default) means "use the shared timeout."
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
