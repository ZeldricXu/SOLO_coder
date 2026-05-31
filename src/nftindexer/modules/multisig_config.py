from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from enum import Enum

from ..utils import get_logger

logger = get_logger(__name__)


class MultiSigStrategyType(str, Enum):
    DEFAULT = "default"
    CONSERVATIVE = "conservative"
    FAST_TRACK = "fast_track"
    HIGH_SECURITY = "high_security"
    CUSTOM = "custom"


@dataclass
class MultiSigStrategyConfig:
    strategy_type: MultiSigStrategyType = MultiSigStrategyType.DEFAULT
    min_signers: int = 2
    max_signers: int = 20
    default_confirmations_required: int = 2
    proposal_expiry_blocks: int = 10000
    execution_timeout: int = 300
    max_retry_attempts: int = 3
    auto_execute_on_threshold: bool = True
    require_sequential_nonce: bool = True
    allow_batch_execution: bool = False
    signature_expiry_blocks: Optional[int] = None
    description: str = ""


class IMultiSigConfigStrategy(ABC):
    @abstractmethod
    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        ...

    @abstractmethod
    def get_strategy_type(self) -> MultiSigStrategyType:
        ...

    @abstractmethod
    def validate_wallet_creation(
        self, chain_id: int, signers: List[str], threshold: int
    ) -> bool:
        ...

    @abstractmethod
    def should_auto_execute(
        self, current_signatures: int, required_threshold: int, context: Optional[Dict[str, Any]] = None
    ) -> bool:
        ...


class DefaultMultiSigStrategy(IMultiSigConfigStrategy):
    def __init__(self, base_config: Optional[MultiSigStrategyConfig] = None):
        self._config = base_config or MultiSigStrategyConfig(
            strategy_type=MultiSigStrategyType.DEFAULT,
            description="Default multi-sig strategy with balanced security and usability",
        )

    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        if context and "chain_id" in context:
            chain_id = context["chain_id"]
            adjusted = MultiSigStrategyConfig(**self._config.__dict__)
            if chain_id in [1, 137, 42161]:
                adjusted.proposal_expiry_blocks = 5000
            return adjusted
        return self._config

    def get_strategy_type(self) -> MultiSigStrategyType:
        return MultiSigStrategyType.DEFAULT

    def validate_wallet_creation(
        self, chain_id: int, signers: List[str], threshold: int
    ) -> bool:
        if threshold < self._config.min_signers:
            return False
        if threshold > len(signers):
            return False
        if len(signers) > self._config.max_signers:
            return False
        return True

    def should_auto_execute(
        self, current_signatures: int, required_threshold: int, context: Optional[Dict[str, Any]] = None
    ) -> bool:
        return self._config.auto_execute_on_threshold and current_signatures >= required_threshold


class ConservativeMultiSigStrategy(IMultiSigConfigStrategy):
    def __init__(self):
        self._config = MultiSigStrategyConfig(
            strategy_type=MultiSigStrategyType.CONSERVATIVE,
            min_signers=3,
            max_signers=10,
            default_confirmations_required=3,
            proposal_expiry_blocks=20000,
            execution_timeout=600,
            max_retry_attempts=1,
            auto_execute_on_threshold=False,
            require_sequential_nonce=True,
            allow_batch_execution=False,
            signature_expiry_blocks=10000,
            description="Conservative strategy with higher security requirements",
        )

    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        return self._config

    def get_strategy_type(self) -> MultiSigStrategyType:
        return MultiSigStrategyType.CONSERVATIVE

    def validate_wallet_creation(
        self, chain_id: int, signers: List[str], threshold: int
    ) -> bool:
        if threshold < self._config.min_signers:
            return False
        if threshold > len(signers):
            return False
        if len(signers) > self._config.max_signers:
            return False
        if threshold < len(signers) // 2 + 1:
            return False
        return True

    def should_auto_execute(
        self, current_signatures: int, required_threshold: int, context: Optional[Dict[str, Any]] = None
    ) -> bool:
        return False


class FastTrackMultiSigStrategy(IMultiSigConfigStrategy):
    def __init__(self):
        self._config = MultiSigStrategyConfig(
            strategy_type=MultiSigStrategyType.FAST_TRACK,
            min_signers=1,
            max_signers=10,
            default_confirmations_required=1,
            proposal_expiry_blocks=1000,
            execution_timeout=60,
            max_retry_attempts=5,
            auto_execute_on_threshold=True,
            require_sequential_nonce=False,
            allow_batch_execution=True,
            description="Fast track strategy for low-risk operations",
        )

    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        if context and context.get("high_value", False):
            adjusted = MultiSigStrategyConfig(**self._config.__dict__)
            adjusted.min_signers = 2
            adjusted.default_confirmations_required = 2
            return adjusted
        return self._config

    def get_strategy_type(self) -> MultiSigStrategyType:
        return MultiSigStrategyType.FAST_TRACK

    def validate_wallet_creation(
        self, chain_id: int, signers: List[str], threshold: int
    ) -> bool:
        if threshold < self._config.min_signers:
            return False
        if threshold > len(signers):
            return False
        if len(signers) > self._config.max_signers:
            return False
        return True

    def should_auto_execute(
        self, current_signatures: int, required_threshold: int, context: Optional[Dict[str, Any]] = None
    ) -> bool:
        return True


