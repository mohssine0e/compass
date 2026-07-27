package com.compass.app.entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips every enum value through its JPA converter, and pins down what happens on an
 * unknown DB string — exactly what breaks silently the next time a CHECK constraint and the
 * Java enum drift apart (V3-3.4).
 */
class EntryEnumConvertersTest {

    private final EntryEnumConverters.EntryTypeConverter typeConverter = new EntryEnumConverters.EntryTypeConverter();
    private final EntryEnumConverters.EntryStatusConverter statusConverter = new EntryEnumConverters.EntryStatusConverter();
    private final EntryEnumConverters.SignificanceConverter significanceConverter = new EntryEnumConverters.SignificanceConverter();

    @ParameterizedTest
    @EnumSource(EntryType.class)
    @DisplayName("every EntryType round-trips through the converter")
    void entryTypeRoundTrips(EntryType type) {
        String db = typeConverter.convertToDatabaseColumn(type);
        assertThat(typeConverter.convertToEntityAttribute(db)).isEqualTo(type);
    }

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    @DisplayName("every EntryStatus round-trips through the converter")
    void entryStatusRoundTrips(EntryStatus status) {
        String db = statusConverter.convertToDatabaseColumn(status);
        assertThat(statusConverter.convertToEntityAttribute(db)).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(Significance.class)
    @DisplayName("every Significance round-trips through the converter")
    void significanceRoundTrips(Significance significance) {
        String db = significanceConverter.convertToDatabaseColumn(significance);
        assertThat(significanceConverter.convertToEntityAttribute(db)).isEqualTo(significance);
    }

    @Test
    @DisplayName("null attribute converts to null DB column, for every converter")
    void nullAttributeConvertsToNullColumn() {
        assertThat(typeConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(statusConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(significanceConverter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("null DB data converts to null attribute, for every converter")
    void nullColumnConvertsToNullAttribute() {
        assertThat(typeConverter.convertToEntityAttribute(null)).isNull();
        assertThat(statusConverter.convertToEntityAttribute(null)).isNull();
        assertThat(significanceConverter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("an unknown DB string throws rather than silently becoming null — a next-migration trap otherwise")
    void unknownDbStringThrows() {
        assertThatThrownBy(() -> typeConverter.convertToEntityAttribute("not_a_real_type"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> statusConverter.convertToEntityAttribute("not_a_real_status"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> significanceConverter.convertToEntityAttribute("not_a_real_significance"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the converter is case-insensitive on both the enum name and its DB value")
    void converterIsCaseInsensitive() {
        assertThat(typeConverter.convertToEntityAttribute("ROADMAP_STEP")).isEqualTo(EntryType.ROADMAP_STEP);
        assertThat(typeConverter.convertToEntityAttribute("Roadmap_Step")).isEqualTo(EntryType.ROADMAP_STEP);
    }
}
