import pytest
from unittest.mock import MagicMock
from streamsql.modules.cdc_capture.binlog_parser import BinlogParser
from streamsql.modules.cdc_capture.event_serializer import EventSerializer, SerializationFormat
from streamsql.modules.cdc_capture.output_adapter import OutputAdapter, OutputType
from streamsql.modules.cdc_capture.capture import CDCCapture


def test_binlog_parser_parse_mysql_event(sample_cdc_event):
    parser = BinlogParser(db_type="mysql")
    parsed = parser.parse_event(sample_cdc_event)
    assert parsed["type"] == "insert"
    assert parsed["table"] == "users"
    assert parsed["data"]["id"] == 1
    assert "schema" in parsed


def test_binlog_parser_filter_events():
    parser = BinlogParser(db_type="mysql")
    events = [
        {"type": "insert", "table": "users", "data": {}},
        {"type": "update", "table": "orders", "data": {}},
        {"type": "delete", "table": "users", "data": {}},
    ]
    filtered = parser.filter_events(events, tables=["users"])
    assert len(filtered) == 2
    assert all(e["table"] == "users" for e in filtered)


def test_event_serializer_json(sample_cdc_event):
    serializer = EventSerializer(format=SerializationFormat.JSON)
    serialized = serializer.serialize(sample_cdc_event)
    assert isinstance(serialized, str)
    assert '"type": "insert"' in serialized


def test_event_serializer_compressed_json(sample_cdc_event):
    serializer = EventSerializer(format=SerializationFormat.COMPRESSED_JSON)
    serialized = serializer.serialize(sample_cdc_event)
    assert isinstance(serialized, bytes)
    deserialized = serializer.deserialize(serialized)
    assert deserialized["type"] == "insert"


def test_event_serializer_pickle(sample_cdc_event):
    serializer = EventSerializer(format=SerializationFormat.PICKLE)
    serialized = serializer.serialize(sample_cdc_event)
    assert isinstance(serialized, bytes)
    deserialized = serializer.deserialize(serialized)
    assert deserialized["type"] == "insert"


def test_output_adapter_console(sample_cdc_event, capsys):
    adapter = OutputAdapter(output_type=OutputType.CONSOLE)
    adapter.output(sample_cdc_event)
    captured = capsys.readouterr()
    assert "CDC Event" in captured.out


def test_output_adapter_file(tmp_path, sample_cdc_event):
    output_file = tmp_path / "cdc_output.jsonl"
    adapter = OutputAdapter(
        output_type=OutputType.FILE,
        config={"file_path": str(output_file)},
    )
    adapter.output(sample_cdc_event)
    adapter.close()
    assert output_file.exists()
    content = output_file.read_text()
    assert '"type": "insert"' in content


def test_cdc_capture_process_events(sample_cdc_event):
    capture = CDCCapture(
        db_type="mysql",
        output_type=OutputType.MEMORY,
        serializer_format=SerializationFormat.JSON,
    )
    events = [sample_cdc_event, sample_cdc_event]
    processed = capture.process_events(events)
    assert len(processed) == 2
    assert len(capture.get_memory_output()) == 2


def test_cdc_capture_start_stop():
    capture = CDCCapture(
        db_type="mysql",
        output_type=OutputType.MEMORY,
    )
    assert capture.is_running() is False
    capture.start()
    assert capture.is_running() is True
    capture.stop()
    assert capture.is_running() is False
