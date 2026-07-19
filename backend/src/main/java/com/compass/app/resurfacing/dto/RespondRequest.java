package com.compass.app.resurfacing.dto;

/**
 * A response to a resurfacing prompt. {@code option} is one of the defaults
 * (still_relevant / stuck / lost_interest / something_else) or {@code skip}. Free text and
 * voice both arrive as {@code something_else} with {@code text}.
 */
public record RespondRequest(String option, String text) {
}
