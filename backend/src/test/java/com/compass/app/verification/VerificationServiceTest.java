package com.compass.app.verification;

import com.compass.app.ai.AiVoiceWorker;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.VerificationAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.roadmap.RoadmapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A verification pass used to produce no acknowledgment at all — {@link VerificationService}
 * saved the step directly rather than going through {@code EntryController.patch()}, the only
 * path that called the async voice-ack worker. Fixed by having {@link #verify} call
 * {@link AiVoiceWorker} itself on a pass, same as the self-report ("mark done anyway") path
 * already did. This test is the regression guard for that fix, not a full behavioral suite for
 * the rest of the service.
 */
class VerificationServiceTest {

    private EntryRepository repository;
    private VerificationAiService verifyAi;
    private AiVoiceWorker aiVoiceWorker;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(EntryRepository.class);
        verifyAi = mock(VerificationAiService.class);
        RoadmapAiService roadmapAi = mock(RoadmapAiService.class);
        RoadmapService roadmapService = mock(RoadmapService.class);
        aiVoiceWorker = mock(AiVoiceWorker.class);
        service = new VerificationService(repository, verifyAi, roadmapAi, roadmapService, aiVoiceWorker);

        when(repository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findAncestors(anyLong())).thenReturn(List.of());
    }

    private static Entry stepWithPendingCheck(Map<String, Object> pendingCheck) {
        Entry step = org.mockito.Mockito.spy(new Entry());
        org.mockito.Mockito.doReturn(1L).when(step).getId();
        step.setType(EntryType.ROADMAP_STEP);
        step.setStatus(EntryStatus.CAPTURED);
        step.setContent(new java.util.HashMap<>(Map.of("text", "Ownership basics", "pendingCheck", pendingCheck)));
        return step;
    }

    @Test
    @DisplayName("a passed free-text check marks the step done AND queues an async acknowledgment")
    void passedCheckQueuesAcknowledgment() {
        Entry step = stepWithPendingCheck(Map.of("format", "free_response", "question", "Why does ownership matter?"));
        when(repository.findById(1L)).thenReturn(Optional.of(step));
        when(verifyAi.isAvailable()).thenReturn(true);
        when(verifyAi.evaluate(any(), any(), any()))
                .thenReturn(new VerificationAiService.Evaluation(true, null));

        var result = service.verify(1L, "because it prevents data races", null);

        assertThat(result.passed()).isTrue();
        assertThat(step.getStatus()).isEqualTo(EntryStatus.DONE);
        verify(aiVoiceWorker).acknowledgeAsync(step);
    }

    @Test
    @DisplayName("a failed check does NOT queue an acknowledgment — nothing was completed")
    void failedCheckDoesNotQueueAcknowledgment() {
        Entry step = stepWithPendingCheck(Map.of("format", "free_response", "question", "Why does ownership matter?"));
        when(repository.findById(1L)).thenReturn(Optional.of(step));
        when(verifyAi.isAvailable()).thenReturn(true);
        when(verifyAi.evaluate(any(), any(), any()))
                .thenReturn(new VerificationAiService.Evaluation(false, "Missed the borrow-checker angle."));

        var result = service.verify(1L, "not sure", null);

        assertThat(result.passed()).isFalse();
        assertThat(step.getStatus()).isNotEqualTo(EntryStatus.DONE);
        verify(aiVoiceWorker, never()).acknowledgeAsync(any());
    }

    @Test
    @DisplayName("a passed multiple-choice check (no AI grading call) also queues an acknowledgment")
    void passedMultipleChoiceQueuesAcknowledgmentToo() {
        Entry step = stepWithPendingCheck(Map.of(
                "format", "multiple_choice",
                "question", "Which keyword marks a mutable borrow?",
                "options", List.of("&", "&mut", "mut", "ref"),
                "correctIndex", 1));
        when(repository.findById(1L)).thenReturn(Optional.of(step));

        var result = service.verify(1L, null, 1);

        assertThat(result.passed()).isTrue();
        verify(aiVoiceWorker).acknowledgeAsync(step);
    }
}
