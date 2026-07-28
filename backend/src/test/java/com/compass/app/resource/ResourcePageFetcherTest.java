package com.compass.app.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code htmlToText} is deliberately a regex-based tag strip, not a real HTML parser (RES-3) —
 * this pins down that it does the one job it needs to: drop scripts/styles entirely (their
 * content is never meant to reach a prompt), strip tags, decode the handful of entities worth
 * bothering with, and collapse whitespace into something readable.
 */
class ResourcePageFetcherTest {

    @Test
    @DisplayName("strips tags, leaving the visible text")
    void stripsTags() {
        String html = "<html><body><h1>Title</h1><p>Some <b>bold</b> text.</p></body></html>";
        assertThat(ResourcePageFetcher.htmlToText(html)).contains("Title").contains("Some bold text.");
    }

    @Test
    @DisplayName("drops script and style blocks entirely, including their content")
    void dropsScriptAndStyleContent() {
        String html = "<html><head><style>.a { color: red; }</style>"
                + "<script>alert('should not appear');</script></head>"
                + "<body><p>Real content.</p></body></html>";
        String text = ResourcePageFetcher.htmlToText(html);
        assertThat(text).contains("Real content.");
        assertThat(text).doesNotContain("color: red").doesNotContain("should not appear");
    }

    @Test
    @DisplayName("decodes the common HTML entities")
    void decodesCommonEntities() {
        String html = "<p>Tom &amp; Jerry &mdash;&nbsp;&quot;fun&quot; &lt;tag&gt; isn&#39;t real</p>";
        String text = ResourcePageFetcher.htmlToText(html);
        assertThat(text).contains("Tom & Jerry").contains("\"fun\"").contains("<tag>").contains("isn't real");
    }

    @Test
    @DisplayName("collapses runs of whitespace and blank lines")
    void collapsesWhitespace() {
        String html = "<p>Line   one.</p>\n\n\n\n<p>Line two.</p>";
        String text = ResourcePageFetcher.htmlToText(html);
        assertThat(text).doesNotContain("   ");
        assertThat(text).doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("caps the result length rather than sending a whole long-form page to the model")
    void capsLength() {
        String longBody = "word ".repeat(3000); // well over the 6000-char cap
        String html = "<p>" + longBody + "</p>";
        assertThat(ResourcePageFetcher.htmlToText(html).length()).isLessThanOrEqualTo(6000);
    }

    @Test
    @DisplayName("case-insensitive script/style tag matching (SCRIPT, Style, etc.)")
    void caseInsensitiveScriptStyleMatching() {
        String html = "<SCRIPT>bad();</SCRIPT><Style>.x{}</Style><p>Good.</p>";
        String text = ResourcePageFetcher.htmlToText(html);
        assertThat(text).contains("Good.");
        assertThat(text).doesNotContain("bad()");
    }
}
