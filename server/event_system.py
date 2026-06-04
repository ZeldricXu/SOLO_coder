import json
import random
import copy
from enum import Enum
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any

from .item_system import ItemFactory, Item, Rarity
from .character import Character


class EventTriggerType(Enum):
    ENTER_ROOM = "enter_room"
    INTERACT = "interact"
    ON_DEATH = "on_death"
    ON_KILL = "on_kill"


TRIGGER_TYPE_MAP = {
    "enter_room": EventTriggerType.ENTER_ROOM,
    "interact": EventTriggerType.INTERACT,
    "on_death": EventTriggerType.ON_DEATH,
    "on_kill": EventTriggerType.ON_KILL,
}


@dataclass
class EventResult:
    event_id: str
    message: str
    choices: List[Dict[str, Any]] = field(default_factory=list)
    rewards: Dict[str, Any] = field(default_factory=dict)
    triggered_effect: str = ""


class Event:
    def __init__(self, data: Dict[str, Any]):
        self.id = data.get("id", "")
        self.name = data.get("name", "")
        self.type = data.get("type", "")
        trigger_str = data.get("trigger", "enter_room")
        self.trigger_type = TRIGGER_TYPE_MAP.get(trigger_str, EventTriggerType.ENTER_ROOM)
        self.probability = data.get("probability", 0.1)
        self.description = data.get("description", "")
        self.effect = data.get("effect", "")
        self.min_floor = data.get("min_floor", 1)
        self.choices = data.get("choices", [])
        self.raw_data = copy.deepcopy(data)

    def to_dict(self) -> Dict[str, Any]:
        return copy.deepcopy(self.raw_data)


