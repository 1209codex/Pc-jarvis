"""
4D Autonomy Policy Engine (Tiers 0–10)
Replicates Android PolicyEngine.kt for Linux Software.
"""

from enum import IntEnum
import logging

logger = logging.getLogger("PolicyEngine")

class AutonomyTier(IntEnum):
    TIER_0_READ_ONLY = 0
    TIER_1_LOW_RISK_LOCAL = 1
    TIER_2_MEDIA_CONTROL = 2
    TIER_3_FILE_SYSTEM_READ = 3
    TIER_4_MESSAGING_DRAFT = 4
    TIER_5_SYSTEM_SETTINGS = 5
    TIER_6_MESSAGING_SEND = 6
    TIER_7_FILE_SYSTEM_WRITE = 7
    TIER_8_TELEPHONY_CALL = 8
    TIER_9_DEVICE_ADMIN = 9
    TIER_10_UNRESTRICTED_ROOT = 10

class PolicyEngine:
    def __init__(self, full_auto: bool = False, active_tier: AutonomyTier = AutonomyTier.TIER_5_SYSTEM_SETTINGS):
        self.full_auto = full_auto
        self.active_tier = active_tier
        self.security_audit_log = []

    def set_autonomy_tier(self, tier: AutonomyTier):
        logger.info(f"Autonomy Tier updated: {self.active_tier.name} -> {tier.name}")
        self.active_tier = tier

    def validate_action(self, tool_name: str, required_tier: AutonomyTier, is_root_required: bool = False) -> tuple[bool, str]:
        """
        Validates if an action is permitted under the current autonomy policy tier.
        Returns (is_allowed, reason).
        """
        if is_root_required and self.active_tier < AutonomyTier.TIER_10_UNRESTRICTED_ROOT:
            reason = f"Action '{tool_name}' requires Root privileges (Tier 10), current tier is {self.active_tier.name}"
            self._log_audit(tool_name, required_tier, False, reason)
            return False, reason

        if self.full_auto or required_tier <= self.active_tier:
            reason = f"Allowed under {self.active_tier.name}"
            self._log_audit(tool_name, required_tier, True, reason)
            return True, reason
        else:
            reason = f"Action '{tool_name}' requires {required_tier.name}, which exceeds active tier {self.active_tier.name}"
            self._log_audit(tool_name, required_tier, False, reason)
            return False, reason

    def _log_audit(self, tool_name: str, required_tier: AutonomyTier, allowed: bool, reason: str):
        entry = {
            "tool": tool_name,
            "tier": required_tier.name,
            "allowed": allowed,
            "reason": reason
        }
        self.security_audit_log.append(entry)
        if len(self.security_audit_log) > 500:
            self.security_audit_log.pop(0)
