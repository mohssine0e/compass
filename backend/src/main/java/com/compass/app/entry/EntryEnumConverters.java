package com.compass.app.entry;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converters so the enums persist as their lowercase DB strings ('idea', 'captured',
 * 'big') to match the CHECK constraints in V1__create_entries.sql, rather than the Java
 * constant names.
 */
final class EntryEnumConverters {

    private EntryEnumConverters() {
    }

    @Converter(autoApply = true)
    public static class EntryTypeConverter implements AttributeConverter<EntryType, String> {
        @Override
        public String convertToDatabaseColumn(EntryType attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public EntryType convertToEntityAttribute(String dbData) {
            return EntryType.fromValue(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class EntryStatusConverter implements AttributeConverter<EntryStatus, String> {
        @Override
        public String convertToDatabaseColumn(EntryStatus attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public EntryStatus convertToEntityAttribute(String dbData) {
            return EntryStatus.fromValue(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class SignificanceConverter implements AttributeConverter<Significance, String> {
        @Override
        public String convertToDatabaseColumn(Significance attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Significance convertToEntityAttribute(String dbData) {
            return Significance.fromValue(dbData);
        }
    }
}
