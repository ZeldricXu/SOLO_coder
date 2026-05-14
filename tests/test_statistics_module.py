import pytest
import time
from datetime import date, timedelta
from unittest.mock import Mock, patch, MagicMock

from behaviortrack.modules import StatisticsModule, StatisticsCache
from .test_data_builder import TestDataBuilder


class TestStatisticsCache:
    def test_cache_set_and_get(self):
        cache = StatisticsCache(default_ttl_seconds=60)
        
        cache.set("test_key", {"value": 123})
        result = cache.get("test_key")
        
        assert result == {"value": 123}
    
    def test_cache_miss(self):
        cache = StatisticsCache(default_ttl_seconds=60)
        
        result = cache.get("nonexistent_key")
        assert result is None
        
        stats = cache.get_stats()
        assert stats["miss_count"] == 1
    
    def test_cache_hit_rate(self):
        cache = StatisticsCache(default_ttl_seconds=60)
        
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        
        cache.get("key1")
        cache.get("key1")
        cache.get("key2")
        cache.get("nonexistent")
        
        stats = cache.get_stats()
        
        assert stats["hit_count"] == 3
        assert stats["miss_count"] == 1
        assert stats["hit_rate"] == 0.75
    
    def test_cache_ttl_expiration(self):
        cache = StatisticsCache(default_ttl_seconds=1)
        
        cache.set("temp_key", "temp_value")
        assert cache.get("temp_key") == "temp_value"
        
        time.sleep(1.1)
        
        assert cache.get("temp_key") is None
        
        stats = cache.get_stats()
        assert stats["cache_size"] == 0
    
    def test_cache_delete(self):
        cache = StatisticsCache(default_ttl_seconds=60)
        
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        
        assert cache.delete("key1") is True
        assert cache.get("key1") is None
        
        assert cache.delete("nonexistent") is False
        
        stats = cache.get_stats()
        assert stats["cache_size"] == 1
    
    def test_cache_clear(self):
        cache = StatisticsCache(default_ttl_seconds=60)
        
        for i in range(100):
            cache.set(f"key_{i}", f"value_{i}")
        
        stats_before = cache.get_stats()
        assert stats_before["cache_size"] == 100
        
        cache.clear()
        
        stats_after = cache.get_stats()
        assert stats_after["cache_size"] == 0
        assert stats_after["hit_count"] == 0
        assert stats_after["miss_count"] == 0
    
    def test_cache_evict_expired(self):
        cache = StatisticsCache(default_ttl_seconds=1)
        
        cache.set("expired_key1", "value1")
        cache.set("expired_key2", "value2")
        cache.set("persistent_key", "value3", ttl_seconds=300)
        
        time.sleep(1.1)
        
        evicted_count = cache.evict_expired()
        assert evicted_count == 2
        
        stats = cache.get_stats()
        assert stats["cache_size"] == 1
    
    def test_custom_ttl(self):
        cache = StatisticsCache(default_ttl_seconds=1)
        
        cache.set("short_ttl", "value1")
        cache.set("long_ttl", "value2", ttl_seconds=300)
        
        time.sleep(1.1)
        
        assert cache.get("short_ttl") is None
        assert cache.get("long_ttl") == "value2"


