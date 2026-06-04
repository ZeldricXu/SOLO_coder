from enum import Enum
from .character import Character, Classes
import json
import uuid


class ConnectionState(Enum):
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    READY = "ready"


class Player(Character):
    def __init__(self, character_id, name, class_type, client_id, websocket):
        super().__init__(character_id, name, class_type)
        self.client_id = client_id
        self.websocket = websocket
        self.connection_state = ConnectionState.CONNECTED
        self.has_acted_this_turn = False
        self.visible_tiles = set()

    def to_dict(self):
        data = super().to_dict()
        data.update({
            "client_id": self.client_id,
            "connection_state": self.connection_state.value,
            "has_acted_this_turn": self.has_acted_this_turn
        })
        return data

    @classmethod
    def from_dict(cls, data, websocket=None):
        player = cls(
            data["id"],
            data["name"],
            Classes(data["class_type"]),
            data["client_id"],
            websocket
        )
        player.level = data.get("level", 1)
        player.hp = data["hp"]
        player.max_hp = data["max_hp"]
        player.mana = data["mana"]
        player.max_mana = data["max_mana"]
        player.base_attack = data["base_attack"]
        player.base_defense = data["base_defense"]
        player.base_speed = data["base_speed"]
        player.crit_rate = data["crit_rate"]
        player.dodge_rate = data["dodge_rate"]
        player.element_resist = data.get("element_resist", {"fire": 0, "ice": 0, "poison": 0})
        player.position = tuple(data.get("position", [0, 0]))
        player.alive = data.get("alive", True)
        player.has_resurrection = data.get("has_resurrection", False)
        player.equipment = data.get("equipment", {
            "weapon": None, "offhand": None, "chest": None,
            "head": None, "ring": None, "accessory": None
        })
        player.active_skills = data.get("active_skills", [])
        player.passive_skills = data.get("passive_skills", [])
        player.skill_cooldowns = data.get("skill_cooldowns", {})
        player.status_effects = data.get("status_effects", [])
        player.inventory = data.get("inventory", [])
        player.gold = data.get("gold", 0)
        player.connection_state = ConnectionState(data.get("connection_state", "connected"))
        player.has_acted_this_turn = data.get("has_acted_this_turn", False)
        return player


class Party:
    MAX_SIZE = 4

    def __init__(self, party_id, leader):
        self.id = party_id
        self.players = [leader]
        self.leader_id = leader.client_id
        self.shared_vision = set()
        self.current_dungeon_id = None
        self.turn_order = [leader.client_id]
        self.turn_index = 0

    def to_dict(self):
        return {
            "id": self.id,
            "players": [p.to_dict() for p in self.players],
            "leader_id": self.leader_id,
            "current_dungeon_id": self.current_dungeon_id,
            "turn_order": self.turn_order,
            "turn_index": self.turn_index,
            "shared_vision": [list(t) for t in self.shared_vision]
        }


class MultiplayerSystem:
    def __init__(self):
        self.parties = {}

    def create_party(self, leader):
        party_id = str(uuid.uuid4())
        party = Party(party_id, leader)
        self.parties[party_id] = party
        self.broadcast_to_party(party, "party_created", party.to_dict())
        return party

    def join_party(self, party_id, player):
        party = self.parties.get(party_id)
        if not party or len(party.players) >= Party.MAX_SIZE:
            return False
        party.players.append(player)
        party.turn_order.append(player.client_id)
        self.broadcast_to_party(party, "player_joined", player.to_dict())
        self.broadcast_to_party(party, "party_updated", party.to_dict())
        return True

    def leave_party(self, party_id, player_id):
        party = self.parties.get(party_id)
        if not party:
            return False
        player = next((p for p in party.players if p.client_id == player_id), None)
        if not player:
            return False
        party.players.remove(player)
        party.turn_order.remove(player_id)
        if len(party.players) == 0:
            del self.parties[party_id]
            return True
        if party.leader_id == player_id:
            party.leader_id = party.players[0].client_id
        if party.turn_index >= len(party.turn_order):
            party.turn_index = 0
        self.broadcast_to_party(party, "player_left", {"player_id": player_id, "leader_id": party.leader_id})
        self.broadcast_to_party(party, "party_updated", party.to_dict())
        return True

    async def broadcast_to_party(self, party, message_type, data, exclude_id=None):
        message = json.dumps({"type": message_type, "data": data})
        for player in party.players:
            if player.client_id == exclude_id:
                continue
            if player.connection_state == ConnectionState.CONNECTED or player.connection_state == ConnectionState.READY:
                if player.websocket and not player.websocket.closed:
                    await player.websocket.send(message)

    def update_shared_vision(self, party, game_map):
        party.shared_vision.clear()
        for player in party.players:
            if player.connection_state != ConnectionState.DISCONNECTED and player.alive:
                party.shared_vision.update(player.visible_tiles)
        return party.shared_vision

    def check_all_acted(self, party):
        for player in party.players:
            if player.connection_state == ConnectionState.DISCONNECTED:
                continue
            if player.alive and not player.has_acted_this_turn:
                return False
        return True

    def reset_turn_actions(self, party):
        for player in party.players:
            player.has_acted_this_turn = False

    def exchange_item(self, party, from_player_id, to_player_id, item_slot):
        from_player = next((p for p in party.players if p.client_id == from_player_id), None)
        to_player = next((p for p in party.players if p.client_id == to_player_id), None)
        if not from_player or not to_player:
            return None
        if not (0 <= item_slot < len(from_player.inventory)):
            return None
        if len(to_player.inventory) >= 20:
            return None
        item = from_player.inventory.pop(item_slot)
        to_player.inventory.append(item)
        return {"item": item, "from": from_player_id, "to": to_player_id}

    def revive_teammate(self, party, reviver_id, target_id, item_slot):
        reviver = next((p for p in party.players if p.client_id == reviver_id), None)
        target = next((p for p in party.players if p.client_id == target_id), None)
        if not reviver or not target:
            return False
        if not (0 <= item_slot < len(reviver.inventory)):
            return False
        item = reviver.inventory[item_slot]
        if not item or item.get("consumable_effect") != "resurrection":
            return False
        if target.alive:
            return False
        reviver.inventory.pop(item_slot)
        target.resurrect()
        return True

    async def handle_disconnect(self, party, player_id):
        player = next((p for p in party.players if p.client_id == player_id), None)
        if not player:
            return False
        player.connection_state = ConnectionState.DISCONNECTED
        player.websocket = None
        self.broadcast_to_party(party, "player_disconnected", {"player_id": player_id})
        return True

    async def handle_reconnect(self, party, player_id, new_websocket):
        player = next((p for p in party.players if p.client_id == player_id), None)
        if not player:
            return False
        player.websocket = new_websocket
        player.connection_state = ConnectionState.CONNECTED
        self.broadcast_to_party(party, "player_reconnected", {"player_id": player_id})
        return True
