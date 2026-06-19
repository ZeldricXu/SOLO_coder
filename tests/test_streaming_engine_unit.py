from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import yaml
from pydantic import ValidationError

from etl_engine.orchestrator.dag import DAG, DAGDefinition, DAGEdge, DAGNode
from etl_engine.transform.streaming import (
    StreamSink,
    StreamingConfig,
    StreamingEngine,
    WindowConfig,
)


@pytest.mark.unit
@pytest.mark.streaming
class TestStreamingConfig:
    def test_window_config_tumbling(self):
        config = WindowConfig(type="tumbling", size_seconds=60)
        assert config.type == "tumbling"
        assert config.size_seconds == 60
        assert config.advance_seconds is None
        assert config.grace_seconds == 60

    def test_window_config_hopping(self):
        config = WindowConfig(type="hopping", size_seconds=60, advance_seconds=30)
        assert config.type == "hopping"
        assert config.size_seconds == 60
        assert config.advance_seconds == 30
        assert config.grace_seconds == 60

    def test_window_config_sliding(self):
        config = WindowConfig(type="sliding", size_seconds=120, grace_seconds=120)
        assert config.type == "sliding"
        assert config.size_seconds == 120
        assert config.grace_seconds == 120

    def test_window_config_session(self):
        config = WindowConfig(type="session", size_seconds=300)
        assert config.type == "session"
        assert config.size_seconds == 300

    def test_window_config_invalid_type(self):
        with pytest.raises(ValidationError):
            WindowConfig(type="invalid", size_seconds=60)

    def test_streaming_config_basic(self):
        config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="localhost:9092",
        )
        assert config.topic == "test_topic"
        assert config.consumer_group == "test_group"
        assert config.bootstrap_servers == "localhost:9092"
        assert config.window is None
        assert config.transformations == []
        assert config.checkpoint_interval == 5000
        assert config.sink_type == "clickhouse"
        assert config.sink_config == {}

    def test_streaming_config_with_window(self):
        window = WindowConfig(type="tumbling", size_seconds=60)
        config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="localhost:9092",
            window=window,
            sink_type="redis",
            sink_config={"host": "localhost"},
        )
        assert config.window is not None
        assert config.window.type == "tumbling"
        assert config.sink_type == "redis"
        assert config.sink_config == {"host": "localhost"}

    def test_streaming_config_with_transformations(self):
        transformations = [
            {"id": "t1", "type": "sql", "expression": "SELECT * FROM input"},
        ]
        config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="localhost:9092",
            transformations=transformations,
        )
        assert len(config.transformations) == 1
        assert config.transformations[0]["id"] == "t1"


@pytest.mark.unit
@pytest.mark.streaming
class TestStreamingConfigValidation:
    def test_streaming_mode_requires_streaming_config(self):
        dag_dict = {
            "nodes": [
                {"id": "node1", "type": "extract", "config": {}},
            ],
            "edges": [],
            "mode": "streaming",
        }
        with pytest.raises(ValidationError) as exc_info:
            DAGDefinition(**dag_dict)
        assert "streaming_config is required" in str(exc_info.value)

    def test_streaming_mode_with_streaming_config(self):
        dag_dict = {
            "nodes": [
                {"id": "node1", "type": "extract", "config": {}},
            ],
            "edges": [],
            "mode": "streaming",
            "streaming_config": {
                "topic": "test",
                "consumer_group": "test",
                "bootstrap_servers": "localhost:9092",
            },
        }
        dag = DAGDefinition(**dag_dict)
        assert dag.mode == "streaming"
        assert dag.streaming_config is not None
        assert dag.streaming_config.topic == "test"

    def test_batch_mode_without_streaming_config(self):
        dag_dict = {
            "nodes": [
                {"id": "node1", "type": "extract", "config": {}},
            ],
            "edges": [],
            "mode": "batch",
        }
        dag = DAGDefinition(**dag_dict)
        assert dag.mode == "batch"
        assert dag.streaming_config is None

    def test_batch_mode_with_streaming_node_requires_config(self):
        dag_dict = {
            "nodes": [
                {"id": "node1", "type": "streaming", "config": {}},
            ],
            "edges": [],
            "mode": "batch",
        }
        with pytest.raises(ValidationError) as exc_info:
            DAGDefinition(**dag_dict)
        assert "streaming_config is required" in str(exc_info.value)


