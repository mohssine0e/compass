package com.compass.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling is enabled for the system_events retention prune (Phase 5) and the generation-job
// sweep (Phase 18); async is enabled so a roadmap-generation job can run in the background while
// the caller polls its progress instead of blocking on one slow AI call (Phase 18).
@EnableScheduling
@EnableAsync
@SpringBootApplication
public class CompassApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompassApplication.class, args);
    }
}
