import asyncio
from datetime import datetime, timedelta
from typing import Generator, AsyncGenerator

import pytest
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, Session
from sqlalchemy.pool import StaticPool

from app.database import Base, get_db
from app.routes import (
    pages_router,
    health_router,
    metrics_router,
    alert_router,
    slow_sql_router,
    asset_router,
    duty_router,
    log_router,
    preference_router,
)
from app.context_processors import init_app

SQLALCHEMY_DATABASE_URL = "sqlite:///:memory:"

engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)

TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def create_test_app():
    app = FastAPI(
        title="Test App",
        version="1.0.0",
        description="Test App",
    )

    app.mount("/static", StaticFiles(directory="static"), name="static")

    app.include_router(pages_router)
    app.include_router(health_router)
    app.include_router(metrics_router)
    app.include_router(alert_router)
    app.include_router(slow_sql_router)
    app.include_router(asset_router)
    app.include_router(duty_router)
    app.include_router(log_router)
    app.include_router(preference_router)

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    init_app(app)

    app.dependency_overrides[get_db] = override_get_db

    return app


app = create_test_app()


@pytest.fixture(scope="function")
def db_session() -> Generator[Session, None, None]:
    Base.metadata.create_all(bind=engine)
    db = TestingSessionLocal()
    
    try:
        yield db
    finally:
        db.rollback()
        db.close()
        Base.metadata.drop_all(bind=engine)


@pytest.fixture(scope="function")
def client() -> Generator[TestClient, None, None]:
    Base.metadata.create_all(bind=engine)
    with TestClient(app) as c:
        yield c
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def mock_prometheus_response():
    return {
        "status": "success",
        "data": {
            "resultType": "matrix",
            "result": [
                {
                    "metric": {"__name__": "cpu_usage", "instance": "localhost:9090"},
                    "values": [[datetime.now().timestamp() - i * 60, str(50 + i % 20)] for i in range(1440)],
                }
            ]
        }
    }


@pytest.fixture
def sample_services_data():
    return [
        {"name": "订单服务", "status": "healthy", "response_time_ms": 45},
        {"name": "用户服务", "status": "healthy", "response_time_ms": 38},
        {"name": "支付服务", "status": "warning", "response_time_ms": 280},
        {"name": "RabbitMQ", "status": "critical", "response_time_ms": None},
    ]