@pytest.mark.unit
@pytest.mark.streaming
class TestWindowAggregation:
    def _create_engine_with_window(self, window_type="tumbling"):
        window = WindowConfig(type=window_type, size_seconds=60)
        config = StreamingConfig(
            topic="test",
            consumer_group="test",
            bootstrap_servers="localhost:9092",
            window=window,
        )
        with patch("faust.App"):
            return StreamingEngine(config)

    def test_aggregate_window_numeric_fields(self):
        engine = self._create_engine_with_window()
        events = [
            {"user_id": 1, "value": 10},
            {"user_id": 2, "value": 20},
        ]
        result = engine.aggregate_window(events)

        assert result["count"] == 2
        assert result["value_count"] == 2
        assert result["value_sum"] == 30.0
        assert result["value_avg"] == 15.0
        assert result["value_min"] == 10.0
        assert result["value_max"] == 20.0

    def test_aggregate_window_empty_events(self):
        engine = self._create_engine_with_window()
        result = engine.aggregate_window([])
        assert result == {}

    def test_aggregate_window_with_string_values(self):
        engine = self._create_engine_with_window()
        events = [
            {"user_id": 1, "name": "alice", "value": 10},
            {"user_id": 2, "name": "bob", "value": 20},
        ]
        result = engine.aggregate_window(events)

        assert result["count"] == 2
        assert result["value_count"] == 2
        assert result["value_sum"] == 30.0
        assert "name_count" not in result
        assert "name_sum" not in result

    def test_aggregate_window_mixed_types(self):
        engine = self._create_engine_with_window()
        events = [
            {"id": 1, "value": 10, "category": "a"},
            {"id": 2, "value": 20, "category": "b"},
            {"id": 3, "value": 30, "category": "c"},
        ]
        result = engine.aggregate_window(events)

        assert result["count"] == 3
        assert result["id_count"] == 3
        assert result["id_sum"] == 6.0
        assert result["value_count"] == 3
        assert result["value_sum"] == 60.0
        assert result["value_avg"] == 20.0


@pytest.mark.unit
@pytest.mark.streaming
class TestStreamSink:
    @pytest.mark.asyncio
    async def test_stream_sink_clickhouse_init(self):
        with patch("clickhouse_driver.Client") as mock_ch:
            mock_client = MagicMock()
            mock_ch.return_value = mock_client

            sink = StreamSink("clickhouse", {"host": "localhost", "table": "test_table"})
            assert sink.sink_type == "clickhouse"

            client = await sink._get_client()
            mock_ch.assert_called_once_with(
                host="localhost",
                port=9000,
                user="default",
                password="",
                database="default",
            )
            assert client == mock_client

    @pytest.mark.asyncio
    async def test_stream_sink_redis_init(self):
        with patch("redis.asyncio.Redis") as mock_redis:
            mock_client = AsyncMock()
            mock_redis.return_value = mock_client

            sink = StreamSink("redis", {"host": "localhost", "port": 6379})
            assert sink.sink_type == "redis"

            client = await sink._get_client()
            mock_redis.assert_called_once()
            assert client == mock_client

    @pytest.mark.asyncio
    async def test_stream_sink_kafka_init(self):
        with patch("confluent_kafka.Producer") as mock_producer:
            mock_client = MagicMock()
            mock_producer.return_value = mock_client

            sink = StreamSink("kafka", {"bootstrap_servers": "localhost:9092"})
            assert sink.sink_type == "kafka"

            client = await sink._get_client()
            mock_producer.assert_called_once()
            assert client == mock_client

    @pytest.mark.asyncio
    async def test_stream_sink_clickhouse_write(self):
        with patch("clickhouse_driver.Client") as mock_ch:
            mock_client = MagicMock()
            mock_client.execute = MagicMock()
            mock_ch.return_value = mock_client

            sink = StreamSink("clickhouse", {"table": "events"})
            data = [{"id": 1, "value": 10}, {"id": 2, "value": 20}]

            await sink.write(data)

            mock_client.execute.assert_called_once()
            call_args = mock_client.execute.call_args
            assert "INSERT INTO events VALUES" in call_args[0][0]
            assert call_args[0][1] == data

    @pytest.mark.asyncio
    async def test_stream_sink_redis_write(self):
        with patch("redis.asyncio.Redis") as mock_redis:
            mock_pipe = AsyncMock()
            mock_pipe.set = AsyncMock()
            mock_pipe.execute = AsyncMock()

            mock_client = AsyncMock()

            class MockPipeline:
                async def __aenter__(self):
                    return mock_pipe
                async def __aexit__(self, *args):
                    pass

            def mock_pipeline(transaction=True):
                return MockPipeline()

            mock_client.pipeline = mock_pipeline
            mock_redis.return_value = mock_client

            sink = StreamSink("redis", {"key_prefix": "test:", "redis_operation": "set"})
            data = {"id": "key1", "value": "test_data"}

            await sink.write(data)

            mock_pipe.set.assert_called_once()
            assert "test:key1" in mock_pipe.set.call_args[0][0]

    @pytest.mark.asyncio
    async def test_stream_sink_kafka_write(self):
        with patch("confluent_kafka.Producer") as mock_producer:
            mock_client = MagicMock()
            mock_client.produce = MagicMock()
            mock_client.flush = MagicMock()
            mock_producer.return_value = mock_client

            sink = StreamSink("kafka", {"topic": "output_topic"})
            data = [{"key": "k1", "value": "v1"}, {"key": "k2", "value": "v2"}]

            await sink.write(data)

            assert mock_client.produce.call_count == 2
            mock_client.flush.assert_called_once()

    @pytest.mark.asyncio
    async def test_stream_sink_unsupported_type(self):
        sink = StreamSink("unsupported", {})
        with pytest.raises(ValueError, match="Unsupported sink type"):
            await sink._get_client()

    @pytest.mark.asyncio
    async def test_stream_sink_empty_data(self):
        sink = StreamSink("clickhouse", {})
        with patch.object(sink, "_get_client") as mock_get_client:
            await sink.write([])
            mock_get_client.assert_not_called()


