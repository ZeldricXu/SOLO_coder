import os
import sys
import pytest
import tempfile
import shutil
import json
import time
from pathlib import Path
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch, PropertyMock
from threading import Lock

sys.path.insert(0, str(Path(__file__).parent.parent))

from shapely.geometry import Point, LineString, Polygon
from geoalchemy2.shape import from_shape

os.environ.setdefault("POSTGRES_HOST", "localhost")
os.environ.setdefault("REDIS_HOST", "localhost")
os.environ.setdefault("INFLUXDB_URL", "http://localhost:8086")
os.environ.setdefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
os.environ.setdefault("TILE_CACHE_DIR", tempfile.mkdtemp())
os.environ.setdefault("HEATMAP_CACHE_DIR", tempfile.mkdtemp())
os.environ.setdefault("PREDICTION_MODEL_DIR", tempfile.mkdtemp())
os.environ.setdefault("SECRET_KEY", "test-secret-key-for-testing-only")


def pytest_configure(config):
    config.addinivalue_line("markers", "unit: Unit tests")
    config.addinivalue_line("markers", "exception: Exception scenario tests")
    config.addinivalue_line("markers", "concurrency: Concurrency scenario tests")
    config.addinivalue_line("markers", "integration: Integration tests requiring docker-compose")


class FakeRedis:
    def __init__(self):
        self._store = {}
        self._expiry = {}
        self._lock = Lock()

    def get(self, key):
        with self._lock:
            if key in self._expiry and time.time() > self._expiry[key]:
                del self._store[key]
                del self._expiry[key]
                return None
            val = self._store.get(key)
            if val is not None and isinstance(val, bytes):
                return val.decode() if isinstance(val, bytes) else val
            return val

    def set(self, key, value, expire=None):
        with self._lock:
            self._store[key] = value
            if expire:
                self._expiry[key] = time.time() + expire
            return True

    def setex(self, key, expire, value):
        return self.set(key, value, expire=expire)

    def delete(self, key):
        with self._lock:
            self._store.pop(key, None)
            self._expiry.pop(key, None)
            return 1

    def exists(self, key):
        with self._lock:
            return key in self._store

    def keys(self, pattern="*"):
        with self._lock:
            if pattern == "*":
                return list(self._store.keys())
            import fnmatch
            return [k for k in self._store if fnmatch.fnmatch(k, pattern)]

    def incr(self, key):
        with self._lock:
            val = self._store.get(key, 0)
            if isinstance(val, str):
                val = int(val)
            self._store[key] = val + 1
            return self._store[key]

    def ping(self):
        return True


@pytest.fixture
def sample_road_network():
    road1 = LineString([(116.4074, 39.9042), (116.4084, 39.9042)])
    road2 = LineString([(116.4074, 39.9042), (116.4074, 39.9052)])
    road3 = LineString([(116.4060, 39.9030), (116.4100, 39.9030)])
    road4 = LineString([(116.4080, 39.9035), (116.4080, 39.9060)])
    road5 = LineString([
        (116.4070, 39.9045), (116.4075, 39.9048),
        (116.4082, 39.9050), (116.4090, 39.9052),
    ])
    return [
        {"id": 1, "name": "东西大街", "road_type": "main_road", "lanes": 6, "geom": road1},
        {"id": 2, "name": "南北大街", "road_type": "main_road", "lanes": 6, "geom": road2},
        {"id": 3, "name": "次干道", "road_type": "secondary", "lanes": 4, "geom": road3},
        {"id": 4, "name": "支路A", "road_type": "branch", "lanes": 2, "geom": road4},
        {"id": 5, "name": "弯曲路", "road_type": "secondary", "lanes": 4, "geom": road5},
    ]


@pytest.fixture
def sample_trajectory_on_road():
    return {
        "vehicle_id": "V001",
        "lon": 116.4075,
        "lat": 39.9042,
        "speed": 45.0,
        "heading": 90.0,
        "vehicle_type": "car",
        "timestamp": datetime.utcnow(),
    }


@pytest.fixture
def sample_trajectory_off_road():
    return {
        "vehicle_id": "V002",
        "lon": 116.4074,
        "lat": 39.9060,
        "speed": 30.0,
        "heading": 180.0,
        "vehicle_type": "bus",
        "timestamp": datetime.utcnow(),
    }


@pytest.fixture
def sample_trajectories():
    road_network = [
        LineString([(116.4074, 39.9042), (116.4084, 39.9042)]),
        LineString([(116.4074, 39.9042), (116.4074, 39.9052)]),
    ]
    pts = []
    for road in road_network:
        for frac in [0.2, 0.4, 0.6, 0.8]:
            p = road.interpolate(frac, normalized=True)
            pts.append({
                "vehicle_id": f"V_{len(pts):03d}",
                "lon": p.x + (0.00001 if len(pts) % 2 else 0),
                "lat": p.y,
                "speed": 40.0,
                "heading": 90.0,
                "vehicle_type": "car",
                "timestamp": datetime.utcnow(),
            })
    pts.append({
        "vehicle_id": "V_OFF",
        "lon": 116.4000,
        "lat": 39.9100,
        "speed": 30.0,
        "heading": 0.0,
        "vehicle_type": "truck",
        "timestamp": datetime.utcnow(),
    })
    return pts


