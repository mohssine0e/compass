package com.compass.app.resource;

import com.compass.app.events.EventService;
import com.compass.app.resource.dto.EnrichmentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of RES-5's lazy enrichment endpoint: a result is 200, nothing is 204. */
@WebMvcTest(ResourceController.class)
class ResourceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ResourceService service;

    @MockBean
    private ResourceEnrichmentService enrichmentService;

    @MockBean
    private EventService events;

    @Test
    @DisplayName("a produced enrichment returns 200 with the pointer")
    void enrichReturns200WithResult() throws Exception {
        when(enrichmentService.enrich("https://example.com/a", "Ownership"))
                .thenReturn(new EnrichmentResponse("written", "Focus on X.", null, null, null, "fetch_fallback"));

        mvc.perform(post("/resources/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceUrl\":\"https://example.com/a\",\"stepTopic\":\"Ownership\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusPointer").value("Focus on X."))
                .andExpect(jsonPath("$.source").value("fetch_fallback"));
    }

    @Test
    @DisplayName("nothing produced (fetch failed, or AI had nothing useful) returns 204, not an error")
    void enrichReturns204WhenNothingProduced() throws Exception {
        when(enrichmentService.enrich("https://example.com/a", "Ownership")).thenReturn(null);

        mvc.perform(post("/resources/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceUrl\":\"https://example.com/a\",\"stepTopic\":\"Ownership\"}"))
                .andExpect(status().isNoContent());
    }
}
