import asyncio
import io
import logging

import boto3
import pandas as pd
from botocore.exceptions import ClientError

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("s3")
class S3Source(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._client = None
        self._bucket: str = ""

    def _get_client_params(self) -> dict:
        params = self.config.get("connection_params", {})
        return {
            "aws_access_key_id": params.get("aws_access_key_id"),
            "aws_secret_access_key": params.get("aws_secret_access_key"),
            "region_name": params.get("region_name", "us-east-1"),
            "endpoint_url": params.get("endpoint_url"),
            "bucket": params.get("bucket", ""),
        }

    async def connect(self) -> None:
        params = self._get_client_params()
        self._bucket = params.pop("bucket")
        for attempt in range(3):
            try:
                self._client = boto3.client("s3", **params)
                self._client.head_bucket(Bucket=self._bucket)
                self._connected = True
                logger.info("S3 connection established for bucket: %s", self._bucket)
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "S3 connect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to connect to S3 after 3 attempts")

    async def disconnect(self) -> None:
        self._client = None
        self._connected = False
        logger.info("S3 connection closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not query:
            raise ValueError("S3 key/prefix is required for S3Source.read()")
        if not self.is_connected:
            await self._reconnect()

        file_format = kwargs.get("file_format", "csv").lower()
        encoding = kwargs.get("encoding", "utf-8")
        compression = kwargs.get("compression")

        keys = await self._resolve_keys(query)

        frames: list[pd.DataFrame] = []
        for key in keys:
            df = await self._read_single_key(key, file_format, encoding, compression)
            frames.append(df)

        if not frames:
            return pd.DataFrame()

        result = pd.concat(frames, ignore_index=True)
        logger.info("S3 read completed, total %d rows from %d files", len(result), len(keys))
        return result

    async def _resolve_keys(self, prefix: str) -> list[str]:
        if not prefix.endswith("/"):
            return [prefix]
        keys = []
        paginator = self._client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self._bucket, Prefix=prefix):
            for obj in page.get("Contents", []):
                keys.append(obj["Key"])
        return keys

    async def _read_single_key(
        self,
        key: str,
        file_format: str,
        encoding: str,
        compression: str | None,
    ) -> pd.DataFrame:
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=key)
            body = response["Body"]

            if file_format == "csv":
                return pd.read_csv(body, encoding=encoding, compression=compression)
            elif file_format == "parquet":
                data = body.read()
                return pd.read_parquet(io.BytesIO(data))
            elif file_format == "json":
                data = body.read()
                return pd.read_json(io.BytesIO(data), encoding=encoding, compression=compression)
            else:
                raise ValueError(f"Unsupported file format: {file_format}")
        except ClientError as e:
            logger.error("S3 read failed for key '%s': %s", key, e)
            raise

    async def test_connection(self) -> bool:
        try:
            self._client.head_bucket(Bucket=self._bucket)
            return True
        except Exception as e:
            logger.error("S3 connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting S3 reconnection...")
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
        raise ConnectionError("Failed to reconnect to S3 after 3 attempts")
