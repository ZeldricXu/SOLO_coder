import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.environment_module import (
    get_environment_manager, EnvironmentStatus, EnvironmentType,
)


def test_create_environment():
    manager = get_environment_manager()
    env = manager.create("test-env", "test-user", EnvironmentType.PREVIEW)
    assert env is not None
    assert env.env_id is not None
    assert env.name == "test-env"
    assert env.owner == "test-user"
    assert env.status == EnvironmentStatus.READY


def test_get_environment():
    manager = get_environment_manager()
    env = manager.create("test-get", "test-user")
    fetched = manager.get(env.env_id)
    assert fetched is not None
    assert fetched.env_id == env.env_id


def test_list_environments():
    manager = get_environment_manager()
    manager.create("test-list-1", "test-user")
    manager.create("test-list-2", "test-user")
    envs = manager.list(owner="test-user")
    assert len(envs) >= 2


def test_stop_environment():
    manager = get_environment_manager()
    env = manager.create("test-stop", "test-user")
    success = manager.stop(env.env_id)
    assert success is True
    updated = manager.get(env.env_id)
    assert updated.status == EnvironmentStatus.STOPPED


def test_delete_environment():
    manager = get_environment_manager()
    env = manager.create("test-delete", "test-user")
    success = manager.delete(env.env_id)
    assert success is True
    assert manager.get(env.env_id) is None


def test_extend_ttl():
    manager = get_environment_manager()
    env = manager.create("test-ttl", "test-user", ttl_hours=1)
    original_expiry = env.expires_at
    success = manager.extend_ttl(env.env_id, 24)
    assert success is True
    assert env.expires_at > original_expiry


def test_user_usage():
    manager = get_environment_manager()
    usage = manager.get_user_usage("test-user")
    assert "owner" in usage
    assert "active_count" in usage
    assert "total_count" in usage
