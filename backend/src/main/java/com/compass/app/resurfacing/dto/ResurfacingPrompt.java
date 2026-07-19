package com.compass.app.resurfacing.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryType;
import com.compass.app.entry.dto.EntryResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * A resurfacing prompt: the stalled entry, the honest question to ask (self-talk voice),
 * and the short options the user can answer with (alongside free text / voice).
 *
 * <p>For a stalled roadmap the options also include restructuring — not just "still relevant /
 * stuck / lost interest", but "change the plan" (Phase 4). Those are flagged {@code restructure}
 * so the UI opens an approve-before-apply flow instead of recording a plain answer.
 */
public record ResurfacingPrompt(
        String mode,
        EntryResponse entry,
        String question,
        String note,
        List<Option> options
) {
    /**
     * @param value  what the API expects back (an answer choice, or a restructuring kind)
     * @param label  what the UI shows
     * @param action {@code respond} → POST it as the answer; {@code restructure} → open the
     *               propose/approve/apply flow with this value as the kind
     */
    public record Option(String value, String label, String action) {
        static Option respond(String value, String label) {
            return new Option(value, label, "respond");
        }

        static Option restructure(String value, String label) {
            return new Option(value, label, "restructure");
        }
    }

    /** The default answer options for the honest question (every entry type gets these). */
    public static final List<Option> DEFAULT_OPTIONS = List.of(
            Option.respond("still_relevant", "still relevant"),
            Option.respond("stuck", "stuck"),
            Option.respond("lost_interest", "lost interest"),
            Option.respond("something_else", "something else"));

    /** Restructuring options offered only for a stalled roadmap (has a current step to change). */
    public static final List<Option> RESTRUCTURE_OPTIONS = List.of(
            Option.restructure("break_down", "break this step down"),
            Option.restructure("add_prerequisite", "something's missing first"));

    /** A normal resurface of a stalled idea/roadmap: an honest question + answer options. */
    public static ResurfacingPrompt of(Entry entry, String question, String note) {
        return new ResurfacingPrompt("resurface", EntryResponse.from(entry), question, note, optionsFor(entry));
    }

    /** A spaced recheck of a done step (Phase 8): answer the check, no answer-options list. */
    public static ResurfacingPrompt recheck(Entry step, String question) {
        return new ResurfacingPrompt("recheck", EntryResponse.from(step), question, null, List.of());
    }

    private static List<Option> optionsFor(Entry entry) {
        List<Option> options = new ArrayList<>(DEFAULT_OPTIONS);
        if (entry != null && entry.getType() == EntryType.ROADMAP) {
            options.addAll(RESTRUCTURE_OPTIONS);
        }
        return options;
    }
}
