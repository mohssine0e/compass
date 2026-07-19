package com.compass.app.events;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converters so the event enums persist as their lowercase DB strings ('ai_provider',
 * 'error') to match the CHECK constraints in V4__create_system_events.sql — same pattern as
 * the entry enums.
 */
final class SystemEventConverters {

    private SystemEventConverters() {
    }

    @Converter(autoApply = true)
    public static class EventSourceConverter implements AttributeConverter<EventSource, String> {
        @Override
        public String convertToDatabaseColumn(EventSource attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public EventSource convertToEntityAttribute(String dbData) {
            return EventSource.fromValue(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class EventSeverityConverter implements AttributeConverter<EventSeverity, String> {
        @Override
        public String convertToDatabaseColumn(EventSeverity attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public EventSeverity convertToEntityAttribute(String dbData) {
            return EventSeverity.fromValue(dbData);
        }
    }
}
