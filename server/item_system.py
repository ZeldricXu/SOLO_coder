import json
import random
import copy
from enum import IntEnum
from dataclasses import dataclass, field
from typing import Optional, Any, Dict, List, Callable

from .events import EventBus, CombatEvent, EventType, get_global_bus


class Rarity(IntEnum):
    WHITE = 0
    BLUE = 1
    PURPLE = 2
    GOLD = 3
    ORANGE = 4


RARITY_NAMES = {
    Rarity.WHITE: "白",
    Rarity.BLUE: "蓝",
    Rarity.PURPLE: "紫",
    Rarity.GOLD: "金",
    Rarity.ORANGE: "橙",
}


class ItemType:
    WEAPON = "weapon"
    ARMOR = "armor"
    ACCESSORY = "accessory"
    CONSUMABLE = "consumable"


RARITY_STRING_MAP = {
    "white": Rarity.WHITE,
    "blue": Rarity.BLUE,
    "purple": Rarity.PURPLE,
    "gold": Rarity.GOLD,
    "orange": Rarity.ORANGE,
}


@dataclass
class Item:
    id: str = ""
    name: str = ""
    item_type: str = ""
    subtype: str = ""
    rarity: Rarity = Rarity.WHITE
    attack: int = 0
    defense: int = 0
    speed: int = 0
    element_bonus: dict = field(default_factory=dict)
    special_effect: Optional[str] = None
    enhance_level: int = 0
    prefix_name: str = ""
    suffix_name: str = ""
    prefix_stats: dict = field(default_factory=dict)
    suffix_stats: dict = field(default_factory=dict)
    stack_count: int = 1
    stack_max: int = 1
    slot: Optional[str] = None
    consumable_effect: Optional[str] = None
    consumable_value: int = 0
    consumable_element: Optional[str] = None
    consumable_duration: Optional[int] = None

    def full_name(self):
        parts = []
        if self.prefix_name:
            parts.append(self.prefix_name)
        if self.enhance_level > 0:
            parts.append(f"+{self.enhance_level}")
        parts.append(self.name)
        if self.suffix_name:
            parts.append(self.suffix_name)
        return "".join(parts)

    def total_attack(self):
        val = self.attack
        if "attack" in self.prefix_stats:
            val += self.prefix_stats["attack"]
        if "attack" in self.suffix_stats:
            val += self.suffix_stats["attack"]
        if self.enhance_level > 0 and self.item_type in (ItemType.WEAPON, ItemType.ARMOR, ItemType.ACCESSORY):
            val += self.enhance_level * 2
        return val

    def total_defense(self):
        val = self.defense
        if "defense" in self.prefix_stats:
            val += self.prefix_stats["defense"]
        if "defense" in self.suffix_stats:
            val += self.suffix_stats["defense"]
        if self.enhance_level > 0 and self.item_type in (ItemType.ARMOR, ItemType.ACCESSORY):
            val += self.enhance_level
        return val

    def total_speed(self):
        val = self.speed
        if "speed" in self.prefix_stats:
            val += self.prefix_stats["speed"]
        if "speed" in self.suffix_stats:
            val += self.suffix_stats["speed"]
        if self.enhance_level > 0 and self.item_type == ItemType.ACCESSORY:
            val += self.enhance_level
        return val

    def to_dict(self):
        d = {
            "id": self.id,
            "name": self.name,
            "item_type": self.item_type,
            "subtype": self.subtype,
            "rarity": int(self.rarity),
            "attack": self.attack,
            "defense": self.defense,
            "speed": self.speed,
            "element_bonus": self.element_bonus,
            "special_effect": self.special_effect,
            "enhance_level": self.enhance_level,
            "prefix_name": self.prefix_name,
            "suffix_name": self.suffix_name,
            "prefix_stats": self.prefix_stats,
            "suffix_stats": self.suffix_stats,
            "stack_count": self.stack_count,
            "stack_max": self.stack_max,
            "slot": self.slot,
            "consumable_effect": self.consumable_effect,
            "consumable_value": self.consumable_value,
            "consumable_element": self.consumable_element,
            "consumable_duration": self.consumable_duration,
        }
        return d

    @classmethod
    def from_dict(cls, d):
        return cls(
            id=d.get("id", ""),
            name=d.get("name", ""),
            item_type=d.get("item_type", ""),
            subtype=d.get("subtype", ""),
            rarity=Rarity(d.get("rarity", 0)),
            attack=d.get("attack", 0),
            defense=d.get("defense", 0),
            speed=d.get("speed", 0),
            element_bonus=d.get("element_bonus", {}),
            special_effect=d.get("special_effect"),
            enhance_level=d.get("enhance_level", 0),
            prefix_name=d.get("prefix_name", ""),
            suffix_name=d.get("suffix_name", ""),
            prefix_stats=d.get("prefix_stats", {}),
            suffix_stats=d.get("suffix_stats", {}),
            stack_count=d.get("stack_count", 1),
            stack_max=d.get("stack_max", 1),
            slot=d.get("slot"),
            consumable_effect=d.get("consumable_effect"),
            consumable_value=d.get("consumable_value", 0),
            consumable_element=d.get("consumable_element"),
            consumable_duration=d.get("consumable_duration"),
        )

    def on_equip(self, event_bus: Optional[EventBus] = None) -> None:
        """装备时调用，订阅事件触发特效"""
        bus = event_bus or get_global_bus()
        self._active_handlers: List[Callable] = []

        if self.special_effect == "lifesteal":
            def lifesteal_handler(event: CombatEvent):
                if event.attacker and event.damage > 0 and not event.was_dodged:
                    heal = max(1, int(event.damage * 0.1))
                    event.attacker["hp"] = min(
                        event.attacker.get("max_hp", event.attacker.get("hp", 0)),
                        event.attacker.get("hp", 0) + heal
                    )
            bus.subscribe(EventType.ON_HIT, lifesteal_handler)
            self._active_handlers.append((EventType.ON_HIT, lifesteal_handler))

        elif self.special_effect == "thorns":
            def thorns_handler(event: CombatEvent):
                if event.attacker and event.damage > 0 and not event.was_dodged:
                    reflect = max(1, int(event.damage * 0.2))
                    event.attacker["hp"] = event.attacker.get("hp", 0) - reflect
            bus.subscribe(EventType.AFTER_ATTACK, thorns_handler)
            self._active_handlers.append((EventType.AFTER_ATTACK, thorns_handler))

        elif self.special_effect == "on_kill_heal":
            def on_kill_handler(event: CombatEvent):
                if event.attacker and event.killed:
                    heal = int(event.attacker.get("max_hp", 0) * 0.15)
                    event.attacker["hp"] = min(
                        event.attacker.get("max_hp", 0),
                        event.attacker.get("hp", 0) + int(heal)
                    )
            bus.subscribe(EventType.ON_KILL, on_kill_handler)
            self._active_handlers.append((EventType.ON_KILL, on_kill_handler))

        elif self.special_effect == "crit_bonus":
            def crit_bonus_handler(event: CombatEvent):
                if event.attacker:
                    current_crit = event.attacker.get("crit_rate", 0)
                    event.attacker["crit_rate"] = current_crit + 0.1
            bus.subscribe(EventType.BEFORE_ATTACK, crit_bonus_handler)
            self._active_handlers.append((EventType.BEFORE_ATTACK, crit_bonus_handler))

            def crit_bonus_reset_handler(event: CombatEvent):
                if event.attacker:
                    current_crit = event.attacker.get("crit_rate", 0.05)
                    event.attacker["crit_rate"] = max(0.05, current_crit - 0.1)
            bus.subscribe(EventType.AFTER_ATTACK, crit_bonus_reset_handler)
            self._active_handlers.append((EventType.AFTER_ATTACK, crit_bonus_reset_handler))

        elif self.special_effect == "elemental_fire":
            def fire_bonus_handler(event: CombatEvent):
                if event.attacker and event.damage > 0 and not event.was_dodged:
                    event.damage = int(event.damage * 1.2)
            bus.subscribe(EventType.AFTER_DAMAGE_CALC, fire_bonus_handler)
            self._active_handlers.append((EventType.AFTER_DAMAGE_CALC, fire_bonus_handler))

    def on_unequip(self, event_bus: Optional[EventBus] = None) -> None:
        """卸下装备时调用，取消事件订阅"""
        bus = event_bus or get_global_bus()
        if hasattr(self, "_active_handlers"):
            for event_type, handler in self._active_handlers:
                try:
                    bus.unsubscribe(event_type, handler)
                except Exception:
                    pass
            self._active_handlers = []


