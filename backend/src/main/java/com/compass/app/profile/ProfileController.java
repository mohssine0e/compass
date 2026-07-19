package com.compass.app.profile;

import com.compass.app.profile.dto.ProfileResponse;
import com.compass.app.profile.dto.SaveProfileRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

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

    /** Save the reviewed profile and mark it confirmed. Sent from the review screen. */
    @PutMapping
    public ProfileResponse save(@RequestBody SaveProfileRequest request) {
        return ProfileResponse.from(service.save(request));
    }

    /**
     * Upload a PDF/DOCX resume; extract text locally and return the AI-structured
     * {skills, experience, education} as a proposal to review — nothing is saved here, and the
     * raw file is never stored. 503 when AI reading is unavailable (fall back to manual entry).
     */
    @PostMapping(value = "/resume/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> extractResume(@RequestParam("file") MultipartFile file) {
        return service.extractResume(file);
    }
}
