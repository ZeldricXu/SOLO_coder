import asyncio
import json
import logging

import pandas as pd

from .base import BaseSource, register_source
from .mongodb_document import MongoDBDocumentSource
from .document_source import DocumentAggregation, DocumentQuery

logger = logging.getLogger(__name__)


@register_source("mongodb")
class MongoDBSource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._doc_source = MongoDBDocumentSource(config)

    @property
    def is_connected(self) -> bool:
        return self._doc_source.is_connected

    async def connect(self) -> None:
        await self._doc_source.connect()
        self._connected = self._doc_source.is_connected

    async def disconnect(self) -> None:
        await self._doc_source.disconnect()
        self._connected = self._doc_source.is_connected

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not self.is_connected:
            await self._reconnect()

        collection_name = kwargs.get("collection")
        pipeline = kwargs.get("pipeline")

        try:
            if pipeline:
                if isinstance(pipeline, str):
                    pipeline = json.loads(pipeline)
                agg_query = DocumentAggregation(pipeline=pipeline)
                return await self._doc_source.aggregate(agg_query)
            else:
                filter_dict = {}
                if query:
                    if isinstance(query, str):
                        filter_dict = json.loads(query)
                    elif isinstance(query, dict):
                        filter_dict = query

                doc_query = DocumentQuery(
                    filter=filter_dict,
                    projection=kwargs.get("projection"),
                    limit=kwargs.get("limit"),
                    skip=kwargs.get("skip", 0),
                    sort=kwargs.get("sort"),
                )

                if collection_name:
                    original_collection = self._doc_source.config.get("collection")
                    self._doc_source.config["collection"] = collection_name
                    try:
                        result = await self._doc_source.find(doc_query)
                    finally:
                        if original_collection:
                            self._doc_source.config["collection"] = original_collection
                    return result
                else:
                    return await self._doc_source.find(doc_query)
        except Exception as e:
            logger.error("MongoDB read failed: %s", e)
            self._connected = False
            raise

    async def test_connection(self) -> bool:
        if not self._doc_source.is_connected or self._doc_source._client is None:
            return False
        try:
            self._doc_source._client.admin.command("ping")
            return True
        except Exception as e:
            logger.error("MongoDB connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting MongoDB reconnection...")
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
