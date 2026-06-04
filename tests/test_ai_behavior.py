import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

import pytest
from server.ai_behavior import BehaviorTree, BTNode, NodeType, VisionSystem, SoundPropagation, MonsterAI


SKELETON_XML = os.path.join("data", "monsters", "behaviors", "skeleton.xml")


def _make_floor_map(size=10):
    return [["floor" for _ in range(size)] for _ in range(size)]


def _make_monster(**overrides):
    monster = {
        "id": "test_monster",
        "hp": 100,
        "max_hp": 100,
        "position": (5, 5),
        "attack_range": 1,
        "sight_range": 5,
        "behavior_tree": "skeleton",
    }
    monster.update(overrides)
    return monster


class TestBehaviorTreeExecution:
    def test_patrol_when_no_targets(self):
        bt = BehaviorTree()
        bt.load(SKELETON_XML)
        monster = _make_monster(hp=100, max_hp=100, position=(5, 5), attack_range=1)
        game_context = {
            "player_positions": [],
            "visible_tiles": set(),
            "monsters": [monster],
        }
        result = bt.execute(monster, game_context)
        assert result["type"] in ("patrol", "wander")

    def test_chase_when_player_visible(self):
        bt = BehaviorTree()
        bt.load(SKELETON_XML)
        monster = _make_monster(hp=100, max_hp=100, position=(5, 5), attack_range=1)
        game_context = {
            "player_positions": [(8, 5)],
            "visible_tiles": {(8, 5), (7, 5), (6, 5)},
            "monsters": [monster],
        }
        result = bt.execute(monster, game_context)
        assert result["type"] == "chase"

    def test_attack_when_in_range(self):
        bt = BehaviorTree()
        bt.load(SKELETON_XML)
        monster = _make_monster(hp=100, max_hp=100, position=(5, 5), attack_range=2)
        game_context = {
            "player_positions": [(6, 5)],
            "visible_tiles": {(6, 5)},
            "monsters": [monster],
        }
        result = bt.execute(monster, game_context)
        assert result["type"] == "attack"

    def test_flee_when_hp_low(self):
        bt = BehaviorTree()
        bt.load(SKELETON_XML)
        monster = _make_monster(hp=15, max_hp=100, position=(5, 5), attack_range=1)
        game_context = {
            "player_positions": [],
            "visible_tiles": set(),
            "monsters": [monster],
        }
        result = bt.execute(monster, game_context)
        assert result["type"] == "flee"


class TestBehaviorTreeXmlLoading:
    def test_behavior_tree_xml_loading(self):
        bt = BehaviorTree()
        bt.load(SKELETON_XML)
        assert bt.name == "skeleton"
        assert bt.root is not None


class TestVisionSystem:
    def test_vision_system_basic(self):
        map_tiles = _make_floor_map(10)
        visible = VisionSystem.compute_visible_tiles((5, 5), sight_range=3, map_tiles=map_tiles)
        assert (5, 5) in visible
        assert (6, 5) in visible
        assert (5, 6) in visible

    def test_vision_system_wall_occlusion(self):
        map_tiles = _make_floor_map(10)
        visible_no_wall = VisionSystem.compute_visible_tiles((5, 5), sight_range=5, map_tiles=map_tiles)

        map_tiles_wall = _make_floor_map(10)
        for x in range(10):
            map_tiles_wall[3][x] = "wall"

        visible_with_wall = VisionSystem.compute_visible_tiles((5, 5), sight_range=5, map_tiles=map_tiles_wall)

        assert (5, 5) in visible_with_wall
        assert len(visible_with_wall) < len(visible_no_wall)


class TestSoundPropagation:
    def test_sound_propagation(self):
        map_tiles = _make_floor_map(10)
        heard = SoundPropagation.propagate_sound((5, 5), intensity=3, map_tiles=map_tiles)
        assert (5, 5) in heard
        assert (6, 5) in heard
        assert (5, 6) in heard
        assert (9, 9) not in heard


class TestMonsterAI:
    def test_monster_ai_decide_action(self):
        ai = MonsterAI()
        ai.load_behavior_trees()
        monster = _make_monster(id="m1", hp=100, max_hp=100, position=(5, 5), attack_range=1, sight_range=5, behavior_tree="skeleton")
        map_tiles = _make_floor_map(10)
        game_context = {
            "monsters": [monster],
            "player_positions": [],
            "map_tiles": map_tiles,
        }
        result = ai.decide_action("m1", game_context)
        assert isinstance(result, dict)
        assert "type" in result
