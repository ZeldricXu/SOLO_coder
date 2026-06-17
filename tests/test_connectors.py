import pytest

from etl_engine.connectors import (
    BaseSource,
    KafkaSource,
    MongoDBSource,
    MySQLSource,
    PostgreSQLSource,
    RESTAPISource,
    S3Source,
    SourceConfig,
    _source_registry,
    get_source,
)


def test_registry_has_all_types():
    expected_types = {"mysql", "postgresql", "mongodb", "s3", "kafka", "rest_api"}
    registered = set(_source_registry.keys())
    assert expected_types.issubset(registered), (
        f"Missing source types: {expected_types - registered}"
    )


@pytest.mark.parametrize(
    "source_type, expected_cls",
    [
        ("mysql", MySQLSource),
        ("postgresql", PostgreSQLSource),
        ("mongodb", MongoDBSource),
        ("s3", S3Source),
        ("kafka", KafkaSource),
        ("rest_api", RESTAPISource),
    ],
)
def test_get_source_factory(source_type, expected_cls):
    instance = get_source(source_type, {})
    assert isinstance(instance, expected_cls)


def test_base_source_is_abstract():
    with pytest.raises(TypeError):
        BaseSource({})


def test_source_config_validation():
    config = SourceConfig(
        name="test_source",
        type="mysql",
        connection_params={"host": "localhost"},
        pool_size=10,
    )
    assert config.name == "test_source"
    assert config.type == "mysql"
    assert config.connection_params == {"host": "localhost"}
    assert config.pool_size == 10


def test_source_config_defaults():
    config = SourceConfig(
        name="minimal",
        type="postgresql",
        connection_params={},
    )
    assert config.pool_size == 5


def test_get_source_unknown_type():
    with pytest.raises(ValueError, match="Unknown source type"):
        get_source("nonexistent", {})
