from typing import List, Optional
from sqlalchemy.orm import Session
from datetime import datetime

from domain.models.telemetry import TelemetryData, AggregatedData
from infrastructure.persistence.models.telemetry_model import TelemetryDataModel, AggregatedDataModel


class TelemetryRepository:
    def __init__(self, db: Session):
        self.db = db

    def save_telemetry(self, telemetry: TelemetryData) -> TelemetryData:
        db_obj = TelemetryDataModel(
            device_id=telemetry.device_id,
            timestamp=telemetry.timestamp,
            data=telemetry.data,
            quality=telemetry.quality,
            model_metadata=telemetry.metadata,
        )
        self.db.add(db_obj)
        self.db.commit()
        return telemetry

    def save_aggregated_data(self, aggregated: AggregatedData) -> AggregatedData:
        db_obj = AggregatedDataModel(
            device_id=aggregated.device_id,
            metric=aggregated.metric,
            aggregation_type=aggregated.aggregation_type,
            period_start=aggregated.period_start,
            period_end=aggregated.period_end,
            value=aggregated.value,
            count=aggregated.count,
            min_value=aggregated.min_value,
            max_value=aggregated.max_value,
            sum_value=aggregated.sum_value,
            avg_value=aggregated.avg_value,
            std_dev=aggregated.std_dev,
            model_metadata=aggregated.metadata,
        )
        self.db.add(db_obj)
        self.db.commit()
        return aggregated

    def get_telemetry_by_device(self, device_id: str, start_time: Optional[datetime] = None,
                                end_time: Optional[datetime] = None, limit: int = 1000) -> List[TelemetryData]:
        query = self.db.query(TelemetryDataModel).filter(TelemetryDataModel.device_id == device_id)
        if start_time:
            query = query.filter(TelemetryDataModel.timestamp >= start_time)
        if end_time:
            query = query.filter(TelemetryDataModel.timestamp <= end_time)
        db_objs = query.order_by(TelemetryDataModel.timestamp.desc()).limit(limit).all()
        return [TelemetryData(
            device_id=obj.device_id,
            timestamp=obj.timestamp,
            data=obj.data,
            quality=obj.quality,
            metadata=obj.model_metadata,
        ) for obj in db_objs]

    def get_latest_telemetry(self, device_id: str) -> Optional[TelemetryData]:
        db_obj = self.db.query(TelemetryDataModel).filter(TelemetryDataModel.device_id == device_id)\
            .order_by(TelemetryDataModel.timestamp.desc()).first()
        if db_obj:
            return TelemetryData(
                device_id=db_obj.device_id,
                timestamp=db_obj.timestamp,
                data=db_obj.data,
                quality=db_obj.quality,
                metadata=db_obj.model_metadata,
            )
        return None
