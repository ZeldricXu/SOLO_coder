from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta

from domain.models.telemetry import TelemetryData, AggregatedData

from modules.data_aggregation.service import DataAggregationService, AggregationError
from modules.offline_cache.service import OfflineCacheService
from modules.protocol_adapter.service import ProtocolAdapterService
from modules.rule_engine.service import RuleEngineService
from modules.device_shadow.service import DeviceShadowService
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class TelemetryService:
    def __init__(
        self,
        aggregation_service: DataAggregationService,
        offline_cache: OfflineCacheService,
        protocol_adapter: ProtocolAdapterService,
        rule_engine: RuleEngineService,
        shadow_service: DeviceShadowService,
    ):
        self._aggregation = aggregation_service
        self._offline_cache = offline_cache
        self._protocol = protocol_adapter
        self._rule_engine = rule_engine
        self._shadow = shadow_service

    def ingest_telemetry(
        self,
        device_id: str,
        data: Dict[str, Any],
        timestamp: Optional[datetime] = None,
        use_offline_cache: bool = True,
    ) -> Dict[str, Any]:
        if timestamp is None:
            timestamp = datetime.utcnow()

        telemetry = TelemetryData(
            device_id=device_id,
            data=data,
            timestamp=timestamp,
        )

        self._shadow.update_reported_state(device_id, data)
        self._aggregation.process_telemetry_data(telemetry)
        self._rule_engine.evaluate_telemetry_data(device_id, data)

        if not self._offline_cache._is_online and use_offline_cache:
            cache_key = self._offline_cache.store_telemetry(device_id, data)
            return {
                "status": "cached",
                "cache_key": cache_key,
                "timestamp": timestamp.isoformat(),
            }

        return {
            "status": "processed",
            "timestamp": timestamp.isoformat(),
        }

    def batch_ingest(
        self,
        device_id: str,
        data_points: List[Dict[str, Any]],
        use_offline_cache: bool = True,
    ) -> Dict[str, Any]:
        processed_count = 0
        cached_count = 0

        for point in data_points:
            ts = point.get("timestamp")
            if isinstance(ts, str):
                ts = datetime.fromisoformat(ts.replace("Z", "+00:00"))
            data = point.get("data", point)
            result = self.ingest_telemetry(
                device_id=device_id,
                data=data,
                timestamp=ts,
                use_offline_cache=use_offline_cache,
            )
            if result["status"] == "cached":
                cached_count += 1
            else:
                processed_count += 1

        return {
            "total": len(data_points),
            "processed": processed_count,
            "cached": cached_count,
        }

    def get_aggregated_data(
        self,
        device_id: str,
        metric: str,
        start_time: datetime,
        end_time: datetime,
        aggregation_type: str = "average",
    ) -> Optional[AggregatedData]:
        return self._aggregation.get_aggregated_data(
            device_id=device_id,
            metric=metric,
            start_time=start_time,
            end_time=end_time,
            aggregation_type=aggregation_type,
        )

    def add_aggregation_rule(
        self,
        device_id: str,
        metric: str,
        aggregation_type: str,
        interval_seconds: int,
    ) -> str:
        return self._aggregation.add_aggregation_rule(
            device_id=device_id,
            metric=metric,
            aggregation_type=aggregation_type,
            interval_seconds=interval_seconds,
        )

    def remove_aggregation_rule(self, rule_id: str) -> bool:
        return self._aggregation.remove_aggregation_rule(rule_id)

    def get_aggregation_rules(self, device_id: Optional[str] = None) -> List[Dict[str, Any]]:
        return self._aggregation.list_rules(device_id=device_id)

    def run_aggregation(self) -> Dict[str, Any]:
        return self._aggregation.run_aggregation_cycle()

    def get_offline_cache_stats(self) -> Dict[str, Any]:
        return self._offline_cache.get_stats()

    def sync_offline_data(self) -> Dict[str, Any]:
        return self._offline_cache.force_sync()

    def read_and_ingest(
        self,
        device_id: str,
        points: List[str],
    ) -> Dict[str, Any]:
        try:
            data = self._protocol.read_device_data(device_id, points)
            if data:
                return self.ingest_telemetry(device_id, data)
            return {"status": "error", "message": "No data read from device"}
        except Exception as exc:
            logger.error(
                "Read and ingest failed",
                extra={"device_id": device_id, "error": str(exc)},
            )
            return {"status": "error", "message": str(exc)}
