package com.compass.app.ai;

import com.compass.app.ai.prompts.ResourcePrompts;

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
 * are ready; see {@code com.compass.app.resource.ResourceService}).
 *
 * <p>Asked of the FAST tier in small batches with its own token ceiling — see the notes on
 * {@link #suggestResources} and {@link #collectBatch} for why each of those matters. Together
 * they're the difference between this feature working and it silently returning nothing.
 */
@Service
public class ResourceAiService {

    private final AiJsonGenerator ai;
    private final AiProperties props;

    public ResourceAiService(AiJsonGenerator ai, AiProperties props) {
        this.ai = ai;
        this.props = props;
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
        // Asked in small batches rather than one call for the whole module. A module expands to
        // ten-plus steps, and three resources each — every one carrying a full url — made this
        // the largest structured reply in the app, reliably running into the token ceiling and
        // arriving truncated. One unusable reply then cost *every* step its resources, which is
        // why almost nothing in the roadmap had any. Smaller asks land intact, and a batch that
        // still fails only costs its own few steps.
        for (int from = 0; from < stepTexts.size(); from += STEPS_PER_CALL) {
            int to = Math.min(from + STEPS_PER_CALL, stepTexts.size());
            collectBatch(goal, stepTexts.subList(from, to), from, results.toString(),
                    avoidFormats, preferFormats, realUrls, avoid, used, perStep);
        }
        return perStep;
    }

    // Small enough that a batch's reply comfortably fits the generation token budget, large
    // enough that a module is still only a handful of calls.
    private static final int STEPS_PER_CALL = 4;

    /**
     * One batch's worth of suggestions, written into {@code perStep} at the batch's real offset.
     * {@code used} carries across batches so a url picked for an earlier step is never repeated
     * for a later one — the same dedup guarantee the single-call version gave (Phase 13).
     */
    private void collectBatch(String goal, List<String> batchTexts, int offset, String results,
                              List<String> avoidFormats, List<String> preferFormats,
                              Set<String> realUrls, Set<String> avoid, Set<String> used,
                              List<List<Resource>> perStep) {
        // FAST, not HEAVY. This call picks three links out of a list of search results that were
        // already fetched and already summarised — it isn't the kind of reasoning the heavy tier
        // exists for. Running it there meant competing for the same scarce Gemini Pro quota that
        // roadmap drafting needs, and losing: in practice the heavy chain was exhausted or
        // erroring most times resources were asked for. The fast chain is twice as deep and far
        // likelier to answer, which for this feature matters more than model strength.
        JsonNode json = ai.generate(AiTier.FAST, "resource suggestions", ResourcePrompts.RESOURCE_SUGGEST_SYSTEM,
                ResourcePrompts.resourceSuggestUser(goal, batchTexts, results, avoidFormats,
                        preferFormats, List.copyOf(used)),
                props.getResourceMaxTokens());
        if (json == null || json.get("steps") == null || !json.get("steps").isArray()) {
            return;
        }

        for (JsonNode stepNode : json.get("steps")) {
            JsonNode indexNode = stepNode.get("index");
            if (indexNode == null || !indexNode.isInt()) {
                continue;
            }
            // The model indexes within the batch it was shown; translate back to the real step.
            int index = offset + indexNode.asInt();
            if (indexNode.asInt() < 0 || indexNode.asInt() >= batchTexts.size()) {
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
