from typing import Callable, Dict, List
from datetime import datetime
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import text
from app.logging_module import get_logger


logger = get_logger(__name__)


class MigrationManager:
    def __init__(self, session: AsyncSession):
        self.session = session
        self._migrations: Dict[int, Dict] = {}
        self._register_migrations()
    
    def _register_migrations(self):
        self._migrations[1] = {
            "name": "initial_schema",
            "up": self._migration_1_up,
            "down": self._migration_1_down
        }
        self._migrations[2] = {
            "name": "add_notification_table",
            "up": self._migration_2_up,
            "down": self._migration_2_down
        }
        self._migrations[3] = {
            "name": "add_schema_versioning",
            "up": self._migration_3_up,
            "down": self._migration_3_down
        }
        self._migrations[4] = {
            "name": "add_notification_persistence",
            "up": self._migration_4_up,
            "down": self._migration_4_down
        }
        self._migrations[5] = {
            "name": "add_dynamic_config_and_cache",
            "up": self._migration_5_up,
            "down": self._migration_5_down
        }
    
    async def _get_current_version(self) -> int:
        try:
            result = await self.session.execute(
                text("SELECT version FROM schema_versions ORDER BY version DESC LIMIT 1")
            )
            row = result.fetchone()
            return row[0] if row else 0
        except Exception:
            return 0
    
    async def _set_version(self, version: int, name: str):
        await self.session.execute(
            text("INSERT INTO schema_versions (version, name, applied_at) VALUES (:v, :n, :t)"),
            {"v": version, "n": name, "t": datetime.utcnow()}
        )
    
    async def _remove_version(self, version: int):
        await self.session.execute(
            text("DELETE FROM schema_versions WHERE version = :v"),
            {"v": version}
        )
    
    async def migrate_up(self, target_version: int = None) -> List[int]:
        current = await self._get_current_version()
        if target_version is None:
            target_version = max(self._migrations.keys())
        
        applied = []
        for version in sorted(self._migrations.keys()):
            if version > current and (target_version is None or version <= target_version):
                migration = self._migrations[version]
                logger.info(f"Applying migration {version}: {migration['name']}")
                await migration["up"]()
                await self._set_version(version, migration["name"])
                applied.append(version)
        
        return applied
    
    async def migrate_down(self, target_version: int = 0) -> List[int]:
        current = await self._get_current_version()
        reverted = []
        
        for version in sorted(self._migrations.keys(), reverse=True):
            if version > target_version and version <= current:
                migration = self._migrations[version]
                logger.info(f"Reverting migration {version}: {migration['name']}")
                await migration["down"]()
                await self._remove_version(version)
                reverted.append(version)
        
        return reverted
    
    async def _migration_1_up(self):
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS entities (
                id VARCHAR(64) PRIMARY KEY,
                type VARCHAR(50) NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'pending',
                attributes JSON,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """))
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                config_id VARCHAR(64) UNIQUE NOT NULL,
                namespace VARCHAR(100) NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                parameters JSON NOT NULL,
                enabled BOOLEAN DEFAULT 1,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """))
    
    async def _migration_1_down(self):
        await self.session.execute(text("DROP TABLE IF EXISTS entities"))
        await self.session.execute(text("DROP TABLE IF EXISTS configs"))
    
    async def _migration_2_up(self):
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS notifications (
                id VARCHAR(64) PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                content TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 5,
                channel VARCHAR(50) NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'pending',
                recipient VARCHAR(200),
                sent_at TIMESTAMP,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                metadata JSON
            )
        """))
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS run_instances (
                run_id VARCHAR(64) PRIMARY KEY,
                entity_id VARCHAR(64) NOT NULL,
                phase VARCHAR(50) NOT NULL DEFAULT 'initialized',
                progress FLOAT NOT NULL DEFAULT 0.0,
                started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP,
                error_detail TEXT
            )
        """))
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS metric_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                snapshot_id VARCHAR(64) UNIQUE NOT NULL,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                metrics JSON NOT NULL,
                dimensions JSON NOT NULL
            )
        """))
    
    async def _migration_2_down(self):
        await self.session.execute(text("DROP TABLE IF EXISTS notifications"))
        await self.session.execute(text("DROP TABLE IF EXISTS run_instances"))
        await self.session.execute(text("DROP TABLE IF EXISTS metric_snapshots"))
    
    async def _migration_3_up(self):
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS schema_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version INTEGER UNIQUE NOT NULL,
                name VARCHAR(200) NOT NULL,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                rollback_sql TEXT
            )
        """))
    
    async def _migration_3_down(self):
        pass
    
    async def _migration_4_up(self):
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS notification_queue_items (
                id VARCHAR(64) PRIMARY KEY,
                priority INTEGER NOT NULL DEFAULT 5,
                title VARCHAR(200) NOT NULL,
                content TEXT NOT NULL,
                channel VARCHAR(50) NOT NULL,
                recipient VARCHAR(200),
                deduplication_key VARCHAR(200),
                ttl_seconds INTEGER,
                metadata JSON,
                status VARCHAR(50) NOT NULL DEFAULT 'pending',
                retry_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at TIMESTAMP,
                error_message TEXT,
                queued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                sent_at TIMESTAMP
            )
        """))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_queue_status ON notification_queue_items(status)"))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_queue_dedup ON notification_queue_items(deduplication_key)"))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_queue_queued ON notification_queue_items(queued_at)"))
        
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS notification_suppression_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rule_id VARCHAR(64) UNIQUE NOT NULL,
                name VARCHAR(200) NOT NULL,
                enabled BOOLEAN DEFAULT 1,
                priority_threshold INTEGER,
                channel VARCHAR(50),
                time_window_seconds INTEGER NOT NULL DEFAULT 60,
                max_count INTEGER NOT NULL DEFAULT 10,
                pattern TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_suppression_rule_id ON notification_suppression_rules(rule_id)"))
    
    async def _migration_4_down(self):
        await self.session.execute(text("DROP INDEX IF EXISTS idx_queue_status"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_queue_dedup"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_queue_queued"))
        await self.session.execute(text("DROP TABLE IF EXISTS notification_queue_items"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_suppression_rule_id"))
        await self.session.execute(text("DROP TABLE IF EXISTS notification_suppression_rules"))
    
    async def _migration_5_up(self):
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS dynamic_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                config_key VARCHAR(200) UNIQUE NOT NULL,
                config_value JSON NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                description VARCHAR(500),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_dynamic_config_key ON dynamic_configs(config_key)"))
        
        await self.session.execute(text("""
            CREATE TABLE IF NOT EXISTS cache_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cache_key VARCHAR(500) UNIQUE NOT NULL,
                cache_value JSON NOT NULL,
                route_path VARCHAR(200),
                expires_at TIMESTAMP,
                hit_count INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_accessed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_cache_key ON cache_entries(cache_key)"))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_cache_route ON cache_entries(route_path)"))
        await self.session.execute(text("CREATE INDEX IF NOT EXISTS idx_cache_expires ON cache_entries(expires_at)"))
    
    async def _migration_5_down(self):
        await self.session.execute(text("DROP INDEX IF EXISTS idx_dynamic_config_key"))
        await self.session.execute(text("DROP TABLE IF EXISTS dynamic_configs"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_cache_key"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_cache_route"))
        await self.session.execute(text("DROP INDEX IF EXISTS idx_cache_expires"))
        await self.session.execute(text("DROP TABLE IF EXISTS cache_entries"))
