package com.compass.app.ai;

import com.compass.app.ai.SearchGroundingService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The AI layer for finding learning resources for already-drafted steps (Phase 7.5) — split out
 * of {@link RoadmapAiService} so step-structure generation and resource generation are two
 * distinct, independently-timed concerns (structure can be shown to the founder before resources
 * are ready; see {@code com.compass.app.resource.ResourceService}). Same HEAVY-tier call as
 * before this split, unchanged prompt.
 */
@Service
public class ResourceAiService {

    private final AiJsonGenerator ai;

    public ResourceAiService(AiJsonGenerator ai) {
        this.ai = ai;
    }

    private static final Set<String> FORMATS =
            Set.of("written", "video", "interactive", "repo", "book_chapter");
    private static final Set<String> SOURCE_TYPES = Set.of("official_docs", "community", "tutorial");

    /**
     * Up to 3 real learning resources per step, drawn only from the given search results (real
     * URLs, never invented), never in an avoided format, and never a url already in
     * {@code excludeUrls} or reused across two steps in this same call — the fix for resources
     * duplicating across a roadmap (Phase 13). Returns a list aligned to {@code stepTexts}
     * (empty list for a step with no fitting resources); an all-empty result when unavailable or
     * nothing fits.
     */
    public List<List<Resource>> suggestResources(String goal, List<String> stepTexts,
                                                 List<Result> groundingResults, List<String> avoidFormats,
                                                 List<String> preferFormats, Set<String> excludeUrls) {
        List<List<Resource>> perStep = new ArrayList<>();
        for (int i = 0; i < stepTexts.size(); i++) {
            perStep.add(new ArrayList<>());
        }
        if (groundingResults == null || groundingResults.isEmpty()) {
            return perStep; // resources are a grounded feature; without search there are none
        }

        Set<String> realUrls = new HashSet<>();
        StringBuilder results = new StringBuilder();
        for (Result r : groundingResults) {
            if (r.url() != null && !r.url().isBlank()) {
                realUrls.add(r.url());
                results.append("- ").append(r.title()).append(" [").append(r.url()).append("]");
                if (r.content() != null && !r.content().isBlank()) {
                    results.append(": ").append(r.content());
                }
                results.append('\n');
            }
        }
        if (realUrls.isEmpty()) {
            return perStep;
        }

        Set<String> avoid = avoidFormats == null ? Set.of() : new HashSet<>(avoidFormats);
        Set<String> used = excludeUrls == null ? new HashSet<>() : new HashSet<>(excludeUrls);
        JsonNode json = ai.generate(AiTier.HEAVY, "resource suggestions", PromptTemplates.RESOURCE_SUGGEST_SYSTEM,
                PromptTemplates.resourceSuggestUser(goal, stepTexts, results.toString(), avoidFormats,
                        preferFormats, List.copyOf(used)));
        if (json == null || json.get("steps") == null || !json.get("steps").isArray()) {
            return perStep;
        }

        for (JsonNode stepNode : json.get("steps")) {
            JsonNode indexNode = stepNode.get("index");
            if (indexNode == null || !indexNode.isInt()) {
                continue;
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= perStep.size()) {
                continue;
            }
            List<Resource> resources = perStep.get(index);
            for (JsonNode resNode : arrayOrEmpty(stepNode.get("resources"))) {
                Resource resource = toResource(resNode, realUrls, avoid);
                // Belt-and-suspenders: even if the model repeats a url despite the prompt rule,
                // never let a duplicate through code-side.
                if (resource != null && !used.contains(resource.url()) && resources.size() < 3) {
                    resources.add(resource);
                    used.add(resource.url());
                }
            }
        }
        return perStep;
    }

    private static Resource toResource(JsonNode node, Set<String> realUrls, Set<String> avoid) {
        String url = AiJsonGenerator.text(node.get("url"));
        String title = AiJsonGenerator.text(node.get("title"));
        // Only real URLs from the search results — drop anything invented.
        if (url == null || !realUrls.contains(url) || title == null || title.isBlank()) {
            return null;
        }
        String format = valueIn(AiJsonGenerator.text(node.get("format")), FORMATS, "written");
        if (avoid.contains(format)) {
            return null; // never suggest an avoided format
        }
        String sourceType = valueIn(AiJsonGenerator.text(node.get("source_type")), SOURCE_TYPES, "community");
        String estimatedTime = AiJsonGenerator.text(node.get("estimated_time"));
        String groundingSource = AiJsonGenerator.text(node.get("ai_grounding_source"));
        return new Resource(title.trim(), url, format, sourceType,
                estimatedTime == null ? null : estimatedTime.trim(),
                groundingSource == null ? null : groundingSource.trim());
    }

    private static Iterable<JsonNode> arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private static String valueIn(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    /**
     * A suggested learning resource for a step (Phase 7.5). {@code url} is always a real link
     * from the search grounding; {@code aiGroundingSource} names which result it came from.
     */
    public record Resource(String title, String url, String format, String sourceType,
                           String estimatedTime, String aiGroundingSource) {
    }
}