class HighSecurityMultiSigStrategy(IMultiSigConfigStrategy):
    def __init__(self):
        self._config = MultiSigStrategyConfig(
            strategy_type=MultiSigStrategyType.HIGH_SECURITY,
            min_signers=3,
            max_signers=7,
            default_confirmations_required=5,
            proposal_expiry_blocks=50000,
            execution_timeout=1200,
            max_retry_attempts=1,
            auto_execute_on_threshold=False,
            require_sequential_nonce=True,
            allow_batch_execution=False,
            signature_expiry_blocks=5000,
            description="High security strategy for high-value operations",
        )

    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        return self._config

    def get_strategy_type(self) -> MultiSigStrategyType:
        return MultiSigStrategyType.HIGH_SECURITY

    def validate_wallet_creation(
        self, chain_id: int, signers: List[str], threshold: int
    ) -> bool:
        if threshold < self._config.min_signers:
            return False
        if threshold > len(signers):
            return False
        if len(signers) > self._config.max_signers:
            return False
        if threshold != len(signers):
            return False
        return True

    def should_auto_execute(
        self, current_signatures: int, required_threshold: int, context: Optional[Dict[str, Any]] = None
    ) -> bool:
        return False


StrategyFactory = Callable[[], IMultiSigConfigStrategy]


class MultiSigConfigManager:
    def __init__(self):
        self._strategies: Dict[MultiSigStrategyType, IMultiSigConfigStrategy] = {}
        self._custom_strategies: Dict[str, IMultiSigConfigStrategy] = {}
        self._active_strategy_type: MultiSigStrategyType = MultiSigStrategyType.DEFAULT
        self._chain_overrides: Dict[int, MultiSigStrategyType] = {}
        self._wallet_overrides: Dict[str, MultiSigStrategyType] = {}
        self._update_callbacks: List[Callable[[MultiSigStrategyType], None]] = []
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        self._strategies[MultiSigStrategyType.DEFAULT] = DefaultMultiSigStrategy()
        self._strategies[MultiSigStrategyType.CONSERVATIVE] = ConservativeMultiSigStrategy()
        self._strategies[MultiSigStrategyType.FAST_TRACK] = FastTrackMultiSigStrategy()
        self._strategies[MultiSigStrategyType.HIGH_SECURITY] = HighSecurityMultiSigStrategy()

        self._initialized = True
        logger.info("MultiSigConfigManager initialized with default strategies")

    async def shutdown(self) -> None:
        self._update_callbacks.clear()
        self._custom_strategies.clear()
        self._chain_overrides.clear()
        self._wallet_overrides.clear()
        self._initialized = False
        logger.info("MultiSigConfigManager shutdown")

    def get_strategy(self, context: Optional[Dict[str, Any]] = None) -> IMultiSigConfigStrategy:
        strategy_type = self._resolve_strategy_type(context)
        return self._strategies.get(strategy_type, self._strategies[MultiSigStrategyType.DEFAULT])

    def get_config(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyConfig:
        strategy = self.get_strategy(context)
        return strategy.get_config(context)

    def set_active_strategy(self, strategy_type: MultiSigStrategyType) -> None:
        if strategy_type not in self._strategies and strategy_type != MultiSigStrategyType.CUSTOM:
            raise ValueError(f"Strategy {strategy_type} not registered")

        old_type = self._active_strategy_type
        self._active_strategy_type = strategy_type
        logger.info(f"Multi-sig strategy changed from {old_type} to {strategy_type}")

        for callback in self._update_callbacks:
            try:
                callback(strategy_type)
            except Exception as e:
                logger.error(f"Error in strategy update callback: {e}")

    def register_custom_strategy(self, name: str, strategy: IMultiSigConfigStrategy) -> None:
        self._custom_strategies[name] = strategy
        logger.info(f"Custom multi-sig strategy registered: {name}")

    def unregister_custom_strategy(self, name: str) -> None:
        if name in self._custom_strategies:
            del self._custom_strategies[name]
            logger.info(f"Custom multi-sig strategy unregistered: {name}")

    def set_chain_strategy(self, chain_id: int, strategy_type: MultiSigStrategyType) -> None:
        self._chain_overrides[chain_id] = strategy_type
        logger.info(f"Chain {chain_id} strategy set to {strategy_type}")

    def set_wallet_strategy(self, wallet_id: str, strategy_type: MultiSigStrategyType) -> None:
        self._wallet_overrides[wallet_id] = strategy_type
        logger.info(f"Wallet {wallet_id} strategy set to {strategy_type}")

    def add_update_callback(self, callback: Callable[[MultiSigStrategyType], None]) -> None:
        self._update_callbacks.append(callback)

    def remove_update_callback(self, callback: Callable[[MultiSigStrategyType], None]) -> None:
        if callback in self._update_callbacks:
            self._update_callbacks.remove(callback)

    def get_available_strategies(self) -> List[Dict[str, Any]]:
        result = []
        for strategy_type, strategy in self._strategies.items():
            config = strategy.get_config()
            result.append({
                "type": strategy_type.value,
                "description": config.description,
                "config": {
                    "min_signers": config.min_signers,
                    "max_signers": config.max_signers,
                    "default_confirmations_required": config.default_confirmations_required,
                    "auto_execute_on_threshold": config.auto_execute_on_threshold,
                },
            })
        return result

    def _resolve_strategy_type(self, context: Optional[Dict[str, Any]] = None) -> MultiSigStrategyType:
        if not context:
            return self._active_strategy_type

        wallet_id = context.get("wallet_id")
        if wallet_id and wallet_id in self._wallet_overrides:
            return self._wallet_overrides[wallet_id]

        chain_id = context.get("chain_id")
        if chain_id and chain_id in self._chain_overrides:
            return self._chain_overrides[chain_id]

        return self._active_strategy_type


_config_manager: Optional[MultiSigConfigManager] = None


def get_config_manager() -> MultiSigConfigManager:
    global _config_manager
    if _config_manager is None:
        _config_manager = MultiSigConfigManager()
    return _config_manager