class TestIncrementalStatistics:
    def test_update_incremental_stat_single(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.update_incremental_stat(
                event_type="click",
                event_date="2026-05-10",
                user_id="user_001",
                duration_seconds=2.5
            )
            
            assert result["success"] is True
            assert result["event_type"] == "click"
            assert result["event_count"] == 1
            assert result["unique_users"] == 1
    
    def test_update_incremental_stat_multiple_same_user(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            for i in range(5):
                result = stats_module.update_incremental_stat(
                    event_type="click",
                    event_date="2026-05-10",
                    user_id="user_001"
                )
            
            assert result["event_count"] == 5
            assert result["unique_users"] == 1
    
    def test_update_incremental_stat_multiple_users(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            for i in range(10):
                stats_module.update_incremental_stat(
                    event_type="page_view",
                    event_date="2026-05-10",
                    user_id=f"user_{i:03d}"
                )
            
            summary = stats_module.get_incremental_stats_summary()
            
            assert summary["total_event_count"] == 10
            assert summary["pending_count"] == 1
    
    def test_flush_incremental_stats(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            for i in range(5):
                stats_module.update_incremental_stat(
                    event_type="click",
                    event_date="2026-05-10",
                    user_id=f"user_{i}"
                )
            
            summary_before = stats_module.get_incremental_stats_summary()
            assert summary_before["total_event_count"] == 5
            
            flush_result = stats_module.flush_incremental_stats()
            
            assert flush_result["success"] is True
            assert flush_result["flushed_count"] == 1
            
            summary_after = stats_module.get_incremental_stats_summary()
            assert summary_after["total_event_count"] == 0
            
            mock_storage.upsert_stat.assert_called_once()
    
    def test_flush_incremental_stats_empty(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.flush_incremental_stats()
            
            assert result["success"] is True
            assert result["flushed_count"] == 0
            assert result["message"] == "No incremental stats to flush"
    
    def test_clear_incremental_stats(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            for i in range(100):
                stats_module.update_incremental_stat(
                    event_type="click",
                    event_date="2026-05-10",
                    user_id=f"user_{i}"
                )
            
            summary_before = stats_module.get_incremental_stats_summary()
            assert summary_before["total_event_count"] == 100
            
            stats_module.clear_incremental_stats()
            
            summary_after = stats_module.get_incremental_stats_summary()
            assert summary_after["total_event_count"] == 0
    
    def test_incremental_stats_multiple_event_types(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            event_types = ["click", "page_view", "scroll", "purchase"]
            
            for event_type in event_types:
                for i in range(10):
                    stats_module.update_incremental_stat(
                        event_type=event_type,
                        event_date="2026-05-10",
                        user_id=f"{event_type}_user_{i}"
                    )
            
            summary = stats_module.get_incremental_stats_summary()
            
            assert summary["total_event_count"] == 40
            assert summary["pending_count"] == 4


class TestStatisticsWithCache:
    def test_get_overview_stats_caching(self, mock_storage):
        mock_storage.count_events = Mock(return_value=1000)
        mock_storage.aggregate_events = Mock(side_effect=[
            [{"count": 100}],
            [{"count": 50}],
            [{"count": 25}],
        ])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            result1 = stats_module.get_overview_stats()
            
            assert result1["success"] is True
            assert mock_storage.count_events.call_count == 1
            
            result2 = stats_module.get_overview_stats()
            
            assert result2["success"] is True
            assert mock_storage.count_events.call_count == 1
            
            cache_stats = stats_module.get_cache_stats()
            assert cache_stats["hit_count"] == 1
            assert cache_stats["miss_count"] == 1
    
    def test_cache_invalidation(self, mock_storage):
        mock_storage.count_events = Mock(return_value=1000)
        mock_storage.aggregate_events = Mock(return_value=[{"count": 100}])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            stats_module.get_overview_stats()
            stats_module.get_overview_stats()
            
            cache_stats_before = stats_module.get_cache_stats()
            assert cache_stats_before["hit_count"] == 1
            
            stats_module.invalidate_cache()
            
            cache_stats_after = stats_module.get_cache_stats()
            assert cache_stats_after["cache_size"] == 0
            
            stats_module.get_overview_stats()
            assert mock_storage.count_events.call_count == 2
    
    def test_cache_bypass(self, mock_storage):
        mock_storage.count_events = Mock(return_value=1000)
        mock_storage.aggregate_events = Mock(return_value=[{"count": 100}])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            stats_module.get_overview_stats(use_cache=False)
            stats_module.get_overview_stats(use_cache=False)
            
            cache_stats = stats_module.get_cache_stats()
            assert cache_stats["hit_count"] == 0
            assert mock_storage.count_events.call_count == 2
    
    def test_get_daily_stats_caching(self, mock_storage):
        mock_storage.aggregate_events = Mock(return_value=[
            {
                "date": "2026-05-01",
                "event_type": "click",
                "event_count": 100,
                "user_count": 50
            }
        ])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            result1 = stats_module.get_daily_stats(
                start_date="2026-05-01",
                end_date="2026-05-07"
            )
            
            result2 = stats_module.get_daily_stats(
                start_date="2026-05-01",
                end_date="2026-05-07"
            )
            
            assert mock_storage.aggregate_events.call_count == 1
            
            cache_stats = stats_module.get_cache_stats()
            assert cache_stats["hit_count"] == 1
    
    def test_time_window_cache_separation(self, mock_storage):
        call_count = [0]
        
        def mock_aggregate(pipeline):
            call_count[0] += 1
            return [{
                "date": "2026-05-01",
                "event_type": "click",
                "event_count": 100 + call_count[0],
                "user_count": 50
            }]
        
        mock_storage.aggregate_events = Mock(side_effect=mock_aggregate)
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            result1 = stats_module.get_daily_stats(
                start_date="2026-05-01",
                end_date="2026-05-07"
            )
            
            result2 = stats_module.get_daily_stats(
                start_date="2026-05-08",
                end_date="2026-05-14"
            )
            
            assert mock_storage.aggregate_events.call_count == 2
            
            cache_stats = stats_module.get_cache_stats()
            assert cache_stats["cache_size"] == 2


class TestStatisticsComputation:
    def test_get_event_distribution(self, mock_storage):
        mock_storage.aggregate_events = Mock(return_value=[
            {"_id": "click", "count": 500},
            {"_id": "page_view", "count": 1000},
            {"_id": "scroll", "count": 300},
            {"_id": "purchase", "count": 50}
        ])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.get_event_distribution()
            
            assert result["success"] is True
            assert result["total_events"] == 1850
            
            distribution = result["distribution"]
            assert len(distribution) == 4
            
            percentages = [d["percentage"] for d in distribution]
            assert sum(percentages) == 100.0
    
    def test_get_hourly_distribution(self, mock_storage):
        mock_storage.aggregate_events = Mock(return_value=[
            {"_id": hour, "count": 10 + hour * 2} for hour in range(24)
        ])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.get_hourly_distribution()
            
            assert result["success"] is True
            
            distribution = result["hourly_distribution"]
            assert len(distribution) == 24
            
            hours = [d["hour"] for d in distribution]
            assert hours == list(range(24))
    
    def test_get_active_users_stats_daily(self, mock_storage):
        today = date.today()
        expected_results = []
        for i in range(7):
            day = today - timedelta(days=6 - i)
            expected_results.append({
                "_id": day.isoformat(),
                "active_users": 50 + i * 10
            })
        
        mock_storage.aggregate_events = Mock(return_value=expected_results)
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.get_active_users_stats(period="daily")
            
            assert result["success"] is True
            assert result["period"] == "daily"
            assert len(result["stats"]) == 7
            
            for stat in result["stats"]:
                assert "period" in stat
                assert "active_users" in stat
                assert stat["active_users"] >= 50
    
    def test_get_retention_stats(self, mock_storage):
        cohort_users = [f"user_{i}" for i in range(100)]
        
        def mock_aggregate(pipeline):
            if "$addToSet" in str(pipeline):
                return [{
                    "users": cohort_users,
                    "count": len(cohort_users)
                }]
            else:
                return [{"active_users": 80}]
        
        mock_storage.aggregate_events = Mock(side_effect=mock_aggregate)
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.get_retention_stats(
                cohort_date="2026-05-01",
                days=7
            )
            
            assert result["success"] is True
            assert result["total_cohort_users"] == 100
            
            retention_stats = result["retention_stats"]
            assert len(retention_stats) == 8
            
            for day_stat in retention_stats:
                assert "day" in day_stat
                assert "date" in day_stat
                assert "active_users" in day_stat
                assert "retention_rate" in day_stat
    
    def test_get_retention_stats_no_users(self, mock_storage):
        mock_storage.aggregate_events = Mock(return_value=[])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(use_cache=False)
            
            result = stats_module.get_retention_stats(
                cohort_date="2026-05-01",
                days=7
            )
            
            assert result["success"] is False
            assert result["error"] == "No users found for cohort date"


class TestCacheConsistency:
    def test_cache_vs_real_data_consistency(self, mock_storage):
        call_count = [0]
        
        def mock_count(query):
            call_count[0] += 1
            return 1000 + call_count[0]
        
        mock_storage.count_events = Mock(side_effect=mock_count)
        mock_storage.aggregate_events = Mock(return_value=[{"count": 100}])
        
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=60, use_cache=True)
            
            result1 = stats_module.get_overview_stats()
            total_events1 = result1["overview"]["total_events"]
            
            result2 = stats_module.get_overview_stats()
            total_events2 = result2["overview"]["total_events"]
            
            assert total_events1 == total_events2
            
            stats_module.invalidate_cache()
            
            result3 = stats_module.get_overview_stats()
            total_events3 = result3["overview"]["total_events"]
            
            assert total_events3 != total_events1
    
    def test_time_window_rolling_cache_clear(self, mock_storage):
        with patch('behaviortrack.modules.statistics_module.MongoStorage', return_value=mock_storage):
            stats_module = StatisticsModule(cache_ttl_seconds=1, use_cache=True)
            
            mock_storage.aggregate_events = Mock(return_value=[{
                "date": "2026-05-10",
                "event_type": "click",
                "event_count": 100,
                "user_count": 50
            }])
            
            stats_module.get_daily_stats(
                start_date="2026-05-10",
                end_date="2026-05-10"
            )
            
            cache_stats1 = stats_module.get_cache_stats()
            assert cache_stats1["cache_size"] == 1
            
            time.sleep(1.1)
            
            evicted = stats_module._cache.evict_expired()
            assert evicted == 1
            
            cache_stats2 = stats_module.get_cache_stats()
            assert cache_stats2["cache_size"] == 0
