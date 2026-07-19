package com.compass.app.reformulate;

import com.compass.app.reformulate.dto.ApplyReformulateRequest;
import com.compass.app.reformulate.dto.ReformulateProposal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-initiated reformulation of a step (Phase 8.5): propose a change, then apply the approved
 * one. Nothing is edited until {@code /apply}.
 */
@RestController
@RequestMapping("/reformulate/steps/{stepId}")
public class ReformulateController {

  private final ReformulateService service;

  public ReformulateController(ReformulateService service) {
    this.service = service;
  }

  /** Draft a reformulation. {@code kind} is break_down / add_prerequisite / easier_resources. */
  @PostMapping
  public ReformulateProposal propose(@PathVariable Long stepId, @RequestParam String kind) {
    return service.propose(stepId, kind);
  }

  /** Apply the approved reformulation. */
  @PostMapping("/apply")
  public void apply(@PathVariable Long stepId, @RequestBody ApplyReformulateRequest request) {
    service.apply(stepId, request);
  }
}
