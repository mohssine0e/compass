package com.compass.app.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Who a {@link SystemEvent} is about: an AI provider, or the system itself. */
public enum EventSource {
    AI_PROVIDER("ai_provider"),
    SYSTEM("system");

    private final String value;

    EventSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EventSource fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        for (EventSource s : values()) {
            if (s.value.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown event source: " + raw);
    }
}
