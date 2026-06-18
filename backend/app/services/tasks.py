import logging
from celery import shared_task, group, chord, chain
from datetime import datetime, timedelta
from typing import List, Dict, Optional

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


@shared_task(name="heatmap.temporal.generate_single_frame_tile")
def generate_single_frame_tile_task(z: int, x: int, y: int,
                                     frame_dt_iso: str,
                                     data_type: str = "vehicle",
                                     vehicle_type: str = "all",
                                     road_level: str = "all",
                                     direction: str = "both") -> Dict:
    from app.heatmap import temporal_heatmap_service
    db = SessionLocal()
    try:
        frame_dt = datetime.fromisoformat(frame_dt_iso)
        tile_bytes = temporal_heatmap_service.get_frame_tile(
            db, z, x, y, frame_dt,
            data_type=data_type,
            vehicle_type=vehicle_type,
            road_level=road_level,
            direction=direction,
        )
        return {
            "status": "success",
            "z": z, "x": x, "y": y,
            "frame_dt": frame_dt_iso,
            "size_bytes": len(tile_bytes),
        }
    except Exception as e:
        logger.error(f"Frame tile generation failed {z}/{x}/{y}@{frame_dt_iso}: {e}")
        return {
            "status": "failed",
            "z": z, "x": x, "y": y,
            "frame_dt": frame_dt_iso,
            "error": str(e),
        }
    finally:
        db.close()


@shared_task(name="heatmap.temporal.pregenerate_day_frames")
def pregenerate_day_frames_task(date_iso: str = None,
                                 min_zoom: int = 10,
                                 max_zoom: int = 15,
                                 bbox: List[float] = None,
                                 data_type: str = "vehicle",
                                 vehicle_type: str = "all",
                                 road_level: str = "all",
                                 direction: str = "both") -> Dict:
    from app.utils.geo_utils import bbox_to_tiles
    from app.heatmap import temporal_heatmap_service

    if date_iso is None:
        date_iso = datetime.utcnow().date().isoformat()
    date_obj = datetime.strptime(date_iso, "%Y-%m-%d")

    bbox = bbox or [116.3, 39.8, 116.5, 40.0]

    frame_timestamps = temporal_heatmap_service.get_frame_timestamps(date_obj)
    total_frames = len(frame_timestamps)

    subtasks = []
    for z in range(min_zoom, max_zoom + 1):
        tiles = bbox_to_tiles(bbox, z)
        for (x, y) in tiles:
            for frame_dt_iso in frame_timestamps:
                subtasks.append(generate_single_frame_tile_task.s(
                    z, x, y, frame_dt_iso,
                    data_type=data_type,
                    vehicle_type=vehicle_type,
                    road_level=road_level,
                    direction=direction,
                ))

    logger.info(f"Pregenerating {len(subtasks)} temporal tiles for {date_iso}")

    if not subtasks:
        return {"status": "empty", "generated": 0, "date": date_iso}

    job = group(subtasks)
    result = job.apply_async()
    results = result.get(timeout=3600 * 6)

    success = [r for r in results if r.get("status") == "success"]
    failed = [r for r in results if r.get("status") != "success"]

    return {
        "status": "completed",
        "date": date_iso,
        "total_tasks": len(subtasks),
        "success_count": len(success),
        "failed_count": len(failed),
        "total_bytes": sum(r.get("size_bytes", 0) for r in success),
        "frames_count": total_frames,
        "zoom_range": [min_zoom, max_zoom],
    }


@shared_task(name="heatmap.temporal.schedule_pregeneration")
def schedule_temporal_pregeneration_task(min_zoom: int = 10,
                                          max_zoom: int = 14,
                                          bbox: List[float] = None) -> Dict:
    target_date = (datetime.utcnow() - timedelta(days=1)).date().isoformat()
    combos = [
        ("vehicle", "all", "all", "both"),
        ("vehicle", "car", "all", "both"),
        ("vehicle", "bus", "all", "both"),
        ("vehicle", "truck", "all", "both"),
        ("congestion", "all", "all", "both"),
    ]

    results = []
    for data_type, vt, rl, d in combos:
        res = pregenerate_day_frames_task(
            date_iso=target_date,
            min_zoom=min_zoom, max_zoom=max_zoom,
            bbox=bbox,
            data_type=data_type, vehicle_type=vt,
            road_level=rl, direction=d,
        )
        results.append(res)

    evict_old_frames_task.apply_async()

    return {
        "date": target_date,
        "combos_processed": len(results),
        "results": results,
    }


@shared_task(name="heatmap.temporal.evict_old_frames")
def evict_old_frames_task(days_to_keep: int = 7) -> Dict:
    from app.heatmap import temporal_heatmap_service
    before = len(temporal_heatmap_service.cache)
    temporal_heatmap_service.evict_old_frames(days_to_keep=days_to_keep)
    after = len(temporal_heatmap_service.cache)
    logger.info(f"Evicted {before - after} old frames (kept last {days_to_keep} days)")
    return {
        "days_to_keep": days_to_keep,
        "frames_before": before,
        "frames_after": after,
        "evicted": before - after,
    }


@shared_task(name="heatmap.dimensions.pregenerate_combo_tiles")
def pregenerate_dimension_combo_task(combo_key: str,
                                      min_zoom: int = 10,
                                      max_zoom: int = 14,
                                      bbox: List[float] = None) -> Dict:
    from app.heatmap import heatmap_dimension_service, heatmap_service
    from app.utils.geo_utils import bbox_to_tiles

    dims = heatmap_dimension_service.parse_dimension_key(combo_key)
    bbox = bbox or [116.3, 39.8, 116.5, 40.0]

    db = SessionLocal()
    try:
        count = 0
        for z in range(min_zoom, max_zoom + 1):
            tiles = bbox_to_tiles(bbox, z)
            for (x, y) in tiles:
                try:
                    heatmap_service.generate_tile(
                        db, z, x, y,
                        timestamp=datetime.utcnow(),
                        data_type=dims.get("data_type", "vehicle"),
                        vehicle_type=dims.get("vehicle_type", "all"),
                    )
                    count += 1
                except Exception as e:
                    logger.warning(f"Dim combo tile {combo_key} {z}/{x}/{y} failed: {e}")
        return {"combo_key": combo_key, "generated_tiles": count, "dims": dims}
    finally:
        db.close()


@shared_task(name="heatmap.dimensions.pregenerate_popular")
def pregenerate_popular_dimensions_task(min_zoom: int = 10,
                                         max_zoom: int = 14,
                                         top_n: int = 10) -> Dict:
    from app.heatmap import heatmap_dimension_service

    db = SessionLocal()
    try:
        popular = heatmap_dimension_service.list_popular_combos(db, top_n=top_n)
    finally:
        db.close()

    results = []
    for combo in popular:
        try:
            res = pregenerate_dimension_combo_task(
                combo["key"], min_zoom=min_zoom, max_zoom=max_zoom,
            )
            results.append(res)
        except Exception as e:
            logger.warning(f"Dimension pregeneration failed for {combo['key']}: {e}")

    return {"pregenerated_count": len(results), "results": results}
