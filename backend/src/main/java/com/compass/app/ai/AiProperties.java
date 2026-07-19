package com.compass.app.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI configuration (prefix {@code compass.ai}). Two OpenAI-compatible providers with
 * failover — primary first, backup on any failure. Only the API keys are secret; they
 * come from environment variables so they never live in the codebase.
 */
@Component
@ConfigurationProperties(prefix = "compass.ai")
public class AiProperties {

    private long timeoutSeconds = 6;
    private int maxTokens = 64;
    // Roadmap generation/restructuring returns a multi-step payload, so it needs a bigger
    // token budget and a longer timeout than a one-line acknowledgment.
    private long generationTimeoutSeconds = 30;
    private int generationMaxTokens = 1200;
    private Provider primary = new Provider();
    private Provider backup = new Provider();

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

    /** One OpenAI-compatible provider (base URL + key + model). */
    public static class Provider {
        private String name = "provider";
        private String baseUrl;
        private String apiKey;
        private String model;

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
    }
}
