import asyncio
import logging

import pandas as pd
from pymongo import MongoClient

from .document_source import (
    DocumentAggregation,
    DocumentQuery,
    DocumentScanResult,
    DocumentSource,
    register_document_source,
)
from ..exceptions import AggregationError, DocumentQueryError

logger = logging.getLogger(__name__)


@register_document_source("mongodb")
class MongoDBDocumentSource(DocumentSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._client: MongoClient | None = None
        self._db = None
        self._scan_cursor: int = 0
        self._scan_total: int = 0

    def _get_connection_params(self) -> dict:
        params = self.config.get("connection_params", {})
        return {
            "host": params.get("host", "localhost"),
            "port": params.get("port", 27017),
            "username": params.get("username"),
            "password": params.get("password"),
            "database": params.get("database", "admin"),
            "auth_source": params.get("auth_source", "admin"),
            "max_pool_size": self.config.get("pool_size", 5),
        }

    async def connect(self) -> None:
        params = self._get_connection_params()
        database = params.pop("database")
        auth_source = params.pop("auth_source")
        for attempt in range(3):
            try:
                self._client = MongoClient(
                    **params,
                    authSource=auth_source,
                )
                self._db = self._client[database]
                self._client.admin.command("ping")
                self._connected = True
                logger.info("MongoDB document source connection established successfully")
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "MongoDB document source connect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to connect to MongoDB after 3 attempts")

    async def disconnect(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
            self._db = None
        self._connected = False
        self._scan_cursor = 0
        self._scan_total = 0
        logger.info("MongoDB document source connection closed")

    async def find(self, query: DocumentQuery) -> pd.DataFrame:
        if not self.is_connected:
            await self._reconnect()

        collection_name = self.config.get("collection")
        if not collection_name:
            raise DocumentQueryError("'collection' must be specified in config for find()")

        try:
            collection = self._db[collection_name]
            cursor = collection.find(query.filter, query.projection)

            if query.sort:
                cursor = cursor.sort(query.sort)

            cursor = cursor.skip(query.skip)
            if query.limit is not None:
                cursor = cursor.limit(query.limit)

            docs = list(cursor)
            df = pd.DataFrame(docs)
            if "_id" in df.columns:
                df["_id"] = df["_id"].astype(str)

            logger.info("MongoDB find executed, returned %d documents", len(df))
            return df
        except Exception as e:
            logger.error("MongoDB find failed: %s", e)
            self._connected = False
            raise DocumentQueryError(f"Find query failed: {e}") from e

    async def aggregate(self, pipeline: DocumentAggregation) -> pd.DataFrame:
        if not self.is_connected:
            await self._reconnect()

        collection_name = self.config.get("collection")
        if not collection_name:
            raise AggregationError("'collection' must be specified in config for aggregate()")

        try:
            collection = self._db[collection_name]
            cursor = collection.aggregate(pipeline.pipeline)
            docs = list(cursor)
            df = pd.DataFrame(docs)
            if "_id" in df.columns:
                df["_id"] = df["_id"].astype(str)

            logger.info("MongoDB aggregation executed, returned %d documents", len(df))
            return df
        except Exception as e:
            logger.error("MongoDB aggregation failed: %s", e)
            self._connected = False
            raise AggregationError(f"Aggregation pipeline failed: {e}") from e

    async def scan(self, batch_size: int = 1000, **kwargs) -> DocumentScanResult:
        if not self.is_connected:
            await self._reconnect()

        collection_name = kwargs.get("collection") or self.config.get("collection")
        if not collection_name:
            raise DocumentQueryError("'collection' must be specified for scan()")

        try:
            collection = self._db[collection_name]

            if self._scan_cursor == 0:
                self._scan_total = collection.count_documents({})

            cursor = collection.find().skip(self._scan_cursor).limit(batch_size)
            docs = list(cursor)
            docs_returned = len(docs)
            self._scan_cursor += docs_returned

            has_more = self._scan_cursor < self._scan_total
            next_cursor = str(self._scan_cursor) if has_more else None

            for doc in docs:
                if "_id" in doc:
                    doc["_id"] = str(doc["_id"])

            result = DocumentScanResult(
                documents=docs,
                total=self._scan_total,
                cursor=next_cursor,
                has_more=has_more,
            )

            logger.info(
                "MongoDB scan returned batch of %d documents (cursor=%s, total=%d, has_more=%s)",
                docs_returned, next_cursor, self._scan_total, has_more,
            )
            return result
        except Exception as e:
            logger.error("MongoDB scan failed: %s", e)
            self._connected = False
            raise DocumentQueryError(f"Scan failed: {e}") from e

    async def _reconnect(self) -> None:
        logger.info("Attempting MongoDB document source reconnection...")
        for attempt in range(3):
            try:
                await self.disconnect()
                await self.connect()
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "Reconnect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to reconnect to MongoDB after 3 attempts")
