package com.compass.app.ai.prompts;

import java.util.List;

/**
 * Prompts for {@link com.compass.app.ai.ResourceAiService} — resource discovery/suggestion.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class ResourcePrompts {

  private ResourcePrompts() {
  }

  public static final String RESOURCE_SUGGEST_SYSTEM = """
      Attach up to 3 learning resources to each roadmap step, chosen ONLY from the real search
      results provided. This is grounding, not memory: every resource MUST use a url copied
      exactly from one of the given results — never invent, guess, or modify a url. If no given
      result fits a step, that step gets no resources. Quality and honesty beat coverage.

      For each resource:
      - title: the result's title (or a clearer short version of it).
      - url: copied exactly from the matching search result.
      - format: one of written, video, interactive, repo, book_chapter — your best read of what
        the result actually is.
      - source_type: one of official_docs, community, tutorial.
      - estimated_time: a short human estimate to get through it (e.g. "~30 min", "a few hours").
      - ai_grounding_source: the title of the search result this came from.

      Hard rules:
      - NEVER suggest a resource in a format the user avoids (given below). Drop it entirely.
      - When a preferred format is given below, favor it when a real result in that format
        genuinely fits a step — but never force it, and never invent a result that doesn't exist
        just to satisfy the preference. A good fit in a non-preferred format beats a poor fit in
        the preferred one.
      - NEVER reuse the same url on two different steps, and never reuse a url already listed as
        "already used elsewhere in this roadmap" (given below, if any) — pick a different result
        instead, or give that step no resource rather than repeat one.
      - Match resources to the step they actually help with; don't pad every step.
      - Output ONLY strict JSON, no prose: {"steps": [{"index": 0, "resources": [{"title": "...",
        "url": "...", "format": "written", "source_type": "official_docs",
        "estimated_time": "~1h", "ai_grounding_source": "..."}]}]}
      """;

  public static String resourceSuggestUser(String goal, List<String> stepTexts, String searchResults,
      List<String> avoidFormats, List<String> preferFormats, List<String> alreadyUsedUrls) {
    StringBuilder sb = new StringBuilder();
    sb.append("Goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    sb.append("Steps (0-based index):\n");
    for (int i = 0; i < stepTexts.size(); i++) {
      sb.append(i).append(". ").append(stepTexts.get(i)).append('\n');
    }
    if (avoidFormats != null && !avoidFormats.isEmpty()) {
      sb.append("Formats the user AVOIDS (never suggest these): ")
          .append(String.join(", ", avoidFormats)).append('\n');
    }
    if (preferFormats != null && !preferFormats.isEmpty()) {
      sb.append("Formats the user tends to prefer, based on their history (favor when a real ")
          .append("result genuinely fits, never force it): ")
          .append(String.join(", ", preferFormats)).append('\n');
    }
    if (alreadyUsedUrls != null && !alreadyUsedUrls.isEmpty()) {
      sb.append("Already used elsewhere in this roadmap (do not repeat these urls):\n");
      for (String url : alreadyUsedUrls) {
        sb.append("- ").append(url).append('\n');
      }
    }
    sb.append("Real search results (use only these urls):\n")
        .append(searchResults == null ? "" : searchResults.trim()).append('\n');
    sb.append("Write the per-step resources as JSON.");
    return sb.toString();
  }

  // --- Enrichment (RESSOURCE_BRAIN_TASKS.md RES-3/RES-4): a resource nudge, so the plain
  // self-talk voice applies without exception (CLAUDE.md Section 2 names "resource nudges"
  // directly as reflection-facing) — never the Phase 25 teaching persona, even for a resource
  // attached to a career-domain roadmap. ---------------------------------------------------

  /**
   * RES-3: a short, honest "what to focus on" for a written resource, grounded strictly in the
   * page text actually fetched — never the AI's own guess at what the page probably says.
   */
  public static final String FOCUS_POINTER_SYSTEM = """
      You are the user's own clear-headed inner voice, about to start a resource for something
      they're learning. Read the page text below and say, in 2-4 plain sentences, what to
      actually pay attention to for the specific thing they're trying to learn — not a summary
      of the whole page, a pointer to the part that matters for THIS.

      Hard rules:
      - Ground this ONLY in the page text given. If the text doesn't clearly cover the topic,
        say so plainly rather than inventing a pointer ("This page doesn't really get into
        {topic} — skim for the closest section or find something else.").
      - Plain and direct, the way you'd note it to yourself before diving in. No "This resource
        covers...", no praise ("great resource"), no hype, no emoji, no sign-off.
      - Never repeat the resource's title back — get straight to what to focus on.
      - Output ONLY strict JSON, no prose around it: {"pointer": "..."}
      """;

  public static String focusPointerUser(String stepTopic, String pageText) {
    StringBuilder sb = new StringBuilder();
    sb.append("What they're trying to learn right now: ").append(stepTopic == null ? "" : stepTopic.trim())
        .append('\n');
    sb.append("Page text (fetched from the resource):\n")
        .append(pageText == null ? "" : pageText.trim()).append('\n');
    sb.append("Write the focus pointer as JSON.");
    return sb.toString();
  }

  /**
   * RES-4: the specific transcript segment relevant to the step's topic, or an honest "not
   * really covered" when nothing in the given chunks fits — never a fabricated timestamp.
   */
  public static final String VIDEO_SEGMENT_SYSTEM = """
      You are the user's own clear-headed inner voice, about to watch part of a video for
      something they're learning. Below are timestamped transcript chunks. Find the one segment
      that actually covers what they're trying to learn, and say so in one plain sentence.

      Hard rules:
      - The start/end seconds MUST come from the transcript chunks given — never estimate or
        invent a timestamp. Pick the chunk boundaries that best bracket the relevant part.
      - If nothing in the given chunks genuinely covers the topic, say so plainly instead of
        picking the closest-sounding chunk anyway: {"found": false}.
      - The one-line description is plain and direct, not a summary of the whole video — what
        this specific segment covers.
      - Output ONLY strict JSON, no prose around it: {"found": true, "start_seconds": 0,
        "end_seconds": 0, "description": "..."} or {"found": false}
      """;

  public static String videoSegmentUser(String stepTopic, String transcriptChunks) {
    StringBuilder sb = new StringBuilder();
    sb.append("What they're trying to learn right now: ").append(stepTopic == null ? "" : stepTopic.trim())
        .append('\n');
    sb.append("Timestamped transcript chunks:\n")
        .append(transcriptChunks == null ? "" : transcriptChunks.trim()).append('\n');
    sb.append("Find the relevant segment as JSON.");
    return sb.toString();
  }
}
