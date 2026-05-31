import pytest
from unittest.mock import MagicMock, patch
from streamsql.modules.metadata_crawler.schema_extractor import SchemaExtractor
from streamsql.modules.metadata_crawler.stats_collector import StatsCollector
from streamsql.modules.metadata_crawler.crawler import MetadataCrawler


def test_schema_extractor_type_mapping():
    extractor = SchemaExtractor()
    assert extractor.map_type("INT") == "INTEGER"
    assert extractor.map_type("VARCHAR(255)") == "STRING"
    assert extractor.map_type("DATETIME") == "TIMESTAMP"
    assert extractor.map_type("DECIMAL(10,2)") == "FLOAT"


def test_schema_extractor_extract_from_dict(sample_table_schema):
    extractor = SchemaExtractor()
    schema = extractor.extract_from_dict(sample_table_schema)
    assert schema.name == "users"
    assert len(schema.columns) == 4
    assert schema.columns[0].name == "id"
    assert schema.columns[0].is_primary is True


def test_stats_collector_calculate_column_stats():
    collector = StatsCollector()
    values = [1, 2, 3, 4, 5, None, 7, 8, 9, 10]
    stats = collector.calculate_column_stats(values)
    assert stats["null_count"] == 1
    assert stats["unique_count"] == 9
    assert stats["min"] == 1
    assert stats["max"] == 10
    assert stats["avg"] == 5.444444444444445


def test_stats_collector_calculate_table_stats():
    collector = StatsCollector()
    data = [
        {"id": 1, "name": "Alice", "age": 25},
        {"id": 2, "name": "Bob", "age": 30},
        {"id": 3, "name": "Charlie", "age": None},
    ]
    stats = collector.calculate_table_stats(data)
    assert stats["row_count"] == 3
    assert "column_stats" in stats
    assert stats["column_stats"]["name"]["null_count"] == 0
    assert stats["column_stats"]["age"]["null_count"] == 1


def test_metadata_crawler_collect_sample_data():
    crawler = MetadataCrawler()
    data = [{"id": i, "name": f"User{i}"} for i in range(100)]
    samples = crawler.collect_sample_data(data, sample_size=10)
    assert len(samples) == 10
    assert all("id" in s for s in samples)


def test_metadata_crawler_crawl_mock():
    crawler = MetadataCrawler()
    mock_data = {
        "schema": {
            "name": "users",
            "columns": [
                {"name": "id", "type": "INT", "nullable": False, "is_primary": True},
                {"name": "name", "type": "VARCHAR(255)", "nullable": False},
            ],
        },
        "data": [
            {"id": 1, "name": "Alice"},
            {"id": 2, "name": "Bob"},
            {"id": 3, "name": "Charlie"},
        ],
    }

    result = crawler.crawl(
        data_source={"type": "mock"},
        options={"sample_size": 10},
        mock_data=mock_data,
    )

    assert result["schema"].name == "users"
    assert result["table_stats"]["row_count"] == 3
    assert len(result["sample_data"]) == 3
    assert result["status"] == "completed"
