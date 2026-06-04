from __future__ import annotations

import asyncio
import json
import logging
import signal
import uuid
from collections import deque
from typing import Any, Dict, Optional

import websockets

from .config import DungeonConfig, get_config
from .database import get_player, create_player, update_player_stats, get_leaderboard, save_run_history, init_db
from .multiplayer import MultiplayerSystem, Player, Party
from .character import Classes
from .season_challenge import SeasonChallengeSystem, Season
from .game_manager import GameManager

logger = logging.getLogger("dungeon.server")


class ClientSession:
    def __init__(self, client_id: str, websocket):
        self.client_id = client_id
        self.websocket = websocket
        self.player_ref: Optional[Player] = None
        self.authenticated = False
        self.last_ping: float = 0.0
        self.party_id: Optional[str] = None
        self.message_timestamps: deque = deque(maxlen=10)
        self.db_player_id: Optional[int] = None
        self.username: Optional[str] = None


class NetworkServer:
    def __init__(self, config: DungeonConfig | None = None):
        self.config = config or get_config()
        self.host = self.config.host
        self.port = self.config.port
        self.sessions: Dict[str, ClientSession] = {}
        self.pending_auth: Dict[str, str] = {}
        self.multiplayer = MultiplayerSystem()
        self.season_system = SeasonChallengeSystem()
        self.game_manager = GameManager()
        self.game_runs: Dict[str, Dict[str, Any]] = {}
        self._ping_task: Optional[asyncio.Task] = None
        self._shutting_down = False
        self._ws_server = None

    async def start(self):
        async with websockets.serve(
            self.handle_client,
            self.host,
            self.port,
            ping_interval=30,
            ping_timeout=60,
            close_timeout=self.config.graceful_shutdown_timeout,
        ) as ws_server:
            self._ws_server = ws_server
            self._ping_task = asyncio.create_task(self.periodic_ping())
            logger.info("Server started on %s:%d [%s]", self.host, self.port, self.config.environment.value)
            stop_event = asyncio.Event()
            loop = asyncio.get_running_loop()

            def _signal_handler():
                if not self._shutting_down:
                    logger.info("Received shutdown signal")
                    asyncio.create_task(self.graceful_shutdown(stop_event))

            for sig in (signal.SIGTERM, signal.SIGINT):
                loop.add_signal_handler(sig, _signal_handler)

            await stop_event.wait()

    async def graceful_shutdown(self, stop_event: asyncio.Event):
        if self._shutting_down:
            return
        self._shutting_down = True

        logger.info("Initiating graceful shutdown...")
        logger.info("Notifying %d connected players...", len(self.sessions))

        maintenance_msg = json.dumps({
            "type": "SERVER_MAINTENANCE",
            "data": {"message": "服务器维护中，请保存进度", "grace_period": self.config.graceful_shutdown_timeout}
        })

        close_tasks = []
        for cid, session in list(self.sessions.items()):
            if session.websocket and not session.websocket.closed:
                try:
                    await session.websocket.send(maintenance_msg)
                except Exception:
                    pass

        logger.info("Waiting for in-progress turns to settle...")
        await asyncio.sleep(min(2.0, self.config.turn_settlement_timeout))

        for cid, session in list(self.sessions.items()):
            if session.player_ref and session.player_ref.db_player_id:
                try:
                    await self._save_player_state(session)
                except Exception as e:
                    logger.error("Failed to save player %s: %s", cid, e)

            if session.websocket and not session.websocket.closed:
                close_tasks.append(session.websocket.close(1001, "server_shutdown"))

        if close_tasks:
            await asyncio.gather(*close_tasks, return_exceptions=True)

        if self._ping_task:
            self._ping_task.cancel()

        logger.info("Graceful shutdown complete")
        stop_event.set()

    async def _save_player_state(self, session: ClientSession):
        if not session.player_ref:
            return
        p = session.player_ref
        if session.db_player_id:
            await update_player_stats(
                session.db_player_id,
                max_floor_reached=getattr(p, "max_floor_reached", 0),
            )

    async def handle_client(self, websocket):
        if self._shutting_down:
            await websocket.close(1001, "server_shutting_down")
            return

        client_id = str(uuid.uuid4())
        session = ClientSession(client_id, websocket)
        self.sessions[client_id] = session

        try:
            async for message in websocket:
                if self._shutting_down:
                    break
                if not self._check_rate_limit(session):
                    await self.send_to_client(client_id, "ERROR", {"message": "Rate limit exceeded"})
                    continue

                try:
                    msg = json.loads(message)
                    await self.route_message(session, msg)
                except json.JSONDecodeError:
                    await self.send_to_client(client_id, "ERROR", {"message": "Invalid JSON"})
                except Exception as e:
                    logger.exception("Error handling message from %s", client_id)
                    await self.send_to_client(client_id, "ERROR", {"message": str(e)})
        finally:
            if session.party_id:
                party = self.multiplayer.parties.get(session.party_id)
                if party:
                    await self.multiplayer.handle_disconnect(party, client_id)
            del self.sessions[client_id]

    def _check_rate_limit(self, session: ClientSession) -> bool:
        import time
        now = time.time()
        while session.message_timestamps and now - session.message_timestamps[0] > 1.0:
            session.message_timestamps.popleft()
        if len(session.message_timestamps) >= self.config.rate_limit_per_second:
            return False
        session.message_timestamps.append(now)
        return True

    async def broadcast(self, message_type: str, data: dict, client_ids: Optional[list] = None):
        msg = json.dumps({"type": message_type, "data": data})
        targets = client_ids if client_ids else list(self.sessions.keys())
        for cid in targets:
            session = self.sessions.get(cid)
            if session and session.websocket and not session.websocket.closed:
                try:
                    await session.websocket.send(msg)
                except Exception:
                    pass

    async def send_to_client(self, client_id: str, message_type: str, data: dict, request_id: Optional[str] = None):
        session = self.sessions.get(client_id)
        if not session or not session.websocket or session.websocket.closed:
            return
        msg = {"type": message_type, "data": data}
        if request_id:
            msg["request_id"] = request_id
        try:
            await session.websocket.send(json.dumps(msg))
        except Exception:
            pass

    async def authenticate_client(self, token: str, client_data: dict) -> Optional[dict]:
        import hashlib
        auth_type = client_data.get("type")
        username = client_data.get("username")
        password = client_data.get("password")

        if not username or not password:
            return None

        password_hash = hashlib.sha256(password.encode()).hexdigest()

        if auth_type == "LOGIN":
            player = await get_player(username)
            if not player or player["password_hash"] != password_hash:
                return None
            return {"success": True, "player_id": player["id"], "username": username}
        elif auth_type == "REGISTER":
            existing = await get_player(username)
            if existing:
                return None
            player_id = await create_player(username, password_hash)
            return {"success": True, "player_id": player_id, "username": username}
        return None

    async def route_message(self, session: ClientSession, msg: dict):
        import time
        msg_type = msg.get("type")
        data = msg.get("data", {})
        request_id = msg.get("request_id")

        unauthenticated_types = {"LOGIN", "REGISTER", "PING"}
        if not session.authenticated and msg_type not in unauthenticated_types:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not authenticated"}, request_id)
            return

        handlers = {
            "LOGIN": self.handle_login,
            "REGISTER": self.handle_register,
            "SELECT_CLASS": self.handle_select_class,
            "MOVE": self.handle_move,
            "ATTACK": self.handle_attack,
            "USE_SKILL": self.handle_use_skill,
            "USE_ITEM": self.handle_use_item,
            "EQUIP_ITEM": self.handle_equip_item,
            "INTERACT": self.handle_interact,
            "CHAT": self.handle_chat,
            "CREATE_PARTY": self.handle_create_party,
            "JOIN_PARTY": self.handle_join_party,
            "LEAVE_PARTY": self.handle_leave_party,
            "READY": self.handle_ready,
            "EXCHANGE_ITEM": self.handle_exchange_item,
            "REVIVE_TEAMMATE": self.handle_revive_teammate,
            "GET_LEADERBOARD": self.handle_get_leaderboard,
            "START_SEASON_RUN": self.handle_start_season_run,
            "START_NORMAL_RUN": self.handle_start_normal_run,
            "ENHANCE_ITEM": self.handle_enhance_item,
            "JOIN_DUNGEON": self.handle_join_dungeon,
            "PING": self.handle_ping,
        }

        handler = handlers.get(msg_type)
        if not handler:
            await self.send_to_client(session.client_id, "ERROR", {"message": f"Unknown type: {msg_type}"}, request_id)
            return

        try:
            await handler(session, data, request_id)
        except Exception as e:
            logger.exception("Handler error for %s", msg_type)
            await self.send_to_client(session.client_id, "ERROR", {"message": str(e)}, request_id)

    async def periodic_ping(self):
        import time
        while True:
            await asyncio.sleep(30)
            now = time.time()
            dead_sessions = []
            for cid, session in self.sessions.items():
                if now - session.last_ping > 60:
                    dead_sessions.append(cid)
                else:
                    await self.send_to_client(cid, "PING", {"timestamp": now})
            for cid in dead_sessions:
                session = self.sessions.get(cid)
                if session:
                    if session.party_id:
                        party = self.multiplayer.parties.get(session.party_id)
                        if party:
                            await self.multiplayer.handle_disconnect(party, cid)
                    if session.websocket and not session.websocket.closed:
                        await session.websocket.close()
                    del self.sessions[cid]

    async def handle_login(self, session: ClientSession, data: dict, request_id: Optional[str]):
        result = await self.authenticate_client("", {"type": "LOGIN", **data})
        if not result:
            await self.send_to_client(session.client_id, "LOGIN_FAILED", {"message": "Invalid credentials"}, request_id)
            return
        import time
        session.authenticated = True
        session.db_player_id = result["player_id"]
        session.username = result["username"]
        session.last_ping = time.time()
        await self.send_to_client(session.client_id, "LOGIN_SUCCESS", {
            "player_id": result["player_id"],
            "username": result["username"]
        }, request_id)

    async def handle_register(self, session: ClientSession, data: dict, request_id: Optional[str]):
        result = await self.authenticate_client("", {"type": "REGISTER", **data})
        if not result:
            await self.send_to_client(session.client_id, "REGISTER_FAILED", {"message": "Username exists"}, request_id)
            return
        import time
        session.authenticated = True
        session.db_player_id = result["player_id"]
        session.username = result["username"]
        session.last_ping = time.time()
        await self.send_to_client(session.client_id, "REGISTER_SUCCESS", {
            "player_id": result["player_id"],
            "username": result["username"]
        }, request_id)

    async def handle_ping(self, session: ClientSession, data: dict, request_id: Optional[str]):
        import time
        session.last_ping = time.time()
        await self.send_to_client(session.client_id, "PING", {"timestamp": time.time()}, request_id)

    async def handle_select_class(self, session: ClientSession, data: dict, request_id: Optional[str]):
        class_name = data.get("class_name")
        try:
            class_type = Classes(class_name)
        except ValueError:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Invalid class"}, request_id)
            return

        player_id = str(uuid.uuid4())
        player = Player(
            player_id,
            session.username or "Player",
            class_type,
            session.client_id,
            session.websocket
        )
        session.player_ref = player

        if session.db_player_id:
            await update_player_stats(session.db_player_id, class_name=class_name)

        await self.send_to_client(session.client_id, "GAME_STATE", {
            "player": player.to_dict(),
            "selected_class": class_name
        }, request_id)

    async def handle_create_party(self, session: ClientSession, data: dict, request_id: Optional[str]):
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character selected"}, request_id)
            return

        party = self.multiplayer.create_party(session.player_ref)
        session.party_id = party.id
        session.player_ref.connection_state = session.player_ref.connection_state.__class__.READY

        await self.send_to_client(session.client_id, "PARTY_UPDATED", party.to_dict(), request_id)

    async def handle_join_party(self, session: ClientSession, data: dict, request_id: Optional[str]):
        party_id = data.get("party_id")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character selected"}, request_id)
            return

        success = self.multiplayer.join_party(party_id, session.player_ref)
        if not success:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Party not found or full"}, request_id)
            return

        session.party_id = party_id
        session.player_ref.connection_state = session.player_ref.connection_state.__class__.READY
        party = self.multiplayer.parties[party_id]

        await self.send_to_client(session.client_id, "PARTY_UPDATED", party.to_dict(), request_id)

    async def handle_leave_party(self, session: ClientSession, data: dict, request_id: Optional[str]):
        if not session.party_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in party"}, request_id)
            return

        success = self.multiplayer.leave_party(session.party_id, session.client_id)
        if success:
            session.party_id = None
            if session.player_ref:
                session.player_ref.connection_state = session.player_ref.connection_state.__class__.CONNECTED

        await self.send_to_client(session.client_id, "PARTY_UPDATED", {"party_id": None}, request_id)

    async def handle_ready(self, session: ClientSession, data: dict, request_id: Optional[str]):
        if session.player_ref:
            from .multiplayer import ConnectionState
            session.player_ref.connection_state = ConnectionState.READY
        await self.send_to_client(session.client_id, "GAME_STATE", {"ready": True}, request_id)

    async def handle_chat(self, session: ClientSession, data: dict, request_id: Optional[str]):
        import time
        message = data.get("message", "")
        if not message:
            return

        chat_data = {
            "sender": session.username or "Unknown",
            "client_id": session.client_id,
            "message": message,
            "timestamp": time.time()
        }

        if session.party_id:
            party = self.multiplayer.parties.get(session.party_id)
            if party:
                client_ids = [p.client_id for p in party.players]
                await self.broadcast("CHAT_MESSAGE", chat_data, client_ids)
        else:
            await self.broadcast("CHAT_MESSAGE", chat_data)

        await self.send_to_client(session.client_id, "CHAT_MESSAGE", chat_data, request_id)

    async def handle_move(self, session: ClientSession, data: dict, request_id: Optional[str]):
        direction = data.get("direction")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dx, dy = 0, 0
        if direction == "up":
            dy = -1
        elif direction == "down":
            dy = 1
        elif direction == "left":
            dx = -1
        elif direction == "right":
            dx = 1

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if not dungeon_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in dungeon"}, request_id)
            return

        result = await self.game_manager.process_player_action(
            dungeon_id, session.client_id, "MOVE", {"dx": dx, "dy": dy}
        )

        if result.get("success"):
            if session.party_id:
                party = self.multiplayer.parties.get(session.party_id)
                if party:
                    client_ids = [p.client_id for p in party.players]
                    for cid in client_ids:
                        state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                        await self.send_to_client(cid, "GAME_STATE", state_view)
            else:
                await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)
        else:
            await self.send_to_client(session.client_id, "ERROR", {"message": result.get("error", "Move failed")}, request_id)

    async def handle_attack(self, session: ClientSession, data: dict, request_id: Optional[str]):
        target_id = data.get("target_id")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if not dungeon_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in dungeon"}, request_id)
            return

        result = await self.game_manager.process_player_action(
            dungeon_id, session.client_id, "ATTACK", {"target_id": target_id}
        )

        if result.get("success"):
            combat_result = result.get("combat_result", {})
            combat_log = {
                "attacker": session.player_ref.name,
                "attacker_id": session.player_ref.id,
                "target_id": target_id,
                "damage": combat_result.get("damage", 0),
                "was_crit": combat_result.get("was_crit", False),
                "was_dodged": combat_result.get("was_dodged", False),
                "killed": combat_result.get("killed", False),
                "type": "attack"
            }

            if session.party_id:
                party = self.multiplayer.parties.get(session.party_id)
                if party:
                    client_ids = [p.client_id for p in party.players]
                    await self.broadcast("COMBAT_LOG", combat_log, client_ids)
                    for cid in client_ids:
                        state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                        await self.send_to_client(cid, "GAME_STATE", state_view)
            else:
                await self.send_to_client(session.client_id, "COMBAT_LOG", combat_log)
                await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)
        else:
            await self.send_to_client(session.client_id, "ERROR", {"message": result.get("error", "Attack failed")}, request_id)

    async def handle_use_skill(self, session: ClientSession, data: dict, request_id: Optional[str]):
        skill_id = data.get("skill_id")
        target_id = data.get("target_id")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if not dungeon_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in dungeon"}, request_id)
            return

        result = await self.game_manager.process_player_action(
            dungeon_id, session.client_id, "USE_SKILL", {"skill_id": skill_id, "target_id": target_id}
        )

        if result.get("success"):
            if session.party_id:
                party = self.multiplayer.parties.get(session.party_id)
                if party:
                    client_ids = [p.client_id for p in party.players]
                    for cid in client_ids:
                        state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                        await self.send_to_client(cid, "GAME_STATE", state_view)
            else:
                await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)
        else:
            await self.send_to_client(session.client_id, "ERROR", {"message": result.get("error", "Skill failed")}, request_id)

    async def handle_use_item(self, session: ClientSession, data: dict, request_id: Optional[str]):
        item_slot = data.get("item_slot")
        target_id = data.get("target_id")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if dungeon_id:
            result = await self.game_manager.process_player_action(
                dungeon_id, session.client_id, "USE_ITEM", {"item_slot": item_slot, "target_id": target_id}
            )

            if result.get("success"):
                if session.party_id:
                    party = self.multiplayer.parties.get(session.party_id)
                    if party:
                        client_ids = [p.client_id for p in party.players]
                        for cid in client_ids:
                            state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                            await self.send_to_client(cid, "GAME_STATE", state_view)
                else:
                    await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)

                await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
                    "inventory": session.player_ref.inventory
                })
                return

        if not session.player_ref:
            return
        if not (0 <= (item_slot or 0) < len(session.player_ref.inventory)):
            await self.send_to_client(session.client_id, "ERROR", {"message": "Invalid slot"}, request_id)
            return

        item = session.player_ref.inventory[item_slot]
        effect = item.get("consumable_effect", "")
        value = item.get("consumable_value", 0)

        if effect == "heal":
            healed = session.player_ref.heal(value)
            log = {"type": "heal", "amount": healed, "item": item.get("name")}
        elif effect == "mana":
            session.player_ref.mana = min(session.player_ref.max_mana, session.player_ref.mana + value)
            log = {"type": "mana", "amount": value, "item": item.get("name")}
        else:
            log = {"type": "use_item", "item": item.get("name")}

        session.player_ref.inventory.pop(item_slot)
        session.player_ref.has_acted_this_turn = True

        await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
            "inventory": session.player_ref.inventory
        }, request_id)
        await self._send_game_state_update(session, None)

    async def handle_equip_item(self, session: ClientSession, data: dict, request_id: Optional[str]):
        item_slot = data.get("item_slot")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if dungeon_id:
            result = await self.game_manager.process_player_action(
                dungeon_id, session.client_id, "EQUIP_ITEM", {"item_slot": item_slot}
            )

            if result.get("success"):
                if session.party_id:
                    party = self.multiplayer.parties.get(session.party_id)
                    if party:
                        client_ids = [p.client_id for p in party.players]
                        for cid in client_ids:
                            state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                            await self.send_to_client(cid, "GAME_STATE", state_view)
                else:
                    await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)

                await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
                    "inventory": session.player_ref.inventory,
                    "equipment": session.player_ref.equipment
                })
                return

        if not session.player_ref:
            return
        if not (0 <= (item_slot or 0) < len(session.player_ref.inventory)):
            await self.send_to_client(session.client_id, "ERROR", {"message": "Invalid slot"}, request_id)
            return

        item = session.player_ref.inventory[item_slot]
        success = session.player_ref.equip_item(item)
        if not success:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Cannot equip"}, request_id)
            return

        session.player_ref.inventory.pop(item_slot)

        await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
            "inventory": session.player_ref.inventory,
            "equipment": session.player_ref.equipment
        }, request_id)
        await self._send_game_state_update(session, None)

    async def handle_interact(self, session: ClientSession, data: dict, request_id: Optional[str]):
        interaction_type = data.get("type", "default")
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if dungeon_id:
            result = await self.game_manager.process_player_action(
                dungeon_id, session.client_id, "INTERACT", {"type": interaction_type}
            )

            if result.get("success"):
                if session.party_id:
                    party = self.multiplayer.parties.get(session.party_id)
                    if party:
                        client_ids = [p.client_id for p in party.players]
                        for cid in client_ids:
                            state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                            await self.send_to_client(cid, "GAME_STATE", state_view)
                else:
                    await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)

                if "event" in result:
                    await self.send_to_client(session.client_id, "EVENT_TRIGGERED", result["event"])
                return

        event_data = {
            "event_id": str(uuid.uuid4()),
            "type": interaction_type,
            "title": "Mysterious Chest",
            "description": "You found a mysterious chest. What do you do?",
            "choices": [
                {"id": "open", "text": "Open it"},
                {"id": "leave", "text": "Leave it"}
            ]
        }

        session.player_ref.has_acted_this_turn = True

        await self.send_to_client(session.client_id, "EVENT_TRIGGERED", event_data, request_id)

    async def handle_exchange_item(self, session: ClientSession, data: dict, request_id: Optional[str]):
        target_id = data.get("target_id")
        item_slot = data.get("item_slot")
        if not session.party_id or not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in party"}, request_id)
            return

        party = self.multiplayer.parties.get(session.party_id)
        if not party:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Party not found"}, request_id)
            return

        result = self.multiplayer.exchange_item(party, session.client_id, target_id, item_slot)
        if not result:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Exchange failed"}, request_id)
            return

        client_ids = [p.client_id for p in party.players]
        await self.broadcast("INVENTORY_UPDATED", {
            "from": result["from"],
            "to": result["to"],
            "item": result["item"]
        }, client_ids)

        await self.send_to_client(session.client_id, "PARTY_UPDATED", party.to_dict(), request_id)

    async def handle_revive_teammate(self, session: ClientSession, data: dict, request_id: Optional[str]):
        target_id = data.get("target_id")
        item_slot = data.get("item_slot")
        if not session.party_id or not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in party"}, request_id)
            return

        party = self.multiplayer.parties.get(session.party_id)
        if not party:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Party not found"}, request_id)
            return

        success = self.multiplayer.revive_teammate(party, session.client_id, target_id, item_slot)
        if not success:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Revive failed"}, request_id)
            return

        client_ids = [p.client_id for p in party.players]
        await self.broadcast("PARTY_UPDATED", party.to_dict(), client_ids)
        await self.send_to_client(session.client_id, "PARTY_UPDATED", party.to_dict(), request_id)

    async def handle_get_leaderboard(self, session: ClientSession, data: dict, request_id: Optional[str]):
        season_id = data.get("season_id")
        limit = data.get("limit", 10)

        if not season_id:
            season = await self.season_system.get_active_daily()
            season_id = season.id

        entries = await get_leaderboard(season_id, limit)
        await self.send_to_client(session.client_id, "LEADERBOARD_DATA", {
            "season_id": season_id,
            "entries": entries
        }, request_id)

    async def handle_start_season_run(self, session: ClientSession, data: dict, request_id: Optional[str]):
        import time as _time
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        season_type = data.get("season_type", "daily")
        if season_type == "daily":
            season = await self.season_system.get_active_daily()
        else:
            season = await self.season_system.get_active_weekly()

        players = [session.player_ref]
        game_state = self.game_manager.create_dungeon(players, floor=1, season=season)

        run_id = game_state.dungeon_id
        self.game_runs[run_id] = {
            "run_id": run_id,
            "season": season,
            "player_id": session.db_player_id,
            "start_time": _time.time(),
            "floor_reached": 1,
            "monsters_killed": 0,
            "items_found": 0,
            "deaths": 0
        }

        session.player_ref.has_acted_this_turn = False
        state_view = self.game_manager.get_game_state_view(run_id, session.client_id)

        await self.send_to_client(session.client_id, "GAME_STATE", state_view, request_id)

    async def handle_start_normal_run(self, session: ClientSession, data: dict, request_id: Optional[str]):
        import time as _time
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        players = [session.player_ref]
        game_state = self.game_manager.create_dungeon(players, floor=1, season=None)

        run_id = game_state.dungeon_id
        self.game_runs[run_id] = {
            "run_id": run_id,
            "season": None,
            "player_id": session.db_player_id,
            "start_time": _time.time(),
            "floor_reached": 1,
            "monsters_killed": 0,
            "items_found": 0,
            "deaths": 0
        }

        session.player_ref.has_acted_this_turn = False
        state_view = self.game_manager.get_game_state_view(run_id, session.client_id)

        await self.send_to_client(session.client_id, "GAME_STATE", state_view, request_id)

    async def handle_enhance_item(self, session: ClientSession, data: dict, request_id: Optional[str]):
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = self.game_manager.player_to_dungeon.get(session.client_id)
        if not dungeon_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "Not in dungeon"}, request_id)
            return

        result = await self.game_manager.process_player_action(
            dungeon_id, session.client_id, "ENHANCE_ITEM", data
        )

        if result.get("success"):
            if session.party_id:
                party = self.multiplayer.parties.get(session.party_id)
                if party:
                    client_ids = [p.client_id for p in party.players]
                    for cid in client_ids:
                        state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                        await self.send_to_client(cid, "GAME_STATE", state_view)
            else:
                await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)

            await self.send_to_client(session.client_id, "ENHANCE_RESULT", result.get("result", {}), request_id)
            await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
                "inventory": session.player_ref.inventory,
                "equipment": session.player_ref.equipment
            })
        else:
            await self.send_to_client(session.client_id, "ERROR", {"message": result.get("error", "Enhance failed")}, request_id)

    async def handle_join_dungeon(self, session: ClientSession, data: dict, request_id: Optional[str]):
        if not session.player_ref:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No character"}, request_id)
            return

        dungeon_id = data.get("dungeon_id")
        if not dungeon_id:
            await self.send_to_client(session.client_id, "ERROR", {"message": "No dungeon_id"}, request_id)
            return

        result = self.game_manager.add_player_to_dungeon(dungeon_id, session.player_ref)

        if result.get("success"):
            self.game_manager.player_to_dungeon[session.client_id] = dungeon_id

            gs = self.game_manager.active_dungeons.get(dungeon_id)
            if gs and gs.party:
                session.party_id = gs.party.id
                client_ids = [p.client_id for p in gs.party.players]
                for cid in client_ids:
                    state_view = self.game_manager.get_game_state_view(dungeon_id, cid)
                    await self.send_to_client(cid, "GAME_STATE", state_view)
                await self.broadcast("PARTY_UPDATED", gs.party.to_dict(), client_ids)

            await self.send_to_client(session.client_id, "GAME_STATE", result.get("game_state", {}), request_id)
            await self.send_to_client(session.client_id, "INVENTORY_UPDATED", {
                "inventory": session.player_ref.inventory,
                "equipment": session.player_ref.equipment
            }, request_id)
        else:
            await self.send_to_client(session.client_id, "ERROR", {"message": result.get("error", "Join failed")}, request_id)

    async def _send_game_state_update(self, session: ClientSession, request_id: Optional[str]):
        import time
        if not session.player_ref:
            return

        state = {
            "player": session.player_ref.to_dict(),
            "timestamp": time.time()
        }

        if session.party_id:
            party = self.multiplayer.parties.get(session.party_id)
            if party:
                state["party"] = party.to_dict()
                client_ids = [p.client_id for p in party.players]
                await self.broadcast("GAME_STATE", state, client_ids)
                return

        await self.send_to_client(session.client_id, "GAME_STATE", state, request_id)

    async def _finalize_run(self, run_id: str, death_cause: str = "completed"):
        import time
        run = self.game_runs.get(run_id)
        if not run:
            return

        duration = time.time() - run["start_time"]

        if run["player_id"]:
            class_name = ""
            for session in self.sessions.values():
                if session.db_player_id == run["player_id"] and session.player_ref:
                    class_name = session.player_ref.class_type.value
                    break

            await save_run_history(
                player_id=run["player_id"],
                class_name=class_name,
                floor_reached=run["floor_reached"],
                monsters_killed=run["monsters_killed"],
                items_found=run["items_found"],
                death_cause=death_cause,
                duration_seconds=duration
            )

            if run["season"] and isinstance(run["season"], Season):
                for session in self.sessions.values():
                    if session.db_player_id == run["player_id"] and session.player_ref:
                        await self.season_system.submit_score(
                            session.player_ref,
                            run,
                            run["season"]
                        )
                        break

        del self.game_runs[run_id]
