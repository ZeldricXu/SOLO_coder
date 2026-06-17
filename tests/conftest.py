from datetime import datetime

import pandas as pd
import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.models.base import Base


@pytest_asyncio.fixture
async def db_session():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest.fixture
def sample_dag_definition() -> dict:
    return {
        "nodes": [
            {"id": "extract_mysql", "type": "extract", "config": {"source_type": "mysql"}, "dependencies": []},
            {"id": "transform", "type": "transform", "config": {"sql": "SELECT * FROM input"}, "dependencies": ["extract_mysql"]},
            {"id": "quality_check", "type": "quality_check", "config": {"rules": []}, "dependencies": ["transform"]},
            {"id": "load_clickhouse", "type": "load", "config": {"target_type": "clickhouse"}, "dependencies": ["quality_check"]},
        ],
        "edges": [
            {"source": "extract_mysql", "target": "transform"},
            {"source": "transform", "target": "quality_check"},
            {"source": "quality_check", "target": "load_clickhouse"},
        ],
        "schedule": "0 * * * *",
        "sla_seconds": 3600,
    }


@pytest.fixture
def sample_quality_rules() -> list[dict]:
    return [
        {"rule_type": "null_rate", "column": "name", "params": {"max_null_rate": 0.05}, "threshold": 1.0, "strategy": "alert"},
        {"rule_type": "uniqueness", "column": "id", "params": {"expect_unique": True}, "threshold": 1.0, "strategy": "alert"},
        {"rule_type": "value_range", "column": "value", "params": {"min_value": 0, "max_value": 100}, "threshold": 1.0, "strategy": "block"},
    ]


@pytest.fixture
def sample_df() -> pd.DataFrame:
    return pd.DataFrame({
        "id": [1, 2, 3, 4, 5],
        "name": ["Alice", "Bob", "Charlie", "David", "Eve"],
        "value": [10.5, 20.3, 30.1, 40.7, 50.9],
        "created_at": pd.to_datetime([
            "2024-01-01", "2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"
        ]),
    })
