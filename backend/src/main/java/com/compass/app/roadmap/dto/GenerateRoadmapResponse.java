package com.compass.app.roadmap.dto;

import com.compass.app.ai.ResourceAiService;
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
 *   <li>{@code routed_to_task} (RB-2) → the goal classified as TASK with high confidence, so no
 *       roadmap was drafted at all — {@code routedTask} holds the already-created task entry's
 *       id, text, and self-talk-voice acknowledgment, exactly as if it had been captured
 *       directly. Nothing else on this record is populated.</li>
 *   <li>{@code topic_match} (RB-3) → the goal matched (exact/subtopic/prerequisite) against a
 *       topic the founder already has a roadmap for — {@code topicMatch} holds the candidate and
 *       the founder's options; no generation has run yet. Resubmit with
 *       {@link GenerateRoadmapRequest#skipTopicMatch()} to proceed as a genuinely new goal.</li>
 * </ul>
 * Nothing is persisted here — the user accepts by creating a roadmap / adding module steps the
 * normal way. When an accepted outline/flat proposal is turned into a real roadmap, its
 * {@code assessment} is sent back on create and stored on the roadmap so a later module-expand
 * can read the same numbers (see {@code CreateRoadmapRequest}).
 *
 * <p>{@code skeletonOnly} (Phase 19) is true only when the full drafting call failed across the
 * whole heavy provider chain and this is the emergency fallback instead — titles only, no
 * kind/weight/resources/dependencies. Carried through to accepted steps so the UI can flag them
 * ("basic outline — details pending") and a background retry can find and fill them in later.
 *
 * <p>{@code tier} (RB-2, nullable) is the TASK/MINI/TOPIC/CAREER classification, set on every
 * shape once classified on the first turn — including {@code needs_clarification}, so the
 * frontend can echo it back on {@link GenerateRoadmapRequest#tier()} for later turns without a
 * second classification call — and finally round-tripped via {@code CreateRoadmapRequest} to be
 * stored on the created roadmap entry. Not yet used to change generation behavior (that's RB-4).
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
        ProposedAssessment assessment,
        boolean skeletonOnly,
        List<ProposedIssue> issues,
        String tier,
        RoutedTask routedTask,
        TopicMatch topicMatch
) {
    /** The already-created task entry a TASK-tier goal was routed to instead of a roadmap (RB-2). */
    public record RoutedTask(Long entryId, String text, String acknowledgment) {
    }

    /**
     * A Canonical Topic Match candidate (RB-3) — {@code matchType} is exact|subtopic|prerequisite
     * (never "new"; a "new" result just continues generation unchanged, no response shape for
     * it). {@code candidateRoadmapId} is nullable (the topic's roadmap may since have been
     * deleted/re-tiered) — a null value means "open existing" isn't offered, only "start new."
     */
    public record TopicMatch(String matchType, double confidence, String reasoning,
                             Long candidateTopicId, String candidateName, Long candidateRoadmapId,
                             List<String> candidateSubtopics) {
    }

    /** A TASK-tier goal, routed straight to the existing task-creation path — no roadmap drafted. */
    public static GenerateRoadmapResponse routedToTask(Long entryId, String text, String acknowledgment) {
        return new GenerateRoadmapResponse("routed_to_task", null, null, null, null, null, null,
                null, null, false, List.of(), null, new RoutedTask(entryId, text, acknowledgment), null);
    }

    /** A Canonical Topic Match found something worth the founder's decision (RB-3) — nothing generated yet. */
    public static GenerateRoadmapResponse topicMatch(TopicMatch match) {
        return new GenerateRoadmapResponse("topic_match", null, null, null, null, null, null,
                null, null, false, List.of(), null, null, match);
    }

    /** A copy of this response with {@code tier} set (RB-2) — every shape can carry it forward. */
    public GenerateRoadmapResponse withTier(String tier) {
        return new GenerateRoadmapResponse(status, questions, title, interpretation, modules, steps,
                skipped, sources, assessment, skeletonOnly, issues, tier, routedTask, topicMatch);
    }

    /**
     * One flagged issue from the Phase 20 self-critique pass over a step-list proposal —
     * ordering, clarity, missing prerequisites, technical accuracy, or a gap against scope.
     * {@code stepIndex} names the one step it's about (or null for a plan-wide issue);
     * {@code suggestedFix} is a concrete replacement for that step's text, set only for an
     * unambiguous wording fix. The founder accepts (applies the fix to the draft, same as
     * editing it by hand) or dismisses each one; nothing is ever applied silently.
     */
    public record ProposedIssue(String severity, String message, Integer stepIndex, String suggestedFix) {
        static ProposedIssue from(RoadmapAiService.CritiqueIssue i) {
            return new ProposedIssue(i.severity(), i.message(), i.stepIndex(), i.suggestedFix());
        }
    }
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
        public static ProposedResource from(ResourceAiService.Resource r) {
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
                                     String priorLevel, String shape, String archetype) {
        public static ProposedAssessment from(RoadmapAiService.GoalAssessment a) {
            return a == null ? null : new ProposedAssessment(
                    a.complexity(), a.estimatedTotalHours(), a.domain(), a.priorLevel(), a.shape(),
                    a.archetype());
        }
    }

    public static GenerateRoadmapResponse needsClarification(List<String> questions) {
        return new GenerateRoadmapResponse(
                "needs_clarification", questions, null, null, null, null, null, null, null, false,
                List.of(), null, null, null);
    }

    /** A top-level module outline (Phase 13) — no steps yet; each module is expanded on demand. */
    public static GenerateRoadmapResponse outline(String title, String interpretation,
                                                   List<RoadmapAiService.OutlineModule> modules,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment) {
        List<ProposedModule> proposedModules = modules.stream().map(ProposedModule::from).toList();
        return new GenerateRoadmapResponse("outline", null, title, interpretation, proposedModules,
                null, skipped, sources, ProposedAssessment.from(assessment), false, List.of(), null, null, null);
    }

    /**
     * A flat step-list proposal (Phase 18, small goal) or a module's expanded steps (Phase 13).
     * {@code priorStepTextById} resolves a cross-module {@code dependsOnEntryId} to its display
     * text; empty for a flat (top-level) proposal, since there are no other modules yet.
     */
    public static GenerateRoadmapResponse proposal(String title, String interpretation,
                                                   List<RoadmapAiService.DraftStep> steps,
                                                   List<List<ResourceAiService.Resource>> resources,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment,
                                                   Map<Long, String> priorStepTextById) {
        return proposal(title, interpretation, steps, resources, skipped, sources, assessment,
                priorStepTextById, false, List.of());
    }

    /** As above, but flagging a skeleton (titles-only) result (Phase 19) — see the class doc. */
    public static GenerateRoadmapResponse proposal(String title, String interpretation,
                                                   List<RoadmapAiService.DraftStep> steps,
                                                   List<List<ResourceAiService.Resource>> resources,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment,
                                                   Map<Long, String> priorStepTextById,
                                                   boolean skeletonOnly) {
        return proposal(title, interpretation, steps, resources, skipped, sources, assessment,
                priorStepTextById, skeletonOnly, List.of());
    }

    /** As above, plus the Phase 20 self-critique issues flagged over this same step list. */
    public static GenerateRoadmapResponse proposal(String title, String interpretation,
                                                   List<RoadmapAiService.DraftStep> steps,
                                                   List<List<ResourceAiService.Resource>> resources,
                                                   List<String> skipped, List<String> sources,
                                                   RoadmapAiService.GoalAssessment assessment,
                                                   Map<Long, String> priorStepTextById,
                                                   boolean skeletonOnly,
                                                   List<RoadmapAiService.CritiqueIssue> critiqueIssues) {
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
        List<ProposedIssue> issues = critiqueIssues == null ? List.of()
                : critiqueIssues.stream().map(ProposedIssue::from).toList();
        return new GenerateRoadmapResponse("proposal", null, title, interpretation, null,
                proposedSteps, skipped, sources, ProposedAssessment.from(assessment), skeletonOnly,
                issues, null, null, null);
    }
}
