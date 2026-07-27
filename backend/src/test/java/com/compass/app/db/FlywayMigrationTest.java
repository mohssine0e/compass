package com.compass.app.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every Flyway migration from a genuinely empty schema — the one check that would have
 * caught a broken migration before "the founder's next {@code mvn spring-boot:run}" does
 * (V3-1.2). Deliberately not a Spring context: this only needs Flyway and a JDBC URL, and
 * pulling in the full application would make an unrelated bean-wiring problem look like a
 * migration failure.
 *
 * <p>Runs against the same local Postgres instance and database the app already requires (see
 * SETUP.md), but inside its own dedicated schema — {@code mohssine} has no {@code CREATEDB}
 * privilege, so a throwaway sibling database isn't an option, and the schema is dropped and
 * recreated on every run rather than reusing {@code public}, which is where the founder's real
 * captures live. If no Postgres is reachable at all (e.g. a CI box with no local DB), the test
 * skips itself rather than failing the whole suite over an environment gap.
 */
class FlywayMigrationTest {

    private static final String DB_URL = System.getenv().getOrDefault(
            "COMPASS_TEST_DB_URL", "jdbc:postgresql://localhost:5433/compass");
    private static final String USER = System.getenv().getOrDefault("COMPASS_DB_USER", "mohssine");
    private static final String PASSWORD = System.getenv().getOrDefault("COMPASS_DB_PASSWORD", "");
    private static final String SCHEMA = "flyway_migration_test";

    private static boolean dbAvailable;

    @BeforeAll
    static void checkDatabaseReachable() {
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASSWORD)) {
            dbAvailable = true;
        } catch (SQLException ex) {
            dbAvailable = false;
        }
    }

    @AfterAll
    static void dropTestSchema() {
        if (!dbAvailable) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (SQLException ignored) {
            // Best-effort cleanup; a leftover throwaway schema is not worth failing the build over.
        }
    }

    private static Flyway flywayOnTestSchema() {
        return Flyway.configure()
                .dataSource(DB_URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .cleanDisabled(false)
                .load();
    }

    @Test
    @DisplayName("every migration applies cleanly to an empty schema")
    void allMigrationsApplyFromEmpty() {
        Assumptions.assumeTrue(dbAvailable, "No local Postgres reachable at " + DB_URL + " — skipping.");

        Flyway flyway = flywayOnTestSchema();
        flyway.clean(); // drops and recreates the schema — never touches `public`.

        MigrationInfo[] pending = flyway.info().pending();
        assertThat(pending).as("migrations discovered on the classpath").isNotEmpty();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(pending.length);
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("migrating an already-current schema is a no-op, not a re-apply")
    void migratingTwiceIsIdempotent() {
        Assumptions.assumeTrue(dbAvailable, "No local Postgres reachable at " + DB_URL + " — skipping.");

        Flyway flyway = flywayOnTestSchema();
        flyway.clean();
        flyway.migrate();

        MigrateResult second = flyway.migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
    }
}
