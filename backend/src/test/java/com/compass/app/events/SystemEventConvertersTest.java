package com.compass.app.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips every event enum value through its JPA converter, and confirms an unknown DB
 * string fails loudly rather than quietly becoming null (V3-3.4).
 */
class SystemEventConvertersTest {

    private final SystemEventConverters.EventSourceConverter sourceConverter = new SystemEventConverters.EventSourceConverter();
    private final SystemEventConverters.EventSeverityConverter severityConverter = new SystemEventConverters.EventSeverityConverter();

    @ParameterizedTest
    @EnumSource(EventSource.class)
    @DisplayName("every EventSource round-trips through the converter")
    void eventSourceRoundTrips(EventSource source) {
        String db = sourceConverter.convertToDatabaseColumn(source);
        assertThat(sourceConverter.convertToEntityAttribute(db)).isEqualTo(source);
    }

    @ParameterizedTest
    @EnumSource(EventSeverity.class)
    @DisplayName("every EventSeverity round-trips through the converter")
    void eventSeverityRoundTrips(EventSeverity severity) {
        String db = severityConverter.convertToDatabaseColumn(severity);
        assertThat(severityConverter.convertToEntityAttribute(db)).isEqualTo(severity);
    }

    @Test
    @DisplayName("null attribute/column convert to null on both converters")
    void nullsRoundTripToNull() {
        assertThat(sourceConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(sourceConverter.convertToEntityAttribute(null)).isNull();
        assertThat(severityConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(severityConverter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("an unknown DB string throws rather than silently becoming null")
    void unknownDbStringThrows() {
        assertThatThrownBy(() -> sourceConverter.convertToEntityAttribute("not_a_real_source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> severityConverter.convertToEntityAttribute("not_a_real_severity"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