class AffixGenerator:
    def __init__(self, template_path="data/items/templates.json"):
        self.prefixes = []
        self.suffixes = []
        self._load(template_path)

    def _load(self, path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.prefixes = data.get("prefixes", [])
        self.suffixes = data.get("suffixes", [])

    def _roll_affix(self, pool, rarity, item_type):
        eligible = [
            a for a in pool
            if RARITY_STRING_MAP.get(a.get("min_rarity", "white"), Rarity.WHITE) <= rarity
        ]
        if not eligible:
            return None
        chosen = random.choice(eligible)
        lo, hi = chosen["value"]
        if isinstance(lo, float) or isinstance(hi, float):
            value = round(random.uniform(lo, hi), 2)
        else:
            value = random.randint(lo, hi)
        stat = chosen["stat"]
        result_name = chosen["name"]
        result_stats = {}
        if stat.startswith("element_"):
            element = stat[len("element_"):]
            result_stats["element_bonus"] = {element: value}
        else:
            result_stats[stat] = value
        return result_name, result_stats

    def _count_guaranteed_affixes(self, rarity):
        if rarity <= Rarity.WHITE:
            return 0, 0
        if rarity == Rarity.BLUE:
            return 1, 1
        if rarity == Rarity.PURPLE:
            return random.randint(1, 2), random.randint(1, 2)
        if rarity == Rarity.GOLD:
            return 2, 2
        if rarity == Rarity.ORANGE:
            return random.randint(2, 3), random.randint(2, 3)
        return 0, 0

    def generate_affix(self, rarity, item_type):
        prefix_name = ""
        prefix_stats = {}
        suffix_name = ""
        suffix_stats = {}

        if item_type == ItemType.CONSUMABLE:
            return prefix_name, prefix_stats, suffix_name, suffix_stats

        num_prefixes, num_suffixes = self._count_guaranteed_affixes(rarity)

        for _ in range(num_prefixes):
            result = self._roll_affix(self.prefixes, rarity, item_type)
            if result:
                prefix_name = result[0]
                prefix_stats.update(result[1])

        for _ in range(num_suffixes):
            result = self._roll_affix(self.suffixes, rarity, item_type)
            if result:
                suffix_name = result[0]
                suffix_stats.update(result[1])

        return prefix_name, prefix_stats, suffix_name, suffix_stats


class ItemFactory:
    def __init__(self, template_path="data/items/templates.json"):
        self.template_path = template_path
        self.bases = {}
        self.rarity_weights = {}
        self.affix_generator = AffixGenerator(template_path)
        self._load()

    def _load(self):
        with open(self.template_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for category in ("weapons", "armors", "accessories", "consumables"):
            for base in data.get(category, []):
                self.bases[base["id"]] = base
        self.rarity_weights = data.get("rarity_weights", {})

    def _roll_rarity(self, floor_depth):
        floor_key = str(min(floor_depth, 7))
        weights = self.rarity_weights.get(floor_key, self.rarity_weights.get("1", {}))
        choices = list(weights.keys())
        w = [weights[c] for c in choices]
        chosen = random.choices(choices, weights=w, k=1)[0]
        return RARITY_STRING_MAP[chosen]

    def _get_closest_weights_floor(self, floor_depth):
        available = sorted(int(k) for k in self.rarity_weights.keys())
        closest = available[0]
        for f in available:
            if f <= floor_depth:
                closest = f
        return str(closest)

    def create_item(self, base_id, floor_depth):
        base = self.bases.get(base_id)
        if not base:
            return None
        base_copy = copy.deepcopy(base)
        rarity = self._roll_rarity(floor_depth)
        base_rarity = RARITY_STRING_MAP.get(base_copy.get("rarity", "white"), Rarity.WHITE)
        final_rarity = max(rarity, base_rarity)

        item = Item(
            id=base_copy["id"],
            name=base_copy["name"],
            item_type=base_copy["type"],
            subtype=base_copy.get("subtype", ""),
            rarity=final_rarity,
            attack=base_copy.get("attack", 0),
            defense=base_copy.get("defense", 0),
            speed=base_copy.get("speed", 0),
            element_bonus=base_copy.get("element_bonus", {}),
            special_effect=base_copy.get("special_effect"),
            slot=base_copy.get("slot"),
        )

        if item.item_type == ItemType.CONSUMABLE:
            item.consumable_effect = base_copy.get("effect")
            item.consumable_value = base_copy.get("value", 0)
            item.consumable_element = base_copy.get("element")
            item.consumable_duration = base_copy.get("duration")
            item.stack_max = base_copy.get("stack_max", 1)
            item.stack_count = 1
        else:
            prefix_name, prefix_stats, suffix_name, suffix_stats = \
                self.affix_generator.generate_affix(final_rarity, item.item_type)
            item.prefix_name = prefix_name
            item.prefix_stats = prefix_stats
            item.suffix_name = suffix_name
            item.suffix_stats = suffix_stats

        return item

    def create_random_drop(self, floor_depth, monster_tags=None):
        eligible = []
        for base_id, base in self.bases.items():
            min_floor = base.get("min_floor", 1)
            if min_floor > floor_depth:
                continue
            if monster_tags:
                if not _matches_tags(base, monster_tags):
                    continue
            eligible.append(base_id)
        if not eligible:
            return None
        base_id = random.choice(eligible)
        return self.create_item(base_id, floor_depth)


def _matches_tags(base, monster_tags):
    return True


ENHANCE_SUCCESS_RATES = {
    1: 1.0, 2: 1.0, 3: 1.0,
    4: 0.7, 5: 0.7, 6: 0.7,
    7: 0.4, 8: 0.4, 9: 0.4,
    10: 0.15,
}

ENHANCE_DEGRADE_CHANCE = {
    1: 0.0, 2: 0.0, 3: 0.0,
    4: 0.0, 5: 0.0, 6: 0.0,
    7: 0.5, 8: 0.5, 9: 0.5,
    10: 0.0,
}

ENHANCE_COST_GOLD = {
    1: 50, 2: 100, 3: 200,
    4: 400, 5: 800, 6: 1500,
    7: 3000, 8: 6000, 9: 12000,
    10: 25000,
}

ENHANCE_COST_STONES = {
    1: 1, 2: 2, 3: 3,
    4: 4, 5: 5, 6: 6,
    7: 7, 8: 8, 9: 9,
    10: 10,
}


class EnhancementSystem:
    @staticmethod
    def can_enhance(item: Item) -> bool:
        if item.item_type == ItemType.CONSUMABLE:
            return False
        if item.enhance_level >= 10:
            return False
        return True

    @staticmethod
    def get_enhance_cost(item: Item, floor_depth: int = 1) -> dict:
        if not EnhancementSystem.can_enhance(item):
            return {}
        next_level = item.enhance_level + 1
        gold_mult = 1 + (floor_depth - 1) * 0.2
        return {
            "gold": int(ENHANCE_COST_GOLD.get(next_level, 0) * gold_mult),
            "stones": ENHANCE_COST_STONES.get(next_level, 0),
            "success_rate": ENHANCE_SUCCESS_RATES.get(next_level, 0),
            "degrade_chance": ENHANCE_DEGRADE_CHANCE.get(next_level, 0),
            "next_level": next_level,
        }

    @staticmethod
    def enhance(item: Item, player: Any, use_protection: bool = False, floor_depth: int = 1) -> dict:
        if not EnhancementSystem.can_enhance(item):
            return {"success": False, "error": "cannot_enhance"}

        cost = EnhancementSystem.get_enhance_cost(item, floor_depth)
        next_level = cost["next_level"]
        gold_cost = cost["gold"]
        stone_cost = cost["stones"]
        success_rate = cost["success_rate"]
        degrade_chance = cost["degrade_chance"]

        if player.gold < gold_cost:
            return {"success": False, "error": "insufficient_gold"}

        stone_count = 0
        for inv_item in player.inventory:
            if inv_item.get("id") == "enhance_stone":
                stone_count += inv_item.get("stack_count", 1)

        protection_count = 0
        for inv_item in player.inventory:
            if inv_item.get("id") == "protection_rune":
                protection_count += inv_item.get("stack_count", 1)

        if stone_count < stone_cost:
            return {"success": False, "error": "insufficient_stones"}

        if use_protection and protection_count <= 0:
            return {"success": False, "error": "no_protection_rune"}

        player.gold -= gold_cost

        remaining = stone_cost
        for i, inv_item in enumerate(player.inventory):
            if inv_item.get("id") == "enhance_stone" and remaining > 0:
                take = min(inv_item.get("stack_count", 1), remaining)
                inv_item["stack_count"] -= take
                remaining -= take
                if inv_item["stack_count"] <= 0:
                    player.inventory.pop(i)
                    break

        if use_protection:
            for i, inv_item in enumerate(player.inventory):
                if inv_item.get("id") == "protection_rune":
                    inv_item["stack_count"] -= 1
                    if inv_item["stack_count"] <= 0:
                        player.inventory.pop(i)
                    break

        roll = random.random()
        if roll < success_rate:
            item.enhance_level = next_level
            return {
                "success": True,
                "enhanced": True,
                "new_level": next_level,
                "degraded": False,
                "message": f"强化成功！{item.full_name()}",
            }
        else:
            degraded = False
            new_level = item.enhance_level
            if not use_protection and random.random() < degrade_chance:
                item.enhance_level = max(0, item.enhance_level - 1)
                new_level = item.enhance_level
                degraded = True
            return {
                "success": True,
                "enhanced": False,
                "new_level": new_level,
                "degraded": degraded,
                "message": "强化失败" + ("，等级下降！" if degraded else "。"),
            }


class Backpack:
    MAX_SLOTS = 20

    def __init__(self):
        self.items: dict = {}

    def add_item(self, item):
        if item.item_type == ItemType.CONSUMABLE:
            for slot_idx, existing in self.items.items():
                if existing.id == item.id and existing.stack_count < existing.stack_max:
                    space = existing.stack_max - existing.stack_count
                    transfer = min(space, item.stack_count)
                    existing.stack_count += transfer
                    item.stack_count -= transfer
                    if item.stack_count <= 0:
                        return True

        if item.stack_count > 0:
            for i in range(self.MAX_SLOTS):
                if i not in self.items:
                    self.items[i] = item
                    return True
        return False

    def remove_item(self, slot_index):
        if slot_index in self.items:
            return self.items.pop(slot_index)
        return None

    def swap_items(self, slot1, slot2):
        if slot1 in self.items or slot2 in self.items:
            self.items[slot1], self.items[slot2] = \
                self.items.get(slot2), self.items.get(slot1)
            if self.items.get(slot1) is None and slot1 in self.items:
                del self.items[slot1]
            if self.items.get(slot2) is None and slot2 in self.items:
                del self.items[slot2]

    def get_item(self, slot_index):
        return self.items.get(slot_index)

    def to_dict(self):
        return {str(k): v.to_dict() for k, v in self.items.items()}

    @classmethod
    def from_dict(cls, d):
        bp = cls()
        for k, v in d.items():
            bp.items[int(k)] = Item.from_dict(v)
        return bp