class EventSystem:
    def __init__(self, events_path: str = "data/events/events.json"):
        self.events_path = events_path
        self.events: List[Event] = []
        self.item_factory = ItemFactory()
        self.load_events()

    def load_events(self) -> None:
        with open(self.events_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.events = [Event(e) for e in data.get("events", [])]

    def check_trigger(self, trigger_type: EventTriggerType, room, game_state) -> Optional[EventResult]:
        current_floor = game_state.get("current_floor", 1)
        applicable = []
        for event in self.events:
            if event.trigger_type != trigger_type:
                continue
            if event.min_floor > current_floor:
                continue
            applicable.append(event)
        if not applicable:
            return None
        random.shuffle(applicable)
        for event in applicable:
            if random.random() < event.probability:
                return self.trigger_event(event, game_state)
        return None

    def trigger_event(self, event: Event, game_state) -> EventResult:
        player = game_state.get("player")
        result = EventResult(event_id=event.id, message=event.description, choices=event.choices)
        effect_map = {
            "spawn_mimic": self._handle_mimic_chest,
            "full_heal": self._handle_healing_fountain,
            "random_trap": self._handle_trap_room,
            "cursed_loot": self._handle_cursed_room,
            "timed_loot": self._handle_treasure_vault,
        }
        handler = effect_map.get(event.effect)
        if handler:
            handler(event, game_state, result)
        if event.id == "mysterious_merchant":
            self._handle_mysterious_merchant(event, game_state, result)
        return result

    def process_choice(self, event: Event, choice_index: int, game_state) -> EventResult:
        result = EventResult(event_id=event.id, message="")
        if choice_index < 0 or choice_index >= len(event.choices):
            result.message = "无效选择"
            return result
        choice = event.choices[choice_index]
        effect = choice.get("effect", "none")
        if effect == "none":
            result.message = "你离开了。"
            return result
        cost_gold = choice.get("cost_gold", 0)
        cost_hp_percent = choice.get("cost_hp_percent", 0)
        cost_item = choice.get("cost_item", False)
        player = game_state.get("player")
        if cost_gold > 0:
            if player.gold < cost_gold:
                result.message = "金币不足！"
                return result
            player.gold -= cost_gold
        if cost_hp_percent > 0:
            hp_cost = int(player.max_hp * cost_hp_percent)
            if player.hp <= hp_cost:
                result.message = "生命值不足以献祭！"
                return result
            player.hp -= hp_cost
        if cost_item:
            if not player.equipment or not any(player.equipment.values()):
                result.message = "没有可献祭的装备！"
                return result
            sacrificed = False
            for slot in ["weapon", "offhand", "chest", "head", "ring", "accessory"]:
                if player.equipment.get(slot) is not None:
                    player.equipment[slot] = None
                    sacrificed = True
                    break
            if not sacrificed:
                result.message = "没有可献祭的装备！"
                return result
        if event.id == "wishing_well":
            self._process_wishing_well_choice(choice, game_state, result)
        elif event.id == "altar_sacrifice":
            self._process_altar_choice(choice, game_state, result)
        return result

    def _handle_mimic_chest(self, event: Event, game_state, result: EventResult) -> None:
        current_floor = game_state.get("current_floor", 1)
        mimic_template = {
            "id": "mimic",
            "name": "宝箱怪",
            "hp": 40 + current_floor * 10,
            "max_hp": 40 + current_floor * 10,
            "attack": 8 + current_floor * 2,
            "defense": 4 + current_floor,
            "speed": 3,
            "sight_range": 4,
            "sound_range": 2,
            "exp_reward": 25 + current_floor * 5,
            "behavior_tree": "slime.xml",
            "element_resist": {"fire": 0.0, "ice": 0.0, "poison": 0.3},
            "loot_table": [
                {"item_id": "health_potion", "drop_rate": 0.5},
                {"item_id": "gold_small", "drop_rate": 0.3},
            ],
            "min_floor": 1,
            "tags": ["monster", "mimic"],
            "position": game_state.get("player", Character("", "", None)).position,
            "alive": True,
        }
        monsters = game_state.get("monsters", [])
        monsters.append(mimic_template)
        game_state["monsters"] = monsters
        result.triggered_effect = "spawn_mimic"
        result.message = "宝箱突然睁开了眼睛！宝箱怪出现了！"
        result.rewards["monster_spawned"] = mimic_template

    def _handle_healing_fountain(self, event: Event, game_state, result: EventResult) -> None:
        player = game_state.get("player")
        healed_hp = player.max_hp - player.hp
        healed_mana = player.max_mana - player.mana
        player.hp = player.max_hp
        player.mana = player.max_mana
        result.triggered_effect = "full_heal"
        result.message = f"清澈的泉水恢复了你的全部力量！HP+{healed_hp}, MP+{healed_mana}"
        result.rewards["healed_hp"] = healed_hp
        result.rewards["healed_mana"] = healed_mana

    def _handle_trap_room(self, event: Event, game_state, result: EventResult) -> None:
        player = game_state.get("player")
        trap_types = event.raw_data.get("trap_types", ["spike"])
        damage_range = event.raw_data.get("damage_range", [10, 30])
        trap_type = random.choice(trap_types)
        base_damage = random.randint(damage_range[0], damage_range[1])
        current_floor = game_state.get("current_floor", 1)
        damage = base_damage + current_floor * 2
        element = None
        if trap_type == "poison_gas":
            element = "poison"
            player.add_status_effect({"name": "poison", "value": 5, "duration": 3})
        elif trap_type == "fire":
            element = "fire"
            player.add_status_effect({"name": "burn", "value": 5, "duration": 3})
        elif trap_type == "spike":
            element = None
        elif trap_type == "arrow":
            element = None
        actual_damage = player.take_damage(damage, element)
        trap_names = {
            "spike": "尖刺陷阱",
            "poison_gas": "毒气陷阱",
            "arrow": "箭矢陷阱",
            "fire": "火焰陷阱",
        }
        result.triggered_effect = f"trap_{trap_type}"
        result.message = f"触发了{trap_names.get(trap_type, '陷阱')}！受到{actual_damage}点伤害！"
        result.rewards["damage_taken"] = actual_damage
        result.rewards["trap_type"] = trap_type

    def _handle_cursed_room(self, event: Event, game_state, result: EventResult) -> None:
        player = game_state.get("player")
        current_floor = game_state.get("current_floor", 1)
        curse_type = event.raw_data.get("curse_type", "random_debuff")
        curse_duration = event.raw_data.get("curse_duration", 10)
        loot_rarity_bonus = event.raw_data.get("loot_rarity_bonus", 0)
        debuffs = ["attack_debuff", "defense_debuff", "speed_debuff"]
        chosen_debuff = random.choice(debuffs)
        debuff_names = {
            "attack_debuff": "攻击诅咒",
            "defense_debuff": "防御诅咒",
            "speed_debuff": "速度诅咒",
        }
        player.add_status_effect({
            "name": chosen_debuff,
            "value": 3 + current_floor,
            "duration": curse_duration,
        })
        items = game_state.get("items", [])
        num_items = random.randint(2, 3)
        bonus_items = []
        for _ in range(num_items):
            item = self.item_factory.create_random_drop(current_floor + loot_rarity_bonus)
            if item:
                items.append(item.to_dict())
                bonus_items.append(item.full_name())
        game_state["items"] = items
        result.triggered_effect = "cursed_loot"
        result.message = f"房间散发着不祥的气息！你被施加了{debuff_names[chosen_debuff]}，但发现了{num_items}件宝物！"
        result.rewards["curse_applied"] = chosen_debuff
        result.rewards["items_found"] = bonus_items

    def _handle_treasure_vault(self, event: Event, game_state, result: EventResult) -> None:
        current_floor = game_state.get("current_floor", 1)
        turns_limit = event.raw_data.get("turns_limit", 5)
        item_count_range = event.raw_data.get("item_count", [2, 4])
        item_count = random.randint(item_count_range[0], item_count_range[1])
        items = game_state.get("items", [])
        bonus_items = []
        for _ in range(item_count):
            item = self.item_factory.create_random_drop(current_floor + 1)
            if item and item.rarity <= Rarity.GOLD:
                item.rarity = Rarity(min(int(item.rarity) + 1, 4))
            if item:
                items.append(item.to_dict())
                bonus_items.append(item.full_name())
        game_state["items"] = items
        game_state["timed_event"] = {
            "event_id": event.id,
            "turns_remaining": turns_limit,
            "active": True,
        }
        result.triggered_effect = "timed_loot"
        result.message = f"宝藏密室！你有{turns_limit}回合来拿取{item_count}件稀有宝物！"
        result.rewards["turns_limit"] = turns_limit
        result.rewards["items_found"] = bonus_items

    def _handle_mysterious_merchant(self, event: Event, game_state, result: EventResult) -> None:
        current_floor = game_state.get("current_floor", 1)
        num_items = event.raw_data.get("shop_items", 4)
        price_multiplier = event.raw_data.get("price_multiplier", 1.5)
        shop_items = []
        for _ in range(num_items):
            item = self.item_factory.create_random_drop(current_floor)
            if item:
                price = self._calculate_item_price(item, price_multiplier)
                shop_items.append({
                    "item": item.to_dict(),
                    "price": price,
                    "name": item.full_name(),
                })
        npc = {
            "id": "mysterious_merchant",
            "name": "神秘商人",
            "type": "merchant",
            "position": game_state.get("player", Character("", "", None)).position,
            "shop_items": shop_items,
        }
        npcs = game_state.get("npcs", [])
        npcs.append(npc)
        game_state["npcs"] = npcs
        result.triggered_effect = "spawn_merchant"
        result.message = "神秘商人出现了！他有一些有趣的货物出售。"
        result.rewards["npc_spawned"] = npc
        result.choices = [{"name": item["name"] + f" ({item['price']}金币)", "price": item["price"]} for item in shop_items]

    def _calculate_item_price(self, item: Item, multiplier: float) -> int:
        base_price = {
            Rarity.WHITE: 10,
            Rarity.BLUE: 30,
            Rarity.PURPLE: 80,
            Rarity.GOLD: 200,
            Rarity.ORANGE: 500,
        }
        rarity = item.rarity
        price = base_price.get(rarity, 10)
        price += item.total_attack() * 3
        price += item.total_defense() * 2
        price += item.total_speed() * 2
        return int(price * multiplier)

    def _process_wishing_well_choice(self, choice: Dict[str, Any], game_state, result: EventResult) -> None:
        player = game_state.get("player")
        current_floor = game_state.get("current_floor", 1)
        effect = choice.get("effect", "")
        if effect == "random_buff":
            duration = choice.get("duration", 5)
            buffs = ["attack_buff", "defense_buff", "speed_buff"]
            chosen = random.choice(buffs)
            buff_names = {"attack_buff": "攻击", "defense_buff": "防御", "speed_buff": "速度"}
            player.add_status_effect({"name": chosen, "value": 3 + current_floor, "duration": duration})
            result.message = f"许愿井闪烁着光芒！你获得了{buff_names[chosen]}提升！"
            result.rewards["buff"] = chosen
        elif effect == "rare_item":
            item = None
            for _ in range(5):
                candidate = self.item_factory.create_random_drop(current_floor)
                if candidate and candidate.rarity >= Rarity.PURPLE:
                    item = candidate
                    break
            if item is None:
                item = self.item_factory.create_random_drop(current_floor)
                if item:
                    item.rarity = Rarity.PURPLE
            if item:
                player.add_to_inventory(item.to_dict())
                result.message = f"许愿井中升起一件宝物！获得：{item.full_name()}"
                result.rewards["item"] = item.full_name()
            else:
                result.message = "许愿井没有回应..."

    def _process_altar_choice(self, choice: Dict[str, Any], game_state, result: EventResult) -> None:
        player = game_state.get("player")
        current_floor = game_state.get("current_floor", 1)
        effect = choice.get("effect", "")
        if effect == "random_stat_boost":
            value = choice.get("value", 2)
            stats = ["base_attack", "base_defense", "base_speed"]
            chosen = random.choice(stats)
            stat_names = {"base_attack": "攻击", "base_defense": "防御", "base_speed": "速度"}
            current = getattr(player, chosen, 0)
            setattr(player, chosen, current + value)
            result.message = f"祭坛接受了献祭！你的{stat_names[chosen]}永久提升了{value}点！"
            result.rewards["stat_boost"] = {chosen: value}
        elif effect == "rare_item":
            item = self.item_factory.create_random_drop(current_floor + 1)
            if item and item.rarity < Rarity.PURPLE:
                item.rarity = Rarity.PURPLE
            if item:
                player.add_to_inventory(item.to_dict())
                result.message = f"祭坛赐予你一件宝物！获得：{item.full_name()}"
                result.rewards["item"] = item.full_name()
        elif effect == "curse":
            curse_duration = choice.get("curse_duration", 10)
            curses = ["attack_debuff", "defense_debuff", "speed_debuff"]
            chosen = random.choice(curses)
            curse_names = {"attack_debuff": "攻击诅咒", "defense_debuff": "防御诅咒", "speed_debuff": "速度诅咒"}
            player.add_status_effect({"name": chosen, "value": 5 + current_floor, "duration": curse_duration})
            result.message = f"你亵渎了祭坛！{curse_names[chosen]}降临到你身上！"
            result.rewards["curse"] = chosen
