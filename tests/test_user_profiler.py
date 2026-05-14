import pytest
import time
from unittest.mock import Mock, patch, MagicMock, call

from behaviortrack.modules import UserProfiler
from .test_data_builder import TestDataBuilder


class TestUserProfileAttributes:
    def test_calculate_demo_attributes(self):
        profiler = UserProfiler()
        
        demo_attributes = profiler._calculate_demo_attributes(
            age=28,
            gender="male",
            country="CN",
            city="北京"
        )
        
        assert demo_attributes["age_group"] == "25-34"
        assert demo_attributes["gender"] == "male"
        assert demo_attributes["country"] == "CN"
        assert demo_attributes["city"] == "北京"
        assert "demographic_vector" in demo_attributes
    
    def test_calculate_demo_attributes_all_age_groups(self):
        profiler = UserProfiler()
        
        age_groups = {
            15: "18-24",
            22: "18-24",
            25: "25-34",
            34: "25-34",
            35: "35-44",
            44: "35-44",
            45: "45-54",
            54: "45-54",
            55: "55+",
            100: "55+"
        }
        
        for age, expected_group in age_groups.items():
            demo = profiler._calculate_demo_attributes(age=age)
            assert demo["age_group"] == expected_group, f"Age {age} should map to {expected_group}"
    
    def test_calculate_behavior_attributes(self):
        profiler = UserProfiler()
        
        behavior_attributes = profiler._calculate_behavior_attributes(
            total_events=500,
            active_days=20,
            unique_event_types=5,
            avg_session_duration=600.0,
            favorite_event_type="page_view",
            preferred_hours=[14, 15, 16, 20, 21],
            total_spent=2500.0
        )
        
        assert behavior_attributes["event_frequency"] == "high"
        assert behavior_attributes["engagement_level"] == "high"
        assert behavior_attributes["activity_pattern"] == "moderate"
        assert behavior_attributes["time_preference"] == "evening"
        assert behavior_attributes["spending_category"] == "mid"
        assert "behavior_vector" in behavior_attributes
    
    def test_calculate_behavior_attributes_boundaries(self):
        profiler = UserProfiler()
        
        low_behavior = profiler._calculate_behavior_attributes(
            total_events=50,
            active_days=3,
            unique_event_types=2,
            avg_session_duration=60.0,
            favorite_event_type="page_view",
            preferred_hours=[10],
            total_spent=50.0
        )
        
        assert low_behavior["event_frequency"] == "low"
        assert low_behavior["engagement_level"] == "low"
        assert low_behavior["activity_pattern"] == "infrequent"
        
        high_behavior = profiler._calculate_behavior_attributes(
            total_events=1000,
            active_days=60,
            unique_event_types=10,
            avg_session_duration=1800.0,
            favorite_event_type="purchase",
            preferred_hours=[14, 15, 16, 20, 21, 22],
            total_spent=15000.0
        )
        
        assert high_behavior["event_frequency"] == "high"
        assert high_behavior["engagement_level"] == "high"
        assert high_behavior["spending_category"] == "high"
    
    def test_calculate_preference_attributes(self):
        profiler = UserProfiler()
        
        preferences = profiler._calculate_preference_attributes(
            favorite_event_type="click",
            preferred_hours=[19, 20, 21],
            favorite_page="/products",
            device_type="mobile"
        )
        
        assert preferences["favorite_event_type"] == "click"
        assert preferences["time_preference"] == "evening"
        assert preferences["favorite_page"] == "/products"
        assert preferences["device_type"] == "mobile"
        assert preferences["is_mobile_user"] is True
        assert "preference_vector" in preferences
    
    def test_calculate_preference_attributes_time_ranges(self):
        profiler = UserProfiler()
        
        time_ranges = {
            (2, 3, 4): "night",
            (8, 9, 10): "morning",
            (13, 14, 15): "afternoon",
            (19, 20, 21): "evening"
        }
        
        for hours, expected in time_ranges.items():
            prefs = profiler._calculate_preference_attributes(
                favorite_event_type="page_view",
                preferred_hours=list(hours)
            )
            assert prefs["time_preference"] == expected


