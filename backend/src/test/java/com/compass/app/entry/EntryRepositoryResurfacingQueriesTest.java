package com.compass.app.entry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code findNextResurfaceCandidate} and {@code findNextRecheckCandidate} encode the whole
 * resurfacing engine's selection rules in raw native SQL (V3-1.2) — untestable by inspection,
 * since a broken predicate or a wrong ORDER BY silently picks the wrong entry rather than
 * failing loudly. Runs the real migrations against a dedicated Postgres schema, same approach
 * and same reasoning as {@link com.compass.app.db.FlywayMigrationTest}: {@code mohssine} has no
 * {@code CREATEDB}, so a throwaway schema in the existing {@code compass} database stands in for
 * Testcontainers, and it never touches {@code public} where the founder's real data lives.
 *
 * <p>Skips itself when no local Postgres is reachable, same as {@code FlywayMigrationTest}. The
 * schema is dropped and recreated at the start of every run (not torn down after), unlike {@code
 * FlywayMigrationTest}'s plain-Flyway approach — a {@code @DataJpaTest} context (and its
 * connection pool) is cached and reused across the surefire run, so there's no single safe point
 * to drop it from afterward without risking "in use" errors against a still-open pool.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntryRepositoryResurfacingQueriesTest {

    private static final String SCHEMA = "entry_repository_test";
    private static final String BASE_URL = System.getenv().getOrDefault(
            "COMPASS_TEST_DB_URL", "jdbc:postgresql://localhost:5433/compass");
    private static final String USER = System.getenv().getOrDefault("COMPASS_DB_USER", "mohssine");
    private static final String PASSWORD = System.getenv().getOrDefault("COMPASS_DB_PASSWORD", "");

    private static boolean dbAvailable;

    @BeforeAll
    static void resetSchema() {
        try (Connection conn = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.executeUpdate("CREATE SCHEMA " + SCHEMA);
            dbAvailable = true;
        } catch (SQLException ex) {
            dbAvailable = false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> BASE_URL + "?currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired
    private EntryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("a stale, never-resurfaced big idea is the resurface candidate; done/dropped/small are excluded")
    void resurfaceCandidateHonorsEligibilityAndOrdering() {
        assumeTrue(dbAvailable, "No local Postgres reachable — skipping.");

        Instant now = Instant.now();
        Instant longStale = now.minus(30, ChronoUnit.DAYS);

        save(EntryType.IDEA, EntryStatus.CAPTURED, Significance.BIG, longStale, null); // eligible
        save(EntryType.IDEA, EntryStatus.CAPTURED, Significance.SMALL, longStale, null); // wrong significance
        save(EntryType.IDEA, EntryStatus.DONE, Significance.BIG, longStale, null); // done, excluded
        save(EntryType.IDEA, EntryStatus.DROPPED, Significance.BIG, longStale, null); // dropped, excluded
        Entry fresh = save(EntryType.IDEA, EntryStatus.CAPTURED, Significance.BIG, now, null); // too fresh
        assertThat(fresh).isNotNull();

        Optional<Entry> candidate = repository.findNextResurfaceCandidate(
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

        assertThat(candidate).isPresent();
        assertThat(candidate.get().getSignificance()).isEqualTo(Significance.BIG);
        assertThat(candidate.get().getStatus()).isEqualTo(EntryStatus.CAPTURED);
    }

    @Test
    @DisplayName("a never-resurfaced entry is preferred over a more-stale one that was resurfaced recently")
    void neverResurfacedBeatsStaleness() {
        assumeTrue(dbAvailable, "No local Postgres reachable — skipping.");

        Instant now = Instant.now();
        Instant veryStale = now.minus(60, ChronoUnit.DAYS);
        Instant lessStale = now.minus(10, ChronoUnit.DAYS);

        Entry neverResurfaced = save(EntryType.IDEA, EntryStatus.CAPTURED, Significance.BIG, lessStale, null);
        save(EntryType.IDEA, EntryStatus.CAPTURED, Significance.BIG, veryStale, now.minus(1, ChronoUnit.DAYS));

        Optional<Entry> candidate = repository.findNextResurfaceCandidate(
                now.minus(1, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS));

        assertThat(candidate).isPresent();
        assertThat(candidate.get().getId()).isEqualTo(neverResurfaced.getId());
    }

    @Test
    @DisplayName("a roadmap step due for recheck is found; not-yet-due and un-set nextRecheckAt are excluded")
    void recheckCandidateHonorsDueDate() {
        assumeTrue(dbAvailable, "No local Postgres reachable — skipping.");

        Instant now = Instant.now();
        Entry due = saveStep(now.minus(1, ChronoUnit.DAYS));
        saveStep(now.plus(30, ChronoUnit.DAYS)); // not due yet
        saveStepWithoutRecheck(); // never verified, no nextRecheckAt at all

        Optional<Entry> candidate = repository.findNextRecheckCandidate(now, now.minus(1, ChronoUnit.DAYS));

        assertThat(candidate).isPresent();
        assertThat(candidate.get().getId()).isEqualTo(due.getId());
    }

    @Test
    @DisplayName("the soonest-due recheck candidate wins when more than one step qualifies")
    void recheckCandidateOrdersBySoonestDue() {
        assumeTrue(dbAvailable, "No local Postgres reachable — skipping.");

        Instant now = Instant.now();
        saveStep(now.minus(2, ChronoUnit.DAYS)); // due, but not the soonest
        Entry soonestDue = saveStep(now.minus(10, ChronoUnit.DAYS));

        Optional<Entry> candidate = repository.findNextRecheckCandidate(now, now.minus(1, ChronoUnit.DAYS));

        assertThat(candidate).isPresent();
        assertThat(candidate.get().getId()).isEqualTo(soonestDue.getId());
    }

    private Entry save(EntryType type, EntryStatus status, Significance significance,
                        Instant updatedAt, Instant lastResurfacedAt) {
        Entry e = new Entry();
        e.setType(type);
        e.setStatus(status);
        e.setSignificance(significance);
        e.setContent(Map.of("text", "test entry"));
        Entry saved = repository.save(e);
        // updated_at/last_resurfaced_at are normally driven by @PrePersist/request flow; the
        // resurfacing query's whole job is picking among specific timestamps, so they're forced
        // here via a native update rather than sleeping the clock between inserts.
        forceTimestamps(saved.getId(), updatedAt, lastResurfacedAt);
        return saved;
    }

    private Entry saveStep(Instant nextRecheckAt) {
        Entry e = new Entry();
        e.setType(EntryType.ROADMAP_STEP);
        e.setStatus(EntryStatus.DONE);
        e.setContent(Map.of("text", "test step", "nextRecheckAt", nextRecheckAt.toString()));
        Entry saved = repository.save(e);
        forceTimestamps(saved.getId(), Instant.now(), null);
        return saved;
    }

    private Entry saveStepWithoutRecheck() {
        Entry e = new Entry();
        e.setType(EntryType.ROADMAP_STEP);
        e.setStatus(EntryStatus.DONE);
        e.setContent(Map.of("text", "never verified"));
        return repository.save(e);
    }

    /**
     * {@code updated_at} is fully automatic ({@code @PrePersist}/{@code @PreUpdate} always stamp
     * {@code Instant.now()}, with no entity setter), and the resurfacing queries' whole job is
     * choosing among specific timestamps — so this bypasses the entity and updates the row
     * directly. Runs through {@link TestEntityManager} rather than a separate JDBC connection so
     * it lands in the same transaction as {@code repository.save()}, then flushes and clears the
     * persistence context so the native finder queries below see the real row, not a stale
     * managed entity from the first-level cache.
     */
    private void forceTimestamps(Long id, Instant updatedAt, Instant lastResurfacedAt) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE entries SET updated_at = :updatedAt, last_resurfaced_at = :lastResurfacedAt WHERE id = :id")
                .setParameter("updatedAt", updatedAt.atOffset(java.time.ZoneOffset.UTC))
                .setParameter("lastResurfacedAt", lastResurfacedAt == null ? null : lastResurfacedAt.atOffset(java.time.ZoneOffset.UTC))
                .setParameter("id", id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }
}
