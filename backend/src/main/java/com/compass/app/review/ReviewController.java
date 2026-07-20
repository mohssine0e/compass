package com.compass.app.review;

import com.compass.app.review.dto.ReviewResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-thread depth (Phase 10): recurring patterns and a self-talk review of where things
 * stand. On demand — the view asks for it, it isn't pushed.
 */
@RestController
@RequestMapping("/review")
public class ReviewController {

  private final ReviewService service;

  public ReviewController(ReviewService service) {
    this.service = service;
  }

  @GetMapping
  public ReviewResult review() {
    return service.review();
  }
}
