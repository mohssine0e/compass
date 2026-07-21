package com.compass.app.roadmap.dto;

import com.compass.app.ai.RoadmapAiService;

import java.util.List;

/**
 * The result of a drafting request. Exactly one shape is populated, keyed by {@code status}:
 * <ul>
 *   <li>{@code needs_clarification} → {@code questions} holds 1–2 questions to answer first;</li>
 *   <li>{@code outline} → {@code title}, {@code modules}, and {@code skipped} hold a top-level
 *       module outline the user edits and owns (Phase 13) — no individual steps yet;</li>
 *   <li>{@code proposal} → {@code title}, {@code steps}, and {@code skipped} hold a draft the
 *       user edits and owns. Used both for a module's expanded steps and (legacy shape) a flat
 *       roadmap's steps.</li>
 * </ul>
 * Nothing is persisted here — the user accepts by creating a roadmap / adding module steps the
 * normal way.
 */
public record GenerateRoadmapResponse(
        String status,
        List<String> questions,
        String title,
        List<ProposedModule> modules,
        List<ProposedStep> steps,
        List<String> skipped,
        List<String> sources
) {
    /** A proposed top-level module (Phase 13): a short title and its one-line scope. */
    public record ProposedModule(String title, String scope) {
        static ProposedModule from(RoadmapAiService.OutlineModule m) {
            return new ProposedModule(m.title(), m.scope());
        }
    }

    /**
     * A proposed step (Phase 7): its text plus its kind (concept|project), relative weight
     * (small|medium|large), the 0-based index of a prerequisite step (or null), a one-line
     * rationale, and up to 3 suggested learning resources (Phase 7.5). The user edits these
     * before accepting.
     */
    public record ProposedStep(String text, String kind, String weight, Integer dependsOn,
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

    public static GenerateRoadmapResponse needsClarification(List<String> questions) {
        return new GenerateRoadmapResponse("needs_clarification", questions, null, null, null, null, null);
    }

    /** A top-level module outline (Phase 13) — no steps yet; each module is expanded on demand. */
    public static GenerateRoadmapResponse outline(String title, List<RoadmapAiService.OutlineModule> modules,
                                                   List<String> skipped, List<String> sources) {
        List<ProposedModule> proposedModules = modules.stream().map(ProposedModule::from).toList();
        return new GenerateRoadmapResponse("outline", null, title, proposedModules, null, skipped, sources);
    }

    public static GenerateRoadmapResponse proposal(String title, List<RoadmapAiService.DraftStep> steps,
                                                   List<List<RoadmapAiService.Resource>> resources,
                                                   List<String> skipped, List<String> sources) {
        List<ProposedStep> proposedSteps = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            RoadmapAiService.DraftStep s = steps.get(i);
            List<ProposedResource> stepResources = i < resources.size()
                    ? resources.get(i).stream().map(ProposedResource::from).toList()
                    : List.of();
            proposedSteps.add(new ProposedStep(
                    s.text(), s.kind(), s.weight(), s.dependsOn(), s.rationale(), stepResources));
        }
        return new GenerateRoadmapResponse("proposal", null, title, null, proposedSteps, skipped, sources);
    }
}
