package com.compass.app.entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How much an idea matters. Only meaningful for {@link EntryType#IDEA}; null otherwise.
 */
public enum Significance {
    BIG("big"),
    SMALL("small");

    private final String value;

    Significance(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Significance fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        for (Significance s : values()) {
            if (s.value.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown significance: " + raw);
    }
}
