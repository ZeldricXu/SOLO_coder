import pytest
import time
from unittest.mock import Mock, patch, MagicMock
from typing import Any, Dict, List

from behaviortrack.modules import BehaviorCollector, EventQueue
from behaviortrack.models import BehaviorEvent
from .test_data_builder import TestDataBuilder


class TestBehaviorCollectorSync:
    def test_collect_valid_event_sync(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            result = collector.collect(sample_event_dict)
            
            assert result["success"] is True
            assert "event_id" in result
            mock_storage.insert_event.assert_called_once()
            mock_storage.upsert_trajectory.assert_called_once()
    
    def test_collect_batch_valid_events_sync(self, mock_storage, multiple_event_dicts):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            result = collector.collect_batch(multiple_event_dicts)
            
            assert result["success"] is True
            assert len(result["event_ids"]) == len(multiple_event_dicts)
            mock_storage.insert_events.assert_called_once()
            assert mock_storage.upsert_trajectory.call_count == len(multiple_event_dicts)
    
    def test_collect_invalid_event_sync(self, mock_storage, invalid_event_dicts):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            for invalid_data in invalid_event_dicts:
                result = collector.collect(invalid_data)
                
                assert result["success"] is False
                assert result["error"] == "Invalid data format"
                assert "details" in result
                assert len(result["details"]) > 0
                mock_storage.insert_event.assert_not_called()
                
                mock_storage.reset_mock()
    
    def test_collect_batch_mixed_valid_invalid(self, mock_storage):
        valid_events = TestDataBuilder.build_multiple_event_dicts(count=5, base_user_id="valid_test")
        invalid_events = TestDataBuilder.build_invalid_events_for_testing()
        mixed_events = valid_events + invalid_events
        
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            result = collector.collect_batch(mixed_events)
            
            assert result["success"] is False
            assert len(result["event_ids"]) == 5
            assert len(result["errors"]) == len(invalid_events)
            mock_storage.insert_events.assert_called_once()
    
    def test_validate_data_missing_required_fields(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            validation_result = collector._validate_data({})
            assert validation_result["valid"] is False
            assert "Missing required field: user_id" in validation_result["errors"]
            assert "Missing required field: event_type" in validation_result["errors"]
    
    def test_validate_data_invalid_types(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            validation_result = collector._validate_data({
                "user_id": 123,
                "event_type": "click",
                "event_data": "not_a_dict"
            })
            
            assert validation_result["valid"] is False
            assert "user_id must be a string" in validation_result["errors"]
            assert "event_data must be a dictionary" in validation_result["errors"]
    
    def test_validate_data_valid_event(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            validation_result = collector._validate_data(sample_event_dict)
            assert validation_result["valid"] is True
            assert len(validation_result["errors"]) == 0
    
    def test_create_event_generates_valid_event(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            event = collector._create_event(sample_event_dict)
            
            assert isinstance(event, BehaviorEvent)
            assert event.user_id == sample_event_dict["user_id"]
            assert event.event_type == sample_event_dict["event_type"]
            assert event.event_id.startswith("event_")
            assert event.timestamp is not None


class TestBehaviorCollectorAsync:
    def test_collect_async_returns_immediately(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=True)
            
            start_time = time.time()
            result = collector.collect_async(sample_event_dict)
            elapsed_time = time.time() - start_time
            
            assert result["success"] is True
            assert result["async"] is True
            assert "task_id" in result
            assert elapsed_time < 0.1
            mock_storage.insert_event.assert_not_called()
    
    def test_collect_async_invalid_data(self, mock_storage, invalid_event_dicts):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=True)
            
            for invalid_data in invalid_event_dicts:
                result = collector.collect_async(invalid_data)
                
                assert result["success"] is False
                assert result["async"] is True
                assert "details" in result
                assert result["details"]
    
    def test_collect_batch_async(self, mock_storage, multiple_event_dicts):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=True)
            
            start_time = time.time()
            result = collector.collect_batch_async(multiple_event_dicts)
            elapsed_time = time.time() - start_time
            
            assert result["success"] is True
            assert result["async"] is True
            assert len(result["task_ids"]) == len(multiple_event_dicts)
            assert elapsed_time < 0.1
            mock_storage.insert_events.assert_not_called()
    
    def test_queue_stats_tracking(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=True)
            
            initial_stats = collector.get_queue_stats()
            assert initial_stats is not None
            assert initial_stats["total_enqueued"] == 0
            assert initial_stats["current_queue_size"] == 0
            
            collector.collect_async(sample_event_dict)
            
            stats_after = collector.get_queue_stats()
            assert stats_after["total_enqueued"] == 1
            assert stats_after["current_queue_size"] == 1
    
    def test_worker_processes_queue(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(
                use_async=True,
                batch_size=1,
                flush_interval_ms=10
            )
            
            collector.start_async_processing()
            
            try:
                result = collector.collect_async(sample_event_dict)
                task_id = result["task_id"]
                
                processed = collector.wait_for_processing(timeout_seconds=5.0)
                
                assert processed is True
                
                stats = collector.get_queue_stats()
                assert stats["total_processed"] == 1
                assert stats["current_queue_size"] == 0
                
                mock_storage.insert_event.assert_called_once()
                
            finally:
                collector.stop_async_processing()
    
    def test_high_concurrency_async_processing(self, mock_storage, high_concurrency_events):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(
                use_async=True,
                batch_size=20,
                flush_interval_ms=20,
                worker_count=4
            )
            
            collector.start_async_processing()
            
            try:
                start_time = time.time()
                result = collector.collect_batch_async(high_concurrency_events)
                enqueue_time = time.time() - start_time
                
                assert result["success"] is True
                assert len(result["task_ids"]) == len(high_concurrency_events)
                assert enqueue_time < 0.5
                
                stats_before = collector.get_queue_stats()
                assert stats_before["total_enqueued"] == len(high_concurrency_events)
                
                processed = collector.wait_for_processing(timeout_seconds=10.0)
                assert processed is True
                
                stats_after = collector.get_queue_stats()
                assert stats_after["total_processed"] == len(high_concurrency_events)
                assert stats_after["current_queue_size"] == 0
                
            finally:
                collector.stop_async_processing()
    
    def test_async_to_sync_fallback(self, mock_storage, sample_event_dict):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect_async(sample_event_dict)
            
            assert "async" not in result or result["async"] is False
            mock_storage.insert_event.assert_called_once()


class TestEventQueue:
    def test_queue_enqueue_dequeue(self):
        queue = EventQueue(processor=lambda items: {"success": True, "count": len(items)})
        
        task_id = queue.enqueue({"test": "data"})
        
        assert task_id is not None
        assert len(queue) == 1
        
        stats = queue.get_stats()
        assert stats.total_enqueued == 1
    
    def test_queue_batch_processing(self):
        processed_items = []
        
        def processor(items):
            processed_items.extend(items)
            return {"success": True, "processed": len(items)}
        
        queue = EventQueue(
            processor=processor,
            batch_size=5,
            flush_interval_ms=100
        )
        
        for i in range(20):
            queue.enqueue({"item": i})
        
        queue.start()
        
        try:
            start_time = time.time()
            while len(processed_items) < 20 and time.time() - start_time < 5:
                time.sleep(0.1)
            
            assert len(processed_items) == 20
            
            stats = queue.get_stats()
            assert stats.total_processed == 20
            
        finally:
            queue.stop()
    
    def test_queue_error_handling(self):
        def failing_processor(items):
            raise RuntimeError("Processing failed")
        
        queue = EventQueue(
            processor=failing_processor,
            batch_size=1,
            flush_interval_ms=50
        )
        
        queue.enqueue({"test": "data"})
        queue.start()
        
        try:
            time.sleep(0.5)
            
            stats = queue.get_stats()
            assert stats.total_failed == 1
            
        finally:
            queue.stop()
    
    def test_queue_result_tracking(self):
        def processor(items):
            return {"success": True, "items": len(items)}
        
        queue = EventQueue(
            processor=processor,
            batch_size=1,
            flush_interval_ms=50
        )
        
        task_id = queue.enqueue({"test": "data"})
        queue.start()
        
        try:
            time.sleep(0.3)
            
            result = queue.get_result(task_id)
            assert result is not None
            assert result.status == "completed"
            assert result.result is not None
            
        finally:
            queue.stop()
    
    def test_queue_clear(self):
        queue = EventQueue(processor=lambda items: {})
        
        for i in range(100):
            queue.enqueue({"item": i})
        
        assert len(queue) == 100
        
        queue.clear()
        
        assert len(queue) == 0
        stats = queue.get_stats()
        assert stats.total_enqueued == 0


class TestDataValidation:
    def test_missing_user_id(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "event_type": "click"
            })
            
            assert result["success"] is False
            assert "Missing required field: user_id" in result["details"]
    
    def test_missing_event_type(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "test_user"
            })
            
            assert result["success"] is False
            assert "Missing required field: event_type" in result["details"]
    
    def test_empty_user_id(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "",
                "event_type": "click"
            })
            
            assert result["success"] is False
            assert "Missing required field: user_id" in result["details"]
    
    def test_empty_event_type(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "test_user",
                "event_type": ""
            })
            
            assert result["success"] is False
            assert "Missing required field: event_type" in result["details"]
    
    def test_invalid_event_data_type(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "test_user",
                "event_type": "click",
                "event_data": "should_be_dict"
            })
            
            assert result["success"] is False
            assert "event_data must be a dictionary" in result["details"]
    
    def test_invalid_device_type(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "test_user",
                "event_type": "click",
                "device": "should_be_dict"
            })
            
            assert result["success"] is False
            assert "device must be a dictionary" in result["details"]
    
    def test_invalid_location_type(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            result = collector.collect({
                "user_id": "test_user",
                "event_type": "click",
                "location": "should_be_dict"
            })
            
            assert result["success"] is False
            assert "location must be a dictionary" in result["details"]
    
    def test_all_required_fields_present(self, mock_storage):
        with patch('behaviortrack.modules.behavior_collector.MongoStorage', return_value=mock_storage):
            collector = BehaviorCollector(use_async=False)
            
            validation = collector._validate_data({
                "user_id": "test_user_123",
                "event_type": "page_view"
            })
            
            assert validation["valid"] is True
            assert len(validation["errors"]) == 0
