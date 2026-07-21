package com.compass.app.roadmap.dto;

import com.compass.app.ai.RoadmapAiService;

import java.util.List;
import java.util.Map;

/**
 * The result of a drafting request. Exactly one shape is populated, keyed by {@code status}:
 * <ul>
 *   <li>{@code needs_clarification} → {@code questions} holds 0–4 questions to answer first
 *       (Phase 17: the count is adaptive, not fixed — an empty list means nothing more is worth
 *       asking, and the caller should draft straight away);</li>
 *   <li>{@code outline} → {@code title}, {@code interpretation} (nullable — set only when the
 *       goal was ambiguous or little was clarified, stating the reading/assumptions plainly),
 *       {@code modules}, {@code skipped}, and {@code assessment} (Phase 18 — the shared scope
 *       read used to size this outline) hold a top-level module outline the user edits and owns
 *       (Phase 13) — no individual steps yet;</li>
 *   <li>{@code proposal} → {@code title}, {@code interpretation}, {@code steps}, {@code skipped},
 *       and {@code assessment} hold a draft the user edits and owns. Used both for a small goal's
 *       flat step list (Phase 18) and for one module's expanded steps (Phase 13).</li>
 * </ul>
 * Nothing is persisted here — the user accepts by creating a roadmap / adding module steps the
 * normal way. When an accepted outline/flat proposal is turned into a real roadmap, its
 * {@code assessment} is sent back on create and stored on the roadmap so a later module-expand
 * can read the same numbers (see {@code CreateRoadmapRequest}).
 */
public record GenerateRoadmapResponse(
        String status,
        List<String> questions,
        String title,
        String interpretation,
        List<ProposedModule> modules,
        List<ProposedStep> steps,
        List<String> skipped,
        List<String> sources,
        ProposedAssessment assessment
) {
    /** A proposed top-level module (Phase 13): a short title and its one-line scope. */
    public record ProposedModule(String title, String scope) {
        public static ProposedModule from(RoadmapAiService.OutlineModule m) {
            return m == null ? null : new ProposedModule(m.title(), m.scope());
        }
    }

    /**
     * A proposed step (Phase 7): its text plus its kind (concept|project), relative weight
     * (small|medium|large), the 0-based index of a same-batch prerequisite step (or null), the
     * real id + text of a cross-module prerequisite from an earlier module instead (Phase 18,
     * nullable — at most one of the two dependency forms is ever set), a one-line rationale, and
     * up to 3 suggested learning resources (Phase 7.5). The user edits these before accepting.
     */
    public record ProposedStep(String text, String kind, String weight, Integer dependsOn,
                               Long dependsOnEntryId, String dependsOnEntryText,
                               String rationale, List<ProposedResource> resources) {
    }

    /** A suggested resource on a proposed step — a real link the user can keep, drop, or replace. */
    public record ProposedResource(String title, String url, String format, String sourceType,
                                   String estimatedTime, String aiGroundingSource) {
        static ProposedResource from(RoadmapAiService.Resource r) {
            return new ProposedResource(r.title(), r.url(), r.format(), r.sourceType(),
                    r.estimatedTime(), r.aiGroundingSource());
        }
    }

    /**
     * The shared goal-scope read (Phase 18) that sized this outline/proposal, carried through so
     * a later module-expand call reads the same numbers instead of re-guessing. Round-tripped via
     * {@code CreateRoadmapRequest} and stored on the roadmap entry.
     */
    public record ProposedAssessment(int complexity, Integer estimatedTotalHours, String domain,
                                     String priorLevel, String shape) {
        public static ProposedAssessment from(RoadmapAiService.GoalAssessment a) {
            return a == null ? null : new ProposedAssessment(
                    a.complexity(), a.estimatedTotalHours(), a.domain(), a.priorLevel(), a.shape());
        }
    }

    public static GenerateRoadmapResponse needsClarification(List<String> questions) {
        return new GenerateRoadmapResponse(
                "needs_clarification", questions, null, null, null, null, null, null, null);
    }

    /** A top-level module outline (Phase 13) — no steps yet; each module is expanded on demand. */
    public static GenerateRoadmapResponse outline(String title, String interpretation,
                                                   List<RoadmapAiService.OutlineModule> modules,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment) {
        List<ProposedModule> proposedModules = modules.stream().map(ProposedModule::from).toList();
        return new GenerateRoadmapResponse("outline", null, title, interpretation, proposedModules,
                null, skipped, sources, ProposedAssessment.from(assessment));
    }

    /**
     * A flat step-list proposal (Phase 18, small goal) or a module's expanded steps (Phase 13).
     * {@code priorStepTextById} resolves a cross-module {@code dependsOnEntryId} to its display
     * text; empty for a flat (top-level) proposal, since there are no other modules yet.
     */
    public static GenerateRoadmapResponse proposal(String title, String interpretation,
                                                   List<RoadmapAiService.DraftStep> steps,
                                                   List<List<RoadmapAiService.Resource>> resources,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment,
                                                   Map<Long, String> priorStepTextById) {
        List<ProposedStep> proposedSteps = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            RoadmapAiService.DraftStep s = steps.get(i);
            List<ProposedResource> stepResources = i < resources.size()
                    ? resources.get(i).stream().map(ProposedResource::from).toList()
                    : List.of();
            String depText = s.dependsOnEntryId() != null && priorStepTextById != null
                    ? priorStepTextById.get(s.dependsOnEntryId()) : null;
            proposedSteps.add(new ProposedStep(s.text(), s.kind(), s.weight(), s.dependsOn(),
                    s.dependsOnEntryId(), depText, s.rationale(), stepResources));
        }
        return new GenerateRoadmapResponse("proposal", null, title, interpretation, null,
                proposedSteps, skipped, sources, ProposedAssessment.from(assessment));
    }
}
