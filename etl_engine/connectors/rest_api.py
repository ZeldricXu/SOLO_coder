import asyncio
import logging
from typing import Any

import httpx
import pandas as pd

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("rest_api")
class RESTAPISource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._client: httpx.AsyncClient | None = None

    def _get_base_url(self) -> str:
        return self.config.get("connection_params", {}).get("base_url", "")

    def _build_auth_headers(self) -> dict[str, str]:
        params = self.config.get("connection_params", {})
        auth = params.get("auth", {})
        auth_type = auth.get("type", "")

        if auth_type == "bearer":
            token = auth.get("token", "")
            return {"Authorization": f"Bearer {token}"}
        elif auth_type == "basic":
            return {}
        elif auth_type == "api_key":
            key_name = auth.get("key_name", "X-API-Key")
            key_value = auth.get("key_value", "")
            header_placement = auth.get("placement", "header")
            if header_placement == "header":
                return {key_name: key_value}
            return {}
        return {}

    def _get_basic_auth(self) -> httpx.BasicAuth | None:
        params = self.config.get("connection_params", {})
        auth = params.get("auth", {})
        if auth.get("type") == "basic":
            return httpx.BasicAuth(
                username=auth.get("username", ""),
                password=auth.get("password", ""),
            )
        return None

    async def connect(self) -> None:
        base_url = self._get_base_url()
        headers = self._build_auth_headers()
        auth = self._get_basic_auth()
        try:
            self._client = httpx.AsyncClient(
                base_url=base_url,
                headers=headers,
                auth=auth,
                timeout=30.0,
            )
            self._connected = True
            logger.info("REST API client initialized with base_url: %s", base_url)
        except Exception as e:
            logger.error("REST API client init failed: %s", e)
            raise ConnectionError(f"Failed to initialize REST API client: {e}")

    async def disconnect(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
        self._connected = False
        logger.info("REST API client closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not query:
            raise ValueError("URL path is required for RESTAPISource.read()")
        if not self.is_connected:
            await self._reconnect()

        method = kwargs.get("method", self.config.get("method", "GET")).upper()
        headers = kwargs.get("headers", {})
        params = kwargs.get("params", {})
        body = kwargs.get("body")
        pagination = kwargs.get(
            "pagination",
            self.config.get("connection_params", {}).get("pagination"),
        )

        if pagination:
            return await self._read_paginated(query, method, headers, params, body, pagination)

        return await self._read_single(query, method, headers, params, body)

    async def _read_single(
        self,
        path: str,
        method: str,
        headers: dict,
        params: dict,
        body: Any,
    ) -> pd.DataFrame:
        try:
            response = await self._client.request(
                method=method,
                url=path,
                headers=headers,
                params=params,
                json=body,
            )
            response.raise_for_status()
            data = response.json()
            df = self._json_to_dataframe(data)
            logger.info("REST API read from '%s', returned %d rows", path, len(df))
            return df
        except httpx.HTTPError as e:
            logger.error("REST API request failed: %s", e)
            self._connected = False
            raise

    async def _read_paginated(
        self,
        path: str,
        method: str,
        headers: dict,
        params: dict,
        body: Any,
        pagination: dict,
    ) -> pd.DataFrame:
        strategy = pagination.get("strategy", "offset_limit")
        data_key = pagination.get("data_key", "data")
        max_pages = pagination.get("max_pages", 100)
        all_records: list[dict] = []

        if strategy == "offset_limit":
            offset_param = pagination.get("offset_param", "offset")
            limit_param = pagination.get("limit_param", "limit")
            page_size = pagination.get("page_size", 100)
            offset = 0

            for _ in range(max_pages):
                request_params = {**params, offset_param: offset, limit_param: page_size}
                response = await self._client.request(
                    method=method,
                    url=path,
                    headers=headers,
                    params=request_params,
                    json=body,
                )
                response.raise_for_status()
                data = response.json()
                page_data = self._extract_data(data, data_key)
                if not page_data:
                    break
                all_records.extend(page_data)
                if len(page_data) < page_size:
                    break
                offset += page_size

        elif strategy == "cursor":
            cursor_param = pagination.get("cursor_param", "cursor")
            cursor_path = pagination.get("cursor_path", "next_cursor")
            cursor = None

            for _ in range(max_pages):
                request_params = {**params}
                if cursor:
                    request_params[cursor_param] = cursor
                response = await self._client.request(
                    method=method,
                    url=path,
                    headers=headers,
                    params=request_params,
                    json=body,
                )
                response.raise_for_status()
                data = response.json()
                page_data = self._extract_data(data, data_key)
                if not page_data:
                    break
                all_records.extend(page_data)
                cursor = data.get(cursor_path)
                if not cursor:
                    break

        elif strategy == "page_based":
            page_param = pagination.get("page_param", "page")
            page_size_param = pagination.get("page_size_param", "per_page")
            page_size = pagination.get("page_size", 100)
            total_pages_path = pagination.get("total_pages_path", "total_pages")

            for page_num in range(1, max_pages + 1):
                request_params = {
                    **params,
                    page_param: page_num,
                    page_size_param: page_size,
                }
                response = await self._client.request(
                    method=method,
                    url=path,
                    headers=headers,
                    params=request_params,
                    json=body,
                )
                response.raise_for_status()
                data = response.json()
                page_data = self._extract_data(data, data_key)
                if not page_data:
                    break
                all_records.extend(page_data)
                total_pages = data.get(total_pages_path, max_pages)
                if page_num >= total_pages:
                    break
        else:
            raise ValueError(f"Unsupported pagination strategy: {strategy}")

        df = pd.DataFrame(all_records)
        logger.info("REST API paginated read, total %d rows", len(df))
        return df

    def _extract_data(self, response_json: Any, data_key: str) -> list[dict]:
        if isinstance(response_json, list):
            return response_json
        data = response_json
        for key in data_key.split("."):
            if isinstance(data, dict):
                data = data.get(key, [])
            else:
                return []
        return data if isinstance(data, list) else [data] if data else []

    def _json_to_dataframe(self, data: Any) -> pd.DataFrame:
        if isinstance(data, list):
            return pd.DataFrame(data)
        if isinstance(data, dict):
            return pd.DataFrame([data])
        return pd.DataFrame()

    async def test_connection(self) -> bool:
        try:
            base_url = self._get_base_url()
            response = await self._client.get(base_url)
            return response.status_code < 500
        except Exception as e:
            logger.error("REST API connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting REST API reconnection...")
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
        raise ConnectionError("Failed to reconnect to REST API after 3 attempts")
