import pytest
import pandas as pd
from unittest.mock import AsyncMock, MagicMock, patch

from etl_engine.connectors.document_source import (
    DocumentQuery,
    DocumentAggregation,
    DocumentScanResult,
    DocumentSource,
    register_document_source,
    get_document_source,
    _document_registry,
)
from etl_engine.connectors.mongodb_document import MongoDBDocumentSource
from etl_engine.connectors.dynamodb_document import DynamoDBDocumentSource
from etl_engine.connectors.elasticsearch_document import ElasticsearchDocumentSource
from etl_engine.connectors.mongodb import MongoDBSource
from etl_engine.exceptions import DocumentQueryError


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentQueryModel:
    def test_document_query_default_values(self):
        query = DocumentQuery(filter={"status": "active"})
        assert query.filter == {"status": "active"}
        assert query.projection is None
        assert query.limit is None
        assert query.skip == 0
        assert query.sort is None

    def test_document_query_full_params(self):
        query = DocumentQuery(
            filter={"age": {"$gte": 18}},
            projection={"name": 1, "age": 1, "_id": 0},
            limit=100,
            skip=20,
            sort=[("age", -1), ("name", 1)],
        )
        assert query.filter == {"age": {"$gte": 18}}
        assert query.projection == {"name": 1, "age": 1, "_id": 0}
        assert query.limit == 100
        assert query.skip == 20
        assert query.sort == [("age", -1), ("name", 1)]

    def test_document_query_filter_required(self):
        with pytest.raises(Exception):
            DocumentQuery()


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentAggregationModel:
    def test_document_aggregation_pipeline(self):
        pipeline = [
            {"$match": {"status": "active"}},
            {"$group": {"_id": "$category", "count": {"$sum": 1}}},
            {"$sort": {"count": -1}},
        ]
        agg = DocumentAggregation(pipeline=pipeline)
        assert agg.pipeline == pipeline
        assert len(agg.pipeline) == 3

    def test_document_aggregation_empty_pipeline(self):
        agg = DocumentAggregation(pipeline=[])
        assert agg.pipeline == []


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentScanResultModel:
    def test_document_scan_result_default_values(self):
        result = DocumentScanResult(
            documents=[{"_id": "1", "name": "test"}],
            total=1,
        )
        assert result.documents == [{"_id": "1", "name": "test"}]
        assert result.total == 1
        assert result.cursor is None
        assert result.has_more is False

    def test_document_scan_result_full_params(self):
        result = DocumentScanResult(
            documents=[{"_id": "1"}, {"_id": "2"}],
            total=100,
            cursor="2",
            has_more=True,
        )
        assert len(result.documents) == 2
        assert result.total == 100
        assert result.cursor == "2"
        assert result.has_more is True


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentSourceRegistry:
    def test_get_mongodb_source(self):
        config = {"connection_params": {"host": "localhost", "database": "test"}}
        source = get_document_source("mongodb", config)
        assert isinstance(source, MongoDBDocumentSource)

    def test_register_document_source_decorator(self):
        @register_document_source("test_source")
        class TestSource(DocumentSource):
            async def connect(self):
                pass

            async def disconnect(self):
                pass

            async def find(self, query):
                return pd.DataFrame()

            async def aggregate(self, pipeline):
                return pd.DataFrame()

            async def scan(self, batch_size=1000, **kwargs):
                return DocumentScanResult(documents=[], total=0)

        assert "test_source" in _document_registry
        assert _document_registry["test_source"] == TestSource

        config = {}
        source = get_document_source("test_source", config)
        assert isinstance(source, TestSource)

    def test_get_unknown_source_raises_error(self):
        with pytest.raises(ValueError, match="Unknown document source type"):
            get_document_source("unknown_source", {})


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentSourceInterface:
    def test_cannot_instantiate_abstract_class(self):
        with pytest.raises(TypeError):
            DocumentSource({})

    def test_abstract_methods_exist(self):
        abstract_methods = DocumentSource.__abstractmethods__
        assert "connect" in abstract_methods
        assert "disconnect" in abstract_methods
        assert "find" in abstract_methods
        assert "aggregate" in abstract_methods
        assert "scan" in abstract_methods


