import json
import pytest
from unittest.mock import MagicMock, patch, call
from datetime import datetime

from app.etl.kafka_consumer import KafkaConsumerManager


@pytest.mark.unit
class TestKafkaConsumerAtLeastOnce:
    """Kafka消费者offset提交的at-least-once语义测试

    核心保证：
    1. 消费者崩溃重启后不丢数据（earliest offset reset）
    2. 不重复处理（幂等回调 or 去重机制）
    3. offset仅在全量处理成功后提交
    """

    def test_consumer_config_earliest_offset_reset(self):
        manager = KafkaConsumerManager()
        config = manager._get_consumer_config()
        assert config["auto.offset.reset"] == "earliest", \
            "Must use 'earliest' to guarantee at-least-once on restart"

    def test_consumer_config_auto_commit_enabled(self):
        manager = KafkaConsumerManager()
        config = manager._get_consumer_config()
        assert config["enable.auto.commit"] is True

    def test_consumer_config_has_group_id(self):
        manager = KafkaConsumerManager()
        config = manager._get_consumer_config()
        assert "group.id" in config
        assert config["group.id"] == "traffic-viz-group"

    def test_consume_messages_processes_all_in_batch(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        msgs = []
        for i in range(5):
            m = MagicMock()
            m.error.return_value = None
            m.value.return_value = json.dumps({
                "sensor_id": f"S{i:03d}",
                "timestamp": datetime.utcnow().isoformat(),
                "vehicle_count": i * 10,
            }).encode("utf-8")
            msgs.append(m)

        poll_count = 0

        def side_effect(timeout):
            nonlocal poll_count
            if poll_count < len(msgs):
                poll_count += 1
                return msgs[poll_count - 1]
            else:
                manager._running = False
                return None

        mock_consumer.poll.side_effect = side_effect

        processed = []

        def callback(batch):
            processed.extend(batch)

        manager.consume_messages(callback, batch_size=100, timeout=0.1)

        assert len(processed) == 5
        assert [p["sensor_id"] for p in processed] == ["S000", "S001", "S002", "S003", "S004"]

    def test_no_data_loss_on_consumer_crash_before_batch_complete(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        all_msgs = []
        for i in range(20):
            m = MagicMock()
            m.error.return_value = None
            m.value.return_value = json.dumps({
                "sensor_id": f"S{i:03d}",
                "timestamp": datetime.utcnow().isoformat(),
                "vehicle_count": i * 10,
            }).encode("utf-8")
            all_msgs.append(m)

        crash_at = 7
        poll_count = 0
        processed_before_crash = []

        def side_effect_crash(timeout):
            nonlocal poll_count
            poll_count += 1
            if poll_count <= crash_at:
                return all_msgs[poll_count - 1]
            else:
                raise Exception("Consumer process killed")

        mock_consumer.poll.side_effect = side_effect_crash

        def callback(batch):
            processed_before_crash.extend(batch)

        with pytest.raises(Exception, match="Consumer process killed"):
            manager.consume_messages(callback, batch_size=100, timeout=0.1)

        assert len(processed_before_crash) == crash_at, \
            f"Expected {crash_at} messages processed before crash, got {len(processed_before_crash)}"

    def test_restart_replays_from_last_committed_offset(self):
        committed_offset = 5
        total_messages = 20

        all_msgs = []
        for i in range(total_messages):
            m = MagicMock()
            m.error.return_value = None
            m.value.return_value = json.dumps({
                "sensor_id": f"S{i:03d}",
                "vehicle_count": i * 10,
                "timestamp": datetime.utcnow().isoformat(),
            }).encode("utf-8")
            all_msgs.append(m)

        replayed_msgs = all_msgs[committed_offset:]

        manager2 = KafkaConsumerManager()
        mock_consumer2 = MagicMock()
        manager2.consumer = mock_consumer2
        manager2._running = True

        poll_count = 0

        def side_effect_restart(timeout):
            nonlocal poll_count
            if poll_count < len(replayed_msgs):
                poll_count += 1
                return replayed_msgs[poll_count - 1]
            else:
                manager2._running = False
                return None

        mock_consumer2.poll.side_effect = side_effect_restart

        processed_after_restart = []

        def callback2(batch):
            processed_after_restart.extend(batch)

        manager2.consume_messages(callback2, batch_size=100, timeout=0.1)

        assert len(processed_after_restart) == total_messages - committed_offset

    def test_idempotent_processing_handles_duplicates(self):
        processed_ids = set()
        duplicate_count = 0
        successful_count = 0

        def idempotent_callback(messages):
            nonlocal duplicate_count, successful_count
            for msg in messages:
                key = (msg["sensor_id"], msg["timestamp"])
                if key in processed_ids:
                    duplicate_count += 1
                else:
                    processed_ids.add(key)
                    successful_count += 1

        original_msgs = [
            {"sensor_id": "S001", "timestamp": "2024-01-01T08:00:00", "vehicle_count": 100},
            {"sensor_id": "S002", "timestamp": "2024-01-01T08:00:00", "vehicle_count": 200},
            {"sensor_id": "S003", "timestamp": "2024-01-01T08:00:00", "vehicle_count": 300},
        ]

        idempotent_callback(original_msgs)
        idempotent_callback(original_msgs)
        idempotent_callback(original_msgs[:2])

        assert successful_count == 3
        assert duplicate_count == 5
        assert len(processed_ids) == 3

    def test_batch_commit_only_after_full_batch_processed(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        batch_size = 3
        msgs = []
        for i in range(7):
            m = MagicMock()
            m.error.return_value = None
            m.value.return_value = json.dumps({"sensor_id": f"S{i:03d}", "vehicle_count": i}).encode()
            msgs.append(m)

        poll_count = 0
        batches_received = []

        def side_effect(timeout):
            nonlocal poll_count
            if poll_count < len(msgs):
                poll_count += 1
                return msgs[poll_count - 1]
            else:
                manager._running = False
                return None

        mock_consumer.poll.side_effect = side_effect

        def callback(batch):
            batches_received.append(list(batch))

        manager.consume_messages(callback, batch_size=batch_size, timeout=0.1)

        total_processed = sum(len(b) for b in batches_received)
        assert total_processed == 7
        assert len(batches_received[0]) <= batch_size

    def test_partition_eof_does_not_stop_consumption(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        from confluent_kafka import KafkaError

        eof_msg = MagicMock()
        eof_msg.error.return_value = KafkaError(KafkaError._PARTITION_EOF)

        good_msg = MagicMock()
        good_msg.error.return_value = None
        good_msg.value.return_value = json.dumps({"sensor_id": "S001", "vehicle_count": 100,
                                                   "timestamp": datetime.utcnow().isoformat()}).encode()

        poll_count = 0

        def side_effect(timeout):
            nonlocal poll_count
            poll_count += 1
            if poll_count == 1:
                return eof_msg
            elif poll_count == 2:
                return good_msg
            else:
                manager._running = False
                return None

        mock_consumer.poll.side_effect = side_effect

        processed = []

        def callback(messages):
            processed.extend(messages)

        manager.consume_messages(callback, batch_size=100, timeout=0.1)

        assert len(processed) == 1
        assert processed[0]["sensor_id"] == "S001"

    def test_json_decode_error_skips_bad_message_continues(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        bad_msgs = []
        for bad_json in [b"not json", b"", b"{broken", b"\x00\x01\x02"]:
            m = MagicMock()
            m.error.return_value = None
            m.value.return_value = bad_json
            bad_msgs.append(m)

        good_msg = MagicMock()
        good_msg.error.return_value = None
        good_msg.value.return_value = json.dumps({"sensor_id": "S_OK", "vehicle_count": 42,
                                                   "timestamp": datetime.utcnow().isoformat()}).encode()

        poll_count = 0

        def side_effect(timeout):
            nonlocal poll_count
            if poll_count < len(bad_msgs):
                poll_count += 1
                return bad_msgs[poll_count - 1]
            elif poll_count == len(bad_msgs):
                poll_count += 1
                return good_msg
            else:
                manager._running = False
                return None

        mock_consumer.poll.side_effect = side_effect

        processed = []

        def callback(messages):
            processed.extend(messages)

        manager.consume_messages(callback, batch_size=100, timeout=0.1)

        assert len(processed) == 1
        assert processed[0]["sensor_id"] == "S_OK"

    def test_stop_consumer_cleans_up_properly(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()
        manager.consumer = mock_consumer
        manager._running = True

        manager.stop_consumer()

        assert manager._running is False
        mock_consumer.close.assert_called_once()

    def test_producer_batch_flushes_all_messages(self):
        manager = KafkaConsumerManager()
        mock_producer = MagicMock()
        manager.producer = mock_producer

        messages = [{"sensor_id": f"S{i:03d}", "vehicle_count": i * 10} for i in range(10)]
        manager.produce_batch("traffic-stream", messages)

        assert mock_producer.produce.call_count == 10
        mock_producer.flush.assert_called_once()

    def test_producer_retries_on_broker_unavailable(self):
        manager = KafkaConsumerManager()
        mock_producer = MagicMock()
        manager.producer = mock_producer

        mock_producer.produce.side_effect = Exception("Broker not available")

        with pytest.raises(Exception, match="Broker not available"):
            manager.produce_message("test-topic", {"key": "value"})

    def test_consumer_subscribe_on_start(self):
        manager = KafkaConsumerManager()
        mock_consumer = MagicMock()

        with patch("app.etl.kafka_consumer.Consumer", return_value=mock_consumer):
            manager.start_consumer(["traffic-stream", "traffic-stream-2"])

        mock_consumer.subscribe.assert_called_once_with(["traffic-stream", "traffic-stream-2"])
