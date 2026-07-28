package com.compass.app.ai.prompts;

import java.util.List;

/**
 * Prompts for {@link com.compass.app.ai.TopicAiService} — Canonical Topic Matching's AI
 * judgment call (RB-3.3, only reached when raw embedding similarity is ambiguous) and drafting a
 * founder-suggested topic addition.
 *
 * Split out of the former PromptTemplates god-file (V3-4.2) as a pure move — no wording changed.
 */
public final class TopicPrompts {

  private TopicPrompts() {
  }

  /**
   * System prompt for the ambiguous-similarity match call (RB-3.4) — only reached when a raw
   * embedding cosine similarity landed in the middle band (neither clearly a match nor clearly
   * not); a single best-candidate topic is judged against the new goal. Never shown to the
   * founder directly, so plain and analytical, same discipline as {@link #ASSESS_SYSTEM}.
   */
  public static final String TOPIC_MATCH_SYSTEM = """
      Judge whether a new goal genuinely matches an existing topic the person already has a
      roadmap for. This output is never shown to the person directly — an internal routing
      signal, so be plain, analytical, and honest rather than generous.

      matchType is exactly one of:
      - "exact": the new goal is genuinely the same topic as the candidate — same core subject,
        same rough scope (e.g. "learn Docker" and "get better at Docker").
      - "subtopic": the new goal is a narrower slice already implied within the candidate's scope
        (e.g. candidate "Learn Docker", new goal "Docker networking specifically").
      - "prerequisite": the new goal is something that would normally come BEFORE the candidate,
        not part of it (e.g. candidate "Learn Kubernetes", new goal "Learn Docker first").
      - "new": genuinely a different topic, despite the surface similarity that got it compared
        here at all (e.g. candidate "Learn Spanish", new goal "Learn Portuguese").

      Hard rules:
      - Be honest and specific — a superficial wording overlap is not enough for "exact" or
        "subtopic"; a real, substantive difference in subject means "new".
      - confidence is 0.0-1.0, reflecting how sure you are in the matchType chosen.
      - reasoning is 1-2 plain sentences naming what specifically drove the call.
      - Output ONLY strict JSON, no prose around it:
        {"matchType": "exact", "confidence": 0.8, "reasoning": "..."}
      """;

  public static String topicMatchUser(String goal, String candidateName, List<String> aliases,
      List<String> subtopics) {
    StringBuilder sb = new StringBuilder();
    sb.append("New goal: ").append(goal == null ? "" : goal.trim()).append('\n');
    sb.append("Candidate existing topic: ").append(candidateName == null ? "" : candidateName).append('\n');
    if (aliases != null && !aliases.isEmpty()) {
      sb.append("Known aliases: ").append(String.join(", ", aliases)).append('\n');
    }
    if (subtopics != null && !subtopics.isEmpty()) {
      sb.append("Known subtopics already covered: ").append(String.join(", ", subtopics)).append('\n');
    }
    sb.append("Write the match judgment as JSON.");
    return sb.toString();
  }

  /**
   * System prompt for topic evolution (RB-3.10) — the founder suggests a specific addition to
   * an existing Canonical Topic (a subtopic, prerequisite, or alias it should now know about).
   * Judge duplicates/relevance/fit before proposing the actual edit; never shown to the founder
   * directly at this stage, so plain and analytical.
   */
  public static final String TOPIC_ADDITION_SYSTEM = """
      The founder wants to add something to an existing canonical topic's known shape — a new
      subtopic, a prerequisite, or an alias. Judge whether it's a genuine, non-duplicate addition,
      and propose the specific edit.

      field is exactly one of "subtopics", "prerequisites", "aliases" — whichever the suggestion
      actually is. value is the specific, cleaned-up text to add (a few plain words, not a
      restatement of the founder's whole sentence).

      Hard rules:
      - isDuplicate: true if this is already covered (near-exactly) by an existing entry in that
        same list — check the existing lists given, not just guess.
      - isRelevant: true only if this genuinely belongs to this topic, not a different one.
      - reasoning: 1-2 plain sentences on the call made.
      - Output ONLY strict JSON, no prose around it:
        {"field": "subtopics", "value": "...", "isDuplicate": false, "isRelevant": true, "reasoning": "..."}
      """;

  public static String topicAdditionUser(String canonicalName, List<String> aliases,
      List<String> subtopics, List<String> prerequisites, String suggestion) {
    StringBuilder sb = new StringBuilder();
    sb.append("Topic: ").append(canonicalName == null ? "" : canonicalName).append('\n');
    if (aliases != null && !aliases.isEmpty()) {
      sb.append("Existing aliases: ").append(String.join(", ", aliases)).append('\n');
    }
    if (subtopics != null && !subtopics.isEmpty()) {
      sb.append("Existing subtopics: ").append(String.join(", ", subtopics)).append('\n');
    }
    if (prerequisites != null && !prerequisites.isEmpty()) {
      sb.append("Existing prerequisites: ").append(String.join(", ", prerequisites)).append('\n');
    }
    sb.append("Founder's suggested addition: ").append(suggestion == null ? "" : suggestion.trim()).append('\n');
    sb.append("Write the proposed edit as JSON.");
    return sb.toString();
  }
}
