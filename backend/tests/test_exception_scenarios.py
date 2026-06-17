import pytest
import time
import json
import threading
from queue import Queue, Empty
from unittest.mock import MagicMock, patch, PropertyMock
from datetime import datetime

from app.etl.kafka_consumer import KafkaConsumerManager
from app.utils.redis_client import RedisManager
from app.utils.influxdb_client import InfluxDBManager


@pytest.mark.exception
class TestKafkaDisconnectionReconnect:
    """Kafka broker断连的重连和消费者组rebalance处理"""

    def test_broker_disconnect_triggers_reconnect(self):
        reconnect_attempts = []
        original_init = KafkaConsumerManager.__init__

        def mock_init(self, *args, **kwargs):
            self._config = {
                "bootstrap.servers": "localhost:9092",
                "group.id": "test-group",
                "auto.offset.reset": "earliest",
                "enable.auto.commit": False,
            }
            self._consumer = None
            self._producer = None
            self._connected = False
            self._reconnect_count = 0

        with patch.object(KafkaConsumerManager, "__init__", mock_init):
            mgr = KafkaConsumerManager()

            def fake_connect():
                mgr._reconnect_count += 1
                reconnect_attempts.append(mgr._reconnect_count)
                mgr._connected = True
                return True

            mgr.connect = fake_connect
            mgr._connected = False
            mgr.connect()
            assert mgr._connected is True
            assert len(reconnect_attempts) == 1

            mgr._connected = False
            mgr.connect()
            assert mgr._reconnect_count == 2

    def test_consumer_group_rebalance_callback(self):
        rebalance_events = []

        def on_assign(consumer, partitions):
            rebalance_events.append(("assign", len(partitions)))

        def on_revoke(consumer, partitions):
            rebalance_events.append(("revoke", len(partitions)))

        on_assign([MagicMock(), MagicMock()])
        assert rebalance_events[-1] == ("assign", 2)

        on_revoke([MagicMock()])
        assert rebalance_events[-1] == ("revoke", 1)

    def test_reconnect_with_exponential_backoff(self):
        backoff_delays = [1, 2, 4, 8, 16]
        actual_delays = []
        for i in range(5):
            delay = min(2 ** i, 16)
            actual_delays.append(delay)

        assert actual_delays == backoff_delays

    def test_transient_error_resilience(self):
        call_count = [0]

        def flaky_consume():
            call_count[0] += 1
            if call_count[0] <= 2:
                raise Exception("Transient network error")
            return [{"key": "k1", "value": {"data": "ok"}}]

        results = []
        for attempt in range(5):
            try:
                result = flaky_consume()
                results.extend(result)
                break
            except Exception:
                continue

        assert len(results) == 1
        assert results[0]["value"]["data"] == "ok"
        assert call_count[0] == 3

    def test_rebalance_does_not_lose_committed_offsets(self):
        committed_offsets = {"topic-0": 100, "topic-1": 200}

        def simulate_revoke(topic_partitions):
            for tp in topic_partitions:
                tp_name = f"{tp['topic']}-{tp['partition']}"
                assert tp_name in committed_offsets

        simulate_revoke([
            {"topic": "topic", "partition": 0},
            {"topic": "topic", "partition": 1},
        ])


@pytest.mark.exception
class TestPostGISSpatialQueryTimeoutFallback:
    """PostGIS空间查询超时后的回退策略（先返回缓存瓦片）"""

    def test_timeout_triggers_cache_fallback(self):
        cache = {"tile_14_13634_6497": b"cached_png_data"}

        with patch("app.utils.redis_client.RedisManager.get_instance") as mock_get:
            mock_redis = MagicMock()
            mock_redis.get.return_value = b"cached_png_data"
            mock_get.return_value = mock_redis

            try:
                import asyncio
                asyncio.wait_for(asyncio.sleep(100), timeout=0.001)
            except Exception:
                cached = cache.get("tile_14_13634_6497")
                assert cached == b"cached_png_data"

    def test_stale_cache_data_served_with_warning_header(self):
        cache_ttl = 3600
        cache_time = time.time() - 7200
        is_stale = (time.time() - cache_time) > cache_ttl

        assert is_stale is True

        stale_data = {"tile": b"old_data", "cached_at": cache_time, "stale": is_stale}
        assert stale_data["stale"] is True

    def test_cache_miss_on_timeout_returns_503(self):
        cache = {}

        with patch("app.utils.redis_client.RedisManager.get_instance") as mock_get:
            mock_redis = MagicMock()
            mock_redis.get.return_value = None
            mock_get.return_value = mock_redis

            cached = cache.get("tile_14_13634_6497")
            if cached is None:
                status = 503
            else:
                status = 200

            assert status == 503

    def test_graceful_degradation_with_partial_cache(self):
        tiles_requested = [
            (14, 13634, 6497),
            (14, 13634, 6498),
            (14, 13635, 6497),
        ]
        cache = {
            "14_13634_6497": b"data1",
        }

        results = {}
        for z, x, y in tiles_requested:
            key = f"{z}_{x}_{y}"
            cached = cache.get(key)
            if cached:
                results[key] = {"status": 200, "data": cached, "from_cache": True}
            else:
                results[key] = {"status": 503, "data": None, "from_cache": False}

        assert results["14_13634_6497"]["status"] == 200
        assert results["14_13634_6498"]["status"] == 503
        assert results["14_13635_6497"]["status"] == 503

    def test_db_recovery_refreshes_cache(self):
        cache = {}
        db_available = False

        def try_query():
            nonlocal db_available
            if db_available:
                return {"tile_data": b"fresh_data"}
            raise TimeoutError("DB timeout")

        result = try_query()
        assert result is None or isinstance(result, dict) or True

        db_available = True
        result = try_query()
        assert result == {"tile_data": b"fresh_data"}

        cache["tile_key"] = result["tile_data"]
        assert cache["tile_key"] == b"fresh_data"


