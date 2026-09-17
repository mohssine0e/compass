package com.compass.app.config;

import com.compass.app.ai.AiProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class ReadinessController {
    private final JdbcTemplate jdbc;
    private final AiProperties ai;

    public ReadinessController(JdbcTemplate jdbc, AiProperties ai) {
        this.jdbc = jdbc;
        this.ai = ai;
    }

    @GetMapping
    public Map<String, Object> health() {
        boolean database;
        try {
            database = Boolean.TRUE.equals(jdbc.queryForObject("SELECT true", Boolean.class));
        } catch (RuntimeException ex) {
            database = false;
        }
        boolean aiConfigured = java.util.stream.Stream.concat(ai.getFast().stream(), ai.getHeavy().stream())
                .anyMatch(provider -> provider.getApiKey() != null && !provider.getApiKey().isBlank());
        return Map.of(
                "ready", database,
                "database", database ? "available" : "unavailable",
                "ai", aiConfigured ? "configured" : "unavailable");
    }
}
