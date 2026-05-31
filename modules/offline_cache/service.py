from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta
import threading
import time
import uuid
import json
import os

from domain.models.event import EventType

from infrastructure.persistence.repositories.offline_cache_repository import OfflineCacheRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger
from config.settings import settings

logger = get_logger(__name__)


class OfflineCacheService:
    def __init__(
        self,
        cache_repo: OfflineCacheRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.cache_repo = cache_repo
        self.event_bus = event_bus or get_event_bus()

        self._sync_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_running = False
        self._is_online = True

        self._sync_interval_seconds = 30
        self._max_sync_retries = 5
        self._max_cache_size_mb = settings.offline_cache_max_size_mb

    def store_data(
        self,
        data_type: str,
        data: Dict[str, Any],
        device_id: Optional[str] = None,
        priority: int = 0,
        ttl_seconds: int = 86400,
    ) -> str:
        cache_key = f"{data_type}:{uuid.uuid4()}"

        if not self._check_cache_size():
            logger.warning("Cache size limit reached, evicting old data")
            self._evict_old_data()

        self.cache_repo.save(
            cache_key=cache_key,
            data_type=data_type,
            data=data,
            device_id=device_id,
            priority=priority,
            ttl_seconds=ttl_seconds,
        )

        event = self.event_bus.create_event(
            event_type=EventType.OFFLINE_DATA_STORED,
            device_id=device_id,
            data={"cache_key": cache_key, "data_type": data_type},
        )
        self.event_bus.publish(event)

        logger.debug(f"Stored offline data: {cache_key}")
        return cache_key

    def store_telemetry(self, device_id: str, telemetry_data: Dict[str, Any], priority: int = 0) -> str:
        return self.store_data(
            data_type="telemetry",
            data=telemetry_data,
            device_id=device_id,
            priority=priority,
        )

    def store_event(self, event_data: Dict[str, Any], device_id: Optional[str] = None, priority: int = 1) -> str:
        return self.store_data(
            data_type="event",
            data=event_data,
            device_id=device_id,
            priority=priority,
        )

    def get_pending_data(self, limit: int = 100) -> List[Dict[str, Any]]:
        pending_items = self.cache_repo.get_pending(limit=limit)
        return [
            {
                "id": item.id,
                "cache_key": item.cache_key,
                "data_type": item.data_type,
                "device_id": item.device_id,
                "data": item.data,
                "priority": item.priority,
                "stored_at": item.stored_at,
            }
            for item in pending_items
        ]

    def sync_pending_data(self, batch_size: int = 100) -> Dict[str, Any]:
        if not self._is_online:
            logger.info("Device is offline, skipping sync")
            return {"synced": 0, "failed": 0, "total": 0}

        pending_items = self.cache_repo.get_pending(limit=batch_size)
        synced_count = 0
        failed_count = 0

        for item in pending_items:
            try:
                success = self._sync_single_item(item)
                if success:
                    self.cache_repo.update_status(item.id, "synced")
                    synced_count += 1

                    event = self.event_bus.create_event(
                        event_type=EventType.OFFLINE_DATA_SYNCED,
                        device_id=item.device_id,
                        data={"cache_key": item.cache_key},
                    )
                    self.event_bus.publish(event)
                else:
                    failed_count += 1
                    if item.sync_attempts >= self._max_sync_retries:
                        self.cache_repo.update_status(item.id, "failed", "Max retries exceeded")
                    else:
                        self.cache_repo.update_status(item.id, "pending")

            except Exception as e:
                logger.error(f"Error syncing item {item.cache_key}: {str(e)}")
                failed_count += 1
                error_msg = str(e)
                if item.sync_attempts >= self._max_sync_retries:
                    self.cache_repo.update_status(item.id, "failed", error_msg)

                    event = self.event_bus.create_event(
                        event_type=EventType.OFFLINE_DATA_SYNC_FAILED,
                        device_id=item.device_id,
                        data={"cache_key": item.cache_key, "error": error_msg},
                    )
                    self.event_bus.publish(event)

        logger.info(f"Sync completed: {synced_count} synced, {failed_count} failed")
        return {
            "synced": synced_count,
            "failed": failed_count,
            "total": len(pending_items),
        }

    def _sync_single_item(self, item) -> bool:
        try:
            if item.data_type == "telemetry":
                return self._sync_telemetry(item)
            elif item.data_type == "event":
                return self._sync_event(item)
            else:
                return self._sync_generic(item)
        except Exception as e:
            logger.error(f"Sync failed for {item.cache_key}: {str(e)}")
            return False

    def _sync_telemetry(self, item) -> bool:
        try:
            import requests
            url = f"{settings.cloud_endpoint}/api/v1/telemetry"
            response = requests.post(
                url,
                json={
                    "device_id": item.device_id,
                    "data": item.data,
                    "timestamp": item.stored_at.isoformat() if item.stored_at else datetime.utcnow().isoformat(),
                },
                timeout=10,
            )
            return response.status_code in [200, 201, 204]
        except Exception:
            return False

    def _sync_event(self, item) -> bool:
        try:
            import requests
            url = f"{settings.cloud_endpoint}/api/v1/events"
            response = requests.post(url, json=item.data, timeout=10)
            return response.status_code in [200, 201, 204]
        except Exception:
            return False

    def _sync_generic(self, item) -> bool:
        try:
            import requests
            url = f"{settings.cloud_endpoint}/api/v1/batch"
            response = requests.post(
                url,
                json={
                    "type": item.data_type,
                    "device_id": item.device_id,
                    "data": item.data,
                },
                timeout=10,
            )
            return response.status_code in [200, 201, 204]
        except Exception:
            return False

    def check_connectivity(self) -> bool:
        try:
            import requests
            response = requests.get(f"{settings.cloud_endpoint}/health", timeout=5)
            self._is_online = response.status_code == 200
        except Exception:
            self._is_online = False

        logger.info(f"Connectivity check: {'online' if self._is_online else 'offline'}")
        return self._is_online

    def _check_cache_size(self) -> bool:
        current_size_mb = self.cache_repo.get_total_size() / (1024 * 1024)
        return current_size_mb < self._max_cache_size_mb

    def _evict_old_data(self) -> None:
        from infrastructure.persistence.database import SessionLocal
        from infrastructure.persistence.models.offline_cache_model import OfflineCacheModel

        db = SessionLocal()
        try:
            items_to_delete = db.query(OfflineCacheModel).filter(
                OfflineCacheModel.status == "synced"
            ).order_by(OfflineCacheModel.stored_at.asc()).limit(100).all()

            for item in items_to_delete:
                db.delete(item)
            db.commit()
            logger.info(f"Evicted {len(items_to_delete)} old cache items")
        finally:
            db.close()

    def cleanup(self) -> Dict[str, int]:
        deleted_synced = self.cache_repo.delete_synced(older_than_days=7)
        deleted_expired = self.cache_repo.delete_expired()
        return {
            "deleted_synced": deleted_synced,
            "deleted_expired": deleted_expired,
        }

    def get_stats(self) -> Dict[str, Any]:
        from infrastructure.persistence.database import SessionLocal
        from infrastructure.persistence.models.offline_cache_model import OfflineCacheModel
        from sqlalchemy import func

        db = SessionLocal()
        try:
            total_count = db.query(func.count(OfflineCacheModel.id)).scalar()
            pending_count = db.query(func.count(OfflineCacheModel.id)).filter(
                OfflineCacheModel.status == "pending"
            ).scalar()
            synced_count = db.query(func.count(OfflineCacheModel.id)).filter(
                OfflineCacheModel.status == "synced"
            ).scalar()
            failed_count = db.query(func.count(OfflineCacheModel.id)).filter(
                OfflineCacheModel.status == "failed"
            ).scalar()
            total_size = self.cache_repo.get_total_size()

            return {
                "total_count": total_count,
                "pending_count": pending_count,
                "synced_count": synced_count,
                "failed_count": failed_count,
                "total_size_bytes": total_size,
                "total_size_mb": total_size / (1024 * 1024),
                "is_online": self._is_online,
            }
        finally:
            db.close()

    def set_online_status(self, is_online: bool) -> None:
        self._is_online = is_online
        logger.info(f"Online status set to: {is_online}")

    def start(self) -> None:
        if self._is_running:
            return

        self._is_running = True
        self._stop_event.clear()
        self._sync_thread = threading.Thread(target=self._sync_loop, daemon=True)
        self._sync_thread.start()
        logger.info("Offline cache service started")

    def stop(self) -> None:
        self._is_running = False
        self._stop_event.set()
        if self._sync_thread:
            self._sync_thread.join(timeout=5)
        logger.info("Offline cache service stopped")

    def _sync_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.check_connectivity()
                if self._is_online:
                    self.sync_pending_data(batch_size=settings.offline_sync_batch_size)
                self.cleanup()
            except Exception as e:
                logger.error(f"Error in sync loop: {str(e)}")

            self._stop_event.wait(self._sync_interval_seconds)

    def force_sync(self) -> Dict[str, Any]:
        self.check_connectivity()
        if not self._is_online:
            return {"error": "Device is offline", "synced": 0}
        return self.sync_pending_data(batch_size=1000)
