package com.compass.app.ai;

import com.compass.app.ai.dto.ClassificationResult;
import com.compass.app.ai.dto.ClassifyGoalRequest;
import com.compass.app.ai.dto.TierTestCaseResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway debug screen's backend (RB-1.2) for the Roadmap Brain tier classifier — same
 * spirit as {@link com.compass.app.events.AdminEventController}: no authentication (Compass is
 * single-user, CLAUDE.md "No auth yet"), visible in the browser, clearly separate from the real
 * app's Capture/Draft-with-AI flow. Calls {@link RoadmapAiService#classifyTier} directly; does
 * not touch {@link com.compass.app.roadmap.RoadmapService#generate} or any real roadmap-creation
 * path. Fine to delete once RB-1 is validated and reviewed, or keep as a standing prompt-tuning
 * tool — founder's call, not decided here.
 */
@RestController
@RequestMapping("/admin/classify-test")
public class AdminClassifyTestController {

    private final RoadmapAiService ai;

    public AdminClassifyTestController(RoadmapAiService ai) {
        this.ai = ai;
    }

    /** Classify one arbitrary goal, typed into the debug screen's textarea. */
    @PostMapping("/classify")
    public ClassificationResult classify(@RequestBody ClassifyGoalRequest request) {
        String goal = request.goal() == null ? "" : request.goal().trim();
        if (goal.isEmpty()) {
            throw new IllegalArgumentException("Goal text is required.");
        }
        RoadmapAiService.TierClassification result = ai.classifyTier(goal, null);
        if (result == null) {
            throw new IllegalStateException("Tier classification is unavailable right now.");
        }
        return ClassificationResult.of(goal, result);
    }

    /**
     * Run the exact 26-goal set from {@code TASKS_v2.md} RB-1.3 in one click. A goal that fails
     * to classify (provider down, unparseable reply) still gets a row — surfaced as its own
     * result rather than aborting the whole batch, since a single flaky call shouldn't hide the
     * other 25 results while iterating on the prompt.
     *
     * <p>Paced with a gap between calls: at ~1300 tokens/call, Groq's free-tier 12000 TPM budget
     * on the FAST tier only sustains ~9 calls/minute, so 26 back-to-back calls (and the Gemini
     * fallback returning its own persistent 0-quota error, observed separately — a standing
     * provider-config issue, not caused by this screen) were turning real classification results
     * into rate-limit noise during RB-1.4 iteration. This is a debug-tool reliability fix, not a
     * change to the classifier itself.
     */
    @PostMapping("/run-all")
    public List<TierTestCaseResult> runAll() {
        List<TierTestCaseResult> results = new ArrayList<>();
        for (TierClassifierTestSet.Case testCase : TierClassifierTestSet.CASES) {
            if (!results.isEmpty()) {
                sleepBetweenCalls();
            }
            results.add(classifyCase(testCase));
        }
        return results;
    }

    private static void sleepBetweenCalls() {
        try {
            Thread.sleep(7000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TierTestCaseResult classifyCase(TierClassifierTestSet.Case testCase) {
        String expected = testCase.expectedTier() == null ? null : testCase.expectedTier().name();
        RoadmapAiService.TierClassification result = ai.classifyTier(testCase.goal(), null);
        if (result == null) {
            return new TierTestCaseResult(testCase.goal(), expected, "ERROR", 0.0,
                    "Classification failed (provider unavailable or unparseable reply).",
                    expected == null ? null : false);
        }
        String actual = result.tier().name();
        Boolean pass = expected == null ? null : expected.equals(actual);
        return new TierTestCaseResult(testCase.goal(), expected, actual, result.confidence(),
                result.reasoning(), pass);
    }
}
