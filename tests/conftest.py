import pytest
from unittest.mock import Mock, MagicMock, patch
from typing import Any, Dict, List, Generator

from behaviortrack.models import BehaviorEvent, UserProfile, BehaviorStat
from .test_data_builder import TestDataBuilder


@pytest.fixture
def mock_storage():
    storage = Mock()
    
    storage.insert_event = Mock(return_value="event_test_001")
    storage.insert_events = Mock(return_value=["event_test_001", "event_test_002"])
    storage.find_events = Mock(return_value=[])
    storage.find_event_by_id = Mock(return_value=None)
    storage.count_events = Mock(return_value=0)
    storage.aggregate_events = Mock(return_value=[])
    storage.upsert_stat = Mock(return_value="stat_test_001")
    storage.find_stats = Mock(return_value=[])
    storage.upsert_trajectory = Mock(return_value="trajectory_test_001")
    storage.find_trajectories = Mock(return_value=[])
    storage.find_trajectory_by_session = Mock(return_value=None)
    storage.upsert_profile = Mock(return_value="profile_test_001")
    storage.find_profile_by_user_id = Mock(return_value=None)
    storage.find_profiles = Mock(return_value=[])
    storage.upsert_relation = Mock(return_value="relation_test_001")
    storage.find_relations = Mock(return_value=[])
    storage.insert_abnormal = Mock(return_value="abnormal_test_001")
    storage.find_abnormal = Mock(return_value=[])
    
    storage.profiles_collection = Mock()
    storage.profiles_collection.aggregate = Mock(return_value=[])
    
    return storage


@pytest.fixture
def sample_behavior_event() -> BehaviorEvent:
    return TestDataBuilder.build_behavior_event(
        user_id="test_user_001",
        event_type="click",
        event_name="按钮点击"
    )


@pytest.fixture
def sample_event_dict() -> Dict[str, Any]:
    return TestDataBuilder.build_behavior_event_dict(
        user_id="test_user_001",
        event_type="page_view",
        event_name="页面浏览"
    )


@pytest.fixture
def multiple_events() -> List[BehaviorEvent]:
    return TestDataBuilder.build_multiple_events(
        count=50,
        base_user_id="test_user_batch"
    )


@pytest.fixture
def multiple_event_dicts() -> List[Dict[str, Any]]:
    return TestDataBuilder.build_multiple_event_dicts(
        count=100,
        base_user_id="test_user_dict_batch"
    )


@pytest.fixture
def user_events_for_profile() -> List[BehaviorEvent]:
    return TestDataBuilder.build_user_events_for_profile(
        user_id="profile_test_user",
        active_days=10,
        total_events=150
    )


@pytest.fixture
def sample_user_profile() -> UserProfile:
    return TestDataBuilder.build_user_profile(
        user_id="profile_test_001",
        active_days=15,
        total_events=300,
        mobile_ratio=0.9
    )


@pytest.fixture
def sample_behavior_stat() -> BehaviorStat:
    return TestDataBuilder.build_behavior_stat(
        event_type="click",
        event_count=150,
        user_count=75
    )


@pytest.fixture
def invalid_event_dicts() -> List[Dict[str, Any]]:
    return TestDataBuilder.build_invalid_events_for_testing()


@pytest.fixture
def high_concurrency_events() -> List[Dict[str, Any]]:
    return TestDataBuilder.build_high_concurrency_events(
        user_count=20,
        events_per_user=10
    )


@pytest.fixture
def active_user_events() -> List[BehaviorEvent]:
    return TestDataBuilder.build_user_events_for_active_profile(
        user_id="active_user_test",
        active_days=10,
        total_events=200,
        mobile_ratio=0.85
    )


@pytest.fixture
def low_active_user_events() -> List[BehaviorEvent]:
    return TestDataBuilder.build_user_events_for_tag_testing(
        user_id="low_active_user",
        active_days=1,
        total_events=5,
        device_type="desktop"
    )
