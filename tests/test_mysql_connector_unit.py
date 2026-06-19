import pytest
import pandas as pd
from unittest.mock import MagicMock, patch, AsyncMock

from etl_engine.connectors.mysql import MySQLSource
from etl_engine.connectors import get_source


@pytest.mark.unit
class TestMySQLConnectorQuery:
    @pytest.mark.asyncio
    async def test_read_returns_correct_dataframe(self):
        config = {
            "connection_params": {
                "host": "localhost",
                "port": 3306,
                "user": "root",
                "password": "test",
                "database": "test_db",
            },
            "pool_size": 5,
        }
        source = MySQLSource(config)

        mock_pool = MagicMock()
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_cursor.fetchall.return_value = [(1, "Alice", 10.5), (2, "Bob", 20.3)]
        mock_cursor.description = [("id",), ("name",), ("value",)]
        mock_conn.cursor.return_value = mock_cursor
        mock_conn.__enter__.return_value = mock_conn
        mock_pool.connection.return_value = mock_conn

        source._pool = mock_pool
        source._connected = True

        with patch("pandas.read_sql") as mock_read_sql:
            mock_read_sql.return_value = pd.DataFrame(
                [(1, "Alice", 10.5), (2, "Bob", 20.3)],
                columns=["id", "name", "value"],
            )
            df = await source.read("SELECT * FROM test")

        assert df.shape[0] == 2
        assert list(df.columns) == ["id", "name", "value"]
        assert df["id"].dtype in ("int64", "int32", "Int64")
        assert df.iloc[0]["name"] == "Alice"


@pytest.mark.unit
class TestMySQLConnectorTestConnection:
    @pytest.mark.asyncio
    async def test_connection_returns_true(self):
        config = {
            "connection_params": {
                "host": "localhost",
                "port": 3306,
                "user": "root",
                "password": "test",
                "database": "test_db",
            },
            "pool_size": 5,
        }
        source = MySQLSource(config)

        mock_pool = MagicMock()
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_cursor.execute.return_value = None
        mock_conn.cursor.return_value = mock_cursor
        mock_conn.__enter__.return_value = mock_conn
        mock_pool.connection.return_value = mock_conn

        source._pool = mock_pool
        source._connected = True

        result = await source.test_connection()
        assert result is True


@pytest.mark.unit
class TestMySQLConnectorRegistry:
    def test_get_source_returns_mysql_instance(self):
        config = {
            "connection_params": {
                "host": "localhost",
                "port": 3306,
                "user": "root",
                "password": "test",
                "database": "test_db",
            },
            "pool_size": 5,
        }
        source = get_source("mysql", config)
        assert isinstance(source, MySQLSource)
        assert source.config == config


@pytest.mark.unit
class TestMySQLConnectorConnectionPool:
    @pytest.mark.asyncio
    async def test_pool_size_parameter_respected(self):
        custom_pool_size = 10
        config = {
            "connection_params": {
                "host": "localhost",
                "port": 3306,
                "user": "root",
                "password": "test",
                "database": "test_db",
            },
            "pool_size": custom_pool_size,
        }
        source = MySQLSource(config)

        with patch("etl_engine.connectors.mysql.PooledDB") as mock_pooled_db:
            mock_pool = MagicMock()
            mock_conn = MagicMock()
            mock_cursor = MagicMock()
            mock_cursor.execute.return_value = None
            mock_conn.cursor.return_value = mock_cursor
            mock_conn.__enter__.return_value = mock_conn
            mock_pool.connection.return_value = mock_conn
            mock_pooled_db.return_value = mock_pool

            await source.connect()

            mock_pooled_db.assert_called_once()
            call_kwargs = mock_pooled_db.call_args
            assert call_kwargs.kwargs["maxconnections"] == custom_pool_size
