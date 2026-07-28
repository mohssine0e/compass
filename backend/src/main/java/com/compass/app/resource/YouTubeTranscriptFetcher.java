package com.compass.app.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's public transcript/captions, via the same unofficial mechanism tools like
 * youtube-transcript-api use (RESSOURCE_BRAIN_TASKS.md decision #3, RES-4): the watch page
 * embeds a {@code ytInitialPlayerResponse} JSON blob naming each available caption track's own
 * fetch URL, and that URL (with no {@code fmt} param) returns a plain timed-text XML document —
 * no API key, no official quota consumed. Deliberately not the official YouTube Data API, which
 * is quota-metered and needs a key; acceptable for personal, low-volume use, but this isn't a
 * documented/guaranteed integration and can break if YouTube changes the page's structure.
 *
 * <p>Every step here degrades to {@code null} rather than throwing — a video with captions
 * disabled, a page structure YouTube has since changed, a network hiccup, all read the same to
 * the caller: no transcript, fall back to the honest description-only pointer (RES-4).
 */
@Component
class YouTubeTranscriptFetcher {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTranscriptFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|v/)|youtu\\.be/)([A-Za-z0-9_-]{6,})");
    private static final Pattern PLAYER_RESPONSE_MARKER =
            Pattern.compile("ytInitialPlayerResponse\\s*=\\s*\\{");

    private final int timeoutSeconds;

    YouTubeTranscriptFetcher(@Value("${compass.resource.fetch-timeout-seconds:8}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    boolean isYouTubeUrl(String url) {
        return url != null && (url.contains("youtube.com/") || url.contains("youtu.be/"));
    }

    /** The video id from any of the common YouTube url shapes, or {@code null} if not found. */
    static String extractVideoId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = VIDEO_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The full transcript as timed segments (seconds, not milliseconds), or {@code null} when
     * the video has no captions, the page structure couldn't be read, or any step failed —
     * never partial/best-guess data.
     */
    List<Segment> fetchTranscript(String videoUrl) {
        String videoId = extractVideoId(videoUrl);
        if (videoId == null) {
            return null;
        }
        try {
            String watchPageHtml = get("https://www.youtube.com/watch?v=" + videoId);
            if (watchPageHtml == null) {
                return null;
            }
            String captionsBaseUrl = findCaptionsBaseUrl(watchPageHtml);
            if (captionsBaseUrl == null) {
                return null;
            }
            String transcriptXml = get(captionsBaseUrl);
            if (transcriptXml == null) {
                return null;
            }
            List<Segment> segments = parseTimedTextXml(transcriptXml);
            return segments.isEmpty() ? null : segments;
        } catch (RuntimeException ex) {
            log.warn("YouTube transcript fetch failed for {}: {}", videoId, ex.getMessage());
            return null;
        }
    }

    private String get(String url) {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
            factory.setConnectTimeout(millis);
            factory.setReadTimeout(millis);
            return RestClient.builder().requestFactory(factory).build()
                    .get()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; CompassBot/1.0)")
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            log.warn("Fetch failed for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    /**
     * Finds {@code ytInitialPlayerResponse}'s JSON value by brace-counting from its opening
     * {@code {} rather than a regex over the whole blob — the JSON itself can contain semicolons
     * and nested braces inside string values, which a naive "up to the next ;" match would cut
     * short on. Returns the first usable caption track's {@code baseUrl} (preferring an English
     * one when more than one language is available), or {@code null} if the video has no
     * captions or the page didn't contain the expected structure at all.
     */
    static String findCaptionsBaseUrl(String html) {
        Matcher marker = PLAYER_RESPONSE_MARKER.matcher(html);
        if (!marker.find()) {
            return null;
        }
        int openBrace = marker.end() - 1; // the '{' itself
        int end = matchingBraceIndex(html, openBrace);
        if (end < 0) {
            return null;
        }
        String json = html.substring(openBrace, end + 1);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            return null;
        }
        JsonNode tracks = root.path("captions").path("playerCaptionsTracklistRenderer").path("captionTracks");
        if (!tracks.isArray() || tracks.isEmpty()) {
            return null;
        }
        JsonNode chosen = null;
        for (JsonNode track : tracks) {
            String lang = track.path("languageCode").asText("");
            if (lang.startsWith("en")) {
                chosen = track;
                break;
            }
            if (chosen == null) {
                chosen = track;
            }
        }
        String baseUrl = chosen.path("baseUrl").asText(null);
        // The default response is XML unless a `fmt` param says otherwise — strip any that's
        // already there so we always get the plain <text start dur> shape parseTimedTextXml
        // expects, regardless of what the page happened to embed.
        return baseUrl == null ? null : baseUrl.replaceAll("(?i)[&?]fmt=[^&]*", "");
    }

    /** The index of the {@code {}'s matching closing brace, respecting string/escape state, or -1. */
    private static int matchingBraceIndex(String s, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** {@code <transcript><text start="1.23" dur="4.56">caption &amp; text</text>...</transcript>}. */
    static List<Segment> parseTimedTextXml(String xml) {
        List<Segment> segments = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Defused against XXE — this parses a remote, third-party response.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList textNodes = doc.getElementsByTagName("text");
            for (int i = 0; i < textNodes.getLength(); i++) {
                Element el = (Element) textNodes.item(i);
                double start = parseDouble(el.getAttribute("start"));
                double dur = parseDouble(el.getAttribute("dur"));
                String text = el.getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
                segments.add(new Segment(start, start + dur, text.strip()));
            }
        } catch (Exception ex) {
            log.warn("Could not parse YouTube timed-text XML: {}", ex.getMessage());
            return List.of();
        }
        return segments;
    }

    private static double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? 0.0 : Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /** One caption line with its real start/end (seconds) from the video's own transcript. */
    record Segment(double startSeconds, double endSeconds, String text) {
    }
}