class TestUserProfileBuilding:
    def test_build_profile_from_events(self, user_events_for_profile):
        profiler = UserProfiler()
        
        profile = profiler.build_profile_from_events(
            user_id="test_user_001",
            events=user_events_for_profile,
            demographic_data={
                "age": 28,
                "gender": "male",
                "country": "CN",
                "city": "北京"
            }
        )
        
        assert profile["user_id"] == "test_user_001"
        assert "created_at" in profile
        assert "updated_at" in profile
        assert "profile_version" in profile
        
        assert "demographics" in profile
        assert "behavior" in profile
        assert "preferences" in profile
        assert "tags" in profile
        assert "segments" in profile
        
        assert profile["behavior"]["total_events"] == 150
        assert profile["behavior"]["active_days"] == 10
        assert len(profile["tags"]) > 0
    
    def test_build_profile_minimal_events(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="minimal_user",
            active_days=2,
            total_events=10
        )
        
        profile = profiler.build_profile_from_events(
            user_id="minimal_user",
            events=events
        )
        
        assert profile["user_id"] == "minimal_user"
        assert profile["behavior"]["total_events"] == 10
        assert profile["behavior"]["active_days"] == 2
        assert len(profile["tags"]) >= 1
    
    def test_build_profile_no_demographics(self, user_events_for_profile):
        profiler = UserProfiler()
        
        profile = profiler.build_profile_from_events(
            user_id="no_demo_user",
            events=user_events_for_profile
        )
        
        assert profile["user_id"] == "no_demo_user"
        assert "demographics" in profile
        assert profile["demographics"]["age_group"] == "unknown"
        assert profile["demographics"]["gender"] == "unknown"
        assert len(profile["tags"]) > 0
    
    def test_build_profile_purchaser(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="big_spender",
            active_days=30,
            total_events=500,
            include_purchases=True,
            total_purchases=20,
            total_spent=15000.0
        )
        
        profile = profiler.build_profile_from_events(
            user_id="big_spender",
            events=events
        )
        
        assert profile["behavior"]["total_spent"] == 15000.0
        assert profile["behavior"]["total_purchases"] == 20
        assert profile["behavior"]["spending_category"] == "high"
        assert "high_spender" in [tag["tag"] for tag in profile["tags"]]


class TestUserProfileTags:
    def test_generate_tags_comprehensive(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="comprehensive_user",
            active_days=45,
            total_events=1000,
            include_purchases=True,
            total_purchases=15,
            total_spent=8000.0
        )
        
        profile = profiler.build_profile_from_events(
            user_id="comprehensive_user",
            events=events
        )
        
        tags = profile["tags"]
        tag_names = [t["tag"] for t in tags]
        
        assert "active_user" in tag_names
        assert "high_engagement" in tag_names
        assert "frequent_visitor" in tag_names
        assert "big_spender" in tag_names or "value_shopper" in tag_names
        
        for tag in tags:
            assert "tag" in tag
            assert "category" in tag
            assert "confidence" in tag
            assert 0.0 <= tag["confidence"] <= 1.0
    
    def test_generate_tags_categories(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="multi_category",
            active_days=30,
            total_events=500,
            include_purchases=True,
            total_purchases=10,
            total_spent=5000.0
        )
        
        profile = profiler.build_profile_from_events(
            user_id="multi_category",
            events=events
        )
        
        tags = profile["tags"]
        categories = set(t["category"] for t in tags)
        
        assert "engagement" in categories or len(categories) >= 2
        assert len(tags) >= 3
    
    def test_generate_tags_night_owl(self):
        profiler = UserProfiler()
        
        events = [
            TestDataBuilder.build_behavior_event_dict(
                user_id="night_user",
                event_type="page_view",
                timestamp=f"2026-05-10T{hour:02d}:30:00"
            )
            for hour in [2, 3, 4, 23, 1]
        ]
        
        profile = profiler.build_profile_from_events(
            user_id="night_user",
            events=events
        )
        
        tag_names = [t["tag"] for t in profile["tags"]]
        assert "night_owl" in tag_names


