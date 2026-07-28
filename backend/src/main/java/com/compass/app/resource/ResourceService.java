package com.compass.app.resource;

import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.ProfileService;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse.ProposedResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Learning-resource discovery (Phase 7.5), split out of {@code RoadmapService}/
 * {@code RoadmapAiService} so step-structure generation and resource generation are two
 * independently-timed concerns — CLAUDE.md's suggested package layout (Section 5) already
 * anticipated this {@code resource/} package; it just hadn't been carved out yet. Structure can
 * now be shown to the founder before resources are ready (see {@code ResourceController}), except
 * for module expansion, which keeps its existing combined timing (already solved by background
 * prefetching — see {@code ModulePrefetchService}).
 *
 * <p>Also owns the format-preference/dedup reads ({@link #avoidedFormats}, {@link #preferredFormats},
 * {@link #usedResourceUrls}) previously on {@code RoadmapService} — these are resource concerns,
 * not roadmap-structural ones.
 */
@Service
public class ResourceService {

    private final ResourceAiService resourceAi;
    private final SearchGroundingService searchGrounding;
    private final EntryRepository repository;
    private final ProfileService profileService;
    private final ResourceEnrichmentService enrichmentService;

    public ResourceService(ResourceAiService resourceAi, SearchGroundingService searchGrounding,
                           EntryRepository repository, ProfileService profileService,
                           ResourceEnrichmentService enrichmentService) {
        this.resourceAi = resourceAi;
        this.searchGrounding = searchGrounding;
        this.repository = repository;
        this.profileService = profileService;
        this.enrichmentService = enrichmentService;
    }

    /**
     * Resources for a just-drafted batch of steps, using grounding the caller already computed
     * (module expansion, which grounds multiple query framings before drafting steps and reuses
     * the same result set here) — same call, same timing as before this concern was split out.
     * Returns a list aligned by index to {@code stepTexts} (empty list per step with nothing
     * fitting).
     *
     * <p>Also piggybacks RES-2's zero-AI-call enrichment cache: any chosen resource whose grounding
     * result carried a real Exa highlight gets that highlight cached as its focus pointer for this
     * step's topic, right here — no extra call, no extra fetch, just surfacing something Exa had
     * already computed for a page that was picked anyway.
     */
    public List<List<ResourceAiService.Resource>> suggestResourcesPerStep(
            String scope, List<String> stepTexts, List<SearchGroundingService.Result> groundingResults,
            Long roadmapId) {
        List<List<ResourceAiService.Resource>> perStep = resourceAi.suggestResources(scope, stepTexts,
                groundingResults, avoidedFormats(), preferredFormats(),
                roadmapId == null ? Set.of() : usedResourceUrls(roadmapId));
        for (int i = 0; i < perStep.size() && i < stepTexts.size(); i++) {
            enrichmentService.cacheExaHighlights(perStep.get(i), groundingResults, stepTexts.get(i));
        }
        return perStep;
    }

    /**
     * As above, but grounds {@code scope} itself first (already TTL-cached — see
     * {@link SearchGroundingService}) and returns the already-mapped {@link ProposedResource}
     * response shape — used by the follow-up resources call for paths that show steps to the
     * founder before resources are ready (flat-goal proposals, reformulate/resurfacing
     * break-downs), where there's no already-computed grounding to reuse.
     */
    public List<List<ProposedResource>> suggestResourcesFor(String scope, List<String> stepTexts, Long roadmapId) {
        SearchGroundingService.Grounding grounding = searchGrounding.ground(scope);
        List<List<ResourceAiService.Resource>> perStep = suggestResourcesPerStep(
                scope, stepTexts, grounding == null ? null : grounding.results(), roadmapId);
        return perStep.stream()
                .map(step -> step.stream().map(ProposedResource::from).toList())
                .toList();
    }

    /**
     * Attach resources to already-persisted steps under {@code parentId} (a module, or the
     * roadmap itself for a flat one) that don't have any yet, and return how many steps gained
     * some.
     *
     * <p>This fills a real hole rather than adding a feature: resource discovery only ever ran
     * while a proposal was still open for review, so a step that was persisted without resources
     * — because the AI call failed that minute, or because it was accepted from the titles-only
     * skeleton fallback — could never get them afterwards. Steps that already have resources are
     * left completely alone: the founder curates these (CLAUDE.md Section 2, "resources are
     * suggestions"), and silently replacing a curated list would be the system overruling them.
     */
    @Transactional
    public int backfillResources(Long parentId, Long roadmapId) {
        Entry parent = repository.findById(parentId).orElseThrow(
                () -> new java.util.NoSuchElementException("No entry with id " + parentId));

        List<Entry> needing = repository.findByParentIdOrderByOrderIndexAsc(parentId).stream()
                .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
                .filter(e -> !hasResources(e))
                .filter(e -> stepText(e) != null)
                .toList();
        if (needing.isEmpty()) {
            return 0;
        }

        String scope = scopeOf(parent);
        List<String> texts = needing.stream().map(ResourceService::stepText).toList();
        SearchGroundingService.Grounding grounding = searchGrounding.ground(scope);
        List<List<ResourceAiService.Resource>> found = suggestResourcesPerStep(
                scope, texts, grounding == null ? null : grounding.results(), roadmapId);

        int filled = 0;
        for (int i = 0; i < needing.size(); i++) {
            List<ResourceAiService.Resource> resources = i < found.size() ? found.get(i) : List.of();
            if (resources.isEmpty()) {
                continue;
            }
            Entry step = needing.get(i);
            Map<String, Object> content = step.getContent() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(step.getContent());
            content.put("resources", stored(resources));
            step.setContent(content);
            repository.save(step);
            filled++;
        }
        return filled;
    }

    /** The text to search against for a step's parent: a module's title and scope, or a title. */
    private static String scopeOf(Entry parent) {
        Map<String, Object> content = parent.getContent();
        String title = content != null && content.get("title") instanceof String s ? s : null;
        String scope = content != null && content.get("scope") instanceof String s ? s : null;
        if (title == null || title.isBlank()) {
            return scope == null ? "" : scope;
        }
        return scope == null || scope.isBlank() ? title : title + ": " + scope;
    }

    private static String stepText(Entry step) {
        Object text = step.getContent() != null ? step.getContent().get("text") : null;
        return text instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean hasResources(Entry step) {
        Object resources = step.getContent() != null ? step.getContent().get("resources") : null;
        return resources instanceof List<?> list && !list.isEmpty();
    }

    /**
     * Suggested resources in their stored shape. The single place a resource becomes a stored
     * map — {@code RoadmapService} builds the same shape when accepting a reviewed proposal and
     * delegates here, so the two paths can't drift apart on field names or defaults.
     */
    public static List<Map<String, Object>> stored(List<ResourceAiService.Resource> resources) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (resources == null) {
            return out;
        }
        for (ResourceAiService.Resource r : resources) {
            Map<String, Object> map = storedResource(null, r.title(), r.url(), r.format(),
                    r.sourceType(), r.estimatedTime(), r.aiGroundingSource());
            if (map != null) {
                out.add(map);
            }
        }
        return out;
    }

    /**
     * One stored resource map, or {@code null} if it lacks a real title/url. Each gets a stable
     * id (generated when the caller has none) and starts {@code userRating} null.
     */
    public static Map<String, Object> storedResource(String id, String title, String url, String format,
                                                     String sourceType, String estimatedTime,
                                                     String groundingSource) {
        if (title == null || title.isBlank() || url == null || url.isBlank()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id != null && !id.isBlank() ? id : UUID.randomUUID().toString());
        map.put("title", title.trim());
        map.put("url", url.trim());
        putIfPresent(map, "format", format);
        putIfPresent(map, "sourceType", sourceType);
        putIfPresent(map, "estimatedTime", estimatedTime);
        putIfPresent(map, "aiGroundingSource", groundingSource);
        map.put("userRating", null);
        return map;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    /** Every resource url already attached anywhere in this roadmap's tree (Phase 13 dedup). */
    @Transactional(readOnly = true)
    public Set<String> usedResourceUrls(Long roadmapId) {
        Set<String> urls = new HashSet<>();
        collectResourceUrls(roadmapId, urls);
        return urls;
    }

    @SuppressWarnings("unchecked")
    private void collectResourceUrls(Long parentId, Set<String> urls) {
        for (Entry child : repository.findByParentIdOrderByOrderIndexAsc(parentId)) {
            if (child.getType() == EntryType.ROADMAP_STEP && child.getContent() != null
                    && child.getContent().get("resources") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && map.get("url") instanceof String url
                            && !url.isBlank()) {
                        urls.add(url);
                    }
                }
            }
            collectResourceUrls(child.getId(), urls);
        }
    }

    /** The founder's stated avoided resource formats, or empty if none/no confirmed profile. */
    public List<String> avoidedFormats() {
        return formatPreferenceList("avoid");
    }

    /**
     * The founder's confirmed behaviorally-inferred preferred formats (Phase 20) — a soft bias
     * for {@code resourceSuggestUser}, never a hard requirement. Only ever set via confirming an
     * {@code InferredPreference}, never stated directly.
     */
    public List<String> preferredFormats() {
        return formatPreferenceList("prefer");
    }

    @SuppressWarnings("unchecked")
    private List<String> formatPreferenceList(String key) {
        return profileService.confirmedProfile()
                .map(p -> p.getFormatPreferences())
                .map(prefs -> prefs.get(key))
                .filter(a -> a instanceof List)
                .map(a -> (List<String>) a)
                .orElseGet(List::of);
    }
}
