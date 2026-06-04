import json
import random
from enum import Enum
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Any
from pathlib import Path
from datetime import datetime, timezone, timedelta

from .database import (
    create_season,
    get_active_season,
    get_leaderboard as db_get_leaderboard,
    update_leaderboard,
)


class ChallengeType(Enum):
    NO_ARMOR = "no_armor"
    DOUBLE_HP = "double_hp"
    TIME_LIMIT = "time_limit"
    NO_HEALING = "no_healing"
    ONE_WEAPON = "one_weapon"


@dataclass
class Challenge:
    id: str
    name: str
    description: str
    rule: str
    value: Any = None
    score_multiplier: float = 1.0

    @classmethod
    def from_dict(cls, data):
        return cls(
            id=data["id"],
            name=data["name"],
            description=data["description"],
            rule=data["rule"],
            value=data.get("value", data.get("item_type", data.get("item_subtype"))),
            score_multiplier=data.get("score_multiplier", 1.0),
        )

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "rule": self.rule,
            "value": self.value,
            "score_multiplier": self.score_multiplier,
        }


@dataclass
class Season:
    id: Optional[int]
    name: str
    start_date: str
    end_date: Optional[str]
    challenges: List[Challenge] = field(default_factory=list)
    is_active: bool = True
    seed: int = 0

    @classmethod
    def from_dict(cls, data):
        challenge_data = data.get("challenge_rules", [])
        if isinstance(challenge_data, str):
            challenge_data = json.loads(challenge_data)
        challenges = [Challenge.from_dict(c) for c in challenge_data] if isinstance(challenge_data, list) else []
        return cls(
            id=data.get("id"),
            name=data["name"],
            start_date=data["start_date"],
            end_date=data.get("end_date"),
            challenges=challenges,
            is_active=bool(data.get("is_active", 1)),
            seed=data.get("seed", random.randint(0, 999999)),
        )

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "start_date": self.start_date,
            "end_date": self.end_date,
            "challenge_rules": [c.to_dict() for c in self.challenges],
            "is_active": 1 if self.is_active else 0,
            "seed": self.seed,
        }


@dataclass
class LeaderboardEntry:
    id: Optional[int]
    player_id: int
    username: str
    season_id: int
    floor_reached: int
    score: int
    timestamp: str
    class_name: str = ""

    @classmethod
    def from_dict(cls, data):
        return cls(
            id=data.get("id"),
            player_id=data["player_id"],
            username=data.get("username", ""),
            season_id=data["season_id"],
            floor_reached=data["floor_reached"],
            score=data["score"],
            timestamp=data["timestamp"],
            class_name=data.get("class_name", ""),
        )

    def to_dict(self):
        return {
            "id": self.id,
            "player_id": self.player_id,
            "username": self.username,
            "season_id": self.season_id,
            "floor_reached": self.floor_reached,
            "score": self.score,
            "timestamp": self.timestamp,
            "class_name": self.class_name,
        }