@pytest.fixture
def sample_traffic_records():
    now = datetime.utcnow()
    records = []
    for i in range(100):
        records.append({
            "sensor_id": f"SENSOR_{i % 5:04d}",
            "timestamp": (now - timedelta(minutes=i * 5)).isoformat(),
            "vehicle_count": 50 + i,
            "pedestrian_count": 10 + i // 2,
            "avg_speed": 40.0 + (i % 20),
            "congestion_index": min(1.0, i / 100),
            "vehicle_type": "car" if i % 3 == 0 else "bus" if i % 3 == 1 else "truck",
            "direction": "east" if i % 2 == 0 else "west",
            "lon": 116.4074 + (i % 10) * 0.001,
            "lat": 39.9042 + (i % 8) * 0.001,
        })
    return records


@pytest.fixture
def sample_zero_flow_records():
    now = datetime.utcnow()
    return [
        {
            "sensor_id": f"SENSOR_{i:04d}",
            "timestamp": (now - timedelta(minutes=i * 5)).isoformat(),
            "vehicle_count": 0,
            "pedestrian_count": 0,
            "avg_speed": 0,
            "congestion_index": 0.0,
            "vehicle_type": "all",
            "direction": "both",
            "lon": 116.4074 + i * 0.001,
            "lat": 39.9042 + i * 0.001,
        }
        for i in range(10)
    ]


@pytest.fixture
def sample_extreme_flow_records():
    now = datetime.utcnow()
    return [
        {
            "sensor_id": "SENSOR_EXTREME",
            "timestamp": now.isoformat(),
            "vehicle_count": 9999,
            "pedestrian_count": 5000,
            "avg_speed": 5.0,
            "congestion_index": 1.0,
            "vehicle_type": "all",
            "direction": "both",
            "lon": 116.4074,
            "lat": 39.9042,
        },
        *[
            {
                "sensor_id": f"SENSOR_NORMAL_{i:04d}",
                "timestamp": now.isoformat(),
                "vehicle_count": 50,
                "pedestrian_count": 10,
                "avg_speed": 45.0,
                "congestion_index": 0.3,
                "vehicle_type": "car",
                "direction": "east",
                "lon": 116.4074 + i * 0.002,
                "lat": 39.9042 + i * 0.002,
            }
            for i in range(5)
        ]
    ]


@pytest.fixture
def sample_heatmap_data_points():
    return [
        {"lon": 116.4074, "lat": 39.9042, "value": 100},
        {"lon": 116.4084, "lat": 39.9042, "value": 200},
        {"lon": 116.4074, "lat": 39.9052, "value": 150},
        {"lon": 116.4090, "lat": 39.9060, "value": 50},
    ]


@pytest.fixture
def sample_kafka_messages():
    now = datetime.utcnow()
    return [
        {
            "sensor_id": f"SENSOR_{i:04d}",
            "timestamp": (now - timedelta(seconds=i * 30)).isoformat(),
            "vehicle_count": 30 + i * 2,
            "pedestrian_count": 5 + i,
            "avg_speed": 40.0 + i % 10,
            "congestion_index": min(1.0, i / 50),
        }
        for i in range(120)
    ]


@pytest.fixture
def tmp_cache_dir():
    d = tempfile.mkdtemp()
    yield d
    shutil.rmtree(d, ignore_errors=True)


@pytest.fixture
def mock_redis():
    return FakeRedis()


@pytest.fixture
def mock_db_session():
    session = MagicMock()
    session.query.return_value.filter.return_value.all.return_value = []
    session.query.return_value.filter.return_value.first.return_value = None
    session.query.return_value.filter.return_value.limit.return_value.all.return_value = []
    session.query.return_value.filter.return_value.order_by.return_value.limit.return_value.all.return_value = []
    session.query.return_value.join.return_value.filter.return_value.limit.return_value.all.return_value = []
    session.query.return_value.join.return_value.filter.return_value.all.return_value = []
    session.add = MagicMock()
    session.commit = MagicMock()
    session.rollback = MagicMock()
    session.close = MagicMock()
    session.bulk_save_objects = MagicMock()
    return session


@pytest.fixture
def app_client():
    from fastapi.testclient import TestClient
    with patch("app.database.init_db"):
        with patch("app.utils.redis_client.RedisManager._connect"):
            with patch("app.utils.influxdb_client.InfluxDBManager._connect"):
                from main import app
                client = TestClient(app, raise_server_exceptions=False)
                yield client


@pytest.fixture
def httpx_client():
    import httpx
    from main import app
    transport = httpx.ASGITransport(app=app)
    with httpx.Client(transport=transport, base_url="http://test") as client:
        yield client
