import asyncio
import json
import logging

import pandas as pd
from pymongo import MongoClient

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("mongodb")
class MongoDBSource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._client: MongoClient | None = None
        self._db = None

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
                logger.info("MongoDB connection established successfully")
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "MongoDB connect attempt %d/3 failed: %s. Retrying in %ds...",
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
        logger.info("MongoDB connection closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not self.is_connected:
            await self._reconnect()

        collection_name = kwargs.get("collection")
        if not collection_name:
            raise ValueError("'collection' parameter is required for MongoDBSource.read()")

        pipeline = kwargs.get("pipeline")
        collection = self._db[collection_name]

        try:
            if pipeline:
                if isinstance(pipeline, str):
                    pipeline = json.loads(pipeline)
                cursor = collection.aggregate(pipeline)
            else:
                filter_dict = {}
                if query:
                    if isinstance(query, str):
                        filter_dict = json.loads(query)
                    elif isinstance(query, dict):
                        filter_dict = query
                cursor = collection.find(filter_dict)

            docs = list(cursor)
            df = pd.DataFrame(docs)
            if "_id" in df.columns:
                df["_id"] = df["_id"].astype(str)

            logger.info("MongoDB read executed, returned %d documents", len(df))
            return df
        except Exception as e:
            logger.error("MongoDB read failed: %s", e)
            self._connected = False
            raise

    async def test_connection(self) -> bool:
        try:
            self._client.admin.command("ping")
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
