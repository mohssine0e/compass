package com.compass.app.roadmap;

import com.compass.app.config.ApiExceptionHandler;
import com.compass.app.config.ConflictException;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.events.EventService;
import com.compass.app.resource.ResourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link RoadmapController} — status codes and the {@link
 * ApiExceptionHandler} shape. {@link RoadmapService} and friends are mocked; {@link
 * com.compass.app.roadmap.dto.RoadmapResponse#of} itself runs for real against a plain in-memory
 * {@link Entry}, since that's the actual serialization path a caller sees.
 */
@WebMvcTest(RoadmapController.class)
@Import(ApiExceptionHandler.class)
class RoadmapControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RoadmapService service;

    @MockBean
    private GenerationJobService jobs;

    @MockBean
    private ModulePrefetchService prefetch;

    @MockBean
    private ResourceService resources;

    @MockBean
    private EventService events;

    @Test
    @DisplayName("GET /roadmaps/{id} returns the roadmap with an empty tree when it has no steps yet")
    void getReturnsRoadmap() throws Exception {
        Entry rust = roadmap(42L, "Learn Rust");
        when(service.getRoadmap(42L)).thenReturn(rust);
        when(service.stepsOf(anyLong())).thenReturn(List.of());

        mvc.perform(get("/roadmaps/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.title").value("Learn Rust"))
                .andExpect(jsonPath("$.children.length()").value(0))
                .andExpect(jsonPath("$.progress.total").value(0));
    }

    @Test
    @DisplayName("GET /roadmaps/{id} for a missing roadmap surfaces the service's 404")
    void getMissingRoadmapIs404() throws Exception {
        when(service.getRoadmap(999L)).thenThrow(new NoSuchElementException("No roadmap with id 999"));

        mvc.perform(get("/roadmaps/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No roadmap with id 999"));
    }

    @Test
    @DisplayName("GET /roadmaps lists active roadmaps, each rendered through the real RoadmapResponse.of")
    void listReturnsActiveRoadmaps() throws Exception {
        Entry rust = roadmap(1L, "Learn Rust");
        Entry go = roadmap(2L, "Learn Go");
        when(service.listRoadmaps()).thenReturn(List.of(rust, go));
        when(service.stepsOf(anyLong())).thenReturn(List.of());

        mvc.perform(get("/roadmaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Learn Rust"))
                .andExpect(jsonPath("$[1].title").value("Learn Go"));
    }

    @Test
    @DisplayName("a drafting-unavailable failure surfaces as 503, matching CLAUDE.md's no-plain-fallback note")
    void expandUnavailableIs503() throws Exception {
        when(service.expandModule(eq(7L), eq(3L))).thenThrow(
                new IllegalStateException("Drafting is unavailable right now — write its steps yourself."));

        mvc.perform(post("/roadmaps/7/modules/3/expand"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Drafting is unavailable right now — write its steps yourself."));
    }

    @Test
    @DisplayName("a conflict-with-current-state failure maps to 409, distinct from the 503 unavailability path")
    void moduleExpansionConflictIs409() throws Exception {
        when(service.expandModule(eq(7L), eq(3L)))
                .thenThrow(new ConflictException("Already broken down as far as it goes."));

        mvc.perform(post("/roadmaps/7/modules/3/expand"))
                .andExpect(status().isConflict());
    }

    // V4-6.1 (2026-07-30 user audit): a blank `goal` is now rejected by @Valid before the
    // request ever reaches GenerationJobService, instead of relying on every caller to remember
    // the same manual check RoadmapGenerationService.generate() already did downstream.
    @Test
    @DisplayName("POST /roadmaps/generate/start with a blank goal is rejected by validation as 400")
    void startGenerationWithBlankGoalIsBadRequest() throws Exception {
        mvc.perform(post("/roadmaps/generate/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jobs);
    }

    @Test
    @DisplayName("POST /roadmaps/generate/start with a real goal reaches the job service")
    void startGenerationWithRealGoalStartsAJob() throws Exception {
        when(jobs.start(org.mockito.ArgumentMatchers.any())).thenReturn("job-1");

        mvc.perform(post("/roadmaps/generate/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"Learn Rust\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    // V4-6.2 (2026-07-30 user audit): a genuinely unmapped exception (not one of
    // ApiExceptionHandler's specific domain handlers) used to fall through to Spring Boot's own
    // default error page instead of this app's consistent ProblemDetail shape.
    @Test
    @DisplayName("an exception with no specific handler still gets the app's ProblemDetail shape, not a raw 500 page")
    void unmappedExceptionGetsProblemDetailShape() throws Exception {
        when(service.getRoadmap(42L)).thenThrow(new RuntimeException("something exploded"));

        mvc.perform(get("/roadmaps/42"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Something went wrong."))
                // The real exception message never leaks into the response body.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("something exploded"))));

        verify(events).systemError(eq("unhandled_exception"), org.mockito.ArgumentMatchers.contains("something exploded"),
                org.mockito.ArgumentMatchers.isNull());
    }

    private static Entry roadmap(Long id, String title) {
        Entry e = mockEntryWithId(id);
        e.setType(EntryType.ROADMAP);
        e.setStatus(EntryStatus.CAPTURED);
        e.setContent(Map.of("title", title));
        return e;
    }

    // Entry#id is JPA-generated with no public setter; a real never-persisted instance can't
    // carry a chosen id for the controller to echo back, so this test needs a light mock instead.
    private static Entry mockEntryWithId(Long id) {
        Entry e = org.mockito.Mockito.spy(new Entry());
        org.mockito.Mockito.doReturn(id).when(e).getId();
        return e;
    }
}
