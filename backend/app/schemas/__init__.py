from datetime import datetime
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field


class DataSourceBase(BaseModel):
    name: str
    type: str
    config: Dict[str, Any] = {}
    status: str = "inactive"
    description: Optional[str] = None


class DataSourceCreate(DataSourceBase):
    pass


class DataSourceUpdate(BaseModel):
    name: Optional[str] = None
    config: Optional[Dict[str, Any]] = None
    status: Optional[str] = None
    description: Optional[str] = None


class DataSource(DataSourceBase):
    id: int
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class TrafficFlowBase(BaseModel):
    sensor_id: str
    timestamp: datetime
    vehicle_count: int = 0
    pedestrian_count: int = 0
    avg_speed: float = 0.0
    congestion_index: float = 0.0
    vehicle_type: str = "all"
    direction: str = "both"


class TrafficFlowCreate(TrafficFlowBase):
    pass


class TrafficFlow(TrafficFlowBase):
    id: int
    created_at: datetime

    class Config:
        from_attributes = True


class HeatmapQuery(BaseModel):
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    timestamp: Optional[datetime] = None
    data_type: str = "vehicle"
    vehicle_type: str = "all"
    bbox: Optional[List[float]] = None
    zoom: int = 12
    time_window: str = "1h"


class HeatmapTileQuery(BaseModel):
    z: int
    x: int
    y: int
    timestamp: Optional[datetime] = None
    data_type: str = "vehicle"
    vehicle_type: str = "all"


class ODQuery(BaseModel):
    origin_zone: Optional[int] = None
    dest_zone: Optional[int] = None
    time_period: str = "morning_peak"
    travel_mode: str = "all"
    limit: int = 1000


class ODResult(BaseModel):
    origin_lng: float
    origin_lat: float
    dest_lng: float
    dest_lat: float
    trip_count: int
    avg_travel_time: Optional[float] = None


class PredictionQuery(BaseModel):
    sensor_id: str
    horizons: List[int] = Field(default_factory=lambda: [15, 30, 60])
    model_type: str = "lstm"


class PredictionResponse(BaseModel):
    sensor_id: str
    prediction_time: datetime
    horizons: List[int]
    flows: List[float]
    congestions: List[float]
    confidences: List[float]


class SensorBase(BaseModel):
    sensor_id: str
    sensor_type: str = "camera"
    name: Optional[str] = None
    lng: float
    lat: float
    direction: float = 0.0
    status: str = "active"
    properties: Dict[str, Any] = {}


class SensorCreate(SensorBase):
    pass


class Sensor(SensorBase):
    id: int
    installed_at: Optional[datetime] = None
    created_at: datetime

    class Config:
        from_attributes = True


class BuildingBase(BaseModel):
    name: Optional[str] = None
    height: float = 0.0
    floors: int = 0
    building_type: Optional[str] = None


class Building(BuildingBase):
    id: int
    geom: Any
    created_at: datetime

    class Config:
        from_attributes = True


class POIBase(BaseModel):
    name: str
    category: Optional[str] = None
    address: Optional[str] = None
    lng: float
    lat: float
    properties: Dict[str, Any] = {}


class POICreate(POIBase):
    pass


class POI(POIBase):
    id: int
    created_at: datetime

    class Config:
        from_attributes = True


class RoadBase(BaseModel):
    name: Optional[str] = None
    road_type: Optional[str] = None
    lanes: int = 2
    speed_limit: float = 60.0


class Road(RoadBase):
    id: int
    created_at: datetime

    class Config:
        from_attributes = True


class TaskJobBase(BaseModel):
    task_type: str
    params: Dict[str, Any] = {}


class TaskJobCreate(TaskJobBase):
    pass


class TaskJob(TaskJobBase):
    id: int
    task_id: str
    status: str
    progress: float
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    created_at: datetime

    class Config:
        from_attributes = True


class SignalTimingBase(BaseModel):
    name: str
    intersection_id: int
    intersection_name: str
    phases: List[Dict[str, Any]] = []
    cycle_length: int = 120


class SignalTimingCreate(SignalTimingBase):
    pass


class SignalTiming(SignalTimingBase):
    id: int
    status: str
    created_at: datetime

    class Config:
        from_attributes = True


class UserBase(BaseModel):
    username: str
    email: Optional[str] = None
    full_name: Optional[str] = None
    role: str = "viewer"


class UserCreate(UserBase):
    password: str


class UserLogin(BaseModel):
    username: str
    password: str


class User(UserBase):
    id: int
    is_active: bool
    created_at: datetime
    last_login: Optional[datetime] = None

    class Config:
        from_attributes = True


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class TokenData(BaseModel):
    username: Optional[str] = None


class TimeSeriesQuery(BaseModel):
    sensor_id: str
    start_time: datetime
    end_time: datetime
    aggregation: str = "5m"
    field: str = "vehicle_count"
