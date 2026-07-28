package com.compass.app.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-4's parsing logic, pinned down against synthetic fixtures rather than live YouTube pages —
 * this codebase never spends live network calls/quota in a test (see the memory note on the
 * matter). {@code isYouTubeUrl}/{@code extractVideoId}/{@code findCaptionsBaseUrl}/
 * {@code parseTimedTextXml} are the pure pieces; {@code fetchTranscript} itself (which does real
 * HTTP) is exercised only through {@link ResourceEnrichmentServiceTest}'s mocked fetcher.
 */
class YouTubeTranscriptFetcherTest {

    private final YouTubeTranscriptFetcher fetcher = new YouTubeTranscriptFetcher(8);

    @Test
    @DisplayName("recognizes youtube.com and youtu.be urls, rejects everything else")
    void isYouTubeUrlRecognizesKnownHosts() {
        assertThat(fetcher.isYouTubeUrl("https://www.youtube.com/watch?v=abc123DEF45")).isTrue();
        assertThat(fetcher.isYouTubeUrl("https://youtu.be/abc123DEF45")).isTrue();
        assertThat(fetcher.isYouTubeUrl("https://example.com/article")).isFalse();
        assertThat(fetcher.isYouTubeUrl(null)).isFalse();
    }

    @Test
    @DisplayName("extracts the video id from every common url shape")
    void extractVideoIdHandlesCommonShapes() {
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://www.youtube.com/watch?v=abc123DEF45"))
                .isEqualTo("abc123DEF45");
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://www.youtube.com/watch?v=abc123DEF45&t=30s"))
                .isEqualTo("abc123DEF45");
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://youtu.be/abc123DEF45"))
                .isEqualTo("abc123DEF45");
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://www.youtube.com/embed/abc123DEF45"))
                .isEqualTo("abc123DEF45");
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://www.youtube.com/shorts/abc123DEF45"))
                .isEqualTo("abc123DEF45");
        assertThat(YouTubeTranscriptFetcher.extractVideoId("https://example.com/not-youtube")).isNull();
        assertThat(YouTubeTranscriptFetcher.extractVideoId(null)).isNull();
    }

    @Test
    @DisplayName("finds the English caption track's baseUrl inside ytInitialPlayerResponse, brace-matched even with nested braces/semicolons in strings")
    void findCaptionsBaseUrlLocatesEnglishTrack() {
        String html = "<html><body><script>var ytInitialPlayerResponse = {\"videoDetails\": "
                + "{\"title\": \"A title; with a semicolon and a {brace}\"}, \"captions\": "
                + "{\"playerCaptionsTracklistRenderer\": {\"captionTracks\": ["
                + "{\"baseUrl\": \"https://youtube.com/api/timedtext?lang=fr&v=abc\", \"languageCode\": \"fr\"},"
                + "{\"baseUrl\": \"https://youtube.com/api/timedtext?lang=en&v=abc\", \"languageCode\": \"en\"}"
                + "]}}};</script></body></html>";

        String baseUrl = YouTubeTranscriptFetcher.findCaptionsBaseUrl(html);

        assertThat(baseUrl).contains("lang=en");
    }

    @Test
    @DisplayName("falls back to the first available track when nothing is English")
    void findCaptionsBaseUrlFallsBackWhenNoEnglishTrack() {
        String html = "var ytInitialPlayerResponse = {\"captions\": {\"playerCaptionsTracklistRenderer\": "
                + "{\"captionTracks\": [{\"baseUrl\": \"https://youtube.com/api/timedtext?lang=de\", "
                + "\"languageCode\": \"de\"}]}}};";

        assertThat(YouTubeTranscriptFetcher.findCaptionsBaseUrl(html)).contains("lang=de");
    }

    @Test
    @DisplayName("strips an existing fmt param so the response comes back as plain timed-text xml")
    void findCaptionsBaseUrlStripsFmtParam() {
        String html = "var ytInitialPlayerResponse = {\"captions\": {\"playerCaptionsTracklistRenderer\": "
                + "{\"captionTracks\": [{\"baseUrl\": \"https://youtube.com/api/timedtext?v=abc&fmt=srv3&lang=en\", "
                + "\"languageCode\": \"en\"}]}}};";

        assertThat(YouTubeTranscriptFetcher.findCaptionsBaseUrl(html)).doesNotContain("fmt=srv3");
    }

    @Test
    @DisplayName("a video with captions disabled (no captions key at all) yields no baseUrl")
    void findCaptionsBaseUrlReturnsNullWhenNoCaptions() {
        String html = "var ytInitialPlayerResponse = {\"videoDetails\": {\"title\": \"No captions here\"}};";

        assertThat(YouTubeTranscriptFetcher.findCaptionsBaseUrl(html)).isNull();
    }

    @Test
    @DisplayName("a page with no ytInitialPlayerResponse at all (structure changed, or blocked) degrades to null")
    void findCaptionsBaseUrlReturnsNullWhenMarkerMissing() {
        assertThat(YouTubeTranscriptFetcher.findCaptionsBaseUrl("<html><body>nothing here</body></html>")).isNull();
    }

    @Test
    @DisplayName("parses the timed-text xml into real start/end segments, decoding entities")
    void parseTimedTextXmlParsesSegments() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><transcript>"
                + "<text start=\"1.5\" dur=\"3.2\">Hello &amp; welcome</text>"
                + "<text start=\"120.0\" dur=\"8.5\">ownership and borrowing explained</text>"
                + "</transcript>";

        List<YouTubeTranscriptFetcher.Segment> segments = YouTubeTranscriptFetcher.parseTimedTextXml(xml);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).startSeconds()).isEqualTo(1.5);
        assertThat(segments.get(0).endSeconds()).isCloseTo(4.7, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(segments.get(0).text()).isEqualTo("Hello & welcome");
        assertThat(segments.get(1).startSeconds()).isEqualTo(120.0);
        assertThat(segments.get(1).text()).isEqualTo("ownership and borrowing explained");
    }

    @Test
    @DisplayName("malformed xml degrades to an empty list rather than throwing")
    void parseTimedTextXmlDegradesOnMalformedInput() {
        assertThat(YouTubeTranscriptFetcher.parseTimedTextXml("not xml at all")).isEmpty();
    }

    @Test
    @DisplayName("a document with no <text> elements at all yields an empty list")
    void parseTimedTextXmlHandlesEmptyTranscript() {
        assertThat(YouTubeTranscriptFetcher.parseTimedTextXml("<transcript></transcript>")).isEmpty();
    }
}