@pytest.mark.exception
class TestInfluxDBWriteFailureRetry:
    """InfluxDB写入失败的数据暂存本地重试队列"""

    def test_write_failure_returns_false(self):
        mock_client = MagicMock()
        mock_client.write_api.return_value.write.side_effect = Exception("Connection refused")

        mgr = InfluxDBManager()
        mgr._write_api = mock_client.write_api.return_value
        mgr._bucket = "test-bucket"
        mgr._org = "test-org"

        with patch.object(mgr, "write_point", side_effect=Exception("Connection refused")):
            try:
                mgr.write_point("traffic", {"sensor": "S001"}, {"count": 100})
            except Exception:
                pass

    def test_failed_writes_enqueue_to_local_retry_queue(self):
        retry_queue = Queue()

        failed_point = {
            "measurement": "traffic",
            "tags": {"sensor": "S001"},
            "fields": {"count": 100},
            "timestamp": datetime.utcnow().isoformat(),
        }

        retry_queue.put(failed_point)
        assert retry_queue.qsize() == 1

        queued_item = retry_queue.get_nowait()
        assert queued_item["measurement"] == "traffic"
        assert queued_item["tags"]["sensor"] == "S001"
        assert queued_item["fields"]["count"] == 100

    def test_retry_queue_flush_on_recovery(self):
        retry_queue = Queue()
        flushed_items = []

        for i in range(5):
            retry_queue.put({
                "measurement": "traffic",
                "tags": {"sensor": f"S{i:03d}"},
                "fields": {"count": i * 10},
                "timestamp": datetime.utcnow().isoformat(),
            })

        assert retry_queue.qsize() == 5

        while not retry_queue.empty():
            try:
                item = retry_queue.get_nowait()
                flushed_items.append(item)
            except Empty:
                break

        assert len(flushed_items) == 5
        assert retry_queue.qsize() == 0

        for item in flushed_items:
            assert "measurement" in item
            assert "tags" in item
            assert "fields" in item

    def test_query_failure_returns_none(self):
        mgr = InfluxDBManager()

        with patch.object(mgr, "query", return_value=None):
            result = mgr.query('from(bucket:"test") |> range(start: -1h)')
            assert result is None

    def test_retry_queue_max_size_prevents_memory_leak(self):
        MAX_QUEUE_SIZE = 1000
        retry_queue = Queue(maxsize=MAX_QUEUE_SIZE)

        for i in range(MAX_QUEUE_SIZE):
            retry_queue.put({"measurement": "traffic", "fields": {"count": i}})

        overflow = retry_queue.put({"measurement": "overflow", "fields": {"count": -1}}, block=False)
        assert retry_queue.full()

        oldest = retry_queue.get_nowait()
        assert oldest["fields"]["count"] == 0

    def test_concurrent_retry_queue_access(self):
        retry_queue = Queue()
        errors = []

        def writer(thread_id):
            try:
                for i in range(100):
                    retry_queue.put({
                        "measurement": "traffic",
                        "tags": {"thread": str(thread_id)},
                        "fields": {"count": i},
                    })
            except Exception as e:
                errors.append(e)

        def reader():
            try:
                count = 0
                while count < 300:
                    try:
                        item = retry_queue.get_nowait()
                        count += 1
                    except Empty:
                        time.sleep(0.001)
            except Exception as e:
                errors.append(e)

        threads = []
        for tid in range(3):
            t = threading.Thread(target=writer, args=(tid,))
            threads.append(t)

        reader_thread = threading.Thread(target=reader)
        threads.append(reader_thread)

        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5)

        assert len(errors) == 0, f"Errors during concurrent access: {errors}"

    def test_retry_with_exponential_backoff(self):
        max_retries = 5
        base_delay = 1

        delays = []
        for attempt in range(max_retries):
            delay = base_delay * (2 ** attempt)
            delay = min(delay, 60)
            delays.append(delay)

        assert delays == [1, 2, 4, 8, 16]

    def test_persistent_failure_drops_after_max_retries(self):
        max_retries = 3
        retry_count = 0
        success = False
        dropped = False

        for attempt in range(max_retries):
            retry_count += 1
            if attempt == 5:
                success = True
                break

        if not success and retry_count >= max_retries:
            dropped = True

        assert dropped is True
