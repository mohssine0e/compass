package com.compass.app.ai;

/**
 * Which quota/latency tier an AI call belongs to (Phase 19). {@code FAST} is for short,
 * frequent calls (tone acknowledgments, clarifying questions, verification checks) — cheap
 * and quick, so a snappy provider matters more than depth. {@code HEAVY} is for
 * generation-weight calls (outline drafting, module expansion, self-critique) that need a
 * stronger model and can tolerate a longer wait. Each tier has its own two-provider failover
 * chain (see {@link AiProperties}) — four providers total, not six.
 */
public enum AiTier {
    FAST,
    HEAVY
}
