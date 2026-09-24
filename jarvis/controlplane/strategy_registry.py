"""
Strategy Registry & Dynamic Strategy Fallbacks
Replicates Android StrategyRegistry.kt for Linux Software.
"""

from typing import Dict, List, Callable, Any

class StrategyRegistry:
    shared = None

    def __init__(self):
        self.strategies: Dict[str, List[Callable[..., Any]]] = {}

    def register_strategy(self, category: str, strategy_fn: Callable[..., Any]):
        if category not in self.strategies:
            self.strategies[category] = []
        self.strategies[category].append(strategy_fn)

    def get_strategies(self, category: str) -> List[Callable[..., Any]]:
        return self.strategies.get(category, [])

StrategyRegistry.shared = StrategyRegistry()