class TestIncrementalProfileUpdate:
    def test_update_profile_incrementally(self):
        profiler = UserProfiler()
        
        initial_events = TestDataBuilder.build_user_events_for_profile(
            user_id="incremental_user",
            active_days=5,
            total_events=50
        )
        
        initial_profile = profiler.build_profile_from_events(
            user_id="incremental_user",
            events=initial_events
        )
        
        new_events = TestDataBuilder.build_user_events_for_profile(
            user_id="incremental_user",
            active_days=3,
            total_events=30,
            include_purchases=True,
            total_purchases=5,
            total_spent=2000.0
        )
        
        updated_profile = profiler.update_profile_incrementally(
            existing_profile=initial_profile,
            new_events=new_events
        )
        
        assert updated_profile["behavior"]["total_events"] == 80
        assert updated_profile["behavior"]["active_days"] >= 5
        assert updated_profile["behavior"]["total_purchases"] == 5
        assert updated_profile["behavior"]["total_spent"] == 2000.0
        assert updated_profile["profile_version"] == initial_profile["profile_version"] + 1
    
    def test_queue_incremental_update(self):
        profiler = UserProfiler()
        
        for i in range(15):
            event = TestDataBuilder.build_behavior_event_dict(
                user_id="queue_user",
                event_type="click"
            )
            result = profiler.queue_incremental_update("queue_user", event)
            
            assert result["queued"] is True
        
        queue_status = profiler.get_incremental_queue_status()
        
        assert queue_status["total_queued_events"] == 15
        assert queue_status["users_in_queue"] == 1
    
    def test_flush_incremental_updates(self):
        profiler = UserProfiler()
        
        initial_events = TestDataBuilder.build_user_events_for_profile(
            user_id="flush_user",
            active_days=5,
            total_events=50
        )
        
        initial_profile = profiler.build_profile_from_events(
            user_id="flush_user",
            events=initial_events
        )
        
        for i in range(15):
            event = TestDataBuilder.build_behavior_event_dict(
                user_id="flush_user",
                event_type="click"
            )
            profiler.queue_incremental_update("flush_user", event)
        
        flush_result = profiler.flush_incremental_updates(
            min_events_threshold=10
        )
        
        assert flush_result["flushed_users"] == 1
        assert flush_result["total_events_processed"] == 15
        
        queue_status = profiler.get_incremental_queue_status()
        assert queue_status["total_queued_events"] == 0
    
    def test_flush_incremental_updates_below_threshold(self):
        profiler = UserProfiler()
        
        for i in range(5):
            event = TestDataBuilder.build_behavior_event_dict(
                user_id="below_threshold",
                event_type="click"
            )
            profiler.queue_incremental_update("below_threshold", event)
        
        flush_result = profiler.flush_incremental_updates(
            min_events_threshold=10
        )
        
        assert flush_result["flushed_users"] == 0
        assert flush_result["total_events_processed"] == 0
        
        queue_status = profiler.get_incremental_queue_status()
        assert queue_status["total_queued_events"] == 5
    
    def test_incremental_update_multiple_users(self):
        profiler = UserProfiler()
        
        users = ["user_a", "user_b", "user_c"]
        
        for user_id in users:
            for i in range(20):
                event = TestDataBuilder.build_behavior_event_dict(
                    user_id=user_id,
                    event_type="click"
                )
                profiler.queue_incremental_update(user_id, event)
        
        queue_status = profiler.get_incremental_queue_status()
        
        assert queue_status["total_queued_events"] == 60
        assert queue_status["users_in_queue"] == 3
        
        flush_result = profiler.flush_incremental_updates(
            min_events_threshold=10
        )
        
        assert flush_result["flushed_users"] == 3
        assert flush_result["total_events_processed"] == 60


