import sys
import os
import random
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from server.combat_engine import CombatEngine, StatusEffect, StatusEffectType, CombatResult


@pytest.fixture
def engine():
    return CombatEngine()


def test_damage_formula_basic(engine):
    random.seed(0)
    attacker = {"id": "atk", "attack": 10}
    defender = {"id": "def", "defense": 3, "hp": 100, "alive": True}
    result = engine.resolve_attack(attacker, defender)
    expected_damage = max(1, 10 - 3)
    assert result.damage == expected_damage
    assert defender["hp"] == 100 - expected_damage


def test_damage_formula_high_defense(engine):
    random.seed(0)
    attacker = {"id": "atk", "attack": 5}
    defender = {"id": "def", "defense": 10, "hp": 100, "alive": True}
    result = engine.resolve_attack(attacker, defender)
    expected_damage = 1
    assert result.damage == expected_damage
    assert defender["hp"] == 100 - expected_damage


def test_crit_rate_simulation(engine):
    crit_count = 0
    for _ in range(10000):
        attacker = {"id": "atk", "attack": 10, "crit_rate": 0.05}
        defender = {"id": "def", "defense": 0, "hp": 99999, "dodge_rate": 0.0, "alive": True}
        result = engine.resolve_attack(attacker, defender)
        if result.was_crit:
            crit_count += 1
    crit_rate = crit_count / 10000
    assert 0.02 <= crit_rate <= 0.08


def test_dodge_rate_simulation(engine):
    dodge_count = 0
    for _ in range(10000):
        attacker = {"id": "atk", "attack": 10, "crit_rate": 0.0}
        defender = {"id": "def", "defense": 0, "hp": 99999, "dodge_rate": 0.10, "alive": True}
        result = engine.resolve_attack(attacker, defender)
        if result.was_dodged and result.damage == 0:
            dodge_count += 1
    dodge_rate = dodge_count / 10000
    assert 0.07 <= dodge_rate <= 0.13


def test_poison_damage_per_turn(engine):
    engine.turn_log.clear()
    combatant = {
        "id": "test",
        "hp": 100,
        "status_effects": [
            StatusEffect("poison", StatusEffectType.POISON, duration=3, value_per_turn=5, source="test")
        ]
    }
    engine._process_status_effects(combatant)
    assert combatant["hp"] == 95
    assert combatant["status_effects"][0].duration == 2


def test_status_expires_after_duration(engine):
    engine.turn_log.clear()
    combatant = {
        "id": "test",
        "hp": 100,
        "status_effects": [
            StatusEffect("poison", StatusEffectType.POISON, duration=1, value_per_turn=5, source="test")
        ]
    }
    engine._process_status_effects(combatant)
    assert len(combatant["status_effects"]) == 0


def test_status_processing_order_poison_then_burn(engine):
    engine.turn_log.clear()
    combatant = {
        "id": "test",
        "hp": 100,
        "status_effects": [
            StatusEffect("poison", StatusEffectType.POISON, duration=2, value_per_turn=10, source="test"),
            StatusEffect("burn", StatusEffectType.BURN, duration=2, value_per_turn=8, source="test")
        ]
    }
    engine._process_status_effects(combatant)
    assert combatant["hp"] == 82
    poison_entries = [e for e in engine.turn_log if e.get("effect") == "poison"]
    burn_entries = [e for e in engine.turn_log if e.get("effect") == "burn"]
    assert len(poison_entries) == 1
    assert len(burn_entries) == 1
    poison_idx = engine.turn_log.index(poison_entries[0])
    burn_idx = engine.turn_log.index(burn_entries[0])
    assert poison_idx < burn_idx


def test_frozen_burn_cancel(engine):
    engine.turn_log.clear()
    combatant = {
        "id": "test",
        "hp": 100,
        "status_effects": [
            StatusEffect("frozen", StatusEffectType.FROZEN, duration=3, value_per_turn=0, source="test"),
            StatusEffect("burn", StatusEffectType.BURN, duration=3, value_per_turn=5, source="test")
        ]
    }
    engine._handle_status_interactions(combatant)
    assert len(combatant["status_effects"]) == 0


def test_poison_stacking(engine):
    engine.turn_log.clear()
    combatant = {
        "id": "test",
        "hp": 100,
        "status_effects": [
            StatusEffect("poison1", StatusEffectType.POISON, duration=3, value_per_turn=5, source="test"),
            StatusEffect("poison2", StatusEffectType.POISON, duration=5, value_per_turn=8, source="test")
        ]
    }
    engine._handle_status_interactions(combatant)
    assert len(combatant["status_effects"]) == 1
    remaining = combatant["status_effects"][0]
    assert remaining.value_per_turn == 13
    assert remaining.duration == 5


def test_element_resistance(engine):
    random.seed(0)
    attacker = {"id": "atk", "attack": 20}
    defender = {"id": "def", "defense": 0, "hp": 100, "element_resist": {"fire": 0.5}, "alive": True}
    result = engine.resolve_attack(attacker, defender, element="fire")
    assert result.damage == 10
    assert defender["hp"] == 90
