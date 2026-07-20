package com.compass.app.entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle state of an entry. Captured things start at {@link #CAPTURED};
 * self-reported completion moves them to {@link #DONE} (see TASKS.md Phase 1).
 */
public enum EntryStatus {
    CAPTURED("captured"),
    DEVELOPING("developing"),
    IN_MOTION("in_motion"),
    DONE("done"),
    DROPPED("dropped"),
    ARCHIVED("archived");

    private final String value;

    EntryStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EntryStatus fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        for (EntryStatus s : values()) {
            if (s.value.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown entry status: " + raw);
    }
}
