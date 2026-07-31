package com.compass.app.notifications;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * Everything queued since {@code after} (a client-tracked cursor — the id of the last
     * notification it already showed). Pass 0 on first load to pick up anything queued since
     * boot.
     */
    @GetMapping("/poll")
    public List<Notification> poll(@RequestParam(defaultValue = "0") long after) {
        return service.since(after);
    }

    /**
     * The most recent notifications regardless of any poll cursor, newest first — for a "past
     * notifications" history panel, independent of what's currently shown as a toast.
     */
    @GetMapping("/history")
    public List<Notification> history(@RequestParam(required = false) Integer limit) {
        return service.recent(limit);
    }
}
