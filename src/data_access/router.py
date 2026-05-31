from __future__ import annotations

from typing import Any, Dict

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from src.common.models import APIResponse
from src.data_access.cache import CacheManager

router = APIRouter(prefix="/data-access", tags=["Data Access"])

_cache_manager: CacheManager | None = None


def get_cache_manager() -> CacheManager:
    global _cache_manager
    if _cache_manager is None:
        _cache_manager = CacheManager(strategy="lru", capacity=1000, default_ttl=300)
    return _cache_manager


class CacheEntryRequest(BaseModel):
    key: str
    value: Any
    ttl: float | None = None


class InvalidateRequest(BaseModel):
    pattern: str
    tags: list[str] | None = None


@router.get("/cache/stats")
async def get_cache_stats(
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    return APIResponse(data=cache.get_stats())


@router.get("/cache/{key}")
async def get_cache_value(
    key: str,
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    value = await cache.get(key)
    if value is None:
        raise HTTPException(status_code=404, detail="Cache key not found")
    return APIResponse(data={"key": key, "value": value})


@router.post("/cache", status_code=201)
async def set_cache_value(
    request: CacheEntryRequest,
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    await cache.set(request.key, request.value, request.ttl)
    return APIResponse(code=201, data={"key": request.key})


@router.delete("/cache/{key}")
async def delete_cache_value(
    key: str,
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    deleted = await cache.delete(key)
    if not deleted:
        raise HTTPException(status_code=404, detail="Cache key not found")
    return APIResponse(data={"key": key, "deleted": True})


@router.post("/cache/clear")
async def clear_cache(
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    await cache.clear()
    return APIResponse(data={"cleared": True})


@router.post("/cache/invalidate")
async def invalidate_cache(
    request: InvalidateRequest,
    cache: CacheManager = Depends(get_cache_manager),
) -> APIResponse:
    count = 0
    from src.data_access.cache import InvalidationManager
    inv_manager = InvalidationManager(cache)
    count += await inv_manager.invalidate_pattern(request.pattern)
    if request.tags:
        count += await inv_manager.invalidate_by_tags(request.tags)
    return APIResponse(data={"invalidated_count": count})
