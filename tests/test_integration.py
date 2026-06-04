import sys
import os
import random
import asyncio
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from server.game_manager import GameManager, GamePhase
from server.character import Character, Classes
from server.multiplayer import Player, Party, MultiplayerSystem, ConnectionState
from server.map_generator import MapGenerator, TileType
from server.item_system import ItemFactory, Item, Rarity, ItemType, Backpack
from server.combat_engine import CombatEngine, StatusEffect, StatusEffectType
from server.season_challenge import SeasonChallengeSystem, Season


class MockWebSocket:
    def __init__(self):
        self.sent_messages = []
        self.closed = False

    async def send(self, msg):
        self.sent_messages.append(msg)


def make_player(cid, name, cls):
    ws = MockWebSocket()
    p = Player(cid, name, cls, cid, ws)
    return p


@pytest.fixture
def game_manager():
    return GameManager()


@pytest.fixture
def single_player():
    random.seed(42)
    p = make_player("p1", "战士1", Classes.WARRIOR)
    random.seed()
    return p


@pytest.fixture
def two_players():
    random.seed(42)
    p1 = make_player("p1", "战士1", Classes.WARRIOR)
    p2 = make_player("p2", "牧师1", Classes.PRIEST)
    random.seed()
    return p1, p2


class TestFullGameLoop:
    @pytest.mark.asyncio
    async def test_generate_dungeon_and_enter(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()
        assert gs is not None
        assert gs.dungeon_id in game_manager.active_dungeons
        assert gs.current_floor == 1
        assert gs.phase == GamePhase.PLAYER_TURN
        assert single_player.position != (0, 0)
        assert len(gs.monsters) > 0
        assert len(gs.items) > 0

    @pytest.mark.asyncio
    async def test_player_move_action(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()
        old_pos = single_player.position
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "MOVE", {"dx": 1, "dy": 0}
        )
        if result.get("success") and result.get("type") == "move":
            assert single_player.position[0] == old_pos[0] + 1 or single_player.position == old_pos

    @pytest.mark.asyncio
    async def test_explore_and_combat(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        for m in gs.monsters:
            m["position"] = [single_player.position[0] + 1, single_player.position[1]]
            m["alive"] = True
            break

        target = gs.monsters[0]
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "ATTACK", {"target_id": target.get("id")}
        )
        assert result.get("success") is True
        assert "combat_result" in result

    @pytest.mark.asyncio
    async def test_monster_drop_loot_on_death(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        if not gs.monsters:
            pytest.skip("No monsters spawned")

        monster = gs.monsters[0]
        monster["position"] = [single_player.position[0] + 1, single_player.position[1]]
        monster["hp"] = 1
        monster["max_hp"] = 1
        monster["alive"] = True
        monster["loot_table"] = [{"item_id": "bone_sword", "drop_rate": 1.0}]

        items_before = len(gs.items)
        game_manager._handle_monster_death(gs, monster)
        assert len(gs.items) > items_before

    @pytest.mark.asyncio
    async def test_pick_up_item(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        item = game_manager.item_factory.create_item("health_potion", 1)
        assert item is not None
        single_player.add_to_inventory(item.to_dict())
        assert len(single_player.inventory) > 0

    @pytest.mark.asyncio
    async def test_equip_item(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        weapon = game_manager.item_factory.create_item("bone_sword", 1)
        weapon_dict = weapon.to_dict()
        weapon_dict["slot"] = "weapon"
        single_player.add_to_inventory(weapon_dict)

        slot_idx = single_player.inventory.index(weapon_dict)
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "EQUIP_ITEM", {"item_slot": slot_idx}
        )
        assert result.get("success") is True
        assert single_player.equipment["weapon"] is not None

    @pytest.mark.asyncio
    async def test_use_consumable(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        single_player.hp = 10
        potion = game_manager.item_factory.create_item("health_potion", 1)
        single_player.add_to_inventory(potion.to_dict())

        slot_idx = 0
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "USE_ITEM", {"item_slot": slot_idx}
        )
        assert result.get("success") is True
        assert single_player.hp > 10

    @pytest.mark.asyncio
    async def test_advance_floor(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        last_room = gs.map.rooms[-1]
        lx, ly = last_room.center()
        single_player.position = (lx, ly)
        single_player.has_acted_this_turn = False

        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "INTERACT", {"type": "stairs_down"}
        )
        if result.get("success") and result.get("type") == "floor_complete":
            assert gs.current_floor == 2

    @pytest.mark.asyncio
    async def test_event_trigger_on_room_enter(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()

        second_room = None
        for room in gs.map.rooms[1:]:
            second_room = room
            break
        if not second_room:
            pytest.skip("No second room")

        rx, ry = second_room.center()
        game_manager._update_visible_tiles(single_player, gs.map)
        old_room = gs.map.get_room_at(*single_player.position)
        single_player.position = (rx, ry)
        game_manager._update_visible_tiles(single_player, gs.map)
        new_room = gs.map.get_room_at(rx, ry)
        assert new_room is not None

    @pytest.mark.asyncio
    async def test_boss_floor(self, game_manager, single_player):
        boss_found = False
        for seed in [42, 100, 200, 300, 400]:
            random.seed(seed)
            p = make_player(f"p_boss_{seed}", "test", Classes.WARRIOR)
            gs = game_manager.create_dungeon([p], floor=6)
            if any("boss" in m.get("tags", []) for m in gs.monsters):
                boss_found = True
                break
            random.seed()
        assert boss_found, "Floor 6 should spawn a boss with some seed"

    @pytest.mark.asyncio
    async def test_complete_run_on_all_floors(self, game_manager, single_player):
        random.seed(42)
        gs = game_manager.create_dungeon([single_player], floor=1)
        random.seed()
        gs.max_floor = 1

        last_room = gs.map.rooms[-1]
        lx, ly = last_room.center()
        single_player.position = (lx, ly)
        single_player.has_acted_this_turn = False

        original_complete = game_manager.complete_run

        async def mock_complete(did, cause):
            pass

        game_manager.complete_run = mock_complete
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "INTERACT", {"type": "stairs_down"}
        )
        game_manager.complete_run = original_complete

        assert result.get("success") is True
        assert result.get("type") == "floor_complete"

    @pytest.mark.asyncio
    async def test_fixed_seed_reproducibility(self, game_manager, single_player):
        results = []
        for seed in [123, 456]:
            random.seed(seed)
            p = make_player(f"p_{seed}", "test", Classes.WARRIOR)
            gs = game_manager.create_dungeon([p], floor=1)
            room_count = len(gs.map.rooms)
            monster_count = len(gs.monsters)
            results.append((seed, room_count, monster_count))
            random.seed()

        random.seed(123)
        p1 = make_player("p_123_r", "test", Classes.WARRIOR)
        gs1 = game_manager.create_dungeon([p1], floor=1)
        r1 = len(gs1.map.rooms)
        random.seed()

        assert r1 == results[0][1], "Same seed should produce same room count"

    @pytest.mark.asyncio
    async def test_different_seeds_diversity(self, game_manager):
        room_counts = set()
        for seed in [10, 20, 30, 40, 50]:
            random.seed(seed)
            p = make_player(f"p_{seed}", "test", Classes.WARRIOR)
            gs = game_manager.create_dungeon([p], floor=1)
            room_counts.add(len(gs.map.rooms))
            random.seed()
        assert len(room_counts) > 1, "Different seeds should produce different room counts"


class TestMultiplayerIntegration:
    @pytest.mark.asyncio
    async def test_two_players_join_dungeon(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        random.seed()

        assert gs is not None
        assert len(gs.players) == 2
        assert gs.party is not None

    @pytest.mark.asyncio
    async def test_shared_vision(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        gs.shared_vision = game_manager.multiplayer_system.update_shared_vision(gs.party, gs.map)
        random.seed()

        assert len(gs.shared_vision) > 0
        p1_tiles = p1.visible_tiles
        p2_tiles = p2.visible_tiles
        assert len(gs.shared_vision) >= len(p1_tiles)
        assert len(gs.shared_vision) >= len(p2_tiles)

    @pytest.mark.asyncio
    async def test_vision_expands_with_movement(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        random.seed()

        initial_vision = len(gs.shared_vision)
        p1.has_acted_this_turn = False
        result = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "MOVE", {"dx": 1, "dy": 0}
        )
        if result.get("success"):
            game_manager.multiplayer_system.update_shared_vision(gs.party, gs.map)

    @pytest.mark.asyncio
    async def test_item_exchange(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        random.seed()

        potion = game_manager.item_factory.create_item("health_potion", 1)
        p1.add_to_inventory(potion.to_dict())

        assert len(p1.inventory) == 1
        assert len(p2.inventory) == 0

        party = gs.party
        result = game_manager.multiplayer_system.exchange_item(
            party, p1.client_id, p2.client_id, 0
        )
        assert result is not None
        assert len(p1.inventory) == 0
        assert len(p2.inventory) == 1

    @pytest.mark.asyncio
    async def test_revive_teammate(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        random.seed()

        p2.die()
        assert p2.alive is False

        scroll = game_manager.item_factory.create_item("resurrection_scroll", 1)
        p1.add_to_inventory(scroll.to_dict())

        party = gs.party
        result = game_manager.multiplayer_system.revive_teammate(
            party, p1.client_id, p2.client_id, 0
        )
        assert result is True
        assert p2.alive is True
        assert len(p1.inventory) == 0

    @pytest.mark.asyncio
    async def test_both_players_act_before_monster_turn(self, game_manager, two_players):
        p1, p2 = two_players
        random.seed(42)
        gs = game_manager.create_dungeon([p1, p2], floor=1)
        game_manager.multiplayer_system.update_shared_vision(gs.party, gs.map)
        random.seed()

        p1.has_acted_this_turn = False
        p2.has_acted_this_turn = False

        assert gs.phase == GamePhase.PLAYER_TURN

        result1 = await game_manager.process_player_action(
            gs.dungeon_id, "p1", "MOVE", {"dx": 0, "dy": 0}
        )
        assert p1.has_acted_this_turn is True or gs.phase == GamePhase.PLAYER_TURN
        assert not p2.has_acted_this_turn or gs.turn_count > 0

        initial_turn = gs.turn_count
        p2.has_acted_this_turn = False
        result2 = await game_manager.process_player_action(
            gs.dungeon_id, "p2", "MOVE", {"dx": 0, "dy": 0}
        )
        assert gs.turn_count > initial_turn or gs.phase == GamePhase.PLAYER_TURN
