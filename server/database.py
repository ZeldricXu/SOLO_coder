import sqlite3
import json
import asyncio
from pathlib import Path
from datetime import datetime, timezone

_project_root = Path(__file__).resolve().parent.parent
DATA_DIR = _project_root / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA_DIR / "dungeon.db"

_lock = asyncio.Lock()


def _get_conn():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


async def init_db():
    async with _lock:
        conn = _get_conn()
        try:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS players (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    last_login TEXT
                );

                CREATE TABLE IF NOT EXISTS player_stats (
                    player_id INTEGER PRIMARY KEY,
                    class_name TEXT NOT NULL DEFAULT '',
                    max_floor_reached INTEGER NOT NULL DEFAULT 0,
                    total_monsters_killed INTEGER NOT NULL DEFAULT 0,
                    total_deaths INTEGER NOT NULL DEFAULT 0,
                    total_items_found INTEGER NOT NULL DEFAULT 0,
                    resurrection_count INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (player_id) REFERENCES players(id)
                );

                CREATE TABLE IF NOT EXISTS inventory_save (
                    player_id INTEGER PRIMARY KEY,
                    item_data TEXT NOT NULL DEFAULT '{}',
                    FOREIGN KEY (player_id) REFERENCES players(id)
                );

                CREATE TABLE IF NOT EXISTS seasons (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    start_date TEXT NOT NULL,
                    end_date TEXT,
                    challenge_rules TEXT NOT NULL DEFAULT '{}',
                    is_active INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS leaderboard (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id INTEGER NOT NULL,
                    season_id INTEGER NOT NULL,
                    floor_reached INTEGER NOT NULL DEFAULT 0,
                    score INTEGER NOT NULL DEFAULT 0,
                    timestamp TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id),
                    FOREIGN KEY (season_id) REFERENCES seasons(id)
                );

                CREATE TABLE IF NOT EXISTS run_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id INTEGER NOT NULL,
                    class_name TEXT NOT NULL DEFAULT '',
                    floor_reached INTEGER NOT NULL DEFAULT 0,
                    monsters_killed INTEGER NOT NULL DEFAULT 0,
                    items_found INTEGER NOT NULL DEFAULT 0,
                    death_cause TEXT NOT NULL DEFAULT '',
                    duration_seconds REAL NOT NULL DEFAULT 0.0,
                    timestamp TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id)
                );
            """)
            conn.commit()
        finally:
            conn.close()


async def create_player(username, password_hash):
    async with _lock:
        conn = _get_conn()
        try:
            now = datetime.now(timezone.utc).isoformat()
            cursor = conn.execute(
                "INSERT INTO players (username, password_hash, created_at, last_login) VALUES (?, ?, ?, ?)",
                (username, password_hash, now, now),
            )
            player_id = cursor.lastrowid
            conn.execute(
                "INSERT INTO player_stats (player_id) VALUES (?)",
                (player_id,),
            )
            conn.execute(
                "INSERT INTO inventory_save (player_id, item_data) VALUES (?, ?)",
                (player_id, "{}"),
            )
            conn.commit()
            return player_id
        finally:
            conn.close()


async def get_player(username):
    async with _lock:
        conn = _get_conn()
        try:
            row = conn.execute(
                "SELECT * FROM players WHERE username = ?",
                (username,),
            ).fetchone()
            if row:
                return dict(row)
            return None
        finally:
            conn.close()


async def update_player_stats(player_id, class_name=None, max_floor_reached=None,
                               total_monsters_killed=None, total_deaths=None,
                               total_items_found=None, resurrection_count=None):
    async with _lock:
        conn = _get_conn()
        try:
            existing = conn.execute(
                "SELECT * FROM player_stats WHERE player_id = ?",
                (player_id,),
            ).fetchone()
            if not existing:
                return False

            sets = []
            values = []
            if class_name is not None:
                sets.append("class_name = ?")
                values.append(class_name)
            if max_floor_reached is not None:
                sets.append("max_floor_reached = ?")
                values.append(max_floor_reached)
            if total_monsters_killed is not None:
                sets.append("total_monsters_killed = ?")
                values.append(total_monsters_killed)
            if total_deaths is not None:
                sets.append("total_deaths = ?")
                values.append(total_deaths)
            if total_items_found is not None:
                sets.append("total_items_found = ?")
                values.append(total_items_found)
            if resurrection_count is not None:
                sets.append("resurrection_count = ?")
                values.append(resurrection_count)

            if not sets:
                return True

            values.append(player_id)
            conn.execute(
                f"UPDATE player_stats SET {', '.join(sets)} WHERE player_id = ?",
                values,
            )
            conn.execute(
                "UPDATE players SET last_login = ? WHERE id = ?",
                (datetime.now(timezone.utc).isoformat(), player_id),
            )
            conn.commit()
            return True
        finally:
            conn.close()


async def save_inventory(player_id, item_data):
    async with _lock:
        conn = _get_conn()
        try:
            data = json.dumps(item_data) if not isinstance(item_data, str) else item_data
            conn.execute(
                "INSERT OR REPLACE INTO inventory_save (player_id, item_data) VALUES (?, ?)",
                (player_id, data),
            )
            conn.commit()
        finally:
            conn.close()


async def load_inventory(player_id):
    async with _lock:
        conn = _get_conn()
        try:
            row = conn.execute(
                "SELECT item_data FROM inventory_save WHERE player_id = ?",
                (player_id,),
            ).fetchone()
            if row:
                return json.loads(row["item_data"])
            return {}
        finally:
            conn.close()


async def save_run_history(player_id, class_name, floor_reached, monsters_killed,
                           items_found, death_cause, duration_seconds):
    async with _lock:
        conn = _get_conn()
        try:
            now = datetime.now(timezone.utc).isoformat()
            conn.execute(
                "INSERT INTO run_history (player_id, class_name, floor_reached, monsters_killed, items_found, death_cause, duration_seconds, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (player_id, class_name, floor_reached, monsters_killed, items_found, death_cause, duration_seconds, now),
            )
            conn.commit()
        finally:
            conn.close()


async def get_leaderboard(season_id, limit=10):
    async with _lock:
        conn = _get_conn()
        try:
            rows = conn.execute(
                "SELECT l.*, p.username FROM leaderboard l JOIN players p ON l.player_id = p.id WHERE l.season_id = ? ORDER BY l.score DESC LIMIT ?",
                (season_id, limit),
            ).fetchall()
            return [dict(r) for r in rows]
        finally:
            conn.close()


async def update_leaderboard(player_id, season_id, floor_reached, score):
    async with _lock:
        conn = _get_conn()
        try:
            now = datetime.now(timezone.utc).isoformat()
            existing = conn.execute(
                "SELECT id, score FROM leaderboard WHERE player_id = ? AND season_id = ?",
                (player_id, season_id),
            ).fetchone()

            if existing:
                if score > existing["score"]:
                    conn.execute(
                        "UPDATE leaderboard SET floor_reached = ?, score = ?, timestamp = ? WHERE id = ?",
                        (floor_reached, score, now, existing["id"]),
                    )
            else:
                conn.execute(
                    "INSERT INTO leaderboard (player_id, season_id, floor_reached, score, timestamp) VALUES (?, ?, ?, ?, ?)",
                    (player_id, season_id, floor_reached, score, now),
                )
            conn.commit()
        finally:
            conn.close()


async def create_season(name, start_date, end_date=None, challenge_rules=None):
    async with _lock:
        conn = _get_conn()
        try:
            rules = json.dumps(challenge_rules) if challenge_rules is not None and not isinstance(challenge_rules, str) else (challenge_rules or "{}")
            conn.execute(
                "UPDATE seasons SET is_active = 0 WHERE is_active = 1",
            )
            cursor = conn.execute(
                "INSERT INTO seasons (name, start_date, end_date, challenge_rules, is_active) VALUES (?, ?, ?, ?, 1)",
                (name, start_date, end_date, rules),
            )
            conn.commit()
            return cursor.lastrowid
        finally:
            conn.close()


async def get_active_season():
    async with _lock:
        conn = _get_conn()
        try:
            row = conn.execute(
                "SELECT * FROM seasons WHERE is_active = 1",
            ).fetchone()
            if row:
                result = dict(row)
                result["challenge_rules"] = json.loads(result["challenge_rules"])
                return result
            return None
        finally:
            conn.close()


async def get_player_rank(player_id, season_id):
    async with _lock:
        conn = _get_conn()
        try:
            rows = conn.execute(
                "SELECT player_id, score FROM leaderboard WHERE season_id = ? ORDER BY score DESC",
                (season_id,),
            ).fetchall()
            for i, row in enumerate(rows):
                if row["player_id"] == player_id:
                    return i + 1
            return None
        finally:
            conn.close()