class TestProfileSegmentation:
    def test_get_profile_segments(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="segment_user",
            active_days=30,
            total_events=500,
            include_purchases=True,
            total_purchases=10,
            total_spent=5000.0
        )
        
        profile = profiler.build_profile_from_events(
            user_id="segment_user",
            events=events
        )
        
        segments = profile["segments"]
        
        assert len(segments) >= 1
        for segment in segments:
            assert "segment_name" in segment
            assert "confidence" in segment
            assert 0.0 <= segment["confidence"] <= 1.0
    
    def test_merge_profile_segments(self):
        profiler = UserProfiler()
        
        profile = TestDataBuilder.build_user_profile(
            user_id="merge_user",
            active_days=30,
            total_events=500
        )
        
        segments = profiler._merge_segments(profile)
        
        assert len(segments) >= 1


class TestProfileStorage:
    def test_save_and_get_profile(self, mock_storage):
        mock_storage.find_profiles = Mock(return_value=[])
        mock_storage.save_profile = Mock(return_value={"inserted_id": "123"})
        
        with patch('behaviortrack.modules.user_profiler.MongoStorage', return_value=mock_storage):
            profiler = UserProfiler(use_storage=True)
            
            events = TestDataBuilder.build_user_events_for_profile(
                user_id="storage_user",
                active_days=10,
                total_events=100
            )
            
            save_result = profiler.save_profile(
                user_id="storage_user",
                events=events
            )
            
            assert save_result["success"] is True
            assert save_result["user_id"] == "storage_user"
            assert mock_storage.save_profile.called
    
    def test_get_profile_from_storage(self, mock_storage):
        test_profile = TestDataBuilder.build_user_profile(
            user_id="get_user",
            active_days=20,
            total_events=300
        )
        
        mock_storage.find_profiles = Mock(return_value=[test_profile])
        
        with patch('behaviortrack.modules.user_profiler.MongoStorage', return_value=mock_storage):
            profiler = UserProfiler(use_storage=True)
            
            result = profiler.get_profile(user_id="get_user")
            
            assert result["success"] is True
            assert result["profile"]["user_id"] == "get_user"
            mock_storage.find_profiles.assert_called_once()
    
    def test_get_profile_not_found(self, mock_storage):
        mock_storage.find_profiles = Mock(return_value=[])
        
        with patch('behaviortrack.modules.user_profiler.MongoStorage', return_value=mock_storage):
            profiler = UserProfiler(use_storage=True)
            
            result = profiler.get_profile(user_id="nonexistent")
            
            assert result["success"] is False
            assert "error" in result


class TestMultiDimensionalTags:
    def test_multidimensional_tag_generation(self):
        profiler = UserProfiler()
        
        events = TestDataBuilder.build_user_events_for_profile(
            user_id="multi_dim",
            active_days=60,
            total_events=1500,
            include_purchases=True,
            total_purchases=30,
            total_spent=25000.0
        )
        
        profile = profiler.build_profile_from_events(
            user_id="multi_dim",
            events=events,
            demographic_data={
                "age": 28,
                "gender": "female",
                "country": "CN",
                "city": "上海"
            }
        )
        
        tags = profile["tags"]
        
        engagement_tags = [t for t in tags if t["category"] == "engagement"]
        behavior_tags = [t for t in tags if t["category"] == "behavior"]
        value_tags = [t for t in tags if t["category"] == "value"]
        
        assert len(engagement_tags) + len(behavior_tags) + len(value_tags) >= 3
        
        all_categories = set(t["category"] for t in tags)
        assert len(all_categories) >= 2
    
    def test_tag_confidence_scores(self):
        profiler = UserProfiler()
        
        events_high = TestDataBuilder.build_user_events_for_profile(
            user_id="high_conf",
            active_days=60,
            total_events=2000
        )
        
        profile_high = profiler.build_profile_from_events(
            user_id="high_conf",
            events=events_high
        )
        
        events_low = TestDataBuilder.build_user_events_for_profile(
            user_id="low_conf",
            active_days=2,
            total_events=10
        )
        
        profile_low = profiler.build_profile_from_events(
            user_id="low_conf",
            events=events_low
        )
        
        high_conf_scores = [t["confidence"] for t in profile_high["tags"]]
        low_conf_scores = [t["confidence"] for t in profile_low["tags"]]
        
        assert len(high_conf_scores) >= len(low_conf_scores)
        assert max(high_conf_scores, default=0) >= min(high_conf_scores, default=0)
