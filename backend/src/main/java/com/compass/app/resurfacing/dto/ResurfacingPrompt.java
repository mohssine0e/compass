package com.compass.app.resurfacing.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.dto.EntryResponse;

import java.util.List;

/**
 * A resurfacing prompt: the stalled entry, the honest question to ask (self-talk voice),
 * and the short default options the user can answer with (alongside free text / voice).
 */
public record ResurfacingPrompt(
        EntryResponse entry,
        String question,
        List<Option> options
) {
    /** value is what the API expects back; label is what the UI shows. */
    public record Option(String value, String label) {
    }

    /** The default answer options for the honest question. */
    public static final List<Option> DEFAULT_OPTIONS = List.of(
            new Option("still_relevant", "still relevant"),
            new Option("stuck", "stuck"),
            new Option("lost_interest", "lost interest"),
            new Option("something_else", "something else"));

    public static ResurfacingPrompt of(Entry entry, String question) {
        return new ResurfacingPrompt(EntryResponse.from(entry), question, DEFAULT_OPTIONS);
    }
}
