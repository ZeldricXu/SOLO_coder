import sys
import time
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from searchengine.modules.cache_module import cache_module


class TestCacheModule:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
    
    def test_set_and_get(self):
        key = "test:key:1"
        value = {"data": "test_value"}
        
        result = cache_module.set(key, value)
        assert result is True
        
        retrieved = cache_module.get(key)
        assert retrieved == value
    
    def test_get_nonexistent_key(self):
        result = cache_module.get("nonexistent:key")
        assert result is None
    
    def test_delete_key(self):
        key = "test:key:2"
        cache_module.set(key, "value")
        
        assert cache_module.exists(key) is True
        
        result = cache_module.delete(key)
        assert result is True
        assert cache_module.exists(key) is False
    
    def test_delete_nonexistent_key(self):
        result = cache_module.delete("nonexistent:key")
        assert result is False
    
    def test_exists(self):
        key = "test:key:3"
        assert cache_module.exists(key) is False
        
        cache_module.set(key, "value")
        assert cache_module.exists(key) is True
    
    def test_ttl_expiration(self):
        key = "test:ttl:1"
        cache_module.set(key, "value", ttl=1)
        
        assert cache_module.exists(key) is True
        
        time.sleep(1.1)
        
        assert cache_module.exists(key) is False
    
    def test_get_or_set(self):
        key = "test:getorset:1"
        call_count = [0]
        
        def expensive_func():
            call_count[0] += 1
            return {"result": "computed"}
        
        result1 = cache_module.get_or_set(key, expensive_func)
        assert call_count[0] == 1
        
        result2 = cache_module.get_or_set(key, expensive_func)
        assert call_count[0] == 1
        assert result1 == result2
    
    def test_disable_cache(self):
        cache_module.disable()
        
        key = "test:disabled:1"
        result = cache_module.set(key, "value")
        assert result is False
        
        assert cache_module.get(key) is None
        
        cache_module.enable()
    
    def test_clear_cache(self):
        for i in range(10):
            cache_module.set(f"test:clear:{i}", f"value{i}")
        
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 10
        
        count = cache_module.clear()
        assert count == 10
        
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 0
    
    def test_cache_stats(self):
        cache_module.set("test:stats:1", "value1")
        cache_module.set("test:stats:2", "value2")
        
        cache_module.get("test:stats:1")
        cache_module.get("test:stats:1")
        
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 2
        assert stats["total_hits"] >= 2
    
    def test_get_key_info(self):
        key = "test:info:1"
        cache_module.set(key, "value", ttl=100)
        
        info = cache_module.get_key_info(key)
        assert info is not None
        assert info["key"] == key
        assert info["hits"] == 0
        assert info["remaining_ttl"] <= 100
    
    def test_clean_expired(self):
        cache_module.set("test:expired:1", "value1", ttl=1)
        cache_module.set("test:persist:1", "value2", ttl=1000)
        
        time.sleep(1.1)
        
        cleaned = cache_module.clean_expired()
        assert cleaned == 1
        
        assert cache_module.exists("test:expired:1") is False
        assert cache_module.exists("test:persist:1") is True
    
    def test_delete_pattern(self):
        cache_module.set("prefix:a:1", "value1")
        cache_module.set("prefix:a:2", "value2")
        cache_module.set("prefix:b:1", "value3")
        
        deleted = cache_module.delete_pattern("prefix:a:*")
        assert deleted == 2
        
        assert cache_module.exists("prefix:a:1") is False
        assert cache_module.exists("prefix:b:1") is True
