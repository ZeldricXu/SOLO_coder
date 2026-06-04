from enum import Enum


class Classes(Enum):
    WARRIOR = "warrior"
    MAGE = "mage"
    ROGUE = "rogue"
    PRIEST = "priest"


CLASS_STATS = {
    Classes.WARRIOR: {
        "hp": 100, "mana": 30, "attack": 10, "defense": 8,
        "speed": 4, "crit_rate": 0.05, "dodge_rate": 0.03
    },
    Classes.MAGE: {
        "hp": 60, "mana": 80, "attack": 5, "defense": 3,
        "speed": 5, "crit_rate": 0.08, "dodge_rate": 0.05
    },
    Classes.ROGUE: {
        "hp": 70, "mana": 40, "attack": 8, "defense": 4,
        "speed": 8, "crit_rate": 0.15, "dodge_rate": 0.12
    },
    Classes.PRIEST: {
        "hp": 80, "mana": 70, "attack": 6, "defense": 5,
        "speed": 5, "crit_rate": 0.05, "dodge_rate": 0.05
    }
}


class Character:
    def __init__(self, character_id, name, class_type):
        self.id = character_id
        self.name = name
        self.class_type = class_type
        self.level = 1
        stats = CLASS_STATS[class_type]
        self.max_hp = stats["hp"]
        self.hp = self.max_hp
        self.max_mana = stats["mana"]
        self.mana = self.max_mana
        self.base_attack = stats["attack"]
        self.base_defense = stats["defense"]
        self.base_speed = stats["speed"]
        self.crit_rate = stats["crit_rate"]
        self.dodge_rate = stats["dodge_rate"]
        self.element_resist = {"fire": 0, "ice": 0, "poison": 0}
        self.position = (0, 0)
        self.alive = True
        self.has_resurrection = False
        self.equipment = {
            "weapon": None,
            "offhand": None,
            "chest": None,
            "head": None,
            "ring": None,
            "accessory": None
        }
        self.active_skills = []
        self.passive_skills = []
        self.skill_cooldowns = {}
        self.status_effects = []
        self.inventory = []
        self.gold = 0

    def take_damage(self, amount, element=None):
        if not self.alive:
            return 0
        resist = 0
        if element and element in self.element_resist:
            resist = self.element_resist[element]
        actual_damage = max(0, int(amount * (1 - resist)))
        self.hp -= actual_damage
        if self.hp <= 0:
            self.die()
        return actual_damage

    def heal(self, amount):
        if not self.alive:
            return 0
        old_hp = self.hp
        self.hp = min(self.max_hp, self.hp + amount)
        return self.hp - old_hp

    def use_mana(self, amount):
        if self.mana >= amount:
            self.mana -= amount
            return True
        return False

    def add_status_effect(self, effect):
        self.status_effects.append(effect)

    def tick_status_effects(self):
        skip_turn = False
        expired = []
        for effect in self.status_effects:
            name = effect.get("name", "")
            if name == "poison":
                self.hp -= effect.get("value", 0)
                if self.hp <= 0:
                    self.die()
                    return True
            elif name == "burn":
                self.hp -= effect.get("value", 0)
                if self.hp <= 0:
                    self.die()
                    return True
            elif name in ("frozen", "stun"):
                skip_turn = True
            effect["duration"] -= 1
            if effect["duration"] <= 0:
                expired.append(effect)
        for effect in expired:
            self.status_effects.remove(effect)
        expired_cooldowns = []
        for skill_id, cd in self.skill_cooldowns.items():
            self.skill_cooldowns[skill_id] = cd - 1
            if self.skill_cooldowns[skill_id] <= 0:
                expired_cooldowns.append(skill_id)
        for skill_id in expired_cooldowns:
            del self.skill_cooldowns[skill_id]
        return skip_turn

    def calculate_derived_stats(self):
        attack = self.base_attack
        defense = self.base_defense
        speed = self.base_speed
        for slot, item in self.equipment.items():
            if item is not None:
                attack += item.get("attack", 0)
                defense += item.get("defense", 0)
                speed += item.get("speed", 0)
        for skill in self.passive_skills:
            attack += skill.get("attack_bonus", 0)
            defense += skill.get("defense_bonus", 0)
            speed += skill.get("speed_bonus", 0)
        for effect in self.status_effects:
            name = effect.get("name", "")
            if name in ("attack_up", "attack_buff"):
                attack += effect.get("value", 0)
            elif name in ("defense_up", "defense_buff"):
                defense += effect.get("value", 0)
            elif name in ("speed_up", "speed_buff"):
                speed += effect.get("value", 0)
            elif name in ("attack_down", "attack_debuff"):
                attack -= effect.get("value", 0)
            elif name in ("defense_down", "defense_debuff"):
                defense -= effect.get("value", 0)
            elif name in ("speed_down", "speed_debuff"):
                speed -= effect.get("value", 0)
        return {
            "attack": max(0, attack),
            "defense": max(0, defense),
            "speed": max(0, speed)
        }

    def can_act(self):
        if not self.alive:
            return False
        for effect in self.status_effects:
            if effect.get("name") in ("frozen", "stun"):
                return False
        return True

    def die(self):
        if self.has_resurrection:
            self.has_resurrection = False
            self.hp = self.max_hp // 2
            self.mana = self.max_mana // 2
            self.status_effects.clear()
            return
        self.hp = 0
        self.alive = False

    def resurrect(self):
        if not self.alive:
            self.alive = True
            self.hp = self.max_hp // 2
            self.mana = self.max_mana // 2
            self.status_effects.clear()

    def equip_item(self, item):
        slot = item.get("slot")
        if slot and slot in self.equipment:
            if self.equipment[slot] is not None:
                self.add_to_inventory(self.equipment[slot])
            self.equipment[slot] = item
            return True
        return False

    def unequip_item(self, slot):
        if slot in self.equipment and self.equipment[slot] is not None:
            item = self.equipment[slot]
            if len(self.inventory) < 20:
                self.equipment[slot] = None
                self.add_to_inventory(item)
                return True
        return False

    def add_to_inventory(self, item):
        if len(self.inventory) < 20:
            self.inventory.append(item)
            return True
        return False

    def remove_from_inventory(self, index):
        if 0 <= index < len(self.inventory):
            return self.inventory.pop(index)
        return None

    def learn_skill(self, skill):
        skill_type = skill.get("type", "active")
        if skill_type == "active":
            if len(self.active_skills) < 4:
                self.active_skills.append(skill)
                return True
        elif skill_type == "passive":
            if len(self.passive_skills) < 2:
                self.passive_skills.append(skill)
                return True
        return False

    def use_skill(self, skill_id):
        for skill in self.active_skills:
            if skill.get("id") == skill_id:
                if self.skill_cooldowns.get(skill_id, 0) > 0:
                    return False
                mana_cost = skill.get("mana_cost", 0)
                if not self.use_mana(mana_cost):
                    return False
                cooldown = skill.get("cooldown", 0)
                if cooldown > 0:
                    self.skill_cooldowns[skill_id] = cooldown
                return skill
        return False

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "class_type": self.class_type.value,
            "level": self.level,
            "hp": self.hp,
            "max_hp": self.max_hp,
            "mana": self.mana,
            "max_mana": self.max_mana,
            "base_attack": self.base_attack,
            "base_defense": self.base_defense,
            "base_speed": self.base_speed,
            "crit_rate": self.crit_rate,
            "dodge_rate": self.dodge_rate,
            "element_resist": dict(self.element_resist),
            "position": list(self.position),
            "alive": self.alive,
            "has_resurrection": self.has_resurrection,
            "equipment": self.equipment,
            "active_skills": self.active_skills,
            "passive_skills": self.passive_skills,
            "skill_cooldowns": dict(self.skill_cooldowns),
            "status_effects": self.status_effects,
            "inventory": self.inventory,
            "gold": self.gold
        }

    @classmethod
    def from_dict(cls, data):
        character = cls(data["id"], data["name"], Classes(data["class_type"]))
        character.level = data.get("level", 1)
        character.hp = data["hp"]
        character.max_hp = data["max_hp"]
        character.mana = data["mana"]
        character.max_mana = data["max_mana"]
        character.base_attack = data["base_attack"]
        character.base_defense = data["base_defense"]
        character.base_speed = data["base_speed"]
        character.crit_rate = data["crit_rate"]
        character.dodge_rate = data["dodge_rate"]
        character.element_resist = data.get("element_resist", {"fire": 0, "ice": 0, "poison": 0})
        character.position = tuple(data.get("position", [0, 0]))
        character.alive = data.get("alive", True)
        character.has_resurrection = data.get("has_resurrection", False)
        character.equipment = data.get("equipment", {
            "weapon": None, "offhand": None, "chest": None,
            "head": None, "ring": None, "accessory": None
        })
        character.active_skills = data.get("active_skills", [])
        character.passive_skills = data.get("passive_skills", [])
        character.skill_cooldowns = data.get("skill_cooldowns", {})
        character.status_effects = data.get("status_effects", [])
        character.inventory = data.get("inventory", [])
        character.gold = data.get("gold", 0)
        return character