@pytest.mark.unit
@pytest.mark.nosql
class TestMongoDBDocumentSourceInit:
    def test_mongodb_source_init(self):
        config = {
            "connection_params": {
                "host": "mongodb.example.com",
                "port": 27018,
                "username": "user",
                "password": "pass",
                "database": "mydb",
                "auth_source": "admin",
            },
            "collection": "users",
            "pool_size": 10,
        }
        source = MongoDBDocumentSource(config)
        assert source.config == config
        assert source.is_connected is False
        assert source._client is None
        assert source._db is None

    def test_mongodb_source_default_connection_params(self):
        config = {"collection": "test"}
        source = MongoDBDocumentSource(config)
        params = source._get_connection_params()
        assert params["host"] == "localhost"
        assert params["port"] == 27017
        assert params["database"] == "admin"
        assert params["auth_source"] == "admin"
        assert params["max_pool_size"] == 5


@pytest.mark.unit
@pytest.mark.nosql
class TestMongoDBDocumentFind:
    @pytest.mark.asyncio
    async def test_find_returns_dataframe(self):
        config = {
            "collection": "users",
            "connection_params": {"database": "testdb"},
        }
        source = MongoDBDocumentSource(config)
        source._connected = True

        mock_cursor = MagicMock()
        mock_cursor.sort.return_value = mock_cursor
        mock_cursor.skip.return_value = mock_cursor
        mock_cursor.limit.return_value = mock_cursor
        mock_cursor.__iter__.return_value = iter([{"_id": "1", "name": "test", "age": 25}])

        mock_collection = MagicMock()
        mock_collection.find.return_value = mock_cursor

        mock_db = MagicMock()
        mock_db.__getitem__.return_value = mock_collection
        source._db = mock_db

        query = DocumentQuery(
            filter={"active": True},
            projection={"name": 1, "age": 1},
            limit=10,
            skip=5,
            sort=[("name", 1)],
        )

        result = await source.find(query)

        assert isinstance(result, pd.DataFrame)
        assert len(result) == 1
        assert list(result.columns) == ["_id", "name", "age"]
        assert result.iloc[0]["name"] == "test"
        assert result.iloc[0]["age"] == 25
        assert result.iloc[0]["_id"] == "1"

    @pytest.mark.asyncio
    async def test_find_without_collection_raises_error(self):
        config = {"connection_params": {"database": "testdb"}}
        source = MongoDBDocumentSource(config)
        source._connected = True
        source._db = MagicMock()

        query = DocumentQuery(filter={})
        with pytest.raises(DocumentQueryError, match="collection.*must be specified"):
            await source.find(query)


@pytest.mark.unit
@pytest.mark.nosql
class TestMongoDBDocumentAggregate:
    @pytest.mark.asyncio
    async def test_aggregate_returns_dataframe(self):
        config = {
            "collection": "orders",
            "connection_params": {"database": "testdb"},
        }
        source = MongoDBDocumentSource(config)
        source._connected = True

        mock_cursor = MagicMock()
        mock_cursor.__iter__.return_value = iter([
            {"_id": "cat1", "total": 100},
            {"_id": "cat2", "total": 200},
        ])

        mock_collection = MagicMock()
        mock_collection.aggregate.return_value = mock_cursor

        mock_db = MagicMock()
        mock_db.__getitem__.return_value = mock_collection
        source._db = mock_db

        pipeline = [
            {"$group": {"_id": "$category", "total": {"$sum": "$amount"}}},
        ]
        agg = DocumentAggregation(pipeline=pipeline)
        result = await source.aggregate(agg)

        assert isinstance(result, pd.DataFrame)
        assert len(result) == 2
        assert list(result.columns) == ["_id", "total"]
        assert result.iloc[0]["total"] == 100
        assert result.iloc[1]["total"] == 200


