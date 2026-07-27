package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing rules for model output, which is the part of {@link AiJsonGenerator} with real logic
 * in it. No provider is involved — {@code parse} only touches the mapper — so the collaborators
 * stay null rather than being mocked into existence.
 */
class AiJsonGeneratorTest {

    private final AiJsonGenerator generator = new AiJsonGenerator(null, null, null, null);

    @Test
    @DisplayName("plain JSON parses")
    void plainJson() {
        JsonNode json = generator.parse("{\"title\":\"Rust\"}");
        assertNotNull(json);
        assertEquals("Rust", json.get("title").asText());
    }

    @Test
    @DisplayName("a fenced reply parses")
    void fenced() {
        JsonNode json = generator.parse("```json\n{\"title\":\"Rust\"}\n```");
        assertNotNull(json);
        assertEquals("Rust", json.get("title").asText());
    }

    @Test
    @DisplayName("prose around the JSON is ignored")
    void surroundingProse() {
        JsonNode json = generator.parse("Sure, here you go:\n{\"title\":\"Rust\"}\nHope that helps.");
        assertNotNull(json);
        assertEquals("Rust", json.get("title").asText());
    }

    @Test
    @DisplayName("a reasoning trace before the answer is stripped, braces in it included")
    void reasoningTrace() {
        JsonNode json = generator.parse(
                "<think>I could return {a} or {b}</think>\n{\"title\":\"Rust\"}");
        assertNotNull(json);
        assertEquals("Rust", json.get("title").asText());
    }

    @Test
    @DisplayName("a reply truncated mid-element keeps every element that completed")
    void truncatedMidElement() {
        // What a resource call looks like when it runs into the token ceiling: two steps written
        // out in full, the third cut off mid-url.
        String raw = """
                {"steps":[
                  {"index":0,"resources":[{"title":"The Rust Book","url":"https://doc.rust-lang.org/book/"}]},
                  {"index":1,"resources":[{"title":"Rustlings","url":"https://github.com/rust-lang/rustlings"}]},
                  {"index":2,"resources":[{"title":"Rust by Exa""";

        JsonNode json = generator.parse(raw);

        assertNotNull(json, "a truncated reply should still yield its complete prefix");
        assertEquals(2, json.get("steps").size(), "only the two complete steps survive");
        assertEquals("The Rust Book", json.get("steps").get(0).get("resources").get(0).get("title").asText());
        assertEquals(1, json.get("steps").get(1).get("index").asInt());
    }

    @Test
    @DisplayName("truncation right after a comma doesn't leave a trailing comma behind")
    void truncatedAfterComma() {
        JsonNode json = generator.parse("{\"steps\":[{\"index\":0},{\"index\":1}, ");
        assertNotNull(json);
        assertEquals(2, json.get("steps").size());
    }

    @Test
    @DisplayName("truncation inside a string value drops that element rather than half-writing it")
    void truncatedInsideString() {
        JsonNode json = generator.parse("{\"steps\":[{\"text\":\"done\"},{\"text\":\"half wri");
        assertNotNull(json);
        assertEquals(1, json.get("steps").size());
        assertEquals("done", json.get("steps").get(0).get("text").asText());
    }

    @Test
    @DisplayName("braces inside string values don't confuse the repair")
    void bracesInsideStrings() {
        JsonNode json = generator.parse(
                "{\"steps\":[{\"text\":\"use Vec<T> { and } braces\"},{\"text\":\"cut");
        assertNotNull(json);
        assertEquals(1, json.get("steps").size());
        assertEquals("use Vec<T> { and } braces", json.get("steps").get(0).get("text").asText());
    }

    @Test
    @DisplayName("an escaped quote inside a string doesn't end it early")
    void escapedQuote() {
        JsonNode json = generator.parse("{\"steps\":[{\"text\":\"say \\\"hi\\\" now\"},{\"text\":\"cut");
        assertNotNull(json);
        assertEquals("say \"hi\" now", json.get("steps").get(0).get("text").asText());
    }

    @Test
    @DisplayName("a reply with nothing complete to salvage is still a failure")
    void nothingSalvageable() {
        assertNull(generator.parse("{\"steps\":[{\"title\":\"only this one, cut off"));
    }

    @Test
    @DisplayName("prose with no JSON at all is a failure, not an empty object")
    void noJsonAtAll() {
        assertNull(generator.parse("I can't help with that request."));
    }

    @Test
    @DisplayName("repair never invents content — it only closes what was already open")
    void repairAddsNothing() {
        JsonNode json = generator.parse("{\"steps\":[{\"index\":0,\"resources\":[]},{\"index\":1,\"reso");
        assertNotNull(json);
        assertEquals(1, json.get("steps").size());
        assertTrue(json.get("steps").get(0).get("resources").isEmpty());
    }
}
