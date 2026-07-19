package com.compass.app.profile;

import com.compass.app.profile.dto.ProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner profile (Phase 6). Single-user, no auth, same as the rest of the app
 * (CLAUDE.md "No auth yet").
 */
@RestController
@RequestMapping("/profile")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    /** The current profile — an empty, unconfirmed one until the founder fills it in. */
    @GetMapping
    public ProfileResponse get() {
        return ProfileResponse.from(service.getOrCreate());
    }
}