@pytest.mark.unit
@pytest.mark.nosql
class TestMongoDBDocumentScan:
    @pytest.mark.asyncio
    async def test_scan_returns_scan_result(self):
        config = {
            "collection": "users",
            "connection_params": {"database": "testdb"},
        }
        source = MongoDBDocumentSource(config)
        source._connected = True

        batch1 = [{"_id": "1", "name": "user1"}, {"_id": "2", "name": "user2"}]
        batch2 = [{"_id": "3", "name": "user3"}, {"_id": "4", "name": "user4"}]
        batch3 = [{"_id": "5", "name": "user5"}]

        mock_cursor1 = MagicMock()
        mock_cursor1.skip.return_value = mock_cursor1
        mock_cursor1.limit.return_value = mock_cursor1
        mock_cursor1.__iter__.return_value = iter(batch1)
        mock_cursor1.__len__.return_value = len(batch1)

        mock_collection = MagicMock()
        mock_collection.count_documents.return_value = 5
        mock_collection.find.return_value = mock_cursor1

        mock_db = MagicMock()
        mock_db.__getitem__.return_value = mock_collection
        source._db = mock_db

        result = await source.scan(batch_size=2)

        assert isinstance(result, DocumentScanResult)
        assert len(result.documents) == 2
        assert result.total == 5
        assert result.has_more is True
        assert result.cursor == "2"
        assert result.documents[0]["_id"] == "1"

        mock_cursor2 = MagicMock()
        mock_cursor2.skip.return_value = mock_cursor2
        mock_cursor2.limit.return_value = mock_cursor2
        mock_cursor2.__iter__.return_value = iter(batch2)
        mock_collection.find.return_value = mock_cursor2

        result2 = await source.scan(batch_size=2)
        assert len(result2.documents) == 2
        assert result2.has_more is True
        assert result2.cursor == "4"

        mock_cursor3 = MagicMock()
        mock_cursor3.skip.return_value = mock_cursor3
        mock_cursor3.limit.return_value = mock_cursor3
        mock_cursor3.__iter__.return_value = iter(batch3)
        mock_collection.find.return_value = mock_cursor3

        result3 = await source.scan(batch_size=2)
        assert len(result3.documents) == 1
        assert result3.has_more is False
        assert result3.cursor is None


@pytest.mark.unit
@pytest.mark.nosql
class TestDocumentSourceBackwardCompat:
    @pytest.mark.asyncio
    async def test_mongodb_base_source_read(self):
        config = {
            "type": "mongodb",
            "name": "test_mongo",
            "connection_params": {
                "host": "localhost",
                "database": "test",
            },
            "collection": "users",
        }

        source = MongoDBSource(config)
        source._connected = True

        mock_cursor = MagicMock()
        mock_cursor.sort.return_value = mock_cursor
        mock_cursor.skip.return_value = mock_cursor
        mock_cursor.limit.return_value = mock_cursor
        mock_cursor.__iter__.return_value = iter([
            {"_id": "1", "name": "test"},
        ])

        mock_collection = MagicMock()
        mock_collection.find.return_value = mock_cursor

        mock_db = MagicMock()
        mock_db.__getitem__.return_value = mock_collection
        source._doc_source._db = mock_db
        source._doc_source._connected = True

        query = '{"active": true}'
        result = await source.read(query)

        assert isinstance(result, pd.DataFrame)
        assert len(result) == 1
        assert result.iloc[0]["name"] == "test"


@pytest.mark.unit
@pytest.mark.nosql
class TestDynamoDBPlaceholderExists:
    def test_dynamodb_placeholder_exists(self):
        assert DynamoDBDocumentSource is not None
        assert issubclass(DynamoDBDocumentSource, DocumentSource)

    def test_dynamodb_is_registered(self):
        assert "dynamodb" in _document_registry
        assert _document_registry["dynamodb"] == DynamoDBDocumentSource


@pytest.mark.unit
@pytest.mark.nosql
class TestElasticsearchPlaceholderExists:
    def test_elasticsearch_placeholder_exists(self):
        assert ElasticsearchDocumentSource is not None
        assert issubclass(ElasticsearchDocumentSource, DocumentSource)

    def test_elasticsearch_is_registered(self):
        assert "elasticsearch" in _document_registry
        assert _document_registry["elasticsearch"] == ElasticsearchDocumentSource
