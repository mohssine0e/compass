package com.compass.app.entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The flavor of an entry. New types are added here + as a payload shape in
 * {@code content}, never as a new table (see CLAUDE.md Section 4).
 */
public enum EntryType {
    IDEA("idea"),
    ROADMAP("roadmap"),
    ROADMAP_STEP("roadmap_step");

    private final String value;

    EntryType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EntryType fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        for (EntryType t : values()) {
            if (t.value.equalsIgnoreCase(raw) || t.name().equalsIgnoreCase(raw)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown entry type: " + raw);
    }
}
