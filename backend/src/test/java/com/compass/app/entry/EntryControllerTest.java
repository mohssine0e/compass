package com.compass.app.entry;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.config.ApiExceptionHandler;
import com.compass.app.entry.dto.PatchEntryRequest;
import com.compass.app.events.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link EntryController}: status codes and the {@link ApiExceptionHandler}
 * shape for the domain failures the service layer actually throws. No DB, no AI call — {@link
 * EntryService} and {@link AiVoiceService} are mocked.
 */
@WebMvcTest(EntryController.class)
@Import(ApiExceptionHandler.class)
class EntryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EntryService service;

    @MockBean
    private AiVoiceService aiVoice;

    // ApiExceptionHandler depends on EventService; @Import doesn't pull in its own bean graph.
    @MockBean
    private EventService events;

    @Test
    @DisplayName("POST /entries with text creates an idea and returns 201 with an acknowledgment")
    void createReturns201WithAcknowledgment() throws Exception {
        Entry entry = entry(EntryType.IDEA, EntryStatus.CAPTURED);
        when(service.create(any())).thenReturn(entry);
        when(aiVoice.acknowledge(entry)).thenReturn("Held.");

        mvc.perform(post("/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Build a CLI tool\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("idea"))
                .andExpect(jsonPath("$.acknowledgment").value("Held."));
    }

    @Test
    @DisplayName("a blank capture is rejected by the service as 400, in the ProblemDetail shape")
    void blankCaptureIsBadRequest() throws Exception {
        when(service.create(any())).thenThrow(new IllegalArgumentException("An entry needs some text to capture."));

        mvc.perform(post("/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("An entry needs some text to capture."));
    }

    @Test
    @DisplayName("GET /entries lists every entry, newest first, as delivered by the service")
    void listReturnsAllEntries() throws Exception {
        when(service.listAll()).thenReturn(List.of(entry(EntryType.IDEA, EntryStatus.CAPTURED)));

        mvc.perform(get("/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("PATCH marking a step done triggers an acknowledgment; other edits stay quiet")
    void patchToDoneAcknowledges() throws Exception {
        Entry done = entry(EntryType.ROADMAP_STEP, EntryStatus.DONE);
        when(service.update(eq(1L), any(PatchEntryRequest.class))).thenReturn(done);
        when(aiVoice.acknowledge(done)).thenReturn("Marked done.");

        mvc.perform(patch("/entries/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgment").value("Marked done."));
    }

    @Test
    @DisplayName("PATCH to a non-done status never calls the acknowledgment voice")
    void patchToNonDoneStaysQuiet() throws Exception {
        Entry developing = entry(EntryType.IDEA, EntryStatus.DEVELOPING);
        when(service.update(eq(1L), any(PatchEntryRequest.class))).thenReturn(developing);

        mvc.perform(patch("/entries/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"developing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgment").doesNotExist());

        verify(aiVoice, never()).acknowledge(any());
    }

    @Test
    @DisplayName("PATCH on a missing entry surfaces the service's NoSuchElementException as 404")
    void patchMissingEntryIs404() throws Exception {
        when(service.update(eq(999L), any(PatchEntryRequest.class)))
                .thenThrow(new NoSuchElementException("No entry with id 999"));

        mvc.perform(patch("/entries/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("a self-referential prerequisite is rejected as 400 by the same ApiExceptionHandler path")
    void selfReferentialPrerequisiteIsBadRequest() throws Exception {
        when(service.update(eq(1L), any(PatchEntryRequest.class)))
                .thenThrow(new IllegalArgumentException("A step can't be its own prerequisite."));

        mvc.perform(patch("/entries/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dependsOn\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an AI-unavailable failure surfaces as 503, not 500")
    void aiUnavailableSurfacesAs503() throws Exception {
        when(service.startSession(anyLong())).thenThrow(new IllegalStateException("Unavailable right now."));

        mvc.perform(post("/entries/1/sessions/start"))
                .andExpect(status().isServiceUnavailable());
    }

    private static Entry entry(EntryType type, EntryStatus status) {
        Entry e = new Entry();
        e.setType(type);
        e.setStatus(status);
        e.setContent(java.util.Map.of("text", "Build a CLI tool"));
        return e;
    }
}
