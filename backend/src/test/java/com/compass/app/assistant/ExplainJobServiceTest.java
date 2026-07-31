package com.compass.app.assistant;

import com.compass.app.assistant.dto.ExplainRequest;
import com.compass.app.events.EventService;
import com.compass.app.notifications.Notification;
import com.compass.app.notifications.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explain (V3-10) used to be a single blocking call, exactly the same problem capture/mark-done
 * had — the FAST-tier failover chain can take well past what a synchronous request should hold
 * open. This covers the same background-job contract {@code GenerationJobService}/
 * {@code ModulePrefetchService} already establish: starts PENDING, resolves to DONE/FAILED off
 * the calling thread, and pushes a notification either way so a founder who's navigated away
 * still finds out.
 */
class ExplainJobServiceTest {

    private static ExplainRequest request(Long stepId) {
        return new ExplainRequest("borrow checker",
                new ExplainRequest.ExplainContext(stepId, null, "brief", "explain", null));
    }

    @Test
    @DisplayName("a successful explain completes the job DONE and pushes an info notification")
    void successfulExplainCompletesJobAndNotifies() {
        AssistantService assistantService = mock(AssistantService.class);
        NotificationService notifications = mock(NotificationService.class);
        EventService events = mock(EventService.class);
        when(assistantService.explain(any())).thenReturn("Borrows are checked references.");

        ExplainWorker worker = new ExplainWorker(assistantService, notifications, events);
        ExplainJobService service = new ExplainJobService(worker);

        String jobId = service.start(request(42L));
        ExplainJob job = awaitDone(service, jobId);

        assertThat(job.status()).isEqualTo(ExplainJob.Status.DONE);
        assertThat(job.result()).isEqualTo("Borrows are checked references.");
        org.mockito.Mockito.verify(notifications)
                .push("Borrows are checked references.", java.util.Map.of("stepId", 42L));
    }

    @Test
    @DisplayName("a failed explain fails the job and pushes a danger-toned notification")
    void failedExplainFailsJobAndNotifiesWithDangerTone() {
        AssistantService assistantService = mock(AssistantService.class);
        NotificationService notifications = mock(NotificationService.class);
        EventService events = mock(EventService.class);
        when(assistantService.explain(any()))
                .thenThrow(new IllegalStateException("Help is unavailable right now."));

        ExplainWorker worker = new ExplainWorker(assistantService, notifications, events);
        ExplainJobService service = new ExplainJobService(worker);

        String jobId = service.start(request(null));
        ExplainJob job = awaitDone(service, jobId);

        assertThat(job.status()).isEqualTo(ExplainJob.Status.FAILED);
        assertThat(job.error()).isEqualTo("Help is unavailable right now.");
        org.mockito.Mockito.verify(notifications)
                .push("Help is unavailable right now.", "danger", null);
    }

    @Test
    @DisplayName("a long answer is truncated for the notification but not for the job result itself")
    void longAnswerTruncatedOnlyForTheNotification() {
        AssistantService assistantService = mock(AssistantService.class);
        NotificationService notifications = mock(NotificationService.class);
        EventService events = mock(EventService.class);
        String longAnswer = "x".repeat(500);
        when(assistantService.explain(any())).thenReturn(longAnswer);

        ExplainWorker worker = new ExplainWorker(assistantService, notifications, events);
        ExplainJobService service = new ExplainJobService(worker);

        String jobId = service.start(request(null));
        ExplainJob job = awaitDone(service, jobId);

        assertThat(job.result()).isEqualTo(longAnswer);
        org.mockito.Mockito.verify(notifications).push(
                org.mockito.ArgumentMatchers.argThat(msg -> msg.length() < longAnswer.length() && msg.endsWith("…")),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("an unknown or swept job id throws rather than returning a phantom PENDING job")
    void unknownJobIdThrows() {
        AssistantService assistantService = mock(AssistantService.class);
        NotificationService notifications = mock(NotificationService.class);
        EventService events = mock(EventService.class);
        ExplainJobService service = new ExplainJobService(new ExplainWorker(assistantService, notifications, events));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                java.util.NoSuchElementException.class, () -> service.get("does-not-exist")))
                .hasMessageContaining("does-not-exist");
    }

    /** No Awaitility in this project's dependency set — a plain poll loop, same as elsewhere. */
    private static ExplainJob awaitDone(ExplainJobService service, String jobId) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            ExplainJob job = service.get(jobId);
            if (job.status() != ExplainJob.Status.PENDING) {
                return job;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }
        throw new AssertionError("Job " + jobId + " never left PENDING within 2s");
    }
}
