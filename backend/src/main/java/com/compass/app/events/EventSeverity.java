package com.compass.app.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How much a {@link SystemEvent} matters: routine info, a warning, or a real error. */
public enum EventSeverity {
    INFO("info"),
    WARNING("warning"),
    ERROR("error");

    private final String value;

    EventSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EventSeverity fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        for (EventSeverity s : values()) {
            if (s.value.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown event severity: " + raw);
    }
}