@pytest.mark.unit
@pytest.mark.streaming
class TestStreamingEngineInit:
    def test_streaming_engine_initialization(self):
        config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="kafka://localhost:9092",
        )

        with patch("etl_engine.transform.streaming.faust.App") as mock_faust_app:
            mock_app = MagicMock()
            mock_topic = MagicMock()
            mock_app.topic.return_value = mock_topic
            mock_faust_app.return_value = mock_app

            engine = StreamingEngine(config, app_name="test_app")

            assert engine.config == config
            assert engine.app_name == "test_app"
            mock_faust_app.assert_called_once_with(
                "test_app",
                broker="kafka://localhost:9092",
                consumer_group_prefix="test_group",
                autodiscover=False,
            )
            mock_app.topic.assert_called_once_with("test_topic", value_type=bytes)
            assert engine.topic == mock_topic
            assert engine._processed_count == 0
            assert engine._error_count == 0

    def test_streaming_engine_with_window(self):
        window = WindowConfig(type="tumbling", size_seconds=60)
        config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="kafka://localhost:9092",
            window=window,
        )

        with patch("etl_engine.transform.streaming.faust.App"):
            engine = StreamingEngine(config)
            assert engine.config.window is not None
            assert engine.config.window.type == "tumbling"
            assert engine.config.window.size_seconds == 60


@pytest.mark.unit
@pytest.mark.streaming
class TestStreamingTransformationApply:
    def test_apply_streaming_transformations_sql(self):
        transformations = [
            {
                "id": "t1",
                "type": "sql",
                "expression": "SELECT id, UPPER(name) as name_upper FROM input",
            },
        ]
        config = StreamingConfig(
            topic="test",
            consumer_group="test",
            bootstrap_servers="localhost:9092",
            transformations=transformations,
        )

        with patch("etl_engine.transform.streaming.faust.App"):
            engine = StreamingEngine(config)

        event = {"id": 1, "name": "alice"}
        result = engine.apply_streaming_transformations(event)

        assert result["id"] == 1
        assert result["name_upper"] == "ALICE"

    def test_apply_streaming_transformations_no_transformations(self):
        config = StreamingConfig(
            topic="test",
            consumer_group="test",
            bootstrap_servers="localhost:9092",
        )

        with patch("etl_engine.transform.streaming.faust.App"):
            engine = StreamingEngine(config)

        event = {"id": 1, "name": "alice"}
        result = engine.apply_streaming_transformations(event)

        assert result == event

    def test_apply_streaming_transformations_multiple_steps(self):
        transformations = [
            {
                "id": "t1",
                "type": "sql",
                "expression": "SELECT id, UPPER(name) as name_upper, value FROM input",
            },
            {
                "id": "t2",
                "type": "sql",
                "expression": "SELECT *, value * 2 as doubled FROM input",
            },
        ]
        config = StreamingConfig(
            topic="test",
            consumer_group="test",
            bootstrap_servers="localhost:9092",
            transformations=transformations,
        )

        with patch("etl_engine.transform.streaming.faust.App"):
            engine = StreamingEngine(config)

        event = {"id": 1, "name": "alice", "value": 10}
        result = engine.apply_streaming_transformations(event)

        assert result["id"] == 1
        assert result["name_upper"] == "ALICE"
        assert result["value"] == 10
        assert result["doubled"] == 20


