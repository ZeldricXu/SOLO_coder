import logging
from celery import shared_task
from datetime import datetime, timedelta

from app.database import SessionLocal
from app.prediction import prediction_service
from app.tiles import tile_generator
from app.admin import admin_service

logger = logging.getLogger(__name__)


@shared_task(bind=True, name="prediction.train_model_task")
def train_model_task(self, sensor_id: str, model_type: str = "lstm",
                     epochs: int = 100, **kwargs):
    db = SessionLocal()
    try:
        task_id = self.request.id
        admin_service.update_task_progress(db, task_id, 5, "training")

        end_date = datetime.utcnow()
        start_date = end_date - timedelta(days=30)

        admin_service.update_task_progress(db, task_id, 20, "loading_data")

        result = prediction_service.train_model(
            db, sensor_id, model_type, start_date, end_date, epochs
        )

        admin_service.update_task_progress(db, task_id, 100, "completed")
        admin_service.complete_task(db, task_id, result)

        return result

    except Exception as e:
        logger.error(f"Training task failed: {e}")
        admin_service.fail_task(db, self.request.id, str(e))
        raise
    finally:
        db.close()


@shared_task(name="prediction.batch_prediction_task")
def batch_prediction_task(**kwargs):
    db = SessionLocal()
    try:
        from app.models import TrafficSensor
        sensors = db.query(TrafficSensor).filter(
            TrafficSensor.status == "active"
        ).all()

        sensor_ids = [s.sensor_id for s in sensors]
        horizons = [15, 30, 60]

        result = prediction_service.batch_predict(db, sensor_ids, horizons)

        logger.info(f"Batch prediction completed for {len(sensor_ids)} sensors")
        return result

    except Exception as e:
        logger.error(f"Batch prediction task failed: {e}")
        raise
    finally:
        db.close()


@shared_task(name="tiles.regenerate_tiles_task")
def regenerate_tiles_task(layer_type: str = "all", min_zoom: int = 10,
                          max_zoom: int = 16, **kwargs):
    db = SessionLocal()
    try:
        if layer_type == "all":
            layer_types = ["buildings", "roads", "pois"]
        else:
            layer_types = [layer_type]

        generated_count = 0

        for lt in layer_types:
            for z in range(min_zoom, max_zoom + 1):
                tile_count = 2 ** z
                for x in range(tile_count):
                    for y in range(tile_count):
                        try:
                            if lt == "buildings":
                                tile_generator.generate_building_tile(db, z, x, y)
                            elif lt == "roads":
                                tile_generator.generate_road_tile(db, z, x, y)
                            elif lt == "pois":
                                tile_generator.generate_poi_tile(db, z, x, y)
                            generated_count += 1
                        except Exception as e:
                            logger.warning(f"Failed to generate tile {lt}/{z}/{x}/{y}: {e}")

        logger.info(f"Tile regeneration completed: {generated_count} tiles generated")
        return {"generated_count": generated_count}

    except Exception as e:
        logger.error(f"Tile regeneration task failed: {e}")
        raise
    finally:
        db.close()


@shared_task(name="heatmap.refresh_heatmap_cache")
def refresh_heatmap_cache(**kwargs):
    logger.info("Heatmap cache refresh started")
    return {"status": "refreshed", "timestamp": datetime.utcnow().isoformat()}


@shared_task(name="etl.cleanup_old_data_task")
def cleanup_old_data_task(days_to_keep: int = 90, **kwargs):
    db = SessionLocal()
    try:
        from app.models import TrafficFlowRecord, TrajectoryPoint
        from sqlalchemy import delete

        cutoff_date = datetime.utcnow() - timedelta(days=days_to_keep)

        flow_count = db.query(TrafficFlowRecord).filter(
            TrafficFlowRecord.timestamp < cutoff_date
        ).delete(synchronize_session=False)

        traj_count = db.query(TrajectoryPoint).filter(
            TrajectoryPoint.timestamp < cutoff_date
        ).delete(synchronize_session=False)

        db.commit()

        logger.info(f"Cleanup completed: {flow_count} flow records, {traj_count} trajectory points deleted")
        return {
            "deleted_flow_records": flow_count,
            "deleted_trajectory_points": traj_count,
            "cutoff_date": cutoff_date.isoformat(),
        }

    except Exception as e:
        db.rollback()
        logger.error(f"Cleanup task failed: {e}")
        raise
    finally:
        db.close()


@shared_task(name="etl.import_hdfs_data_task")
def import_hdfs_data_task(date: str = None, **kwargs):
    from app.etl import hdfs_loader, data_cleaner, time_aggregator

    logger.info(f"Starting HDFS data import for date: {date or 'today'}")

    try:
        raw_data = hdfs_loader.load_traffic_data(date)
        cleaned_data = data_cleaner.clean_traffic_data(raw_data)

        db = SessionLocal()
        try:
            from app.models import TrafficFlowRecord
            from geoalchemy2.shape import from_shape
            from shapely.geometry import Point

            records = []
            for item in cleaned_data:
                if 'lon' in item and 'lat' in item:
                    point = Point(item['lon'], item['lat'])
                    geom = from_shape(point, srid=4326)
                else:
                    geom = None

                record = TrafficFlowRecord(
                    sensor_id=item.get('sensor_id', 'unknown'),
                    timestamp=item.get('timestamp', datetime.utcnow()),
                    vehicle_count=item.get('vehicle_count', 0),
                    pedestrian_count=item.get('pedestrian_count', 0),
                    avg_speed=item.get('avg_speed'),
                    congestion_index=item.get('congestion_index'),
                    vehicle_type=item.get('vehicle_type', 'all'),
                    direction=item.get('direction', 'both'),
                    time_window=item.get('time_window', '5m'),
                    geom=geom,
                )
                records.append(record)

            db.bulk_save_objects(records)
            db.commit()

            logger.info(f"Imported {len(records)} records from HDFS")
            return {"imported_count": len(records)}

        except Exception as e:
            db.rollback()
            raise e
        finally:
            db.close()

    except Exception as e:
        logger.error(f"HDFS import task failed: {e}")
        raise
