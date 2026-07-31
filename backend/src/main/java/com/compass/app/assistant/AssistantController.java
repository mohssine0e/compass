package com.compass.app.assistant;

import com.compass.app.assistant.dto.ExplainJobResponse;
import com.compass.app.assistant.dto.ExplainRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * In-content AI help (Phase 8.5). The response is shown alongside the original text, never
 * replacing it.
 *
 * <p>Runs as a background job + poll (V3-10), same shape as roadmap generation — this used to be
 * a single blocking call, and the AI layer's own FAST-tier failover can iterate several providers
 * before answering, well past what a synchronous request should ever hold open.
 */
@RestController
@RequestMapping("/ai")
public class AssistantController {

  private final ExplainJobService jobs;

  public AssistantController(ExplainJobService jobs) {
    this.jobs = jobs;
  }

  /**
   * Start help with selected text (explain / translate / example / simplify …). Returns
   * immediately with a job id; poll {@link #explainJob} for the eventual answer.
   */
  @PostMapping("/explain/start")
  public Map<String, String> startExplain(@RequestBody ExplainRequest request) {
    return Map.of("jobId", jobs.start(request));
  }

  /** Poll an explain job's progress. {@code status} is PENDING/DONE/FAILED. */
  @GetMapping("/explain/jobs/{jobId}")
  public ExplainJobResponse explainJob(@PathVariable String jobId) {
    ExplainJob job = jobs.get(jobId);
    return new ExplainJobResponse(job.status().name(), job.result(), job.error());
  }
}
