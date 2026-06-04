import random
from enum import Enum
from dataclasses import dataclass
from typing import Optional, List, Dict, Any, Tuple

from .ai_behavior import MonsterAI
from .events import EventBus, CombatEvent, EventType, get_global_bus


class StatusEffectType(Enum):
    POISON = "poison"
    BURN = "burn"
    FROZEN = "frozen"
    STUN = "stun"
    BLEED = "bleed"
    BUFF_ATTACK = "buff_attack"
    BUFF_DEFENSE = "buff_defense"
    BUFF_SPEED = "buff_speed"
    DEBUFF_ATTACK = "debuff_attack"
    DEBUFF_DEFENSE = "debuff_defense"
    CURSE = "curse"


@dataclass
class StatusEffect:
    name: str
    effect_type: StatusEffectType
    duration: int
    value_per_turn: int
    source: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "effect_type": self.effect_type.value,
            "duration": self.duration,
            "value_per_turn": self.value_per_turn,
            "source": self.source
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "StatusEffect":
        return cls(
            name=data["name"],
            effect_type=StatusEffectType(data["effect_type"]),
            duration=data["duration"],
            value_per_turn=data["value_per_turn"],
            source=data["source"]
        )


@dataclass
class CombatResult:
    damage: int
    was_crit: bool
    was_dodged: bool
    status_applied: Optional[StatusEffect]
    killed: bool
    attacker_id: str
    defender_id: str
    element: Optional[str] = None


