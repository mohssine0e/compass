package com.compass.app.resource;

import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.events.EventService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression test for the live bug found in the 2026-07-30 user audit
 * (see {@code USER_AUDIT_2026-07-30.md} §1.1, {@code TASKS_v4.md} V4-0.1/V4-0.3): generating a
 * roadmap under load threw "ERROR: cannot execute INSERT in a read-only transaction"
 * (SQLState 25006). Root cause: {@code RoadmapGenerationService.expandModule} is
 * {@code @Transactional(readOnly = true)}, and its call chain reaches
 * {@link ResourceEnrichmentService#cacheExaHighlights}, which saves a row — a write inherited
 * into the caller's read-only transaction under Spring's default {@code REQUIRED} propagation.
 *
 * <p>Reproduces that exact shape directly (an explicit read-only transaction wrapping the call)
 * rather than standing up the whole generation pipeline, and asserts the row is actually
 * committed afterward — not just that no exception was thrown, since a swallowed exception would
 * pass a weaker assertion while still silently losing the write. Same real-Postgres,
 * dedicated-schema, skip-if-unreachable approach as
 * {@code EntryRepositoryResurfacingQueriesTest}, for the same reason: this is exactly the kind of
 * transactional-propagation behavior that a mocked repository can't exercise at all.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ResourceEnrichmentService.class)
class ResourceEnrichmentServiceTransactionTest {

    private static final String SCHEMA = "resource_enrichment_tx_test";
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
    private ResourceEnrichmentService service;

    @Autowired
    private ResourceEnrichmentRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // cacheExaHighlights doesn't call any of these — only wired because the constructor needs
    // them. Real collaborators aren't relevant to a propagation test.
    @MockBean
    private ResourcePageFetcher pageFetcher;
    @MockBean
    private ResourceAiService resourceAi;
    @MockBean
    private EventService events;
    @MockBean
    private YouTubeTranscriptFetcher transcriptFetcher;

    private static ResourceAiService.Resource resource(String url) {
        return new ResourceAiService.Resource("Title", url, "written", "official_docs", "~20 min", null);
    }

    private static SearchGroundingService.Result exaHighlight(String url, String text) {
        return new SearchGroundingService.Result("Title", url, text, true);
    }

    // @DataJpaTest wraps every test method in its own (read-write) transaction by default and
    // rolls it back afterward. Left in place, that ambient transaction is what a TransactionTemplate
    // below would join under the default REQUIRED propagation — `setReadOnly(true)` only takes
    // effect when a transaction is actually STARTED, not when joining one already open, so the
    // test would silently stop exercising the read-only case it's named for. NOT_SUPPORTED
    // suspends it so the TransactionTemplate below genuinely starts a fresh transaction.
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("cacheExaHighlights commits its write even when called from inside a read-only transaction")
    void savesFromWithinAnOuterReadOnlyTransaction() {
        assumeTrue(dbAvailable, "No local Postgres reachable — skipping.");

        String url = "https://example.com/read-only-tx-regression";
        String highlight = "A genuinely long extractive quote, well past the minimum cache-worthy length.";

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);

        // Same shape as the real bug: an outer read-only transaction (stand-in for
        // RoadmapGenerationService.expandModule) wrapping the call that ultimately saves.
        readOnlyTx.executeWithoutResult(status -> service.cacheExaHighlights(
                List.of(resource(url)),
                List.of(exaHighlight(url, highlight)),
                "topic"));

        // Queried fresh, outside any transaction this test set up itself — proves the row is
        // actually committed (REQUIRES_NEW gave it its own transaction), not just visible within
        // a still-open session that would vanish on rollback.
        Optional<ResourceEnrichment> saved = repository.findByResourceUrlAndTopicKey(url, "topic");
        assertThat(saved).isPresent();
        assertThat(saved.get().getFocusPointer()).isEqualTo(highlight);
    }
}
