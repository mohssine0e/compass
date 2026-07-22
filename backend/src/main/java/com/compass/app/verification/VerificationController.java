package com.compass.app.verification;

import com.compass.app.verification.dto.CheckResult;
import com.compass.app.verification.dto.VerifyRequest;
import com.compass.app.verification.dto.VerifyResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Optional step verification (Phase 8, format variety added Phase 26): ask for a check, then
 * answer it. Only meaningful for a step whose verify mode (its own or its roadmap's) is
 * light/full.
 */
@RestController
@RequestMapping("/verification/steps/{stepId}")
public class VerificationController {

  private final VerificationService service;

  public VerificationController(VerificationService service) {
    this.service = service;
  }

  /** The step's auto-detected default check format (Phase 26), so the founder can preview it. */
  @GetMapping("/default-format")
  public Map<String, String> defaultFormat(@PathVariable Long stepId) {
    return Map.of("format", service.defaultFormat(stepId));
  }

  /**
   * Generate a check for the step, in {@code format} if given (else the step's auto-detected
   * default — Phase 26). 503 when the AI can't write one; 400 when it isn't gated or the format
   * is unrecognized.
   */
  @PostMapping("/check")
  public CheckResult check(@PathVariable Long stepId,
                           @RequestParam(required = false) String format) {
    return service.generateCheck(stepId, format);
  }

  /** Answer the check. On pass the step is marked done; otherwise the gap comes back. */
  @PostMapping("/verify")
  public VerifyResult verify(@PathVariable Long stepId, @RequestBody VerifyRequest request) {
    return service.verify(stepId, request.answer(), request.selectedIndex());
  }
}
