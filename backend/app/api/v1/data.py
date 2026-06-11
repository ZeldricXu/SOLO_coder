from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.models import TrafficSensor, DataSource, Building, POI, RoadNetwork
from app.schemas import SensorCreate, Sensor, POICreate, POI, DataSourceCreate, DataSourceUpdate, DataSource
from app.utils.auth import get_current_active_user, require_role

import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/data", tags=["数据管理"])


@router.get("/sensors")
async def list_sensors(
    sensor_type: Optional[str] = Query(None),
    status: Optional[str] = Query(None),
    limit: int = Query(100),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    query = db.query(TrafficSensor)

    if sensor_type:
        query = query.filter(TrafficSensor.sensor_type == sensor_type)
    if status:
        query = query.filter(TrafficSensor.status == status)

    total = query.count()
    sensors = query.offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(sensors),
        "sensors": sensors,
    }


@router.post("/sensors", response_model=Sensor)
async def create_sensor(
    sensor: SensorCreate,
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin", "editor"])),
):
    from geoalchemy2.shape import from_shape
    from shapely.geometry import Point

    existing = db.query(TrafficSensor).filter(
        TrafficSensor.sensor_id == sensor.sensor_id
    ).first()

    if existing:
        raise HTTPException(status_code=400, detail="Sensor ID already exists")

    point = Point(sensor.lng, sensor.lat)

    db_sensor = TrafficSensor(
        sensor_id=sensor.sensor_id,
        sensor_type=sensor.sensor_type,
        name=sensor.name,
        location=from_shape(point, srid=4326),
        direction=sensor.direction,
        status=sensor.status,
        properties=sensor.properties,
        installed_at=datetime.utcnow(),
    )

    db.add(db_sensor)
    db.commit()
    db.refresh(db_sensor)

    return Sensor(
        id=db_sensor.id,
        sensor_id=db_sensor.sensor_id,
        sensor_type=db_sensor.sensor_type,
        name=db_sensor.name,
        lng=sensor.lng,
        lat=sensor.lat,
        direction=db_sensor.direction,
        status=db_sensor.status,
        properties=db_sensor.properties,
        installed_at=db_sensor.installed_at,
        created_at=db_sensor.created_at,
    )


@router.get("/sensors/{sensor_id}")
async def get_sensor(
    sensor_id: str,
    db: Session = Depends(get_db),
):
    sensor = db.query(TrafficSensor).filter(
        TrafficSensor.sensor_id == sensor_id
    ).first()

    if not sensor:
        raise HTTPException(status_code=404, detail="Sensor not found")

    return sensor


@router.get("/sources")
async def list_data_sources(
    source_type: Optional[str] = Query(None),
    status: Optional[str] = Query(None),
    db: Session = Depends(get_db),
    current_user=Depends(get_current_active_user),
):
    query = db.query(DataSource)

    if source_type:
        query = query.filter(DataSource.type == source_type)
    if status:
        query = query.filter(DataSource.status == status)

    sources = query.all()

    return {
        "count": len(sources),
        "sources": sources,
    }


@router.post("/sources", response_model=DataSource)
async def create_data_source(
    source: DataSourceCreate,
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin"])),
):
    db_source = DataSource(
        name=source.name,
        type=source.type,
        config=source.config,
        status=source.status,
        description=source.description,
    )

    db.add(db_source)
    db.commit()
    db.refresh(db_source)

    return db_source


@router.put("/sources/{source_id}", response_model=DataSource)
async def update_data_source(
    source_id: int,
    source_update: DataSourceUpdate,
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin"])),
):
    db_source = db.query(DataSource).filter(DataSource.id == source_id).first()

    if not db_source:
        raise HTTPException(status_code=404, detail="Data source not found")

    update_data = source_update.dict(exclude_unset=True)
    for field, value in update_data.items():
        setattr(db_source, field, value)

    db_source.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(db_source)

    return db_source


@router.get("/buildings")
async def list_buildings(
    building_type: Optional[str] = Query(None),
    limit: int = Query(100),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    query = db.query(Building)

    if building_type:
        query = query.filter(Building.building_type == building_type)

    total = query.count()
    buildings = query.offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(buildings),
        "buildings": buildings,
    }


@router.get("/pois")
async def list_pois(
    category: Optional[str] = Query(None),
    limit: int = Query(100),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    query = db.query(POI)

    if category:
        query = query.filter(POI.category == category)

    total = query.count()
    pois = query.offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(pois),
        "pois": pois,
    }


@router.post("/pois", response_model=POI)
async def create_poi(
    poi: POICreate,
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin", "editor"])),
):
    from geoalchemy2.shape import from_shape
    from shapely.geometry import Point

    point = Point(poi.lng, poi.lat)

    db_poi = POI(
        name=poi.name,
        category=poi.category,
        address=poi.address,
        geom=from_shape(point, srid=4326),
        properties=poi.properties,
    )

    db.add(db_poi)
    db.commit()
    db.refresh(db_poi)

    return POI(
        id=db_poi.id,
        name=db_poi.name,
        category=db_poi.category,
        address=db_poi.address,
        lng=poi.lng,
        lat=poi.lat,
        properties=db_poi.properties,
        created_at=db_poi.created_at,
    )


@router.get("/roads")
async def list_roads(
    road_type: Optional[str] = Query(None),
    limit: int = Query(100),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    query = db.query(RoadNetwork)

    if road_type:
        query = query.filter(RoadNetwork.road_type == road_type)

    total = query.count()
    roads = query.offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(roads),
        "roads": roads,
    }
