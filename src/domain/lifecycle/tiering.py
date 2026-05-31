import logging
import gc
import weakref
from dataclasses import dataclass, field
from enum import Enum
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional

from src.infrastructure.config.settings import LifecycleConfig

logger = logging.getLogger(__name__)


class DataTier(Enum):
    HOT = "hot"
    COLD = "cold"
    ARCHIVE = "archive"


@dataclass
class TieringPolicy:
    source_tier: DataTier
    target_tier: DataTier
    age_threshold_days: int
    priority: int = 0
    enabled: bool = True
    conditions: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TieringAction:
    table_name: str
    database_name: str
    source_tier: DataTier
    target_tier: DataTier
    row_count: int
    cutoff_date: datetime
    status: str = "pending"
    error_message: Optional[str] = None


class DataTieringManager:
    def __init__(self, config: LifecycleConfig):
        self._config = config
        self._policies: List[TieringPolicy] = []
        self._action_callbacks: Dict[str, weakref.ReferenceType] = {}
        self._closed = False
        self._setup_default_policies()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._action_callbacks.clear()
        self._policies.clear()
        gc.collect()

    def _setup_default_policies(self) -> None:
        self._policies = [
            TieringPolicy(
                source_tier=DataTier.HOT,
                target_tier=DataTier.COLD,
                age_threshold_days=self._config.hot_to_cold_days,
                priority=10,
            ),
            TieringPolicy(
                source_tier=DataTier.COLD,
                target_tier=DataTier.ARCHIVE,
                age_threshold_days=self._config.cold_to_archive_days,
                priority=20,
            ),
        ]

    def add_policy(self, policy: TieringPolicy) -> None:
        if self._closed:
            raise RuntimeError("DataTieringManager has been closed")
        self._policies.append(policy)
        self._policies.sort(key=lambda p: p.priority)

    def remove_policy(self, source_tier: DataTier, target_tier: DataTier) -> None:
        if self._closed:
            raise RuntimeError("DataTieringManager has been closed")
        self._policies = [
            p for p in self._policies
            if not (p.source_tier == source_tier and p.target_tier == target_tier)
        ]

    def register_callback(self, action_type: str, callback: Callable) -> None:
        if self._closed:
            raise RuntimeError("DataTieringManager has been closed")
        try:
            if hasattr(callback, '__self__'):
                self._action_callbacks[action_type] = weakref.WeakMethod(callback)
            else:
                self._action_callbacks[action_type] = weakref.ref(callback)
        except TypeError:
            self._action_callbacks[action_type] = weakref.ref(callback)

    def evaluate(self, database_name: str, table_name: str, table_stats: Dict[str, Any]) -> List[TieringAction]:
        actions = []
        now = datetime.utcnow()
        oldest_record_date = table_stats.get("oldest_record_date")
        total_rows = table_stats.get("total_rows", 0)

        if not oldest_record_date:
            return actions

        if isinstance(oldest_record_date, str):
            oldest_record_date = datetime.fromisoformat(oldest_record_date)

        age_days = (now - oldest_record_date).days

        for policy in self._policies:
            if not policy.enabled:
                continue
            if age_days >= policy.age_threshold_days:
                cutoff_date = now - timedelta(days=policy.age_threshold_days)
                action = TieringAction(
                    table_name=table_name,
                    database_name=database_name,
                    source_tier=policy.source_tier,
                    target_tier=policy.target_tier,
                    row_count=total_rows,
                    cutoff_date=cutoff_date,
                )
                self._apply_conditions(action, policy, table_stats)
                actions.append(action)

        return actions

    def _apply_conditions(self, action: TieringAction, policy: TieringPolicy, stats: Dict[str, Any]) -> None:
        conditions = policy.conditions
        if not conditions:
            return

        min_size_mb = conditions.get("min_size_mb")
        if min_size_mb and stats.get("size_mb", 0) < min_size_mb:
            action.status = "skipped"
            action.error_message = f"Table size {stats.get('size_mb', 0)}MB below minimum {min_size_mb}MB"
            return

        max_rows = conditions.get("max_rows")
        if max_rows and stats.get("total_rows", 0) > max_rows:
            action.status = "skipped"
            action.error_message = f"Row count {stats.get('total_rows', 0)} exceeds maximum {max_rows}"
            return

        exclude_tables = conditions.get("exclude_tables", [])
        if action.table_name in exclude_tables:
            action.status = "skipped"
            action.error_message = f"Table {action.table_name} is excluded from tiering"

    def execute_tiering(self, action: TieringAction) -> TieringAction:
        if self._closed:
            raise RuntimeError("DataTieringManager has been closed")

        try:
            callback_key = f"{action.source_tier.value}_to_{action.target_tier.value}"
            callback_ref = self._action_callbacks.get(callback_key)
            if callback_ref:
                callback = callback_ref()
                if callback:
                    callback(action)
                    action.status = "completed"
                else:
                    del self._action_callbacks[callback_key]
                    logger.warning(f"Callback for tiering action {callback_key} has expired")
                    action.status = "no_handler"
            else:
                logger.warning(f"No callback registered for tiering action: {callback_key}")
                action.status = "no_handler"
        except Exception as e:
            action.status = "failed"
            action.error_message = str(e)
            logger.error(f"Tiering action failed: {e}")
        finally:
            gc.collect()
        return action

    def get_policies(self) -> List[Dict[str, Any]]:
        return [
            {
                "source_tier": p.source_tier.value,
                "target_tier": p.target_tier.value,
                "age_threshold_days": p.age_threshold_days,
                "priority": p.priority,
                "enabled": p.enabled,
                "conditions": p.conditions,
            }
            for p in self._policies
        ]

    def check_all_tables(self, table_stats_map: Dict[str, Dict[str, Any]]) -> List[TieringAction]:
        all_actions = []
        for qualified_name, stats in table_stats_map.items():
            parts = qualified_name.split(".", 1)
            db_name = parts[0] if len(parts) > 1 else "default"
            tbl_name = parts[-1]
            actions = self.evaluate(db_name, tbl_name, stats)
            all_actions.extend(actions)
        return all_actions
