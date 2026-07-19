package com.compass.app.verification;

import com.compass.app.verification.dto.VerifyRequest;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Optional step verification (Phase 8): ask for a check, then answer it. Only meaningful for a
 * step whose verify mode (its own or its roadmap's) is light/full.
 */
@RestController
@RequestMapping("/verification/steps/{stepId}")
public class VerificationController {

  private final VerificationService service;

  public VerificationController(VerificationService service) {
    this.service = service;
  }

  /** Generate a check for the step. 503 when the AI can't write one; 400 when it isn't gated. */
  @PostMapping("/check")
  public Map<String, Object> check(@PathVariable Long stepId) {
    return Map.of("question", service.generateCheck(stepId));
  }

  /** Answer the check. On pass the step is marked done; otherwise the gap comes back. */
  @PostMapping("/verify")
  public VerifyResult verify(@PathVariable Long stepId, @RequestBody VerifyRequest request) {
    return service.verify(stepId, request.answer());
  }
}
