package com.compass.app.roadmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formats generation actually produces, plus the junk it must not guess at.
 *
 * <p>Pure regex logic, no Spring context.
 */
class EstimatedTimeParserTest {

    @ParameterizedTest
    @CsvSource({
            "~30 min, 30",
            "~1h, 60",
            "~2 hours, 120",
            "30 min, 30",
            "45 minutes, 45",
            "1 hour, 60",
            "2h, 120",
            "3hr, 180",
            "3hrs, 180",
            "5m, 5",
            "5mins, 5",
            "5minute, 5",
            "5minutes, 5",
            "~90 min., 90",
    })
    @DisplayName("known formats parse to minutes")
    void parsesKnownFormats(String input, int expectedMinutes) {
        assertThat(EstimatedTimeParser.parseMinutes(input)).isEqualTo(expectedMinutes);
    }

    @ParameterizedTest
    @CsvSource({
            "'  ~30 min  '",
            "'~30 MIN'",
            "'30 Min'",
    })
    @DisplayName("surrounding whitespace and case are tolerated")
    void tolerantOfWhitespaceAndCase(String input) {
        assertThat(EstimatedTimeParser.parseMinutes(input)).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a few hours",
            "quick",
            "1-2 hours",
            "about 30 minutes",
            "half a day",
            "",
            "   ",
            "min",
            "30",
            "30 seconds",
            "30s",
    })
    @DisplayName("unrecognised formats are left unparsed rather than guessed at")
    void leavesJunkUnparsed(String input) {
        assertThat(EstimatedTimeParser.parseMinutes(input)).isNull();
    }

    @Test
    @DisplayName("null input returns null rather than throwing")
    void nullIsNull() {
        assertThat(EstimatedTimeParser.parseMinutes(null)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null and empty are both handled without throwing")
    void nullAndEmptyDoNotThrow(String input) {
        assertThat(EstimatedTimeParser.parseMinutes(input)).isNull();
    }
}
