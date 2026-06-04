"""
事件系统模块 - 战斗引擎与物品系统的解耦层

模块依赖关系图：
          ┌─────────────┐
          │   Network   │
          │  (app.py)   │
          └──────┬──────┘
                 │
          ┌──────▼──────┐
          │ Game Manager│
          └──────┬──────┘
                 │
     ┌───────────┼───────────┐
     │           │           │
┌────▼────┐  ┌──▼───┐  ┌────▼────┐
│  Combat │  │Events│  │   Item  │
│ Engine  │◄─┤Bus   ├─►│ System  │
└────┬────┘  └──────┘  └────┬────┘
     │                       │
     │        ┌─────────┐    │
     └───────►│  AI     │◄───┘
             │Behavior │
             └─────────┘

解决的循环依赖：
- 旧：CombatEngine ↔ ItemSystem（互相引用）
- 新：CombatEngine → Events ← ItemSystem（单向依赖）
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional


class EventType(str, Enum):
    """事件类型定义

    战斗生命周期事件，按触发顺序排列：
    """

    COMBAT_START = "combat_start"
    """战斗开始，双方进入战斗状态"""

    BEFORE_ATTACK = "before_attack"
    """攻击前 - 可用于修改攻击力、触发吸血/暴击等"""

    BEFORE_DEFENSE = "before_defense"
    """防御前 - 可用于修改防御力、触发护盾等"""

    AFTER_DAMAGE_CALC = "after_damage_calc"
    """伤害计算后，扣血前 - 可用于伤害增减、真实伤害穿透"""

    ON_HIT = "on_hit"
    """命中时 - 可用于触发装备特效（冰冻、灼烧等）"""

    AFTER_ATTACK = "after_attack"
    """攻击完成后 - 可用于反伤、吸血结算"""

    ON_KILL = "on_kill"
    """击杀目标时 - 可用于击杀回血、经验加成"""

    COMBAT_END = "combat_end"
    """战斗结束 - 可用于战斗奖励、统计"""

    TURN_START = "turn_start"
    """回合开始 - 可用于回蓝、持续伤害"""

    TURN_END = "turn_end"
    """回合结束 - 可用于冷却减少"""


@dataclass
class CombatEvent:
    """战斗事件数据载体

    所有战斗相关的事件都通过此对象传递，订阅者可以读取或修改字段
    """

    event_type: EventType
    attacker: Optional[Dict[str, Any]] = None
    """攻击者数据字典（攻击/技能释放者）"""
    defender: Optional[Dict[str, Any]] = None
    """防御者数据字典（被攻击目标）"""
    damage: int = 0
    """计算出的伤害值，订阅者可修改"""
    element: Optional[str] = None
    """伤害元素类型: fire/ice/poison/lightning/dark"""
    was_crit: bool = False
    """是否暴击"""
    was_dodged: bool = False
    """是否闪避"""
    killed: bool = False
    """是否击杀目标"""
    skill_id: Optional[str] = None
    """关联的技能ID，普通攻击为None"""
    item_id: Optional[str] = None
    """关联的物品ID（用于装备特效来源追踪）"""
    extra: Dict[str, Any] = field(default_factory=dict)
    """扩展字段，用于特殊事件的额外数据"""

    def stop_propagation(self) -> None:
        """停止事件继续传播给后续订阅者"""
        self._stopped = True

    @property
    def is_stopped(self) -> bool:
        return getattr(self, "_stopped", False)


class EventBus:
    """事件总线 - 发布/订阅模式

    战斗引擎发布事件 -> 物品系统/技能系统/成就系统订阅事件

    使用方式：
        bus = EventBus()

        # 订阅
        def on_kill_handler(event: CombatEvent):
            if event.attacker:
                event.attacker["hp"] += 5  # 击杀回血

        bus.subscribe(EventType.ON_KILL, on_kill_handler)

        # 发布
        event = CombatEvent(event_type=EventType.ON_KILL, attacker=player, defender=monster)
        bus.publish(event)
    """

    def __init__(self):
        self._subscribers: Dict[EventType, List[Callable[[CombatEvent], None]]] = {}
        self._global_subscribers: List[Callable[[CombatEvent], None]] = []

    def subscribe(self, event_type: EventType, handler: Callable[[CombatEvent], None]) -> None:
        """订阅特定类型的事件"""
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)

    def subscribe_all(self, handler: Callable[[CombatEvent], None]) -> None:
        """订阅所有类型的事件（用于日志/调试）"""
        self._global_subscribers.append(handler)

    def unsubscribe(self, event_type: EventType, handler: Callable[[CombatEvent], None]) -> None:
        """取消订阅"""
        if event_type in self._subscribers and handler in self._subscribers[event_type]:
            self._subscribers[event_type].remove(handler)

    def publish(self, event: CombatEvent) -> CombatEvent:
        """发布事件，按订阅顺序调用所有处理器

        处理器可以修改event对象的字段，后续处理器会看到修改后的值
        """
        for handler in self._global_subscribers:
            if event.is_stopped:
                break
            try:
                handler(event)
            except Exception:
                import logging
                logger = logging.getLogger("events")
                logger.exception("Global event handler error")

        if not event.is_stopped and event.event_type in self._subscribers:
            for handler in self._subscribers[event.event_type]:
                if event.is_stopped:
                    break
                try:
                    handler(event)
                except Exception:
                    import logging
                    logger = logging.getLogger("events")
                    logger.exception("Event handler error for %s", event.event_type.value)

        return event

    def clear(self) -> None:
        """清空所有订阅者"""
        self._subscribers.clear()
        self._global_subscribers.clear()


_global_bus: Optional[EventBus] = None


def get_global_bus() -> EventBus:
    """获取全局事件总线单例"""
    global _global_bus
    if _global_bus is None:
        _global_bus = EventBus()
    return _global_bus


def reset_global_bus() -> None:
    """重置全局事件总线（测试用）"""
    global _global_bus
    _global_bus = None
