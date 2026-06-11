from datetime import datetime
from sqlalchemy import Column, Integer, String, Float, DateTime, Boolean, ForeignKey, Text, JSON, BigInteger
from sqlalchemy.orm import relationship
from geoalchemy2 import Geometry
from app.database import Base


class DataSource(Base):
    __tablename__ = "data_sources"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    type = Column(String(50), nullable=False)
    config = Column(JSON, default={})
    status = Column(String(20), default="inactive")
    description = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class RoadNetwork(Base):
    __tablename__ = "road_networks"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100))
    road_type = Column(String(50))
    lanes = Column(Integer)
    speed_limit = Column(Float)
    geom = Column(Geometry(geometry_type="LINESTRING", srid=4326))
    level = Column(Integer, default=0)
    created_at = Column(DateTime, default=datetime.utcnow)


class Building(Base):
    __tablename__ = "buildings"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(200))
    height = Column(Float)
    floors = Column(Integer)
    building_type = Column(String(50))
    geom = Column(Geometry(geometry_type="POLYGON", srid=4326))
    footprint_area = Column(Float)
    created_at = Column(DateTime, default=datetime.utcnow)


class POI(Base):
    __tablename__ = "pois"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(200), nullable=False)
    category = Column(String(50))
    address = Column(String(500))
    geom = Column(Geometry(geometry_type="POINT", srid=4326))
    properties = Column(JSON, default={})
    created_at = Column(DateTime, default=datetime.utcnow)


class TrafficSensor(Base):
    __tablename__ = "traffic_sensors"

    id = Column(Integer, primary_key=True, index=True)
    sensor_id = Column(String(50), unique=True, index=True)
    sensor_type = Column(String(30))
    name = Column(String(100))
    location = Column(Geometry(geometry_type="POINT", srid=4326))
    road_id = Column(Integer, ForeignKey("road_networks.id"))
    direction = Column(Float)
    status = Column(String(20), default="active")
    properties = Column(JSON, default={})
    installed_at = Column(DateTime)
    created_at = Column(DateTime, default=datetime.utcnow)


class TrafficFlowRecord(Base):
    __tablename__ = "traffic_flow_records"

    id = Column(BigInteger, primary_key=True, index=True)
    sensor_id = Column(String(50), index=True)
    timestamp = Column(DateTime, index=True)
    vehicle_count = Column(Integer)
    pedestrian_count = Column(Integer)
    avg_speed = Column(Float)
    congestion_index = Column(Float)
    vehicle_type = Column(String(30))
    direction = Column(String(10))
    time_window = Column(String(20))
    geom = Column(Geometry(geometry_type="POINT", srid=4326))
    created_at = Column(DateTime, default=datetime.utcnow)

    __table_args__ = (
        {"timescaledb_hypertable": {"time_column_name": "timestamp"}}
        if False else {},
    )


class TrajectoryPoint(Base):
    __tablename__ = "trajectory_points"

    id = Column(BigInteger, primary_key=True, index=True)
    vehicle_id = Column(String(50), index=True)
    timestamp = Column(DateTime, index=True)
    location = Column(Geometry(geometry_type="POINT", srid=4326))
    speed = Column(Float)
    heading = Column(Float)
    vehicle_type = Column(String(30))
    matched_road_id = Column(Integer, ForeignKey("road_networks.id"))
    created_at = Column(DateTime, default=datetime.utcnow)


class HeatmapTile(Base):
    __tablename__ = "heatmap_tiles"

    id = Column(Integer, primary_key=True, index=True)
    z = Column(Integer, index=True)
    x = Column(Integer, index=True)
    y = Column(Integer, index=True)
    timestamp = Column(DateTime, index=True)
    layer_type = Column(String(50), index=True)
    data_type = Column(String(30))
    tile_data = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)


class ODRecord(Base):
    __tablename__ = "od_records"

    id = Column(BigInteger, primary_key=True, index=True)
    origin_lng = Column(Float)
    origin_lat = Column(Float)
    dest_lng = Column(Float)
    dest_lat = Column(Float)
    origin_zone_id = Column(Integer)
    dest_zone_id = Column(Integer)
    trip_count = Column(Integer)
    travel_mode = Column(String(30))
    time_period = Column(String(30))
    avg_travel_time = Column(Float)
    created_at = Column(DateTime, default=datetime.utcnow)


class TrafficZone(Base):
    __tablename__ = "traffic_zones"

    id = Column(Integer, primary_key=True, index=True)
    zone_code = Column(String(50), unique=True)
    name = Column(String(100))
    zone_type = Column(String(30))
    geom = Column(Geometry(geometry_type="POLYGON", srid=4326))
    properties = Column(JSON, default={})
    created_at = Column(DateTime, default=datetime.utcnow)


class PredictionModel(Base):
    __tablename__ = "prediction_models"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    model_type = Column(String(50))
    version = Column(String(20))
    status = Column(String(20), default="training")
    metrics = Column(JSON, default={})
    config = Column(JSON, default={})
    model_path = Column(String(500))
    trained_at = Column(DateTime)
    created_at = Column(DateTime, default=datetime.utcnow)


class PredictionResult(Base):
    __tablename__ = "prediction_results"

    id = Column(BigInteger, primary_key=True, index=True)
    model_id = Column(Integer, ForeignKey("prediction_models.id"))
    sensor_id = Column(String(50), index=True)
    prediction_time = Column(DateTime, index=True)
    target_time = Column(DateTime, index=True)
    horizon_minutes = Column(Integer)
    predicted_flow = Column(Float)
    predicted_congestion = Column(Float)
    confidence = Column(Float)
    created_at = Column(DateTime, default=datetime.utcnow)


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, index=True, nullable=False)
    email = Column(String(100), unique=True, index=True)
    hashed_password = Column(String(255), nullable=False)
    full_name = Column(String(100))
    role = Column(String(30), default="viewer")
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    last_login = Column(DateTime)


class TaskJob(Base):
    __tablename__ = "task_jobs"

    id = Column(Integer, primary_key=True, index=True)
    task_type = Column(String(50), index=True)
    task_id = Column(String(100), unique=True, index=True)
    status = Column(String(30), default="pending")
    progress = Column(Float, default=0.0)
    result = Column(JSON, default={})
    error_message = Column(Text)
    params = Column(JSON, default={})
    started_at = Column(DateTime)
    completed_at = Column(DateTime)
    created_by = Column(Integer, ForeignKey("users.id"))
    created_at = Column(DateTime, default=datetime.utcnow)


class SignalTimingPlan(Base):
    __tablename__ = "signal_timing_plans"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    intersection_id = Column(Integer)
    intersection_name = Column(String(200))
    phases = Column(JSON, default=[])
    cycle_length = Column(Integer)
    status = Column(String(20), default="draft")
    created_by = Column(Integer, ForeignKey("users.id"))
    created_at = Column(DateTime, default=datetime.utcnow)
