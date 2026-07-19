package com.compass.app.assistant;

import com.compass.app.assistant.dto.ExplainRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * In-content AI help (Phase 8.5). The response is shown alongside the original text, never
 * replacing it — this endpoint only returns help text.
 */
@RestController
@RequestMapping("/ai")
public class AssistantController {

  private final AssistantService service;

  public AssistantController(AssistantService service) {
    this.service = service;
  }

  /** Help with selected text (explain / translate / example / simplify …). 503 when unavailable. */
  @PostMapping("/explain")
  public Map<String, Object> explain(@RequestBody ExplainRequest request) {
    return Map.of("response", service.explain(request));
  }
}
