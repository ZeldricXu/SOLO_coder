import json
import uuid
import random
import copy
from enum import Enum
from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional, Tuple
from datetime import datetime, timezone

from .map_generator import MapGenerator, TileType, Room
from .combat_engine import CombatEngine
from .event_system import EventSystem, EventTriggerType
from .multiplayer import MultiplayerSystem, Party, ConnectionState
from .season_challenge import SeasonChallengeSystem, Season
from .item_system import ItemFactory, Item, ItemType, EnhancementSystem
from .character import Character, Classes
from .database import (
    update_player_stats,
    save_run_history,
    get_player_rank,
)


class GamePhase(Enum):
    WAITING = "waiting"
    PLAYER_TURN = "player_turn"
    MONSTER_TURN = "monster_turn"
    EVENT = "event"


class ActionType(Enum):
    MOVE = "MOVE"
    ATTACK = "ATTACK"
    USE_SKILL = "USE_SKILL"
    USE_ITEM = "USE_ITEM"
    EQUIP_ITEM = "EQUIP_ITEM"
    INTERACT = "INTERACT"
    ENHANCE_ITEM = "ENHANCE_ITEM"


@dataclass
class GameState:
    dungeon_id: str
    map: Any
    players: List[Any]
    monsters: List[Dict[str, Any]]
    items: List[Dict[str, Any]]
    npcs: List[Dict[str, Any]]
    current_floor: int
    max_floor: int
    turn_count: int
    phase: GamePhase
    active_event: Optional[Any]
    season: Optional[Season]
    start_time: str
    shared_vision: set = field(default_factory=set)
    last_seen: Dict[str, Any] = field(default_factory=dict)
    monsters_killed: int = 0
    items_found: int = 0
    deaths: int = 0
    party: Optional[Party] = None
    initial_weapon: Optional[Dict[str, Any]] = None
    timed_event: Optional[Dict[str, Any]] = None
    floor_turn_limit: Optional[int] = None
    current_floor_turns: int = 0
    healing_disabled: bool = False
    latest_floor_description: str = ""


