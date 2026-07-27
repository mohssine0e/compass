package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * Confirm a re-tier proposal (RB-2.5) — the founder's (possibly edited) groups/order from a
 * prior {@code POST /roadmaps/{id}/re-tier} proposal response, applied for real. {@code kind}
 * must match the proposal's {@code kind} ("regroup" or "arc_order").
 */
public record ApplyReTierProposalRequest(String kind, List<ReTierResponse.Group> groups) {
}
