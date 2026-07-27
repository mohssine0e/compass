package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates prompt *output shape* against fixture JSON, for every JSON-returning prompt family
 * (V3-2.5): does the parsing/mapping code in {@link RoadmapAiService} / {@link
 * VerificationAiService} turn a well-formed reply into the expected domain object, and does a
 * reply with a missing/renamed/out-of-range field degrade the way the code already documents,
 * rather than NPE-ing? This is the substitute for golden-prompt regression tests (V3-1.4): it's
 * coupled to the field *contract*, not prompt wording, so it survives normal prompt iteration and
 * only breaks when a field name or shape actually changes underneath a caller.
 *
 * <p>See {@code src/test/resources/ai-fixtures/README.md} for why these fixtures are
 * hand-authored rather than literal captures from a live call.
 */
class PromptOutputContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture(String name) {
        String path = "/ai-fixtures/" + name + ".json";
        try (InputStream in = PromptOutputContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + path);
            }
            return MAPPER.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static AiJsonGenerator generatorReturning(JsonNode node) {
        AiJsonGenerator ai = mock(AiJsonGenerator.class);
        when(ai.generate(any(), anyString(), anyString(), anyString())).thenReturn(node);
        return ai;
    }

    private static RoadmapAiService roadmapAiService(JsonNode node) {
        // TTL 0 disables the cache outright — every call in this test should actually reach the
        // (mocked) AI layer instead of a prior test's cached result.
        return new RoadmapAiService(generatorReturning(node), new AiGenerationCache(0));
    }

    // ── outline ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("outline: a well-formed reply maps to title/interpretation/modules/skipped")
    void outlineHappyPath() {
        RoadmapAiService.RoadmapOutline outline = roadmapAiService(fixture("outline")).moduleOutline(
                "learn rust", null, null, null, null, null);

        assertThat(outline.title()).isEqualTo("Learn Rust Systems Programming");
        assertThat(outline.modules()).hasSize(3);
        assertThat(outline.modules().get(0).title()).isEqualTo("Ownership & Borrowing");
        assertThat(outline.modules().get(0).scope()).contains("ownership, borrowing");
        assertThat(outline.skipped()).containsExactly("Basic syntax — profile shows prior C++ experience");
    }

    @Test
    @DisplayName("outline: a module with a blank/missing title is dropped, not kept as a blank module")
    void outlineDropsUntitledModules() {
        RoadmapAiService.RoadmapOutline outline = roadmapAiService(fixture("outline_missing_scope"))
                .moduleOutline("learn rust", null, null, null, null, null);

        assertThat(outline.modules()).hasSize(1);
        assertThat(outline.modules().get(0).title()).isEqualTo("Ownership & Borrowing");
        assertThat(outline.modules().get(0).scope()).isNull();
    }

    @Test
    @DisplayName("outline: a reply that resolves to zero usable modules returns null, not an empty outline")
    void outlineAllModulesDroppedReturnsNull() {
        JsonNode allBlank = fixture("outline_missing_scope").deepCopy();
        // Every module in this fixture is unusable once you strip the one with a real title —
        // build a variant with none at all to hit the "zero modules -> null" branch directly.
        com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) allBlank;
        obj.putArray("modules"); // empty array: nothing to parse into a module at all

        RoadmapAiService.RoadmapOutline outline = roadmapAiService(allBlank)
                .moduleOutline("learn rust", null, null, null, null, null);

        assertThat(outline).isNull();
    }

    // ── module expansion (shares parseSteps with flat proposal / step breakdown) ──────

    @Test
    @DisplayName("module expansion: a well-formed reply maps text/kind/weight/dependsOnIndex/rationale")
    void moduleExpansionHappyPath() {
        var steps = roadmapAiService(fixture("module_expansion"))
                .expandModule("Learn Rust", "Ownership", "scope", null, null, null, java.util.List.of(), true, null);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).text()).isEqualTo("Read the Rust Book chapter on ownership");
        assertThat(steps.get(0).kind()).isEqualTo("concept");
        assertThat(steps.get(0).weight()).isEqualTo("medium");
        assertThat(steps.get(1).dependsOn()).isEqualTo(0);
        assertThat(steps.get(1).kind()).isEqualTo("project");
        assertThat(steps.get(1).weight()).isEqualTo("large");
    }

    @Test
    @DisplayName("module expansion: an out-of-enum kind/weight falls back to the documented default; a step missing 'text' is dropped")
    void moduleExpansionDegradesOnRenamedOrInvalidFields() {
        var steps = roadmapAiService(fixture("module_expansion_renamed_field"))
                .expandModule("Learn Rust", "Ownership", "scope", null, null, null, java.util.List.of(), true, null);

        assertThat(steps).hasSize(1); // the 'title'-instead-of-'text' step is dropped, not crashed on
        assertThat(steps.get(0).kind()).isEqualTo("concept"); // documented default for an unrecognised kind
        assertThat(steps.get(0).weight()).isEqualTo("medium"); // documented default for an unrecognised weight
    }

    // ── tier classification ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("tier classification: a well-formed reply maps tier/confidence/reasoning, confidence clamped to [0,1]")
    void tierClassificationHappyPath() {
        RoadmapAiService.TierClassification result = roadmapAiService(fixture("tier_classification"))
                .classifyTier("learn rust", null);

        assertThat(result.tier()).isEqualTo(Tier.TOPIC);
        assertThat(result.confidence()).isEqualTo(0.82);
        assertThat(result.reasoning()).contains("Broad enough");
    }

    @Test
    @DisplayName("tier classification: a tier value outside the enum fails the whole classification, not a silent guess")
    void tierClassificationUnknownTierReturnsNull() {
        RoadmapAiService.TierClassification result = roadmapAiService(fixture("tier_classification_unknown_tier"))
                .classifyTier("learn rust", null);

        assertThat(result).isNull();
    }

    // ── goal assessment ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("goal assessment: a well-formed reply maps every field through")
    void goalAssessmentHappyPath() {
        RoadmapAiService.GoalAssessment result = roadmapAiService(fixture("goal_assessment"))
                .assessGoal("learn rust", null, null, null);

        assertThat(result.complexity()).isEqualTo(4);
        assertThat(result.estimatedTotalHours()).isEqualTo(60);
        assertThat(result.domain()).isEqualTo("systems_programming");
        assertThat(result.shape()).isEqualTo("nested");
        assertThat(result.archetype()).isEqualTo("topic_deep_dive");
    }

    @Test
    @DisplayName("goal assessment: out-of-range complexity clamps to [1,5]; an unrecognised shape/archetype falls back to the documented default")
    void goalAssessmentClampsAndFallsBackOnInvalidValues() {
        RoadmapAiService.GoalAssessment result = roadmapAiService(fixture("goal_assessment_out_of_range"))
                .assessGoal("learn rust", null, null, null);

        assertThat(result.complexity()).isEqualTo(5); // clamped from 11
        assertThat(result.shape()).isEqualTo("nested"); // "diagonal" isn't "flat", so the strict-equality gate defaults nested
        assertThat(result.archetype()).isEqualTo("topic_deep_dive"); // documented fallback for an unrecognised archetype
    }

    // ── verification ─────────────────────────────────────────────────────────────────

    private static VerificationAiService verificationAiService(JsonNode node) {
        return new VerificationAiService(generatorReturning(node));
    }

    @Test
    @DisplayName("verification check: a well-formed reply returns the trimmed question")
    void verificationCheckHappyPath() {
        String question = verificationAiService(fixture("verification_check"))
                .generateCheck("Learn Rust", "Ownership", "full", "free_response");

        assertThat(question).startsWith("You just implemented a custom Drop");
    }

    @Test
    @DisplayName("verification multiple-choice: a well-formed reply maps question/options/correctIndex")
    void verificationMultipleChoiceHappyPath() {
        VerificationAiService.MultipleChoiceCheck check = verificationAiService(fixture("verification_mc"))
                .generateMultipleChoiceCheck("Learn Rust", "Ownership", "full");

        assertThat(check.options()).hasSize(4);
        assertThat(check.correctIndex()).isEqualTo(1);
        assertThat(check.options().get(check.correctIndex())).contains("At most one mutable reference");
    }

    @Test
    @DisplayName("verification multiple-choice: fewer than 2 usable options returns null rather than an unusable question")
    void verificationMultipleChoiceTooFewOptionsReturnsNull() {
        VerificationAiService.MultipleChoiceCheck check = verificationAiService(fixture("verification_mc_too_few_options"))
                .generateMultipleChoiceCheck("Learn Rust", "Ownership", "full");

        assertThat(check).isNull();
    }

    @Test
    @DisplayName("verification evaluation: a pass carries no gap; a fail carries the specific gap text")
    void verificationEvaluationHappyPath() {
        VerificationAiService.Evaluation pass = verificationAiService(fixture("verification_evaluation_pass"))
                .evaluate("Ownership", "question", "answer");
        assertThat(pass.passed()).isTrue();
        assertThat(pass.gap()).isNull();

        VerificationAiService.Evaluation fail = verificationAiService(fixture("verification_evaluation_fail"))
                .evaluate("Ownership", "question", "answer");
        assertThat(fail.passed()).isFalse();
        assertThat(fail.gap()).contains("unwind versus a normal return");
    }
}
