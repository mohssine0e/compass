package com.compass.app.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Fetches a resource's real page content and reduces it to plain text (RES-3) — a plain HTTP
 * GET plus regex-based tag stripping, deliberately not a real HTML parser: CLAUDE.md's tech
 * stack doesn't include one, and the task this exists for explicitly says "no new heavy parsing
 * library needed for this." Good enough to hand a Fast-tier model something groundable; not
 * good enough (and not meant) for anything that needs real DOM structure.
 */
@Component
class ResourcePageFetcher {

    private static final Logger log = LoggerFactory.getLogger(ResourcePageFetcher.class);

    // Enough page text for a 2-4 sentence focus pointer to be grounded in something real,
    // without sending a whole long-form article through the Fast tier's token ceiling.
    private static final int MAX_TEXT_CHARS = 6000;

    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final int timeoutSeconds;

    ResourcePageFetcher(@Value("${compass.resource.fetch-timeout-seconds:8}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * The page's visible text, trimmed to a sane length, or {@code null} if the fetch failed
     * (unreachable, blocked, non-HTML, timed out) — never throws into the caller, same
     * best-effort contract as everything else that touches the network in this codebase.
     */
    String fetchText(String url) {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
            factory.setConnectTimeout(millis);
            factory.setReadTimeout(millis);
            RestClient client = RestClient.builder().requestFactory(factory).build();
            String html = client.get()
                    .uri(URI.create(url))
                    // Several sites (correctly) refuse a request with no user agent at all.
                    .header("User-Agent", "Mozilla/5.0 (compatible; CompassBot/1.0)")
                    .retrieve()
                    .body(String.class);
            if (html == null || html.isBlank()) {
                return null;
            }
            String text = htmlToText(html);
            return text.isBlank() ? null : text;
        } catch (RuntimeException ex) {
            log.warn("Resource fetch failed for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    /** Strips scripts/styles/tags, decodes the handful of entities worth bothering with, and collapses whitespace. */
    static String htmlToText(String html) {
        String noScripts = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        String noTags = TAG.matcher(noScripts).replaceAll(" ");
        String decoded = noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        String collapsed = WHITESPACE.matcher(decoded).replaceAll(" ");
        String[] lines = collapsed.split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line.strip()).append('\n');
        }
        String result = BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").strip();
        return result.length() <= MAX_TEXT_CHARS ? result : result.substring(0, MAX_TEXT_CHARS);
    }
}
