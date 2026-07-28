package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.Tier;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.events.EventService;
import com.compass.app.roadmap.dto.ApplyReTierProposalRequest;
import com.compass.app.roadmap.dto.ReTierRequest;
import com.compass.app.roadmap.dto.ReTierResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The RB-2.5 re-tier escape hatch (V3-4.1's split of the former {@code RoadmapService} god-file —
 * see {@code TASKS_v3.md}): a founder-triggered correction when the classifier got a goal's scale
 * wrong. Pure move, no behaviour change — {@link com.compass.app.roadmap.RoadmapServiceBehaviorTest}'s
 * coverage of these methods moved with them into {@code RoadmapRetierServiceTest}.
 */
@Service
public class RoadmapRetierService {

    private final EntryRepository repository;
    private final RoadmapQueryService queryService;
    private final RoadmapAiService roadmapAi;
    private final EntryService entryService;
    private final AiVoiceService aiVoice;
    private final EventService events;

    public RoadmapRetierService(EntryRepository repository, RoadmapQueryService queryService,
                                RoadmapAiService roadmapAi, EntryService entryService,
                                AiVoiceService aiVoice, EventService events) {
        this.repository = repository;
        this.queryService = queryService;
        this.roadmapAi = roadmapAi;
        this.entryService = entryService;
        this.aiVoice = aiVoice;
        this.events = events;
    }

    /**
     * Never automatic; always the founder's own action, logged as such ({@code source: founder}).
     * Which path runs is decided by the roadmap's REAL current structure (does it have modules
     * with their own steps, or is it a flat list?), not by the stored {@code tier} field — a
     * roadmap created before RB-2.3, or one whose classification failed, still re-tiers correctly
     * this way.
     */
    @Transactional
    public ReTierResponse reTier(Long roadmapId, ReTierRequest request) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        Tier newTier = parseTier(request.tier());
        List<Entry> topChildren = repository.findByParentIdOrderByOrderIndexAsc(roadmapId);
        // A module is type ROADMAP (see createModules); a flat roadmap's direct children are
        // leaf ROADMAP_STEPs instead. Checking the TYPE, not whether a module already has
        // children, matters because an accepted-but-not-yet-expanded module is still a real
        // module (Type ROADMAP, zero children) — not a flat step to be swept into a grouping call.
        boolean currentlyNested = topChildren.stream().anyMatch(c -> c.getType() == EntryType.ROADMAP);

