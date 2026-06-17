import pytest

from etl_engine.writers import (
    BaseWriter,
    BigQueryWriter,
    ClickHouseWriter,
    RedshiftWriter,
    WriteResult,
    _writer_registry,
    get_writer,
)


def test_writer_registry_has_all_types():
    expected_types = {"redshift", "bigquery", "clickhouse"}
    registered = set(_writer_registry.keys())
    assert expected_types.issubset(registered), (
        f"Missing writer types: {expected_types - registered}"
    )


@pytest.mark.parametrize(
    "writer_type, expected_cls",
    [
        ("redshift", RedshiftWriter),
        ("bigquery", BigQueryWriter),
        ("clickhouse", ClickHouseWriter),
    ],
)
def test_get_writer_factory(writer_type, expected_cls):
    if writer_type == "redshift":
        config = {"host": "h", "database": "d", "user": "u", "password": "p"}
    elif writer_type == "bigquery":
        config = {"project_id": "test-project"}
    elif writer_type == "clickhouse":
        config = {"host": "h"}
    else:
        config = {}

    instance = get_writer(writer_type, config)
    assert isinstance(instance, expected_cls)


def test_write_result_model():
    result = WriteResult(
        rows_written=100,
        table="my_table",
        strategy="insert",
        duration_seconds=1.5,
        success=True,
    )
    assert result.rows_written == 100
    assert result.table == "my_table"
    assert result.strategy == "insert"
    assert result.duration_seconds == 1.5
    assert result.success is True
    assert result.error is None


def test_write_result_model_with_error():
    result = WriteResult(
        rows_written=0,
        table="my_table",
        strategy="upsert",
        duration_seconds=0.1,
        success=False,
        error="Connection refused",
    )
    assert result.success is False
    assert result.error == "Connection refused"


def test_get_writer_unknown_type():
    with pytest.raises(ValueError, match="Unknown writer type"):
        get_writer("nonexistent", {})