class CombatEngine:
    def __init__(self, event_bus: Optional[EventBus] = None):
        self.monster_ai = MonsterAI()
        self.monster_ai.load_behavior_trees()
        self.turn_log: List[Dict[str, Any]] = []
        self.event_bus = event_bus or get_global_bus()

    def process_turn(self, game_state: Dict[str, Any]) -> Dict[str, Any]:
        self.turn_log.clear()

        turn_start_event = CombatEvent(EventType.TURN_START, extra={"game_state": game_state})
        self.event_bus.publish(turn_start_event)

        players = game_state.get("players", [])
        monsters = game_state.get("monsters", [])
        all_combatants = players + monsters

        all_combatants.sort(
            key=lambda c: self._get_effective_speed(c),
            reverse=True
        )

        for combatant in all_combatants:
            if not combatant.get("alive", True):
                continue

            skip_turn = self._process_status_effects(combatant)
            self._check_death(combatant)

            if skip_turn or not combatant.get("alive", True):
                continue

            if combatant.get("is_player", False):
                continue
            else:
                self.process_monster_turn(combatant, game_state)

        game_state["turn_log"] = list(self.turn_log)
        return game_state

    def _get_effective_speed(self, combatant: Dict[str, Any]) -> int:
        speed = combatant.get("speed", combatant.get("base_speed", 0))
        for effect in combatant.get("status_effects", []):
            if isinstance(effect, StatusEffect):
                if effect.effect_type == StatusEffectType.BUFF_SPEED:
                    speed += effect.value_per_turn
            else:
                et = effect.get("effect_type", effect.get("name", ""))
                if et in ("buff_speed", "speed_up", "speed_buff"):
                    speed += effect.get("value_per_turn", effect.get("value", 0))
        return speed

    def _get_effective_attack(self, combatant: Dict[str, Any]) -> int:
        attack = combatant.get("attack", combatant.get("base_attack", 0))
        for effect in combatant.get("status_effects", []):
            if isinstance(effect, StatusEffect):
                if effect.effect_type == StatusEffectType.BUFF_ATTACK:
                    attack += effect.value_per_turn
                elif effect.effect_type == StatusEffectType.DEBUFF_ATTACK:
                    attack -= effect.value_per_turn
            else:
                et = effect.get("effect_type", effect.get("name", ""))
                if et in ("buff_attack", "attack_up", "attack_buff"):
                    attack += effect.get("value_per_turn", effect.get("value", 0))
                elif et in ("debuff_attack", "attack_down", "attack_debuff"):
                    attack -= effect.get("value_per_turn", effect.get("value", 0))
        return max(0, attack)

    def _get_effective_defense(self, combatant: Dict[str, Any]) -> int:
        defense = combatant.get("defense", combatant.get("base_defense", 0))
        for effect in combatant.get("status_effects", []):
            if isinstance(effect, StatusEffect):
                if effect.effect_type == StatusEffectType.BUFF_DEFENSE:
                    defense += effect.value_per_turn
                elif effect.effect_type == StatusEffectType.DEBUFF_DEFENSE:
                    defense -= effect.value_per_turn
            else:
                et = effect.get("effect_type", effect.get("name", ""))
                if et in ("buff_defense", "defense_up", "defense_buff"):
                    defense += effect.get("value_per_turn", effect.get("value", 0))
                elif et in ("debuff_defense", "defense_down", "defense_debuff"):
                    defense -= effect.get("value_per_turn", effect.get("value", 0))
        return max(0, defense)

    def _process_status_effects(self, combatant: Dict[str, Any]) -> bool:
        skip_turn = False
        expired: List[Any] = []
        effects = combatant.get("status_effects", [])

        for i, effect in enumerate(effects):
            if isinstance(effect, StatusEffect):
                effect_dict = effect.to_dict()
                effect_type = effect.effect_type
                value = effect.value_per_turn
                duration = effect.duration
            else:
                effect_dict = effect
                et_str = effect.get("effect_type", effect.get("name", ""))
                try:
                    effect_type = StatusEffectType(et_str)
                except ValueError:
                    effect_type = None
                value = effect.get("value_per_turn", effect.get("value", 0))
                duration = effect.get("duration", 0)

            if effect_type == StatusEffectType.POISON:
                dmg = max(1, value)
                combatant["hp"] = combatant.get("hp", 0) - dmg
                self.turn_log.append({
                    "type": "status_damage",
                    "target": combatant.get("id", ""),
                    "effect": "poison",
                    "damage": dmg
                })
            elif effect_type == StatusEffectType.BURN:
                dmg = max(1, value)
                combatant["hp"] = combatant.get("hp", 0) - dmg
                self.turn_log.append({
                    "type": "status_damage",
                    "target": combatant.get("id", ""),
                    "effect": "burn",
                    "damage": dmg
                })
            elif effect_type == StatusEffectType.FROZEN:
                skip_turn = True
                self.turn_log.append({
                    "type": "status_skip",
                    "target": combatant.get("id", ""),
                    "effect": "frozen"
                })
            elif effect_type == StatusEffectType.STUN:
                skip_turn = True
                self.turn_log.append({
                    "type": "status_skip",
                    "target": combatant.get("id", ""),
                    "effect": "stun"
                })
            elif effect_type == StatusEffectType.BLEED:
                dmg = max(1, value)
                combatant["hp"] = combatant.get("hp", 0) - dmg
                self.turn_log.append({
                    "type": "status_damage",
                    "target": combatant.get("id", ""),
                    "effect": "bleed",
                    "damage": dmg
                })
            elif effect_type == StatusEffectType.CURSE:
                dmg = max(1, value)
                combatant["hp"] = combatant.get("hp", 0) - dmg
                self.turn_log.append({
                    "type": "status_damage",
                    "target": combatant.get("id", ""),
                    "effect": "curse",
                    "damage": dmg
                })

            if isinstance(effect, StatusEffect):
                effect.duration -= 1
                if effect.duration <= 0:
                    expired.append(i)
            else:
                effect["duration"] = duration - 1
                if effect["duration"] <= 0:
                    expired.append(i)

        for i in reversed(expired):
            effects.pop(i)

        combatant["status_effects"] = effects
        return skip_turn

    def _check_death(self, combatant: Dict[str, Any]) -> None:
        if combatant.get("hp", 0) <= 0:
            if combatant.get("has_resurrection", False):
                combatant["has_resurrection"] = False
                combatant["hp"] = combatant.get("max_hp", 100) // 2
                combatant["mana"] = combatant.get("max_mana", 50) // 2
                combatant["status_effects"] = []
                self.turn_log.append({
                    "type": "resurrection",
                    "target": combatant.get("id", "")
                })
            else:
                combatant["alive"] = False
                combatant["hp"] = 0
                self.turn_log.append({
                    "type": "death",
                    "target": combatant.get("id", "")
                })

    def _handle_status_interactions(self, combatant: Dict[str, Any]) -> None:
        effects = combatant.get("status_effects", [])
        if len(effects) < 2:
            return

        parsed_effects: List[Tuple[int, StatusEffect]] = []
        for i, eff in enumerate(effects):
            if isinstance(eff, StatusEffect):
                parsed_effects.append((i, eff))
            else:
                try:
                    parsed_effects.append((i, StatusEffect.from_dict(eff)))
                except (KeyError, ValueError):
                    continue

        to_remove: List[int] = []
        frozen_idx = -1
        burn_idx = -1
        poison_indices: List[int] = []
        stun_idx = -1

        for i, (orig_idx, eff) in enumerate(parsed_effects):
            if eff.effect_type == StatusEffectType.FROZEN:
                frozen_idx = i
            elif eff.effect_type == StatusEffectType.BURN:
                burn_idx = i
            elif eff.effect_type == StatusEffectType.POISON:
                poison_indices.append(i)
            elif eff.effect_type == StatusEffectType.STUN:
                stun_idx = i

        if frozen_idx >= 0 and burn_idx >= 0:
            to_remove.append(parsed_effects[frozen_idx][0])
            to_remove.append(parsed_effects[burn_idx][0])
            self.turn_log.append({
                "type": "status_cancel",
                "target": combatant.get("id", ""),
                "effects": ["frozen", "burn"]
            })

        if len(poison_indices) >= 2:
            first_orig_idx, first_eff = parsed_effects[poison_indices[0]]
            max_duration = first_eff.duration
            total_value = first_eff.value_per_turn
            for idx in poison_indices[1:]:
                _, eff = parsed_effects[idx]
                total_value += eff.value_per_turn
                max_duration = max(max_duration, eff.duration)
                to_remove.append(parsed_effects[idx][0])
            first_eff.value_per_turn = total_value
            first_eff.duration = max_duration
            effects[first_orig_idx] = first_eff
            self.turn_log.append({
                "type": "status_stack",
                "target": combatant.get("id", ""),
                "effect": "poison",
                "new_value": total_value,
                "new_duration": max_duration
            })

        if stun_idx >= 0 and frozen_idx >= 0:
            stun_orig_idx, stun_eff = parsed_effects[stun_idx]
            frozen_orig_idx, frozen_eff = parsed_effects[frozen_idx]
            if stun_eff.duration >= frozen_eff.duration:
                to_remove.append(frozen_orig_idx)
            else:
                to_remove.append(stun_orig_idx)
            self.turn_log.append({
                "type": "status_keep_longer",
                "target": combatant.get("id", ""),
                "effects": ["stun", "frozen"]
            })

        for i in sorted(to_remove, reverse=True):
            if 0 <= i < len(effects):
                effects.pop(i)

        combatant["status_effects"] = effects

    def resolve_attack(self, attacker: Dict[str, Any], defender: Dict[str, Any],
                       element: Optional[str] = None) -> CombatResult:
        """
        战斗伤害计算链：

        1. 基础伤害 = max(1, 有效攻击力 - 有效防御力)
        2. 元素抗性 = 伤害 * (1 - 元素抗性百分比)
        3. 暴击判定 = 随机 < 暴击率 → 伤害 * 1.5
        4. 闪避判定 = 随机 < 闪避率 → 伤害 = 0
        5. 扣血 = 目标HP - 伤害
        6. 状态应用 = 命中且有元素 → 对应状态效果

        各步骤之间发布事件，订阅者可修改伤害值
        """
        attacker_id = attacker.get("id", "")
        defender_id = defender.get("id", "")

        if not defender.get("alive", True):
            return CombatResult(0, False, False, None, False, attacker_id, defender_id, element)

        combat_start_event = CombatEvent(
            EventType.COMBAT_START,
            attacker=attacker, defender=defender, element=element
        )
        self.event_bus.publish(combat_start_event)

        atk = self._get_effective_attack(attacker)
        dfn = self._get_effective_defense(defender)

        before_attack_event = CombatEvent(
            EventType.BEFORE_ATTACK,
            attacker=attacker, defender=defender, element=element
        )
        before_attack_event.extra["base_attack"] = atk
        self.event_bus.publish(before_attack_event)
        atk = before_attack_event.extra.get("base_attack", atk)

        base_damage = max(1, atk - dfn)
        damage = base_damage

        before_defense_event = CombatEvent(
            EventType.BEFORE_DEFENSE,
            attacker=attacker, defender=defender, damage=damage, element=element
        )
        self.event_bus.publish(before_defense_event)
        damage = before_defense_event.damage

        if element:
            resist = defender.get("element_resist", {}).get(element, 0)
            damage = int(damage * (1 - resist))

        after_damage_calc_event = CombatEvent(
            EventType.AFTER_DAMAGE_CALC,
            attacker=attacker, defender=defender, damage=damage, element=element
        )
        self.event_bus.publish(after_damage_calc_event)
        damage = after_damage_calc_event.damage

        was_crit = False
        crit_rate = attacker.get("crit_rate", 0.05)
        if random.random() < crit_rate:
            damage = int(damage * 1.5)
            was_crit = True

        was_dodged = False
        dodge_rate = defender.get("dodge_rate", 0.05)
        if random.random() < dodge_rate:
            damage = 0
            was_dodged = True

        on_hit_event = CombatEvent(
            EventType.ON_HIT,
            attacker=attacker, defender=defender, damage=damage,
            element=element, was_crit=was_crit, was_dodged=was_dodged
        )
        self.event_bus.publish(on_hit_event)
        damage = on_hit_event.damage

        killed = False
        if damage > 0:
            defender["hp"] = defender.get("hp", 0) - damage
            if defender.get("hp", 0) <= 0:
                killed = True
                self._check_death(defender)

        after_attack_event = CombatEvent(
            EventType.AFTER_ATTACK,
            attacker=attacker, defender=defender, damage=damage,
            element=element, was_crit=was_crit, was_dodged=was_dodged, killed=killed
        )
        self.event_bus.publish(after_attack_event)

        if killed:
            on_kill_event = CombatEvent(
                EventType.ON_KILL,
                attacker=attacker, defender=defender, damage=damage,
                element=element, was_crit=was_crit, was_dodged=was_dodged, killed=True
            )
            self.event_bus.publish(on_kill_event)

        combat_end_event = CombatEvent(
            EventType.COMBAT_END,
            attacker=attacker, defender=defender, damage=damage,
            element=element, was_crit=was_crit, was_dodged=was_dodged, killed=killed
        )
        self.event_bus.publish(combat_end_event)

        status_applied = None
        if not was_dodged and element:
            status_map = {
                "fire": StatusEffectType.BURN,
                "ice": StatusEffectType.FROZEN,
                "poison": StatusEffectType.POISON
            }
            if element in status_map:
                status_applied = StatusEffect(
                    name=element,
                    effect_type=status_map[element],
                    duration=3,
                    value_per_turn=max(1, damage // 5),
                    source=attacker_id
                )
                self._apply_status_effect(defender, status_applied)

        self.turn_log.append({
            "type": "attack",
            "attacker": attacker_id,
            "defender": defender_id,
            "damage": damage,
            "was_crit": was_crit,
            "was_dodged": was_dodged,
            "element": element,
            "killed": killed
        })

        return CombatResult(
            damage=damage,
            was_crit=was_crit,
            was_dodged=was_dodged,
            status_applied=status_applied,
            killed=killed,
            attacker_id=attacker_id,
            defender_id=defender_id,
            element=element
        )

    def _apply_status_effect(self, target: Dict[str, Any], effect: StatusEffect) -> None:
        effects = target.get("status_effects", [])
        if isinstance(effect, StatusEffect):
            effects.append(effect)
        else:
            effects.append(effect)
        target["status_effects"] = effects
        self._handle_status_interactions(target)

    def apply_skill(self, caster: Dict[str, Any], target: Dict[str, Any],
                    skill: Dict[str, Any], game_state: Dict[str, Any]) -> Dict[str, Any]:
        skill_id = skill.get("id", "")
        mana_cost = skill.get("mana_cost", 0)

        if caster.get("mana", 0) < mana_cost:
            return {"success": False, "reason": "insufficient_mana"}

        cooldowns = caster.get("skill_cooldowns", {})
        if cooldowns.get(skill_id, 0) > 0:
            return {"success": False, "reason": "on_cooldown"}

        caster["mana"] = caster.get("mana", 0) - mana_cost
        cooldown = skill.get("cooldown", 0)
        if cooldown > 0:
            cooldowns[skill_id] = cooldown
            caster["skill_cooldowns"] = cooldowns

        effect_type = skill.get("effect", "damage")
        element = skill.get("element", None)
        results = []

        if effect_type == "damage":
            multiplier = skill.get("damage_multiplier", 1.0)
            aoe = skill.get("aoe", 0)

            base_atk = self._get_effective_attack(caster)
            base_dfn = self._get_effective_defense(target)
            base_dmg = max(1, int((base_atk - base_dfn) * multiplier))

            if element:
                resist = target.get("element_resist", {}).get(element, 0)
                base_dmg = int(base_dmg * (1 - resist))

            targets = [target]
            if aoe > 0:
                targets = self._get_aoe_targets(target, aoe, game_state)

            for t in targets:
                was_crit = False
                if random.random() < caster.get("crit_rate", 0.05):
                    dmg = int(base_dmg * 1.5)
                    was_crit = True
                else:
                    dmg = base_dmg

                was_dodged = False
                if random.random() < t.get("dodge_rate", 0.05):
                    dmg = 0
                    was_dodged = True

                killed = False
                if dmg > 0:
                    t["hp"] = t.get("hp", 0) - dmg
                    if t.get("hp", 0) <= 0:
                        killed = True
                        self._check_death(t)

                status_applied = None
                status_effect_name = skill.get("status_effect")
                status_duration = skill.get("status_duration", 3)
                if not was_dodged and status_effect_name:
                    type_map = {
                        "poison": StatusEffectType.POISON,
                        "burn": StatusEffectType.BURN,
                        "frozen": StatusEffectType.FROZEN,
                        "stun": StatusEffectType.STUN,
                        "bleed": StatusEffectType.BLEED,
                        "curse": StatusEffectType.CURSE
                    }
                    if status_effect_name in type_map:
                        status_applied = StatusEffect(
                            name=status_effect_name,
                            effect_type=type_map[status_effect_name],
                            duration=status_duration,
                            value_per_turn=max(1, dmg // 5),
                            source=caster.get("id", "")
                        )
                        self._apply_status_effect(t, status_applied)

                results.append(CombatResult(
                    damage=dmg,
                    was_crit=was_crit,
                    was_dodged=was_dodged,
                    status_applied=status_applied,
                    killed=killed,
                    attacker_id=caster.get("id", ""),
                    defender_id=t.get("id", ""),
                    element=element
                ))

                self.turn_log.append({
                    "type": "skill_damage",
                    "skill": skill_id,
                    "attacker": caster.get("id", ""),
                    "defender": t.get("id", ""),
                    "damage": dmg,
                    "was_crit": was_crit,
                    "was_dodged": was_dodged,
                    "element": element,
                    "killed": killed
                })

        elif effect_type == "heal":
            heal_value = skill.get("heal_value", 0)
            aoe_friendly = skill.get("aoe_friendly", False)

            targets = [target]
            if aoe_friendly:
                targets = self._get_friendly_targets(caster, game_state)

            for t in targets:
                if t.get("alive", True):
                    old_hp = t.get("hp", 0)
                    max_hp = t.get("max_hp", 100)
                    t["hp"] = min(max_hp, old_hp + heal_value)
                    actual_heal = t["hp"] - old_hp
                    self.turn_log.append({
                        "type": "heal",
                        "skill": skill_id,
                        "healer": caster.get("id", ""),
                        "target": t.get("id", ""),
                        "amount": actual_heal
                    })
                    results.append({"type": "heal", "amount": actual_heal, "target_id": t.get("id", "")})

        elif effect_type == "buff":
            buff_stat = skill.get("buff_stat", "attack")
            buff_value = skill.get("buff_value", 0)
            duration = skill.get("duration", 3)
            aoe_friendly = skill.get("aoe_friendly", False)

            type_map = {
                "attack": StatusEffectType.BUFF_ATTACK,
                "defense": StatusEffectType.BUFF_DEFENSE,
                "speed": StatusEffectType.BUFF_SPEED
            }
            effect_type_enum = type_map.get(buff_stat, StatusEffectType.BUFF_ATTACK)

            targets = [caster]
            if aoe_friendly:
                targets = self._get_friendly_targets(caster, game_state)

            for t in targets:
                status = StatusEffect(
                    name=f"buff_{buff_stat}",
                    effect_type=effect_type_enum,
                    duration=duration,
                    value_per_turn=buff_value,
                    source=caster.get("id", "")
                )
                self._apply_status_effect(t, status)
                results.append({"type": "buff", "effect": status, "target_id": t.get("id", "")})
                self.turn_log.append({
                    "type": "buff",
                    "skill": skill_id,
                    "caster": caster.get("id", ""),
                    "target": t.get("id", ""),
                    "stat": buff_stat,
                    "value": buff_value,
                    "duration": duration
                })

        elif effect_type == "debuff":
            debuff_stat = skill.get("debuff_stat", "attack")
            debuff_value = skill.get("debuff_value", 0)
            duration = skill.get("duration", 3)

            type_map = {
                "attack": StatusEffectType.DEBUFF_ATTACK,
                "defense": StatusEffectType.DEBUFF_DEFENSE,
            }
            effect_type_enum = type_map.get(debuff_stat, StatusEffectType.DEBUFF_ATTACK)

            status = StatusEffect(
                name=f"debuff_{debuff_stat}",
                effect_type=effect_type_enum,
                duration=duration,
                value_per_turn=debuff_value,
                source=caster.get("id", "")
            )
            self._apply_status_effect(target, status)
            results.append({"type": "debuff", "effect": status, "target_id": target.get("id", "")})
            self.turn_log.append({
                "type": "debuff",
                "skill": skill_id,
                "caster": caster.get("id", ""),
                "target": target.get("id", ""),
                "stat": debuff_stat,
                "value": debuff_value,
                "duration": duration
            })

        return {"success": True, "results": results}

    def _get_aoe_targets(self, center: Dict[str, Any], radius: int,
                         game_state: Dict[str, Any]) -> List[Dict[str, Any]]:
        targets = []
        center_pos = center.get("position", (0, 0))
        cx, cy = center_pos[0], center_pos[1]

        for m in game_state.get("monsters", []):
            if m.get("id") == center.get("id"):
                continue
            pos = m.get("position", (0, 0))
            dist = abs(pos[0] - cx) + abs(pos[1] - cy)
            if dist <= radius:
                targets.append(m)

        for p in game_state.get("players", []):
            if p.get("id") == center.get("id"):
                continue
            pos = p.get("position", (0, 0))
            dist = abs(pos[0] - cx) + abs(pos[1] - cy)
            if dist <= radius:
                targets.append(p)

        if center not in targets:
            targets.append(center)
        return targets

    def _get_friendly_targets(self, caster: Dict[str, Any],
                              game_state: Dict[str, Any]) -> List[Dict[str, Any]]:
        if caster.get("is_player", False):
            return list(game_state.get("players", []))
        else:
            return list(game_state.get("monsters", []))

    def process_monster_turn(self, monster: Dict[str, Any],
                             game_state: Dict[str, Any]) -> Dict[str, Any]:
        if not monster.get("alive", True):
            return {"action": "none"}

        context = {
            "monsters": game_state.get("monsters", []),
            "players": game_state.get("players", []),
            "player_positions": [p.get("position", (0, 0)) for p in game_state.get("players", []) if p.get("alive", True)],
            "map_tiles": game_state.get("map_tiles", {}),
            "current_floor": game_state.get("current_floor", 1)
        }

        action = self.monster_ai.decide_action(monster.get("id", ""), context)
        action_type = action.get("type", "wander")
        params = action.get("params", {})

        result = {"monster_id": monster.get("id", ""), "action": action_type}

        if action_type == "attack":
            target = self._find_nearest_player(monster, game_state)
            if target:
                attack_range = monster.get("attack_range", 1)
                mpos = monster.get("position", (0, 0))
                tpos = target.get("position", (0, 0))
                dist = abs(mpos[0] - tpos[0]) + abs(mpos[1] - tpos[1])
                if dist <= attack_range:
                    combat_result = self.resolve_attack(monster, target)
                    result["combat_result"] = combat_result

        elif action_type == "cast_spell":
            spell = params.get("spell", "")
            skill = self._find_monster_skill(monster, spell)
            if skill:
                target = self._find_nearest_player(monster, game_state)
                if target:
                    skill_result = self.apply_skill(monster, target, skill, game_state)
                    result["skill_result"] = skill_result

        elif action_type == "chase":
            target = self._find_nearest_player(monster, game_state)
            if target:
                new_pos = self._move_toward(monster, target, game_state)
                monster["position"] = new_pos
                result["new_position"] = new_pos

        elif action_type == "flee":
            new_pos = self._move_random(monster, game_state)
            monster["position"] = new_pos
            result["new_position"] = new_pos

        elif action_type == "wander":
            new_pos = self._move_random(monster, game_state)
            monster["position"] = new_pos
            result["new_position"] = new_pos

        elif action_type == "summon":
            template = params.get("template", "skeleton")
            count = int(params.get("count", 1))
            max_summons = int(params.get("max_summons", 3))
            current_summons = monster.get("summon_count", 0)

            if current_summons < max_summons:
                summoned = self._summon_minions(monster, template, min(count, max_summons - current_summons), game_state)
                monster["summon_count"] = current_summons + len(summoned)
                monster["cooldowns"] = monster.get("cooldowns", {})
                monster["cooldowns"]["summon"] = 5
                result["summoned"] = summoned

        elif action_type == "retreat":
            distance = int(params.get("distance", 2))
            target = self._find_nearest_player(monster, game_state)
            if target:
                new_pos = self._move_away(monster, target, distance, game_state)
                monster["position"] = new_pos
                result["new_position"] = new_pos

        elif action_type == "swarm":
            target = self._find_nearest_player(monster, game_state)
            if target:
                attack_range = monster.get("attack_range", 1)
                mpos = monster.get("position", (0, 0))
                tpos = target.get("position", (0, 0))
                dist = abs(mpos[0] - tpos[0]) + abs(mpos[1] - tpos[1])
                if dist <= attack_range:
                    combat_result = self.resolve_attack(monster, target)
                    combat_result["damage"] = int(combat_result.get("damage", 0) * 1.3)
                    result["combat_result"] = combat_result

        elif action_type == "call_for_help":
            radius = int(params.get("radius", 8))
            mpos = monster.get("position", (0, 0))
            alerted = []
            for other in game_state.get("monsters", []):
                if other.get("id") == monster.get("id") or not other.get("alive", True):
                    continue
                if other.get("template_id") != monster.get("template_id"):
                    continue
                opos = other.get("position", (0, 0))
                dist = abs(opos[0] - mpos[0]) + abs(opos[1] - mpos[1])
                if dist <= radius:
                    other["heard_sound"] = True
                    other["alert_target"] = self._find_nearest_player(monster, game_state)
                    alerted.append(other.get("id"))
            result["alerted_allies"] = alerted

        cooldowns = monster.get("cooldowns", {})
        expired = []
        for k, v in cooldowns.items():
            cooldowns[k] = v - 1
            if cooldowns[k] <= 0:
                expired.append(k)
        for k in expired:
            del cooldowns[k]
        monster["cooldowns"] = cooldowns

        self.turn_log.append({
            "type": "monster_action",
            "monster": monster.get("id", ""),
            "action": action_type,
            "params": params
        })

        return result

    def _find_nearest_player(self, monster: Dict[str, Any],
                             game_state: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        mpos = monster.get("position", (0, 0))
        nearest = None
        min_dist = float("inf")
        for p in game_state.get("players", []):
            if not p.get("alive", True):
                continue
            ppos = p.get("position", (0, 0))
            dist = abs(mpos[0] - ppos[0]) + abs(mpos[1] - ppos[1])
            if dist < min_dist:
                min_dist = dist
                nearest = p
        return nearest

    def _move_toward(self, monster: Dict[str, Any], target: Dict[str, Any],
                     game_state: Dict[str, Any]) -> Tuple[int, int]:
        mpos = monster.get("position", (0, 0))
        tpos = target.get("position", (0, 0))
        dx = tpos[0] - mpos[0]
        dy = tpos[1] - mpos[1]

        candidates = []
        if abs(dx) > abs(dy):
            if dx > 0:
                candidates.append((mpos[0] + 1, mpos[1]))
            elif dx < 0:
                candidates.append((mpos[0] - 1, mpos[1]))
            if dy > 0:
                candidates.append((mpos[0], mpos[1] + 1))
            elif dy < 0:
                candidates.append((mpos[0], mpos[1] - 1))
        else:
            if dy > 0:
                candidates.append((mpos[0], mpos[1] + 1))
            elif dy < 0:
                candidates.append((mpos[0], mpos[1] - 1))
            if dx > 0:
                candidates.append((mpos[0] + 1, mpos[1]))
            elif dx < 0:
                candidates.append((mpos[0] - 1, mpos[1]))

        occupied = set()
        for m in game_state.get("monsters", []):
            if m.get("id") != monster.get("id"):
                occupied.add(tuple(m.get("position", (0, 0))))
        for p in game_state.get("players", []):
            occupied.add(tuple(p.get("position", (0, 0))))

        for pos in candidates:
            if pos not in occupied and self._is_walkable(pos, game_state):
                return pos
        return mpos

    def _move_random(self, monster: Dict[str, Any],
                     game_state: Dict[str, Any]) -> Tuple[int, int]:
        mpos = monster.get("position", (0, 0))
        directions = [
            (mpos[0] + 1, mpos[1]),
            (mpos[0] - 1, mpos[1]),
            (mpos[0], mpos[1] + 1),
            (mpos[0], mpos[1] - 1)
        ]
        random.shuffle(directions)

        occupied = set()
        for m in game_state.get("monsters", []):
            if m.get("id") != monster.get("id"):
                occupied.add(tuple(m.get("position", (0, 0))))
        for p in game_state.get("players", []):
            occupied.add(tuple(p.get("position", (0, 0))))

        for pos in directions:
            if pos not in occupied and self._is_walkable(pos, game_state):
                return pos
        return mpos

    def _is_walkable(self, pos: Tuple[int, int], game_state: Dict[str, Any]) -> bool:
        map_tiles = game_state.get("map_tiles", {})
        if isinstance(map_tiles, dict):
            tile = map_tiles.get(pos)
        else:
            if 0 <= pos[1] < len(map_tiles) and 0 <= pos[0] < len(map_tiles[0]):
                tile = map_tiles[pos[1]][pos[0]]
            else:
                tile = None
        if tile is None:
            return False
        ts = str(tile).lower()
        return "wall" not in ts

    def _find_monster_skill(self, monster: Dict[str, Any], skill_id: str) -> Optional[Dict[str, Any]]:
        for skill in monster.get("skills", []):
            if skill.get("id") == skill_id:
                return skill
        return None

    def _move_away(self, monster: Dict[str, Any], target: Dict[str, Any],
                   distance: int, game_state: Dict[str, Any]) -> Tuple[int, int]:
        mpos = monster.get("position", (0, 0))
        tpos = target.get("position", (0, 0))
        dx = mpos[0] - tpos[0]
        dy = mpos[1] - tpos[1]

        current_pos = mpos
        for _ in range(distance):
            cx, cy = current_pos
            candidates = []
            if abs(dx) > abs(dy):
                if dx > 0:
                    candidates.append((cx + 1, cy))
                elif dx < 0:
                    candidates.append((cx - 1, cy))
                if dy > 0:
                    candidates.append((cx, cy + 1))
                elif dy < 0:
                    candidates.append((cx, cy - 1))
            else:
                if dy > 0:
                    candidates.append((cx, cy + 1))
                elif dy < 0:
                    candidates.append((cx, cy - 1))
                if dx > 0:
                    candidates.append((cx + 1, cy))
                elif dx < 0:
                    candidates.append((cx - 1, cy))

            occupied = set()
            for m in game_state.get("monsters", []):
                if m.get("id") != monster.get("id"):
                    occupied.add(tuple(m.get("position", (0, 0))))
            for p in game_state.get("players", []):
                occupied.add(tuple(p.get("position", (0, 0))))

            moved = False
            for pos in candidates:
                if pos not in occupied and self._is_walkable(pos, game_state):
                    current_pos = pos
                    moved = True
                    break
            if not moved:
                break

        return current_pos

    def _summon_minions(self, summoner: Dict[str, Any], template_id: str,
                        count: int, game_state: Dict[str, Any]) -> List[Dict[str, Any]]:
        import uuid
        summoned_list = []
        spos = summoner.get("position", (0, 0))

        for _ in range(count):
            spawn_pos = self._find_adjacent_spawn(spos, game_state)
            if spawn_pos is None:
                break

            template = self._get_monster_template(template_id)
            if template is None:
                continue

            minion = {
                "id": str(uuid.uuid4()),
                "template_id": template_id,
                "name": template.get("name", template_id),
                "hp": template.get("hp", 20),
                "max_hp": template.get("hp", 20),
                "attack": template.get("attack", 5),
                "defense": template.get("defense", 2),
                "speed": template.get("speed", 5),
                "position": spawn_pos,
                "alive": True,
                "summoned_by": summoner.get("id"),
                "behavior_tree": template.get("behavior_tree", "skeleton.xml"),
                "cooldowns": {},
                "tags": template.get("tags", [])
            }

            game_state.get("monsters", []).append(minion)
            summoned_list.append(minion)

        return summoned_list

    def _find_adjacent_spawn(self, center: Tuple[int, int], game_state: Dict[str, Any]) -> Optional[Tuple[int, int]]:
        cx, cy = center
        offsets = [(1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)]
        random.shuffle(offsets)

        occupied = set()
        for m in game_state.get("monsters", []):
            occupied.add(tuple(m.get("position", (0, 0))))
        for p in game_state.get("players", []):
            occupied.add(tuple(p.get("position", (0, 0))))

        for dx, dy in offsets:
            pos = (cx + dx, cy + dy)
            if pos not in occupied and self._is_walkable(pos, game_state):
                return pos
        return None

    def _get_monster_template(self, template_id: str) -> Optional[Dict[str, Any]]:
        import os
        import json
        template_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "monsters", "templates.json")
        try:
            with open(template_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for m in data.get("monsters", []):
                if m.get("id") == template_id:
                    return m
        except (IOError, json.JSONDecodeError):
            pass
        return None
