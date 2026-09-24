"""
4D Autonomy Policy Engine
"""

import logging

logger = logging.getLogger("PolicyEngine")

class PolicyEngine:
    def __init__(self, full_auto: bool = False):
        self.full_auto = full_auto
        self.security_audit_log = []

    def set_autonomy(self, full_auto: bool):
        logger.info(f"Autonomy updated: full_auto={full_auto}")
        self.full_auto = full_auto

    def validate_action(self, tool_name: str, is_root_required: bool = False) -> tuple[bool, str]:
        """
        Validates if an action is permitted under the current autonomy policy.
        """
        if is_root_required and not self.full_auto:
            return False, "Root actions require full_auto"
        return True, "Allowed"