@pytest.mark.unit
@pytest.mark.streaming
class TestDAGStreamingMode:
    def test_dag_with_streaming_mode(self):
        streaming_config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="localhost:9092",
        )
        nodes = [
            DAGNode(id="extract", type="extract", config={}, dependencies=[]),
            DAGNode(id="transform", type="transform", config={"sql": "SELECT * FROM input"}, dependencies=["extract"]),
            DAGNode(id="load", type="load", config={}, dependencies=["transform"]),
        ]
        edges = [
            DAGEdge(source="extract", target="transform"),
            DAGEdge(source="transform", target="load"),
        ]
        dag_def = DAGDefinition(
            nodes=nodes,
            edges=edges,
            mode="streaming",
            streaming_config=streaming_config,
        )

        assert dag_def.mode == "streaming"
        assert dag_def.streaming_config is not None
        assert dag_def.streaming_config.topic == "test_topic"

        dag = DAG(dag_def)
        assert dag.validate() is True

    def test_dag_defaults_to_batch_mode(self):
        nodes = [
            DAGNode(id="extract", type="extract", config={}, dependencies=[]),
            DAGNode(id="load", type="load", config={}, dependencies=["extract"]),
        ]
        edges = [DAGEdge(source="extract", target="load")]
        dag_dict = {
            "nodes": [n.model_dump() for n in nodes],
            "edges": [e.model_dump() for e in edges],
        }
        dag_def = DAGDefinition(**dag_dict)

        assert dag_def.mode == "batch"
        assert dag_def.streaming_config is None

        dag = DAG(dag_def)
        assert dag.validate() is True

    def test_dag_explicit_batch_mode(self):
        nodes = [
            DAGNode(id="extract", type="extract", config={}, dependencies=[]),
            DAGNode(id="load", type="load", config={}, dependencies=["extract"]),
        ]
        edges = [DAGEdge(source="extract", target="load")]
        dag_def = DAGDefinition(
            nodes=nodes,
            edges=edges,
            mode="batch",
        )

        assert dag_def.mode == "batch"
        assert dag_def.streaming_config is None

        dag = DAG(dag_def)
        assert dag.validate() is True

    def test_dag_from_yaml_without_mode(self):
        yaml_str = """
nodes:
  - id: extract
    type: extract
    config: {}
    dependencies: []
  - id: load
    type: load
    config: {}
    dependencies: [extract]
edges:
  - source: extract
    target: load
"""
        parsed = yaml.safe_load(yaml_str)
        dag_def = DAGDefinition(**parsed)
        assert dag_def.mode == "batch"

    def test_dag_streaming_mode_with_window(self):
        window = WindowConfig(type="tumbling", size_seconds=60)
        streaming_config = StreamingConfig(
            topic="test_topic",
            consumer_group="test_group",
            bootstrap_servers="localhost:9092",
            window=window,
        )
        nodes = [
            DAGNode(id="streaming_node", type="streaming", config={}, dependencies=[]),
        ]
        edges = []
        dag_def = DAGDefinition(
            nodes=nodes,
            edges=edges,
            mode="streaming",
            streaming_config=streaming_config,
        )

        assert dag_def.mode == "streaming"
        assert dag_def.streaming_config.window is not None
        assert dag_def.streaming_config.window.type == "tumbling"
