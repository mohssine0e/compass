package com.compass.app.topic.dto;

/** The founder's confirmed (possibly edited) addition (RB-3.10) — field is subtopics|prerequisites|aliases. */
public record ApplyAdditionRequest(String field, String value) {
}
