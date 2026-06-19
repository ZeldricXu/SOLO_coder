import asyncio
import json
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pandas as pd
import pytest
import pytest_asyncio
import yaml
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.config import settings
from etl_engine.models.base import Base


class MockConnectTimeoutError(Exception):
    pass


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


@pytest.fixture
def sample_yaml_workflow() -> str:
    yaml_str = """
workflow:
  name: user_pipeline
  tasks:
    - id: extract_users
      type: extract
      config:
        source_type: mysql
        query: "SELECT * FROM users"
      dependencies: []
    - id: clean_users
      type: transform
      config:
        sql: "SELECT id, name, email FROM input WHERE email IS NOT NULL"
      dependencies:
        - extract_users
    - id: load_users
      type: load
      config:
        target_type: postgresql
        table: cleaned_users
      dependencies:
        - clean_users
"""
    parsed = yaml.safe_load(yaml_str)
    assert "workflow" in parsed
    assert len(parsed["workflow"]["tasks"]) == 3
    return yaml_str


@pytest.fixture
def sample_yaml_with_cycle() -> str:
    yaml_str = """
workflow:
  name: cyclic_workflow
  tasks:
    - id: task_a
      type: extract
      config: {}
      dependencies:
        - task_c
    - id: task_b
      type: transform
      config: {}
      dependencies:
        - task_a
    - id: task_c
      type: load
      config: {}
      dependencies:
        - task_b
"""
    parsed = yaml.safe_load(yaml_str)
    assert "workflow" in parsed
    assert len(parsed["workflow"]["tasks"]) == 3
    return yaml_str


@pytest.fixture
def mock_mysql_connection():
    sample_data = [
        {"id": 1, "name": "Alice", "value": 10},
        {"id": 2, "name": "Bob", "value": 20},
        {"id": 3, "name": "Charlie", "value": 30},
    ]

    with patch("pymysql.connect") as mock_connect:
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_cursor.fetchall.return_value = sample_data
        mock_cursor.description = [
            ("id",), ("name",), ("value",)
        ]
        mock_conn.cursor.return_value = mock_cursor
        mock_conn.__enter__.return_value = mock_conn
        mock_connect.return_value = mock_conn
        yield mock_conn


@pytest.fixture
def mock_redis_client():
    store = {}

    def mock_get(key):
        return store.get(key)

    def mock_set(key, value, ex=None):
        store[key] = value
        return True

    def mock_exists(key):
        return key in store

    mock_client = MagicMock()
    mock_client.get = mock_get
    mock_client.set = mock_set
    mock_client.exists = mock_exists
    mock_client.delete = lambda key: store.pop(key, None)
    return mock_client


@pytest.fixture
def expectation_suite_json() -> dict:
    return {
        "expectation_suite_name": "basic_suite",
        "expectations": [
            {
                "expectation_type": "expect_column_values_to_not_be_null",
                "kwargs": {"column": "name"},
            },
            {
                "expectation_type": "expect_column_values_to_be_unique",
                "kwargs": {"column": "id"},
            },
            {
                "expectation_type": "expect_column_values_to_be_between",
                "kwargs": {"column": "value", "min_value": 0, "max_value": 100},
            },
        ],
        "data_asset_type": "Dataset",
        "meta": {},
    }


@pytest.fixture
def sample_transformations() -> list[dict]:
    return [
        {
            "id": "t1",
            "type": "sql",
            "sql": "SELECT id, UPPER(name) as name_upper, value FROM input",
        },
        {
            "id": "t2",
            "type": "udf",
            "inline_code": """
def transform(df):
    df['computed_value'] = df['value'] * 2
    return df
""",
        },
        {
            "id": "t3",
            "type": "sql",
            "sql": "SELECT * FROM input WHERE value > 20",
        },
    ]