class SeasonChallengeSystem:
    def __init__(self, events_path=None):
        if events_path is None:
            events_path = Path(__file__).resolve().parent.parent / "data" / "events" / "events.json"
        self.events_path = Path(events_path)
        self.challenge_templates: List[Challenge] = []
        self.load_challenges()

    def load_challenges(self):
        with open(self.events_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        season_challenges = data.get("season_challenges", [])
        self.challenge_templates = [Challenge.from_dict(c) for c in season_challenges]

    async def create_daily_season(self):
        today = datetime.now(timezone.utc)
        name = f"每日挑战-{today.strftime('%m-%d')}"
        start_date = today.isoformat()
        end_date = (today + timedelta(days=1)).isoformat()
        seed = int(today.strftime("%Y%m%d"))

        num_challenges = random.randint(1, 2)
        selected = random.sample(self.challenge_templates, min(num_challenges, len(self.challenge_templates)))

        season_id = await create_season(
            name=name,
            start_date=start_date,
            end_date=end_date,
            challenge_rules=[c.to_dict() for c in selected],
        )

        return Season(
            id=season_id,
            name=name,
            start_date=start_date,
            end_date=end_date,
            challenges=selected,
            is_active=True,
            seed=seed,
        )

    async def create_weekly_season(self):
        today = datetime.now(timezone.utc)
        week_num = today.isocalendar()[1]
        name = f"每周挑战-W{week_num}"
        start_date = today.isoformat()
        end_date = (today + timedelta(days=7)).isoformat()
        seed = int(today.strftime("%Y%m%d")) * 100 + week_num

        num_challenges = random.randint(3, 5)
        selected = random.sample(self.challenge_templates, min(num_challenges, len(self.challenge_templates)))

        season_id = await create_season(
            name=name,
            start_date=start_date,
            end_date=end_date,
            challenge_rules=[c.to_dict() for c in selected],
        )

        return Season(
            id=season_id,
            name=name,
            start_date=start_date,
            end_date=end_date,
            challenges=selected,
            is_active=True,
            seed=seed,
        )

    def calculate_score(self, floor_reached, monsters_killed, items_found, duration_seconds, deaths, challenges):
        base_score = floor_reached * 100 + monsters_killed * 10 + items_found * 5 - duration_seconds - deaths * 200
        base_score = max(0, int(base_score))

        multiplier = 1.0
        for challenge in challenges:
            multiplier *= challenge.score_multiplier

        final_score = int(base_score * multiplier)
        return max(0, final_score)

    def apply_challenge_rules(self, challenges, game_state):
        if game_state is None:
            game_state = {}

        for challenge in challenges:
            challenge_type = ChallengeType(challenge.id) if challenge.id in [e.value for e in ChallengeType] else None

            if challenge_type == ChallengeType.DOUBLE_HP:
                monsters = game_state.get("monsters", [])
                for monster in monsters:
                    monster["max_hp"] = int(monster.get("max_hp", 0) * 2)
                    monster["hp"] = monster["max_hp"]

            elif challenge_type == ChallengeType.TIME_LIMIT:
                game_state["floor_turn_limit"] = challenge.value or 100
                game_state["current_floor_turns"] = 0

            elif challenge_type == ChallengeType.NO_ARMOR:
                player = game_state.get("player")
                if player and hasattr(player, "equipment"):
                    for slot in ["chest", "head", "ring", "accessory"]:
                        if slot in player.equipment:
                            if player.equipment[slot] is not None:
                                player.add_to_inventory(player.equipment[slot])
                                player.equipment[slot] = None

            elif challenge_type == ChallengeType.ONE_WEAPON:
                player = game_state.get("player")
                if player and hasattr(player, "equipment"):
                    initial_weapon = game_state.get("initial_weapon")
                    if initial_weapon and "weapon" in player.equipment:
                        current_weapon = player.equipment["weapon"]
                        if current_weapon and current_weapon.get("id") != initial_weapon.get("id"):
                            player.add_to_inventory(current_weapon)
                            player.equipment["weapon"] = initial_weapon

            elif challenge_type == ChallengeType.NO_HEALING:
                game_state["healing_disabled"] = True

        return game_state

    def validate_challenge_rule(self, challenge, action, game_state):
        challenge_type = ChallengeType(challenge.id) if challenge.id in [e.value for e in ChallengeType] else None
        action_type = action.get("type") if isinstance(action, dict) else None

        if challenge_type == ChallengeType.NO_ARMOR:
            if action_type == "equip":
                item = action.get("item", {})
                if item.get("item_type") == "armor" or item.get("slot") in ["chest", "head", "ring", "accessory"]:
                    return False

        elif challenge_type == ChallengeType.NO_HEALING:
            if action_type == "use_item":
                item = action.get("item", {})
                if item.get("consumable_effect") == "heal" or item.get("subtype") == "health_potion":
                    return False

        elif challenge_type == ChallengeType.ONE_WEAPON:
            if action_type == "equip":
                item = action.get("item", {})
                if item.get("slot") == "weapon":
                    initial_weapon = game_state.get("initial_weapon")
                    if initial_weapon and item.get("id") != initial_weapon.get("id"):
                        return False

        elif challenge_type == ChallengeType.TIME_LIMIT:
            if action_type == "end_turn":
                current_turns = game_state.get("current_floor_turns", 0)
                limit = challenge.value or 100
                if current_turns >= limit:
                    return False

        return True

    async def get_leaderboard(self, season_id, limit=100):
        rows = await db_get_leaderboard(season_id, limit=limit)
        return [LeaderboardEntry.from_dict(r) for r in rows]

    async def submit_score(self, player, run_stats, season):
        challenges = season.challenges if isinstance(season.challenges, list) else []
        if challenges and not isinstance(challenges[0], Challenge):
            challenges = [Challenge.from_dict(c) if isinstance(c, dict) else c for c in challenges]

        score = self.calculate_score(
            floor_reached=run_stats.get("floor_reached", 0),
            monsters_killed=run_stats.get("monsters_killed", 0),
            items_found=run_stats.get("items_found", 0),
            duration_seconds=run_stats.get("duration_seconds", 0),
            deaths=run_stats.get("deaths", 0),
            challenges=challenges,
        )

        player_id = player.get("id") if isinstance(player, dict) else player.id
        await update_leaderboard(
            player_id=player_id,
            season_id=season.id,
            floor_reached=run_stats.get("floor_reached", 0),
            score=score,
        )

        return score

    def check_season_expiry(self, season):
        if season.end_date is None:
            return True
        try:
            end_dt = datetime.fromisoformat(season.end_date)
            now = datetime.now(timezone.utc)
            if end_dt.tzinfo is None:
                end_dt = end_dt.replace(tzinfo=timezone.utc)
            if now > end_dt:
                season.is_active = False
                return False
            return True
        except (ValueError, TypeError):
            return True

    async def get_active_daily(self):
        active = await get_active_season()
        if active:
            season = Season.from_dict(active)
            if "每日挑战" in season.name:
                if self.check_season_expiry(season):
                    return season
        return await self.create_daily_season()

    async def get_active_weekly(self):
        active = await get_active_season()
        if active:
            season = Season.from_dict(active)
            if "每周挑战" in season.name:
                if self.check_season_expiry(season):
                    return season
        return await self.create_weekly_season()
