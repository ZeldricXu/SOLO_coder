import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.cache_module import get_cache, CacheStrategy


def test_cache_set_get():
    cache = get_cache()
    cache.set("test_key", "test_value")
    assert cache.get("test_key") == "test_value"


def test_cache_delete():
    cache = get_cache()
    cache.set("delete_key", "value")
    cache.delete("delete_key")
    assert cache.get("delete_key") is None


def test_cache_ttl():
    cache = get_cache()
    cache.set("ttl_key", "value", ttl=1)
    assert cache.get("ttl_key") == "value"


def test_cache_stats():
    cache = get_cache()
    stats = cache.get_stats()
    assert "size" in stats
    assert "strategy" in stats


def test_cache_invalidate_tag():
    cache = get_cache()
    cache.set("tag_key1", "value1", tags=["tag1"])
    cache.set("tag_key2", "value2", tags=["tag2"])
    count = cache.invalidate_tag("tag1")
    assert count >= 1
    assert cache.get("tag_key1") is None
