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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public ResourceService(ResourceAiService resourceAi, SearchGroundingService searchGrounding,
                           EntryRepository repository, ProfileService profileService) {
        this.resourceAi = resourceAi;
        this.searchGrounding = searchGrounding;
        this.repository = repository;
        this.profileService = profileService;
    }

    /**
     * Resources for a just-drafted batch of steps, using grounding the caller already computed
     * (module expansion, which grounds multiple query framings before drafting steps and reuses
     * the same result set here) — same call, same timing as before this concern was split out.
     * Returns a list aligned by index to {@code stepTexts} (empty list per step with nothing
     * fitting).
     */
    public List<List<ResourceAiService.Resource>> suggestResourcesPerStep(
            String scope, List<String> stepTexts, List<SearchGroundingService.Result> groundingResults,
            Long roadmapId) {
        return resourceAi.suggestResources(scope, stepTexts, groundingResults, avoidedFormats(),
                preferredFormats(), roadmapId == null ? Set.of() : usedResourceUrls(roadmapId));
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