class GameManager:
    def __init__(self):
        self.active_dungeons: Dict[str, GameState] = {}
        self.player_to_dungeon: Dict[str, str] = {}
        self.pending_season_runs: Dict[str, Season] = {}
        self.map_generator = MapGenerator()
        self.combat_engine = CombatEngine()
        self.event_system = EventSystem()
        self.multiplayer_system = MultiplayerSystem()
        self.season_system = SeasonChallengeSystem()
        self.item_factory = ItemFactory()
        self._monster_templates = self._load_monster_templates()

    def _load_monster_templates(self) -> List[Dict[str, Any]]:
        import os
        template_path = os.path.join(
            os.path.dirname(__file__),
            "..",
            "data",
            "monsters",
            "templates.json"
        )
        try:
            with open(template_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data.get("monsters", [])
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    def create_dungeon(self, players: List[Any], floor: int = 1, season: Optional[Season] = None) -> GameState:
        dungeon_id = str(uuid.uuid4())
        map_gen = MapGenerator()
        map_gen.generate(60, 40, floor)

        for p in players:
            p.has_acted_this_turn = False
            p.visible_tiles = set()

        party = None
        if len(players) > 1:
            party = Party(dungeon_id, players[0])
            for p in players[1:]:
                party.players.append(p)
                party.turn_order.append(p.client_id)

        game_state = GameState(
            dungeon_id=dungeon_id,
            map=map_gen,
            players=players,
            monsters=[],
            items=[],
            npcs=[],
            current_floor=floor,
            max_floor=10,
            turn_count=0,
            phase=GamePhase.PLAYER_TURN,
            active_event=None,
            season=season,
            start_time=datetime.now(timezone.utc).isoformat(),
            party=party,
        )

        if season:
            if players and len(players) > 0:
                p0 = players[0]
                if p0.equipment and "weapon" in p0.equipment and p0.equipment["weapon"]:
                    game_state.initial_weapon = copy.deepcopy(p0.equipment["weapon"])
            gs_dict = self._game_state_to_dict(game_state)
            gs_dict = self.season_system.apply_challenge_rules(season.challenges, gs_dict)
            self._update_game_state_from_dict(game_state, gs_dict)

        first_room = map_gen.rooms[0]
        entrance_pos = first_room.center()
        for i, p in enumerate(players):
            px, py = entrance_pos
            offset = i * 2
            p.position = (px + offset, py)
            self._update_visible_tiles(p, map_gen)

        self.spawn_monsters(game_state, floor)
        self.spawn_items(game_state, floor)

        self.active_dungeons[dungeon_id] = game_state
        for p in players:
            self.player_to_dungeon[p.client_id] = dungeon_id

        return game_state

    def _update_visible_tiles(self, player: Any, map_gen: MapGenerator) -> None:
        px, py = player.position
        vision_range = 6
        player.visible_tiles.clear()
        for dy in range(-vision_range, vision_range + 1):
            for dx in range(-vision_range, vision_range + 1):
                if abs(dx) + abs(dy) > vision_range:
                    continue
                nx, ny = px + dx, py + dy
                if 0 <= ny < map_gen.height and 0 <= nx < map_gen.width:
                    if map_gen.tiles[ny][nx] != TileType.WALL:
                        player.visible_tiles.add((nx, ny))

    def _game_state_to_dict(self, gs: GameState) -> Dict[str, Any]:
        return {
            "players": [p.to_dict() for p in gs.players],
            "monsters": list(gs.monsters),
            "items": list(gs.items),
            "npcs": list(gs.npcs),
            "current_floor": gs.current_floor,
            "player": gs.players[0] if gs.players else None,
            "timed_event": gs.timed_event,
            "floor_turn_limit": gs.floor_turn_limit,
            "current_floor_turns": gs.current_floor_turns,
            "healing_disabled": gs.healing_disabled,
            "initial_weapon": gs.initial_weapon,
        }

    def _update_game_state_from_dict(self, gs: GameState, data: Dict[str, Any]) -> None:
        gs.monsters = data.get("monsters", gs.monsters)
        gs.items = data.get("items", gs.items)
        gs.npcs = data.get("npcs", gs.npcs)
        gs.timed_event = data.get("timed_event", gs.timed_event)
        gs.floor_turn_limit = data.get("floor_turn_limit", gs.floor_turn_limit)
        gs.current_floor_turns = data.get("current_floor_turns", gs.current_floor_turns)
        gs.healing_disabled = data.get("healing_disabled", gs.healing_disabled)
        gs.initial_weapon = data.get("initial_weapon", gs.initial_weapon)

    async def process_player_action(self, dungeon_id: str, player_id: str, action: str, params: Dict[str, Any]) -> Dict[str, Any]:
        game_state = self.active_dungeons.get(dungeon_id)
        if not game_state:
            return {"success": False, "error": "dungeon_not_found"}

        if game_state.phase != GamePhase.PLAYER_TURN:
            return {"success": False, "error": "not_player_turn"}

        player = next((p for p in game_state.players if p.client_id == player_id), None)
        if not player:
            return {"success": False, "error": "player_not_found"}

        if not player.alive:
            return {"success": False, "error": "player_dead"}

        if player.has_acted_this_turn:
            return {"success": False, "error": "already_acted"}

        if not player.can_act():
            return {"success": False, "error": "cannot_act"}

        if game_state.season:
            action_data = {"type": action.lower(), "item": params.get("item", {})}
            for challenge in game_state.season.challenges:
                if not self.season_system.validate_challenge_rule(challenge, action_data, self._game_state_to_dict(game_state)):
                    return {"success": False, "error": "challenge_violation"}

        try:
            action_enum = ActionType(action)
        except ValueError:
            return {"success": False, "error": "invalid_action"}

        result = {"success": False}

        if action_enum == ActionType.MOVE:
            result = await self._handle_move(game_state, player, params)
        elif action_enum == ActionType.ATTACK:
            result = self._handle_attack(game_state, player, params)
        elif action_enum == ActionType.USE_SKILL:
            result = self._handle_use_skill(game_state, player, params)
        elif action_enum == ActionType.USE_ITEM:
            result = self._handle_use_item(game_state, player, params)
        elif action_enum == ActionType.EQUIP_ITEM:
            result = self._handle_equip_item(game_state, player, params)
        elif action_enum == ActionType.INTERACT:
            result = await self._handle_interact(game_state, player, params)
        elif action_enum == ActionType.ENHANCE_ITEM:
            result = self._handle_enhance(game_state, player, params)

        if result.get("success"):
            player.has_acted_this_turn = True
            await self.advance_turn(dungeon_id)

        result["game_state"] = self.get_game_state_view(dungeon_id, player_id)
        return result

    async def _handle_move(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        dx = params.get("dx", 0)
        dy = params.get("dy", 0)
        if dx not in (-1, 0, 1) or dy not in (-1, 0, 1):
            return {"success": False, "error": "invalid_direction"}
        if dx == 0 and dy == 0:
            return {"success": True, "type": "wait"}

        px, py = player.position
        nx, ny = px + dx, py + dy

        if not (0 <= ny < gs.map.height and 0 <= nx < gs.map.width):
            return {"success": False, "error": "out_of_bounds"}

        tile = gs.map.tiles[ny][nx]
        if tile == TileType.WALL:
            return {"success": False, "error": "wall"}

        occupied = False
        for p in gs.players:
            if p.client_id != player.client_id and p.alive:
                if p.position == (nx, ny):
                    occupied = True
                    break
        if occupied:
            return {"success": False, "error": "occupied_by_player"}

        for m in gs.monsters:
            if m.get("alive", True) and tuple(m.get("position", (0, 0))) == (nx, ny):
                combat_result = self.combat_engine.resolve_attack(player.to_dict(), m)
                if combat_result.killed:
                    gs.monsters_killed += 1
                    self._handle_monster_death(gs, m)
                return {"success": True, "type": "attack", "combat_result": combat_result.__dict__}

        player.position = (nx, ny)
        self._update_visible_tiles(player, gs.map)

        if gs.party:
            gs.shared_vision = self.multiplayer_system.update_shared_vision(gs.party, gs.map)

        old_room_id = gs.map.get_room_at(px, py)
        new_room_id = gs.map.get_room_at(nx, ny)
        if old_room_id != new_room_id and new_room_id is not None:
            event_result = self.process_event_trigger(gs.dungeon_id, EventTriggerType.ENTER_ROOM, new_room_id)
            if event_result:
                return {"success": True, "type": "move", "event": event_result}

        if tile == TileType.STAIRS_DOWN:
            if await self.check_floor_complete(gs.dungeon_id):
                return {"success": True, "type": "floor_complete"}

        return {"success": True, "type": "move", "position": [nx, ny]}

    def _handle_attack(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        target_id = params.get("target_id")
        target = next((m for m in gs.monsters if m.get("id") == target_id), None)
        if not target:
            return {"success": False, "error": "target_not_found"}

        if not target.get("alive", True):
            return {"success": False, "error": "target_dead"}

        ppos = player.position
        tpos = tuple(target.get("position", (0, 0)))
        dist = abs(ppos[0] - tpos[0]) + abs(ppos[1] - tpos[1])
        attack_range = 1
        if dist > attack_range:
            return {"success": False, "error": "out_of_range"}

        combat_result = self.combat_engine.resolve_attack(player.to_dict(), target)
        if combat_result.killed:
            gs.monsters_killed += 1
            self._handle_monster_death(gs, target)

        event_result = self.process_event_trigger(gs.dungeon_id, EventTriggerType.ON_KILL, None)

        return {"success": True, "type": "attack", "combat_result": combat_result.__dict__, "event": event_result}

    def _handle_use_skill(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        skill_id = params.get("skill_id")
        target_id = params.get("target_id")

        skill = player.use_skill(skill_id)
        if not skill:
            return {"success": False, "error": "skill_unavailable"}

        target = next((m for m in gs.monsters if m.get("id") == target_id), None)
        if not target:
            target = next((p for p in gs.players if p.client_id == target_id), None)
        if not target:
            target = player

        gs_dict = self._game_state_to_dict(gs)
        skill_result = self.combat_engine.apply_skill(player.to_dict(), target.to_dict() if hasattr(target, "to_dict") else target, skill, gs_dict)

        if not skill_result.get("success"):
            player.mana += skill.get("mana_cost", 0)
            return {"success": False, "error": skill_result.get("reason", "skill_failed")}

        for m in gs.monsters:
            if m.get("hp", 0) <= 0 and m.get("alive", True):
                gs.monsters_killed += 1
                self._handle_monster_death(gs, m)

        return {"success": True, "type": "skill", "skill_result": skill_result}

    def _handle_use_item(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        item_slot = params.get("item_slot")
        target_id = params.get("target_id")

        if item_slot is None or not (0 <= item_slot < len(player.inventory)):
            return {"success": False, "error": "invalid_item_slot"}

        item = player.inventory[item_slot]
        if not item:
            return {"success": False, "error": "no_item"}

        if gs.healing_disabled and item.get("consumable_effect") == "heal":
            return {"success": False, "error": "healing_disabled"}

        target = next((p for p in gs.players if p.client_id == target_id), None)
        if not target:
            target = player

        if item.get("consumable_effect") == "heal":
            heal_val = item.get("consumable_value", 0)
            actual = target.heal(heal_val)
            player.inventory.pop(item_slot)
            return {"success": True, "type": "heal", "amount": actual, "target": target_id}

        elif item.get("consumable_effect") == "mana":
            mana_val = item.get("consumable_value", 0)
            old_mana = target.mana
            target.mana = min(target.max_mana, old_mana + mana_val)
            actual = target.mana - old_mana
            player.inventory.pop(item_slot)
            return {"success": True, "type": "mana", "amount": actual, "target": target_id}

        elif item.get("consumable_effect") == "resurrection":
            if target.alive:
                return {"success": False, "error": "target_alive"}
            target.resurrect()
            player.inventory.pop(item_slot)
            return {"success": True, "type": "resurrection", "target": target_id}

        return {"success": False, "error": "unusable_item"}

    def _handle_equip_item(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        item_slot = params.get("item_slot")

        if item_slot is None or not (0 <= item_slot < len(player.inventory)):
            return {"success": False, "error": "invalid_item_slot"}

        item = player.inventory[item_slot]
        if not item:
            return {"success": False, "error": "no_item"}

        if item.get("item_type") == ItemType.CONSUMABLE:
            return {"success": False, "error": "cannot_equip_consumable"}

        success = player.equip_item(item)
        if success:
            player.inventory.pop(item_slot)
            return {"success": True, "type": "equip", "item": item}

        return {"success": False, "error": "equip_failed"}

    async def _handle_interact(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        px, py = player.position
        tile = gs.map.tiles[py][px]

        if tile == TileType.CHEST:
            gs.map.tiles[py][px] = TileType.FLOOR
            floor_depth = gs.current_floor
            item = self.item_factory.create_random_drop(floor_depth + 1)
            if item:
                gs.items_found += 1
                player.add_to_inventory(item.to_dict())
                return {"success": True, "type": "chest", "item": item.full_name()}
            return {"success": True, "type": "chest_empty"}

        elif tile == TileType.ALTAR:
            event_result = self.process_event_trigger(gs.dungeon_id, EventTriggerType.INTERACT, None)
            gs.map.tiles[py][px] = TileType.FLOOR
            if event_result:
                return {"success": True, "type": "altar", "event": event_result}
            enhance_altar = {
                "event_id": str(uuid.uuid4()),
                "type": "enhance_altar",
                "title": "强化祭坛",
                "description": "古老的祭坛散发着神秘的光芒。你可以在这里强化你的装备。",
                "choices": [
                    {"id": "show_items", "text": "查看可强化装备"},
                    {"id": "leave", "text": "离开"}
                ]
            }
            return {"success": True, "type": "altar", "event": enhance_altar}

        elif tile == TileType.STAIRS_UP:
            return {"success": False, "error": "cannot_go_back"}

        elif tile == TileType.STAIRS_DOWN:
            if await self.check_floor_complete(gs.dungeon_id):
                return {"success": True, "type": "floor_complete"}
            return {"success": False, "error": "wait_for_teammates"}

        npc = next((n for n in gs.npcs if tuple(n.get("position", (0, 0))) == (px, py)), None)
        if npc:
            return {"success": True, "type": "npc", "npc": npc}

        return {"success": False, "error": "nothing_to_interact"}

    def _handle_enhance(self, gs: GameState, player: Any, params: Dict[str, Any]) -> Dict[str, Any]:
        action = params.get("action", "")
        item_slot = params.get("item_slot")
        use_protection = params.get("use_protection", False)

        if action == "check":
            if item_slot is None or not (0 <= item_slot < len(player.inventory)):
                return {"success": False, "error": "invalid_item_slot"}
            item_dict = player.inventory[item_slot]
            if not item_dict:
                return {"success": False, "error": "no_item"}
            item = Item.from_dict(item_dict)
            if not EnhancementSystem.can_enhance(item):
                return {"success": False, "error": "cannot_enhance"}
            cost = EnhancementSystem.get_enhance_cost(item, gs.current_floor)
            return {"success": True, "type": "enhance_check", "cost": cost, "item": item.to_dict()}

        elif action == "enhance":
            if item_slot is None or not (0 <= item_slot < len(player.inventory)):
                return {"success": False, "error": "invalid_item_slot"}
            item_dict = player.inventory[item_slot]
            if not item_dict:
                return {"success": False, "error": "no_item"}

            item = Item.from_dict(item_dict)
            result = EnhancementSystem.enhance(item, player, use_protection, gs.current_floor)

            if result.get("success"):
                player.inventory[item_slot] = item.to_dict()

            return {"success": result.get("success", True), "type": "enhance_result", "result": result}

        elif action == "check_equipment":
            slot_name = params.get("slot_name", "")
            equipped = player.equipment.get(slot_name)
            if not equipped:
                return {"success": False, "error": "no_equipment"}
            item = Item.from_dict(equipped)
            if not EnhancementSystem.can_enhance(item):
                return {"success": False, "error": "cannot_enhance"}
            cost = EnhancementSystem.get_enhance_cost(item, gs.current_floor)
            return {"success": True, "type": "enhance_check", "cost": cost, "item": equipped}

        elif action == "enhance_equipment":
            slot_name = params.get("slot_name", "")
            equipped = player.equipment.get(slot_name)
            if not equipped:
                return {"success": False, "error": "no_equipment"}

            item = Item.from_dict(equipped)
            result = EnhancementSystem.enhance(item, player, use_protection, gs.current_floor)

            if result.get("success"):
                player.equipment[slot_name] = item.to_dict()

            return {"success": result.get("success", True), "type": "enhance_result", "result": result}

        return {"success": False, "error": "invalid_enhance_action"}

    def _handle_monster_death(self, gs: GameState, monster: Dict[str, Any]) -> None:
        loot_table = monster.get("loot_table", [])
        for drop in loot_table:
            if random.random() < drop.get("drop_rate", 0):
                item = self.item_factory.create_item(drop["item_id"], gs.current_floor)
                if item:
                    gs.items.append({
                        "item": item.to_dict(),
                        "position": monster.get("position", (0, 0))
                    })

    async def advance_turn(self, dungeon_id: str) -> None:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        all_acted = True
        for p in gs.players:
            if p.alive and not p.has_acted_this_turn:
                all_acted = False
                break

        if not all_acted:
            return

        gs.phase = GamePhase.MONSTER_TURN
        await self.process_monster_turn(dungeon_id)

        gs.turn_count += 1
        gs.current_floor_turns += 1

        for p in gs.players:
            cooldowns = p.skill_cooldowns
            expired = [k for k, v in cooldowns.items() if v <= 0]
            for k in expired:
                del cooldowns[k]
            for k in cooldowns:
                cooldowns[k] -= 1

        for p in gs.players:
            p.has_acted_this_turn = False

        if gs.party:
            self.multiplayer_system.reset_turn_actions(gs.party)

        gs.phase = GamePhase.PLAYER_TURN

        if gs.timed_event and gs.timed_event.get("active", False):
            gs.timed_event["turns_remaining"] -= 1
            if gs.timed_event["turns_remaining"] <= 0:
                gs.timed_event["active"] = False

        if gs.floor_turn_limit is not None and gs.current_floor_turns >= gs.floor_turn_limit:
            await self.complete_run(dungeon_id, "time_limit_exceeded")

    async def process_monster_turn(self, dungeon_id: str) -> None:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        gs_dict = {
            "players": [p.to_dict() for p in gs.players],
            "monsters": list(gs.monsters),
            "map_tiles": gs.map.tiles,
            "current_floor": gs.current_floor,
        }

        result = self.combat_engine.process_turn(gs_dict)

        for i, m in enumerate(result.get("monsters", [])):
            if i < len(gs.monsters):
                gs.monsters[i].update(m)

        for i, p in enumerate(result.get("players", [])):
            if i < len(gs.players):
                if p.get("hp", 0) <= 0 and gs.players[i].alive:
                    gs.players[i].die()
                    await self.handle_player_death(dungeon_id, gs.players[i].client_id)
                else:
                    gs.players[i].hp = p.get("hp", gs.players[i].hp)
                    gs.players[i].status_effects = p.get("status_effects", gs.players[i].status_effects)

        alive_monsters = [m for m in gs.monsters if m.get("alive", True)]
        gs.monsters = alive_monsters

    async def check_floor_complete(self, dungeon_id: str) -> bool:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return False

        alive_players = [p for p in gs.players if p.alive]
        if not alive_players:
            return False

        for p in alive_players:
            px, py = p.position
            if gs.map.tiles[py][px] != TileType.STAIRS_DOWN:
                return False

        if gs.current_floor >= gs.max_floor:
            await self.complete_run(dungeon_id, "completed_all_floors")
        else:
            await self.advance_floor(dungeon_id)
        return True

    async def advance_floor(self, dungeon_id: str) -> None:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        old_last_room = gs.map.rooms[-1]
        old_exit_pos = old_last_room.center()

        gs.current_floor += 1
        gs.current_floor_turns = 0

        map_gen = MapGenerator()
        map_gen.generate(60, 40, gs.current_floor, previous_stairs_pos=old_exit_pos)
        gs.map = map_gen

        first_room = map_gen.rooms[0]
        entrance_pos = first_room.center()
        for i, p in enumerate(gs.players):
            px, py = entrance_pos
            offset = i * 2
            p.position = (px + offset, py)
            self._update_visible_tiles(p, map_gen)
            p.has_acted_this_turn = False

        gs.monsters.clear()
        gs.items.clear()
        gs.npcs.clear()

        self.spawn_monsters(gs, gs.current_floor)
        self.spawn_items(gs, gs.current_floor)

        if gs.party:
            gs.shared_vision = self.multiplayer_system.update_shared_vision(gs.party, map_gen)

        floor_desc = self._get_staircase_description(gs.current_floor)
        gs.latest_floor_description = floor_desc

    async def complete_run(self, dungeon_id: str, cause: str) -> None:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        start_dt = datetime.fromisoformat(gs.start_time.replace("Z", "+00:00"))
        duration = (datetime.now(timezone.utc) - start_dt).total_seconds()

        challenges = gs.season.challenges if gs.season else []
        score = self.season_system.calculate_score(
            floor_reached=gs.current_floor,
            monsters_killed=gs.monsters_killed,
            items_found=gs.items_found,
            duration_seconds=int(duration),
            deaths=gs.deaths,
            challenges=challenges,
        )

        for p in gs.players:
            player_id = p.id if hasattr(p, "id") else p.client_id
            class_name = p.class_type.value if hasattr(p.class_type, "value") else str(p.class_type)

            await update_player_stats(
                player_id=player_id,
                class_name=class_name,
                max_floor_reached=gs.current_floor,
                total_monsters_killed=gs.monsters_killed,
                total_deaths=gs.deaths,
                total_items_found=gs.items_found,
            )

            await save_run_history(
                player_id=player_id,
                class_name=class_name,
                floor_reached=gs.current_floor,
                monsters_killed=gs.monsters_killed,
                items_found=gs.items_found,
                death_cause=cause,
                duration_seconds=duration,
            )

        if gs.season:
            for p in gs.players:
                player_id = p.id if hasattr(p, "id") else p.client_id
                run_stats = {
                    "floor_reached": gs.current_floor,
                    "monsters_killed": gs.monsters_killed,
                    "items_found": gs.items_found,
                    "duration_seconds": int(duration),
                    "deaths": gs.deaths,
                }
                await self.season_system.submit_score({"id": player_id}, run_stats, gs.season)

        for p in gs.players:
            if p.client_id in self.player_to_dungeon:
                del self.player_to_dungeon[p.client_id]

        del self.active_dungeons[dungeon_id]

    def spawn_monsters(self, dungeon_id: str, floor_depth: int) -> None:
        if isinstance(dungeon_id, GameState):
            gs = dungeon_id
            floor_depth = gs.current_floor
        else:
            gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        eligible = [m for m in self._monster_templates if m.get("min_floor", 1) <= floor_depth]
        bosses = [m for m in eligible if "boss" in m.get("tags", [])]
        regular = [m for m in eligible if "boss" not in m.get("tags", [])]

        first_room = gs.map.rooms[0]
        last_room = gs.map.rooms[-1]
        entrance_center = first_room.center()

        occupied_positions = set()
        for p in gs.players:
            occupied_positions.add(p.position)

        num_monsters = min(3 + floor_depth, 8)
        spawnable_rooms = [r for r in gs.map.rooms if r.id != first_room.id]

        for _ in range(num_monsters):
            if not regular or not spawnable_rooms:
                break
            template = random.choice(regular)
            room = random.choice(spawnable_rooms)
            room_tiles = [t for t in room.tiles if t not in occupied_positions]
            if not room_tiles:
                continue

            dist_to_entrance = abs(room.center()[0] - entrance_center[0]) + abs(room.center()[1] - entrance_center[1])
            if dist_to_entrance < 5:
                continue

            pos = random.choice(room_tiles)
            occupied_positions.add(pos)

            monster = copy.deepcopy(template)
            scale = 1 + (floor_depth - template.get("min_floor", 1)) * 0.1
            monster["hp"] = int(monster["hp"] * scale)
            monster["max_hp"] = monster["hp"]
            monster["attack"] = int(monster["attack"] * scale)
            monster["defense"] = int(monster["defense"] * scale)
            monster["position"] = list(pos)
            monster["alive"] = True
            monster["skills"] = []
            monster["cooldowns"] = {}
            monster["status_effects"] = []

            gs.monsters.append(monster)

        if floor_depth % 3 == 0 and bosses:
            boss_template = random.choice(bosses)
            boss = copy.deepcopy(boss_template)
            boss_pos = last_room.center()
            boss["position"] = list(boss_pos)
            boss["hp"] = int(boss["hp"] * (1 + floor_depth * 0.05))
            boss["max_hp"] = boss["hp"]
            boss["alive"] = True
            boss["skills"] = []
            boss["cooldowns"] = {}
            boss["status_effects"] = []
            gs.monsters.append(boss)

    def spawn_items(self, dungeon_id: str, floor_depth: int) -> None:
        if isinstance(dungeon_id, GameState):
            gs = dungeon_id
            floor_depth = gs.current_floor
        else:
            gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        first_room = gs.map.rooms[0]
        last_room = gs.map.rooms[-1]
        spawnable_rooms = [r for r in gs.map.rooms if r.id != first_room.id and r.id != last_room.id]

        num_items = random.randint(2, 5)
        occupied = set()
        for p in gs.players:
            occupied.add(p.position)
        for m in gs.monsters:
            occupied.add(tuple(m.get("position", (0, 0))))

        for _ in range(num_items):
            if not spawnable_rooms:
                break
            room = random.choice(spawnable_rooms)
            room_tiles = [t for t in room.tiles if t not in occupied]
            if not room_tiles:
                continue
            pos = random.choice(room_tiles)
            occupied.add(pos)

            r = random.random()
            if r < 0.4:
                item = self.item_factory.create_item("health_potion", floor_depth)
            elif r < 0.6:
                item = self.item_factory.create_item("mana_potion", floor_depth)
            else:
                item = self.item_factory.create_random_drop(floor_depth)

            if item:
                gs.items.append({
                    "item": item.to_dict(),
                    "position": list(pos)
                })

    def get_game_state_view(self, dungeon_id: str, player_id: str) -> Dict[str, Any]:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return {}

        player = next((p for p in gs.players if p.client_id == player_id), None)
        if not player:
            return {}

        visible_tiles = gs.shared_vision if gs.party else player.visible_tiles

        map_view = []
        for y in range(gs.map.height):
            row = []
            for x in range(gs.map.width):
                if (x, y) in visible_tiles:
                    tile = gs.map.tiles[y][x]
                    row.append(int(tile))
                    gs.last_seen[(x, y)] = int(tile)
                elif (x, y) in gs.last_seen:
                    row.append(gs.last_seen[(x, y)])
                else:
                    row.append(-1)
            map_view.append(row)

        visible_players = []
        for p in gs.players:
            if p.position in visible_tiles or p.client_id == player_id:
                visible_players.append(p.to_dict())

        visible_monsters = []
        for m in gs.monsters:
            if tuple(m.get("position", (0, 0))) in visible_tiles:
                visible_monsters.append(m)

        visible_items = []
        for item in gs.items:
            if tuple(item.get("position", (0, 0))) in visible_tiles:
                visible_items.append(item)

        visible_npcs = []
        for npc in gs.npcs:
            if tuple(npc.get("position", (0, 0))) in visible_tiles:
                visible_npcs.append(npc)

        return {
            "dungeon_id": gs.dungeon_id,
            "map": map_view,
            "players": visible_players,
            "monsters": visible_monsters,
            "items": visible_items,
            "npcs": visible_npcs,
            "current_floor": gs.current_floor,
            "max_floor": gs.max_floor,
            "turn_count": gs.turn_count,
            "phase": gs.phase.value,
            "season": gs.season.to_dict() if gs.season else None,
            "current_player": player.to_dict(),
            "shared_vision": [list(t) for t in visible_tiles],
            "floor_description": gs.latest_floor_description if hasattr(gs, 'latest_floor_description') else "",
        }

    def process_event_trigger(self, dungeon_id: str, trigger_type: EventTriggerType, room_id: Optional[int]) -> Optional[Any]:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return None

        gs_dict = self._game_state_to_dict(gs)
        result = self.event_system.check_trigger(trigger_type, room_id, gs_dict)
        self._update_game_state_from_dict(gs, gs_dict)

        if result and (result.choices or result.triggered_effect):
            gs.phase = GamePhase.EVENT
            gs.active_event = result

        return result

    async def handle_player_death(self, dungeon_id: str, player_id: str) -> None:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return

        player = next((p for p in gs.players if p.client_id == player_id), None)
        if not player:
            return

        if player.has_resurrection:
            player.has_resurrection = False
            player.hp = player.max_hp // 2
            player.mana = player.max_mana // 2
            player.status_effects.clear()
            player.alive = True
            return

        gs.deaths += 1

        alive_teammates = [p for p in gs.players if p.alive and p.client_id != player_id]
        if not alive_teammates:
            can_revive = False
            for p in gs.players:
                for item in p.inventory:
                    if item.get("consumable_effect") == "resurrection":
                        can_revive = True
                        break
                if can_revive:
                    break
            if not can_revive:
                await self.complete_run(dungeon_id, "party_wiped")

    def start_season_run(self, player_id: str, season_id: int) -> str:
        from .season_challenge import Season
        season_data = {
            "id": season_id,
            "name": f"Season {season_id}",
            "start_date": datetime.now(timezone.utc).isoformat(),
            "end_date": None,
            "challenge_rules": [],
            "is_active": True,
            "seed": random.randint(0, 999999),
        }
        season = Season.from_dict(season_data)
        self.pending_season_runs[player_id] = season
        return season_id

    def start_normal_run(self, player_id: str) -> str:
        if player_id in self.pending_season_runs:
            del self.pending_season_runs[player_id]
        return str(uuid.uuid4())

    def _get_staircase_description(self, floor: int) -> str:
        descriptions = {
            1: "你小心翼翼地踏入地牢入口，空气中弥漫着潮湿和霉味...",
            2: "你走下潮湿的石阶，墙壁上的苔藓闪烁着微弱的磷光...",
            3: "阶梯向下延伸，火把的光芒在远处摇曳...",
            4: "脚下的石阶变得湿滑，你闻到了更深层次的腐朽气息...",
            5: "寒气从石缝中渗出，你不禁打了个寒颤...",
            6: "两侧墙壁上出现了古老的符文，仿佛在警告来者...",
            7: "楼梯变得陡峭，远处传来低沉的咆哮声...",
            8: "空气变得稀薄，你能感觉到强大的魔力波动...",
            9: "每一步都仿佛踏在历史的尘埃上，这里很久没有活人来过...",
            10: "地牢最深处，邪恶的气息几乎令人窒息...",
        }
        return descriptions.get(min(floor, 10), f"你深入地牢第{floor}层...")

    def add_player_to_dungeon(self, dungeon_id: str, new_player: Any) -> Dict[str, Any]:
        gs = self.active_dungeons.get(dungeon_id)
        if not gs:
            return {"success": False, "error": "dungeon_not_found"}

        if gs.party and len(gs.party.players) >= Party.MAX_SIZE:
            return {"success": False, "error": "party_full"}

        min_level = 1
        if gs.party and gs.party.players:
            min_level = min(p.level for p in gs.party.players) - 2
            min_level = max(1, min_level)

        new_player.level = min_level

        self._give_basic_equipment(new_player)

        first_room = gs.map.rooms[0]
        entrance_pos = first_room.center()
        offset = len(gs.players) * 2
        new_player.position = (entrance_pos[0] + offset, entrance_pos[1])
        new_player.has_acted_this_turn = False
        new_player.visible_tiles = set()
        self._update_visible_tiles(new_player, gs.map)

        gs.players.append(new_player)
        self.player_to_dungeon[new_player.client_id] = dungeon_id

        if gs.party:
            gs.party.players.append(new_player)
            gs.party.turn_order.append(new_player.client_id)
            gs.shared_vision = self.multiplayer_system.update_shared_vision(gs.party, gs.map)
        else:
            party = Party(dungeon_id, gs.players[0])
            party.players.append(new_player)
            party.turn_order.append(new_player.client_id)
            gs.party = party

        gs.shared_vision = gs.shared_vision or set()
        gs.shared_vision.update(new_player.visible_tiles)

        return {
            "success": True,
            "game_state": self.get_game_state_view(dungeon_id, new_player.client_id),
            "player": new_player.to_dict(),
        }

    def _give_basic_equipment(self, player: Any) -> None:
        from .item_system import ItemFactory, Item, Rarity, ItemType
        factory = ItemFactory()
        class_type = player.class_type

        weapon_map = {
            Classes.WARRIOR: "iron_sword",
            Classes.MAGE: "wooden_staff",
            Classes.ROGUE: "iron_dagger",
            Classes.PRIEST: "mace",
        }

        armor_map = {
            Classes.WARRIOR: "leather_armor",
            Classes.MAGE: "cloth_robe",
            Classes.ROGUE: "leather_tunic",
            Classes.PRIEST: "cloth_robe",
        }

        weapon_id = weapon_map.get(class_type, "iron_sword")
        armor_id = armor_map.get(class_type, "cloth_robe")

        base_weapon = {
            "id": weapon_id,
            "name": "新手" + ("剑" if class_type == Classes.WARRIOR else "法杖" if class_type == Classes.MAGE else "匕首" if class_type == Classes.ROGUE else "权杖"),
            "item_type": ItemType.WEAPON,
            "rarity": 0,
            "attack": 3,
            "defense": 0,
            "speed": 0,
            "slot": "weapon",
            "prefix_name": "",
            "suffix_name": "",
            "prefix_stats": {},
            "suffix_stats": {},
            "enhance_level": 0,
        }

        base_armor = {
            "id": armor_id,
            "name": "新手" + ("皮甲" if class_type in (Classes.WARRIOR, Classes.ROGUE) else "布袍"),
            "item_type": ItemType.ARMOR,
            "rarity": 0,
            "attack": 0,
            "defense": 2,
            "speed": 0,
            "slot": "chest",
            "prefix_name": "",
            "suffix_name": "",
            "prefix_stats": {},
            "suffix_stats": {},
            "enhance_level": 0,
        }

        player.equip_item(base_weapon)
        player.equip_item(base_armor)

        potion = factory.create_item("health_potion", 1)
        if potion:
            player.add_to_inventory(potion.to_dict())
            player.add_to_inventory(potion.to_dict())
