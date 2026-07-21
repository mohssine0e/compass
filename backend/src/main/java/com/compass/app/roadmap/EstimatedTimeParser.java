package com.compass.app.roadmap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the free-text {@code estimatedTime} strings generation actually produces (e.g.
 * "~30 min", "~1h", "~2 hours") into minutes. Anything else (e.g. "a few hours") is
 * intentionally left unparsed rather than guessed at. Shared by {@code RoadmapResponse}'s
 * per-roadmap rollup and the Phase 20 behavioral pace calibration, which both need the same
 * "how many minutes did this resource say it'd take" read.
 */
public final class EstimatedTimeParser {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^~?\\s*(\\d+)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)\\.?$",
            Pattern.CASE_INSENSITIVE);

    private EstimatedTimeParser() {
    }

    /** Minutes parsed from {@code s}, or {@code null} if it doesn't match a strict pattern. */
    public static Integer parseMinutes(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = TIME_PATTERN.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        int value = Integer.parseInt(m.group(1));
        return m.group(2).toLowerCase().startsWith("h") ? value * 60 : value;
    }
}