        if (newTier == Tier.TASK) {
            return reTierToTask(roadmap);
        }
        if (newTier == Tier.MINI) {
            return currentlyNested ? flattenToMini(roadmap, topChildren) : relabelOnly(roadmap, newTier);
        }
        // newTier is TOPIC or CAREER — both a nested (modules-then-steps) shape.
        if (!currentlyNested && !topChildren.isEmpty()) {
            // Only MINI → TOPIC is a defined single-step grouping transition (RB-2.5) — a flat
            // roadmap going straight to CAREER goes through TOPIC first, same as any other
            // adjacent-tier move, rather than inventing an ungrouped MINI → CAREER path.
            if (newTier == Tier.CAREER) {
                throw new IllegalArgumentException(
                        "Re-tier to TOPIC first, then TOPIC → CAREER — there's no direct MINI → CAREER path.");
            }
            return proposeRegroup(roadmap, topChildren);
        }
        if (newTier == Tier.CAREER && currentlyNested) {
            // Already nested; offer the optional arc-order proposal rather than forcing one —
            // the founder can also just confirm an empty/no-op reorder if the current order is
            // already fine (see applyReTierProposal's arc_order handling).
            return proposeArcOrder(roadmap, topChildren);
        }
        // Already nested, target TOPIC (e.g. from CAREER), or no steps at all yet either way —
        // nothing structural to change; phases aren't a real stored entity (RB-4.3), so CAREER →
        // TOPIC has nothing to strip beyond the label itself.
        return relabelOnly(roadmap, newTier);
    }

    /** Confirm a previously-returned re-tier proposal (RB-2.5), applying the founder's groups/order. */
    @Transactional
    public ReTierResponse applyReTierProposal(Long roadmapId, ApplyReTierProposalRequest request) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        if ("regroup".equals(request.kind())) {
            return applyRegroup(roadmap, request.groups());
        }
        if ("arc_order".equals(request.kind())) {
            return applyArcOrder(roadmap, request.groups());
        }
        throw new IllegalArgumentException("Unknown re-tier proposal kind: " + request.kind());
    }

    private static Tier parseTier(String raw) {
        try {
            return Tier.valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown tier: " + raw);
        }
    }

    /** Update just the stored {@code tier} label — no structural change (RB-2.5). */
    private ReTierResponse relabelOnly(Entry roadmap, Tier newTier) {
        String from = stringOf(roadmap, "tier");
        setTierContent(roadmap, newTier);
        events.founderAction("re_tier", "roadmap re-tiered from " + (from == null ? "unset" : from)
                + " to " + newTier + " — label only, no structural change",
                Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    private void setTierContent(Entry roadmap, Tier tier) {
        Map<String, Object> content = new HashMap<>(
                roadmap.getContent() != null ? roadmap.getContent() : Map.of());
        content.put("tier", tier.name());
        roadmap.setContent(content);
        repository.save(roadmap);
    }

    /** Convert a whole roadmap to a task (RB-2.5): archive it, create a task with its title. */
    private ReTierResponse reTierToTask(Entry roadmap) {
        String title = stringOf(roadmap, "title");
        Entry task = entryService.create(new CreateEntryRequest(EntryType.TASK, title, null, null, null, null));
        String ack = aiVoice.acknowledge(task);
        roadmap.setStatus(EntryStatus.ARCHIVED);
        repository.save(roadmap);
        events.founderAction("re_tier", "roadmap re-tiered to TASK — archived, converted to a task entry",
                Map.of("roadmapId", roadmap.getId(), "taskEntryId", task.getId()));
        return ReTierResponse.taskConversion(task.getId(), title, ack);
    }

    /**
     * Flatten a nested roadmap to MINI (RB-2.5): every leaf step (already-existing rows, kept
     * as-is — completion status survives automatically since nothing is recreated) is reparented
     * directly under the roadmap in order; the now-empty module rows are removed. The original
     * module titles are logged, not silently lost, in case the founder wants to reconstruct them.
     */
    private ReTierResponse flattenToMini(Entry roadmap, List<Entry> topChildren) {
        List<Entry> leaves = queryService.leafStepsOf(roadmap.getId());
        Set<Long> leafIds = leaves.stream().map(Entry::getId).collect(java.util.stream.Collectors.toSet());
        List<Entry> emptiedModules = topChildren.stream()
                .filter(c -> !leafIds.contains(c.getId()))
                .toList();

        int order = 0;
        for (Entry leaf : leaves) {
            leaf.setParentId(roadmap.getId());
            leaf.setOrderIndex(order++);
            repository.save(leaf);
        }
        // Archived, not deleted (RB-2.5): parentId stays put (so it's still found and cleaned up
        // if the whole roadmap is later deleted) but ARCHIVED status hides it from the rendered
        // tree (see RoadmapNodeResponse.of) — the row survives with its title/scope intact,
        // reversible if a re-tier turns out to be the wrong call, unlike a hard delete.
        for (Entry module : emptiedModules) {
            module.setStatus(EntryStatus.ARCHIVED);
            repository.save(module);
        }
        setTierContent(roadmap, Tier.MINI);
        List<String> moduleTitles = emptiedModules.stream()
                .map(c -> stringOf(c, "title")).filter(t -> t != null && !t.isBlank()).toList();
        List<Long> moduleIds = emptiedModules.stream().map(Entry::getId).toList();
        events.founderAction("re_tier", "roadmap re-tiered to MINI — archived "
                + emptiedModules.size() + " module(s) (titles: " + String.join(", ", moduleTitles) + ")",
                Map.of("roadmapId", roadmap.getId(), "archivedModuleIds", moduleIds));
        return ReTierResponse.applied(roadmap.getId());
    }

    /** AI-assisted grouping proposal for MINI → TOPIC (RB-2.5) — nothing applied yet. */
    private ReTierResponse proposeRegroup(Entry roadmap, List<Entry> flatSteps) {
        String title = stringOf(roadmap, "title");
        List<RoadmapAiService.StepForGrouping> steps = flatSteps.stream()
                .map(s -> new RoadmapAiService.StepForGrouping(s.getId(), stringOf(s, "text")))
                .toList();
        List<RoadmapAiService.RegroupedModule> groups = roadmapAi.regroupSteps(title, steps);
        if (groups == null) {
            throw new IllegalStateException(
                    "Couldn't propose a grouping right now — try again shortly.");
        }
        List<ReTierResponse.Group> proposed = groups.stream()
                .map(g -> new ReTierResponse.Group(g.title(), g.scope(), g.stepIds(), null))
                .toList();
        return ReTierResponse.proposal("regroup", proposed);
    }

    /** AI-assisted arc-order proposal for TOPIC → CAREER (RB-2.5) — nothing applied yet. */
    private ReTierResponse proposeArcOrder(Entry roadmap, List<Entry> modules) {
        String title = stringOf(roadmap, "title");
        List<RoadmapAiService.ModuleForArc> forArc = modules.stream()
                .map(m -> new RoadmapAiService.ModuleForArc(m.getId(), stringOf(m, "title"), stringOf(m, "scope")))
                .toList();
        List<RoadmapAiService.ArcPosition> order = roadmapAi.proposeCareerArc(title, forArc);
        if (order == null) {
            throw new IllegalStateException(
                    "Couldn't propose an arc order right now — try again shortly.");
        }
        List<ReTierResponse.Group> proposed = order.stream()
                .map(p -> new ReTierResponse.Group(null, null, List.of(p.moduleId()), p.phaseLabel()))
                .toList();
        return ReTierResponse.proposal("arc_order", proposed);
    }

    /**
     * Apply a confirmed regroup proposal (RB-2.5, MINI → TOPIC/CAREER): create one new module per
     * group, reparenting its referenced (already-existing) steps under it — their completion
     * status survives untouched since they're the same rows, just moved. Any step the founder's
     * confirmed groups leave out still lands somewhere (a catch-all "Ungrouped" module) rather
     * than being silently dropped; a step that was already DONE and got left out gets its own
     * brief log entry too, since that's the case actually worth the founder's attention.
     */
    private ReTierResponse applyRegroup(Entry roadmap, List<ReTierResponse.Group> groups) {
        List<Entry> allSteps = repository.findByParentIdOrderByOrderIndexAsc(roadmap.getId());
        Map<Long, Entry> stepsById = allSteps.stream()
                .collect(java.util.stream.Collectors.toMap(Entry::getId, s -> s));
        Set<Long> grouped = new HashSet<>();

        int moduleOrder = 0;
        for (ReTierResponse.Group g : groups) {
            List<Entry> members = new ArrayList<>();
            for (Long stepId : g.entryIds() == null ? List.<Long>of() : g.entryIds()) {
                Entry step = stepsById.get(stepId);
                if (step != null) {
                    members.add(step);
                    grouped.add(stepId);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            createModuleWithSteps(roadmap.getId(), moduleOrder++, g.title(), g.scope(), members);
        }

        List<Entry> leftOver = allSteps.stream().filter(s -> !grouped.contains(s.getId())).toList();
        List<String> droppedDoneTitles = leftOver.stream()
                .filter(s -> s.getStatus() == EntryStatus.DONE)
                .map(s -> stringOf(s, "text"))
                .toList();
        if (!leftOver.isEmpty()) {
            createModuleWithSteps(roadmap.getId(), moduleOrder, "Ungrouped",
                    "Steps the proposed grouping didn't place — sorted here instead of dropped.", leftOver);
        }

        // Regroup is only ever proposed for MINI → TOPIC (reTier() rejects a direct MINI →
        // CAREER grouping) — TOPIC is always the right target tier to store here.
        setTierContent(roadmap, Tier.TOPIC);
        String msg = "roadmap re-tiered to TOPIC — regrouped " + allSteps.size() + " step(s) into "
                + (moduleOrder + (leftOver.isEmpty() ? 0 : 1)) + " module(s)"
                + (droppedDoneTitles.isEmpty() ? "" : "; completed step(s) left out of the proposed"
                        + " groups, sorted into Ungrouped instead: " + String.join(", ", droppedDoneTitles));
        events.founderAction("re_tier", msg, Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    /** Create one module under a roadmap with the given already-existing steps reparented into it. */
    private void createModuleWithSteps(Long roadmapId, int orderIndex, String title, String scope,
                                       List<Entry> steps) {
        Entry module = new Entry();
        module.setType(EntryType.ROADMAP); // a module is type ROADMAP, same as createModules()
        module.setStatus(EntryStatus.IN_MOTION);
        module.setParentId(roadmapId);
        module.setOrderIndex(orderIndex);
        Map<String, Object> moduleContent = new HashMap<>();
        moduleContent.put("title", title == null || title.isBlank() ? "Module" : title);
        if (scope != null && !scope.isBlank()) {
            moduleContent.put("scope", scope);
        }
        module.setContent(moduleContent);
        module = repository.save(module);
        int stepOrder = 0;
        for (Entry step : steps) {
            step.setParentId(module.getId());
            step.setOrderIndex(stepOrder++);
            repository.save(step);
        }
    }

    /**
     * Apply a confirmed arc-order proposal (RB-2.5, TOPIC → CAREER): reorder the existing modules
     * — nothing is renamed, created, or dropped, {@code phaseLabel} was informational only.
     */
    private ReTierResponse applyArcOrder(Entry roadmap, List<ReTierResponse.Group> order) {
        List<Entry> modules = repository.findByParentIdOrderByOrderIndexAsc(roadmap.getId());
        Map<Long, Entry> byId = modules.stream()
                .collect(java.util.stream.Collectors.toMap(Entry::getId, m -> m));
        int i = 0;
        for (ReTierResponse.Group g : order) {
            Long moduleId = g.entryIds() == null || g.entryIds().isEmpty() ? null : g.entryIds().get(0);
            Entry module = moduleId == null ? null : byId.get(moduleId);
            if (module == null) {
                continue;
            }
            module.setOrderIndex(i++);
            repository.save(module);
        }
        setTierContent(roadmap, Tier.CAREER);
        events.founderAction("re_tier", "roadmap re-tiered to CAREER — reordered "
                + i + " module(s) into the confirmed arc", Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    private static String stringOf(Entry entry, String key) {
        Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
