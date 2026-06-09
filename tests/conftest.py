from __future__ import annotations

import os
import sys
from typing import Generator

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import Base
from app.core.cache import cache
from app.models import (
    User,
    Role,
    Permission,
    Product,
    Category,
    SKU,
    Warehouse,
    Inventory,
    InventoryAlert,
    InventorySync,
    SyncConflict,
    CDCLog,
    CDCEvent,
    PurchaseOrder,
    PurchaseOrderItem,
    ApprovalWorkflow,
    ApprovalNode,
    ApprovalRecord,
    Supplier,
    ReplenishmentSuggestion,
    SalesForecast,
    AttributeTemplate,
)


def pytest_configure(config):
    config.addinivalue_line("markers", "unit: Unit tests")
    config.addinivalue_line("markers", "integration: Integration tests requiring Docker")
    config.addinivalue_line("markers", "sku: SKU management tests")
    config.addinivalue_line("markers", "sync: Inventory sync tests")
    config.addinivalue_line("markers", "purchase: Purchase order tests")
    config.addinivalue_line("markers", "alert: Alert and replenishment tests")
    config.addinivalue_line("markers", "slow: Slow running tests")


@pytest.fixture(scope="session")
def docker_postgres():
    try:
        from testcontainers.postgres import PostgresContainer

        with PostgresContainer("postgres:16-alpine") as postgres:
            yield postgres
    except Exception as e:
        pytest.skip(f"Testcontainers not available: {e}")


@pytest.fixture(scope="session")
def docker_redis():
    try:
        from testcontainers.redis import RedisContainer

        with RedisContainer("redis:7-alpine") as redis:
            yield redis
    except Exception as e:
        pytest.skip(f"Testcontainers not available: {e}")


@pytest.fixture(scope="session")
def test_database_url(docker_postgres):
    return docker_postgres.get_connection_url()


@pytest.fixture(scope="session")
def test_engine(test_database_url):
    engine = create_engine(
        test_database_url,
        pool_size=5,
        max_overflow=10,
        echo=False,
        pool_pre_ping=True,
    )

    Base.metadata.create_all(bind=engine)
    yield engine
    Base.metadata.drop_all(bind=engine)
    engine.dispose()


@pytest.fixture(scope="session")
def TestingSessionLocal(test_engine):
    return sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


@pytest.fixture
def db(TestingSessionLocal) -> Generator[Session, None, None]:
    db = TestingSessionLocal()
    try:
        yield db
        db.rollback()
    finally:
        db.close()


@pytest.fixture
def clean_db(db: Session) -> Session:
    tables = [
        CDCEvent,
        CDCLog,
        SyncConflict,
        InventorySync,
        ApprovalRecord,
        PurchaseOrderItem,
        PurchaseOrder,
        ReplenishmentSuggestion,
        SalesForecast,
        InventoryAlert,
        Inventory,
        SKU,
        Product,
        Category,
        AttributeTemplate,
        Supplier,
        Warehouse,
        ApprovalNode,
        ApprovalWorkflow,
        User,
        Role,
        Permission,
    ]

    for table in reversed(tables):
        db.query(table).delete()
    db.commit()
    return db


@pytest.fixture(autouse=True)
def override_settings(monkeypatch, test_database_url, docker_redis):
    if docker_redis:
        redis_host = docker_redis.get_container_host_ip()
        redis_port = docker_redis.get_exposed_port(6379)
        monkeypatch.setenv("REDIS_BROKER_URL", f"redis://{redis_host}:{redis_port}/0")
        monkeypatch.setenv("REDIS_BACKEND_URL", f"redis://{redis_host}:{redis_port}/1")
    monkeypatch.setenv("DATABASE_URL", test_database_url)
    monkeypatch.setenv("APP_ENV", "testing")
    monkeypatch.setenv("DEBUG", "true")


@pytest.fixture
def cache_client(docker_redis):
    if docker_redis:
        redis_host = docker_redis.get_container_host_ip()
        redis_port = docker_redis.get_exposed_port(6379)
        cache._client = None
        cache._redis_url = f"redis://{redis_host}:{redis_port}/0"
        cache._use_cluster = False
    yield cache
    cache.flushdb()
