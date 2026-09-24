from jarvis.foundation.policy_engine import PolicyEngine, AutonomyTier

def test_policy_tier_validation():
    policy = PolicyEngine(active_tier=AutonomyTier.TIER_2_MEDIA_CONTROL)

    # Allowed under Tier 2
    allowed, _ = policy.validate_action("media_play", AutonomyTier.TIER_2_MEDIA_CONTROL)
    assert allowed is True

    # Blocked exceeding Tier 2 (e.g. Tier 5 System Settings)
    allowed, reason = policy.validate_action("set_volume", AutonomyTier.TIER_5_SYSTEM_SETTINGS)
    assert allowed is False
    assert "exceeds active tier" in reason

def test_full_auto_override():
    policy = PolicyEngine(full_auto=True, active_tier=AutonomyTier.TIER_0_READ_ONLY)
    allowed, _ = policy.validate_action("system_reset", AutonomyTier.TIER_9_DEVICE_ADMIN)
    assert allowed is True
