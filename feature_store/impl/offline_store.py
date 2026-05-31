from typing import List, Dict, Any
from collections import defaultdict
from bisect import bisect_left, bisect_right

from ..protocols import OfflineFeatureStore
from ..schemas import (
    FeatureOfflineFetchRequest,
    FeatureOfflineFetchResponse,
    FeatureValue,
    FeaturePoint,
)
from common.logger import get_logger

logger = get_logger(__name__)


class InMemoryOfflineFeatureStore(OfflineFeatureStore):
    def __init__(self):
        self.store: Dict[str, Dict[str, List[FeaturePoint]]] = defaultdict(
            lambda: defaultdict(list)
        )
        self.sorted_keys: Dict[str, Dict[str, List[float]]] = defaultdict(
            lambda: defaultdict(list)
        )

    async def fetch_features(self, request: FeatureOfflineFetchRequest) -> FeatureOfflineFetchResponse:
        entity_name = request.entity_name
        entity_key = self._get_entity_key(entity_name)

        entity_data = self.store.get(entity_key, {})
        entity_ids_filter = set(request.entity_ids) if request.entity_ids else None
        feature_names_filter = set(request.feature_names) if request.feature_names else None

        filtered_points: List[FeaturePoint] = []

        for entity_id, points in entity_data.items():
            if entity_ids_filter and entity_id not in entity_ids_filter:
                continue

            timestamps = self.sorted_keys[entity_key][entity_id]
            if not timestamps:
                continue

            left_idx = bisect_left(timestamps, request.start_time.timestamp() if hasattr(request.start_time, 'timestamp') else request.start_time)
            right_idx = bisect_right(timestamps, request.end_time.timestamp() if hasattr(request.end_time, 'timestamp') else request.end_time)

            for point in points[left_idx:right_idx]:
                if feature_names_filter:
                    filtered_features = [
                        f for f in point.features if f.feature_name in feature_names_filter
                    ]
                    if filtered_features:
                        filtered_points.append(
                            FeaturePoint(
                                entity_id=point.entity_id,
                                features=filtered_features,
                                event_timestamp=point.event_timestamp,
                            )
                        )
                else:
                    filtered_points.append(point)

                if len(filtered_points) >= request.limit:
                    break

            if len(filtered_points) >= request.limit:
                break

        filtered_points = filtered_points[: request.limit]

        logger.info(
            f"Fetched {len(filtered_points)} offline points for {entity_name}"
        )

        return FeatureOfflineFetchResponse(
            entity_name=entity_name,
            start_time=request.start_time,
            end_time=request.end_time,
            points=filtered_points,
            total_count=len(filtered_points),
        )

    async def ingest(self, entity_key: str, point: FeaturePoint) -> None:
        entity_id = point.entity_id
        timestamp = point.event_timestamp.timestamp() if hasattr(point.event_timestamp, 'timestamp') else point.event_timestamp

        self.store[entity_key][entity_id].append(point)

        sorted_ts = self.sorted_keys[entity_key][entity_id]
        insert_pos = bisect_left(sorted_ts, timestamp)
        sorted_ts.insert(insert_pos, timestamp)

    def get_entity_points(self, entity_key: str, entity_id: str) -> List[FeaturePoint]:
        return self.store.get(entity_key, {}).get(entity_id, [])

    @staticmethod
    def _get_entity_key(entity_name: str) -> str:
        return f"entity:{entity_name}"
