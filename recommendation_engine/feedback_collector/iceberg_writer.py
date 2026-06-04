from typing import Optional, List, Dict, Any
import os
import json
from datetime import datetime
from loguru import logger

try:
    from pyiceberg.catalog import load_catalog
    from pyiceberg.schema import Schema
    from pyiceberg.types import (
        StringType,
        LongType,
        DoubleType,
        TimestampType,
        MapType,
    )
    ICEBERG_AVAILABLE = True
except ImportError:
    ICEBERG_AVAILABLE = False
    logger.warning("PyIceberg not available, using fallback file writer")

from config import settings


class IcebergWriter:
    def __init__(self):
        self._catalog_name = settings.iceberg_catalog_name
        self._database = settings.iceberg_database
        self._table = settings.iceberg_table
        self._warehouse = settings.iceberg_warehouse
        self._catalog = None
        self._iceberg_table = None
        self._fallback_path = os.path.join(self._warehouse, "fallback")
        self._available = ICEBERG_AVAILABLE

        os.makedirs(self._warehouse, exist_ok=True)
        os.makedirs(self._fallback_path, exist_ok=True)

        if self._available:
            self._initialize_catalog()

    def _initialize_catalog(self) -> None:
        try:
            self._catalog = load_catalog(
                self._catalog_name,
                **{
                    "type": "rest",
                    "warehouse": self._warehouse,
                }
            )
            self._ensure_table_exists()
            logger.info("Iceberg catalog initialized")
        except Exception as e:
            logger.warning(f"Failed to initialize Iceberg catalog: {e}")
            self._available = False

    def _ensure_table_exists(self) -> None:
        if not self._available:
            return

        try:
            schema = Schema(
                StringType("event_id"),
                StringType("user_id"),
                StringType("content_id"),
                StringType("event_type"),
                TimestampType("timestamp"),
                StringType("request_id"),
                StringType("scene"),
                LongType("position"),
                DoubleType("value"),
                MapType(StringType(), StringType(), "extra"),
            )

            namespace = (self._database,)
            table_identifier = f"{self._database}.{self._table}"

            try:
                self._iceberg_table = self._catalog.load_table(table_identifier)
            except Exception:
                self._iceberg_table = self._catalog.create_table(
                    table_identifier, schema=schema
                )
                logger.info(f"Created Iceberg table: {table_identifier}")

        except Exception as e:
            logger.warning(f"Failed to ensure Iceberg table exists: {e}")

    def write_events(self, events: List[Dict[str, Any]]) -> bool:
        if not events:
            return True

        if self._available and self._iceberg_table is not None:
            return self._write_to_iceberg(events)
        else:
            return self._write_to_fallback(events)

    def _write_to_iceberg(self, events: List[Dict[str, Any]]) -> bool:
        try:
            rows = []
            for event in events:
                row = {
                    "event_id": event.get("event_id"),
                    "user_id": event.get("user_id"),
                    "content_id": event.get("content_id"),
                    "event_type": event.get("event_type"),
                    "timestamp": event.get("timestamp"),
                    "request_id": event.get("request_id"),
                    "scene": event.get("scene"),
                    "position": event.get("position"),
                    "value": event.get("value"),
                    "extra": event.get("extra", {}),
                }
                rows.append(row)

            self._iceberg_table.append(rows)
            logger.info(f"Written {len(events)} events to Iceberg")
            return True
        except Exception as e:
            logger.error(f"Failed to write to Iceberg: {e}")
            return self._write_to_fallback(events)

    def _write_to_fallback(self, events: List[Dict[str, Any]]) -> bool:
        try:
            date_str = datetime.utcnow().strftime("%Y-%m-%d")
            hour_str = datetime.utcnow().strftime("%H")
            file_path = os.path.join(
                self._fallback_path,
                f"events_{date_str}_{hour_str}.jsonl",
            )

            with open(file_path, "a", encoding="utf-8") as f:
                for event in events:
                    event_copy = event.copy()
                    if "timestamp" in event_copy and isinstance(
                        event_copy["timestamp"], datetime
                    ):
                        event_copy["timestamp"] = event_copy["timestamp"].isoformat()
                    f.write(json.dumps(event_copy, ensure_ascii=False) + "\n")

            logger.debug(f"Written {len(events)} events to fallback file: {file_path}")
            return True
        except Exception as e:
            logger.error(f"Failed to write to fallback file: {e}")
            return False

    def load_fallback_to_iceberg(self, date_str: Optional[str] = None) -> int:
        if not self._available:
            logger.warning("Iceberg not available, cannot load fallback data")
            return 0

        total_loaded = 0
        try:
            if date_str:
                pattern = f"events_{date_str}_*.jsonl"
            else:
                pattern = "events_*.jsonl"

            import glob
            files = glob.glob(os.path.join(self._fallback_path, pattern))

            for file_path in sorted(files):
                events = []
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        for line in f:
                            line = line.strip()
                            if line:
                                events.append(json.loads(line))

                    if events and self._write_to_iceberg(events):
                        total_loaded += len(events)
                        os.remove(file_path)
                        logger.info(
                            f"Loaded {len(events)} events from {file_path} to Iceberg"
                        )
                except Exception as e:
                    logger.error(f"Failed to process file {file_path}: {e}")

            return total_loaded
        except Exception as e:
            logger.error(f"Failed to load fallback data: {e}")
            return total_loaded

    def get_stats(self) -> Dict[str, Any]:
        stats = {
            "available": self._available,
            "catalog_name": self._catalog_name,
            "database": self._database,
            "table": self._table,
            "warehouse": self._warehouse,
        }

        if self._available and self._iceberg_table is not None:
            try:
                stats["current_snapshot_id"] = str(
                    self._iceberg_table.current_snapshot().snapshot_id
                )
            except Exception:
                pass

        try:
            import glob
            fallback_files = glob.glob(
                os.path.join(self._fallback_path, "events_*.jsonl")
            )
            stats["fallback_files"] = len(fallback_files)
        except Exception:
            pass

        return stats

    def close(self) -> None:
        logger.info("IcebergWriter closed")
