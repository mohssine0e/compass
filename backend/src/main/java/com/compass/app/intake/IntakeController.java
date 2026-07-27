package com.compass.app.intake;

import com.compass.app.ai.IntentAiService;
import com.compass.app.intake.dto.ClassifyIntentRequest;
import com.compass.app.intake.dto.ClassifyIntentResponse;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The unified intake's first step (RB-5.2): classify before anything else runs. */
@RestController
@RequestMapping("/intake")
public class IntakeController {

    private final IntentAiService intentAi;
    private final ProfileService profileService;

    public IntakeController(IntentAiService intentAi, ProfileService profileService) {
        this.intentAi = intentAi;
        this.profileService = profileService;
    }

    @PostMapping("/classify")
    public ClassifyIntentResponse classify(@RequestBody ClassifyIntentRequest request) {
        String input = request.input() == null ? "" : request.input().trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Say something first.");
        }
        String profileContext = profileService.confirmedProfile()
                .map(ProfileContext::forPrompt)
                .orElse(null);
        IntentAiService.IntentClassification result = intentAi.classifyIntent(input, profileContext);
        if (result == null) {
            throw new IllegalStateException("Classification is unavailable right now.");
        }
        return ClassifyIntentResponse.from(result);
    }
}
