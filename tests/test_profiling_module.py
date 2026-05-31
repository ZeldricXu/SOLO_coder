from __future__ import annotations
import pytest
import time
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock
from typing import List, Dict, Any
import threading
import concurrent.futures

from conftest import MockProfilingService

from builders import (
    ProfilingSessionBuilder,
    ProfileType,
    Priority,
)


pytestmark = [pytest.mark.unit, pytest.mark.profiling]


class TestProfilingSessionManagement:
    def test_start_cpu_profiling_session(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_cpu)

        assert result["status"] == "started"
        assert "sessionId" in result
        assert result["duration"] == 1000
        assert profiling_service.active_sessions == 1

    def test_start_memory_profiling_session(
        self,
        profiling_service: MockProfilingService,
        profiling_config_memory: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_memory)

        assert result["status"] == "started"
        assert result["duration"] == 2000

    def test_start_wall_profiling_session(self, profiling_service: MockProfilingService):
        config = ProfilingSessionBuilder() \
            .as_wall() \
            .with_duration(5000) \
            .build()

        result = profiling_service.start_session(config)

        assert result["status"] == "started"
        assert result["duration"] == 5000

    def test_stop_profiling_session(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        start_result = profiling_service.start_session(profiling_config_cpu)
        session_id = start_result["sessionId"]

        assert profiling_service.active_sessions == 1

        stop_result = profiling_service.stop_session(session_id)

        assert stop_result["status"] == "completed"
        assert "completedAt" in stop_result
        assert profiling_service.active_sessions == 0

    def test_get_session_info(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        start_result = profiling_service.start_session(profiling_config_cpu)
        session_id = start_result["sessionId"]

        session = profiling_service.get_session(session_id)

        assert session is not None
        assert session["id"] == session_id
        assert session["type"] == "cpu"
        assert session["status"] == "running"

    def test_get_nonexistent_session(self, profiling_service: MockProfilingService):
        session = profiling_service.get_session("nonexistent")
        assert session is None

    def test_stop_nonexistent_session(self, profiling_service: MockProfilingService):
        result = profiling_service.stop_session("nonexistent")
        assert result is None

    def test_session_type_persistence(self, profiling_service: MockProfilingService):
        types = ["cpu", "memory", "wall"]
        for profile_type in types:
            config = ProfilingSessionBuilder() \
                .with_type(ProfileType(profile_type)) \
                .build()
            result = profiling_service.start_session(config)
            session = profiling_service.get_session(result["sessionId"])
            assert session["type"] == profile_type
            profiling_service.stop_session(result["sessionId"])


class TestProfilingTimeoutDegradation:
    def test_duration_exceeds_max_with_degrade_behavior(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_timeout_behavior("degrade")

        config = ProfilingSessionBuilder() \
            .as_cpu() \
            .with_duration(20000) \
            .build()

        result = profiling_service.start_session(config)

        assert result["status"] == "started"
        assert result["duration"] == 10000

    def test_duration_exceeds_max_with_reject_behavior(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_timeout_behavior("reject")

        config = ProfilingSessionBuilder() \
            .as_cpu() \
            .with_duration(20000) \
            .build()

        with pytest.raises(ValueError, match="exceeds maximum allowed"):
            profiling_service.start_session(config)

    def test_duration_within_limit_no_degradation(
        self,
        profiling_service: MockProfilingService
    ):
        config = ProfilingSessionBuilder() \
            .as_cpu() \
            .with_duration(5000) \
            .build()

        result = profiling_service.start_session(config)

        assert result["status"] == "started"
        assert result["duration"] == 5000

    def test_max_concurrent_sessions_degrade(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(2)
        profiling_service.set_timeout_behavior("degrade")

        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        r1 = profiling_service.start_session(config)
        r2 = profiling_service.start_session(config)
        assert r1["status"] == "started"
        assert r2["status"] == "started"
        assert profiling_service.active_sessions == 2

        r3 = profiling_service.start_session(config)
        assert r3["status"] == "degraded"
        assert "queued" in r3["message"].lower()

    def test_max_concurrent_sessions_reject(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(2)
        profiling_service.set_timeout_behavior("reject")

        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        profiling_service.start_session(config)
        profiling_service.start_session(config)

        with pytest.raises(RuntimeError, match="Maximum concurrent sessions exceeded"):
            profiling_service.start_session(config)

    def test_session_completion_releases_concurrent_slot(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(1)

        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        r1 = profiling_service.start_session(config)
        assert profiling_service.active_sessions == 1

        r2 = profiling_service.start_session(config)
        assert r2["status"] == "degraded"

        profiling_service.stop_session(r1["sessionId"])
        assert profiling_service.active_sessions == 0

        r3 = profiling_service.start_session(config)
        assert r3["status"] == "started"

    def test_multiple_behavior_changes(
        self,
        profiling_service: MockProfilingService
    ):
        long_config = ProfilingSessionBuilder().as_cpu().with_duration(20000).build()

        profiling_service.set_timeout_behavior("degrade")
        r1 = profiling_service.start_session(long_config)
        assert r1["status"] == "started"
        assert r1["duration"] == 10000
        profiling_service.stop_session(r1["sessionId"])

        profiling_service.set_timeout_behavior("reject")
        with pytest.raises(ValueError):
            profiling_service.start_session(long_config)

        profiling_service.set_timeout_behavior("degrade")
        r2 = profiling_service.start_session(long_config)
        assert r2["status"] == "started"


class TestFlameGraphGeneration:
    def test_flamegraph_generated_after_completion(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_cpu)
        session_id = result["sessionId"]

        profiling_service.stop_session(session_id)

        flamegraph = profiling_service.get_flamegraph(session_id)
        assert flamegraph is not None
        assert len(flamegraph) > 0
        assert "<svg" in flamegraph
        assert "xmlns" in flamegraph

    def test_flamegraph_not_available_for_running_session(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_cpu)
        session_id = result["sessionId"]

        flamegraph = profiling_service.get_flamegraph(session_id)
        assert flamegraph == ""

    def test_flamegraph_not_available_for_nonexistent_session(
        self,
        profiling_service: MockProfilingService
    ):
        flamegraph = profiling_service.get_flamegraph("nonexistent")
        assert flamegraph == ""

    def test_flamegraph_content_format(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_cpu)
        session_id = result["sessionId"]
        profiling_service.stop_session(session_id)

        flamegraph = profiling_service.get_flamegraph(session_id)

        assert 'width="1200"' in flamegraph
        assert 'height="400"' in flamegraph
        assert session_id in flamegraph

    def test_multiple_flamegraphs_unique(
        self,
        profiling_service: MockProfilingService
    ):
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        session_ids = []
        for i in range(5):
            result = profiling_service.start_session(config)
            session_ids.append(result["sessionId"])
            profiling_service.stop_session(result["sessionId"])

        flamegraphs = [profiling_service.get_flamegraph(sid) for sid in session_ids]
        assert len(flamegraphs) == 5
        assert all(len(fg) > 0 for fg in flamegraphs)


class TestSessionComparison:
    def test_compare_same_type_sessions(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        r1 = profiling_service.start_session(profiling_config_cpu)
        profiling_service.stop_session(r1["sessionId"])

        r2 = profiling_service.start_session(profiling_config_cpu)
        profiling_service.stop_session(r2["sessionId"])

        comparison = profiling_service.compare_sessions(r1["sessionId"], r2["sessionId"])

        assert comparison["session1"] == r1["sessionId"]
        assert comparison["session2"] == r2["sessionId"]
        assert comparison["typeMatch"] is True
        assert comparison["bothCompleted"] is True

    def test_compare_different_type_sessions(
        self,
        profiling_service: MockProfilingService
    ):
        config1 = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()
        config2 = ProfilingSessionBuilder().as_memory().with_duration(1000).build()

        r1 = profiling_service.start_session(config1)
        profiling_service.stop_session(r1["sessionId"])

        r2 = profiling_service.start_session(config2)
        profiling_service.stop_session(r2["sessionId"])

        comparison = profiling_service.compare_sessions(r1["sessionId"], r2["sessionId"])

        assert comparison["typeMatch"] is False
        assert comparison["bothCompleted"] is True

    def test_compare_running_and_completed(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        r1 = profiling_service.start_session(profiling_config_cpu)
        profiling_service.stop_session(r1["sessionId"])

        r2 = profiling_service.start_session(profiling_config_cpu)

        comparison = profiling_service.compare_sessions(r1["sessionId"], r2["sessionId"])

        assert comparison["bothCompleted"] is False

    def test_compare_duration_difference(
        self,
        profiling_service: MockProfilingService
    ):
        config1 = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()
        config2 = ProfilingSessionBuilder().as_cpu().with_duration(3000).build()

        r1 = profiling_service.start_session(config1)
        profiling_service.stop_session(r1["sessionId"])

        r2 = profiling_service.start_session(config2)
        profiling_service.stop_session(r2["sessionId"])

        comparison = profiling_service.compare_sessions(r1["sessionId"], r2["sessionId"])

        assert comparison["durationDiff"] == 2000

    def test_compare_nonexistent_sessions(self, profiling_service: MockProfilingService):
        comparison = profiling_service.compare_sessions("nonexistent1", "nonexistent2")

        assert comparison["typeMatch"] is True
        assert comparison["bothCompleted"] is False
        assert comparison["durationDiff"] == 0


class TestProfilingConcurrency:
    def test_concurrent_session_start(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(100)
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        def start_session():
            return profiling_service.start_session(config)

        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            futures = [executor.submit(start_session) for _ in range(50)]
            results = [f.result() for f in futures]

        assert all(r["status"] == "started" for r in results)
        assert profiling_service.active_sessions == 50

    def test_concurrent_session_stop(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(100)
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        session_ids = []
        for _ in range(50):
            result = profiling_service.start_session(config)
            session_ids.append(result["sessionId"])

        def stop_session(sid):
            return profiling_service.stop_session(sid)

        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            futures = [executor.submit(stop_session, sid) for sid in session_ids]
            results = [f.result() for f in futures]

        assert all(r["status"] == "completed" for r in results)
        assert profiling_service.active_sessions == 0

    def test_concurrent_start_and_stop(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(50)
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        def start_and_stop():
            result = profiling_service.start_session(config)
            time.sleep(0.01)
            return profiling_service.stop_session(result["sessionId"])

        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            futures = [executor.submit(start_and_stop) for _ in range(30)]
            results = [f.result() for f in futures]

        assert len(results) == 30
        assert profiling_service.active_sessions == 0

    def test_concurrent_flamegraph_access(
        self,
        profiling_service: MockProfilingService,
        profiling_config_cpu: Dict[str, Any]
    ):
        result = profiling_service.start_session(profiling_config_cpu)
        session_id = result["sessionId"]
        profiling_service.stop_session(session_id)

        def get_flamegraph():
            return profiling_service.get_flamegraph(session_id)

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(get_flamegraph) for _ in range(20)]
            flamegraphs = [f.result() for f in futures]

        assert all(fg == flamegraphs[0] for fg in flamegraphs)
        assert len(flamegraphs[0]) > 0


class TestProfilingEdgeCases:
    def test_zero_duration_session(self, profiling_service: MockProfilingService):
        config = ProfilingSessionBuilder().as_cpu().with_duration(0).build()

        result = profiling_service.start_session(config)

        assert result["status"] == "started"
        assert result["duration"] == 0

    def test_negative_duration_treated_as_zero(
        self,
        profiling_service: MockProfilingService
    ):
        config = ProfilingSessionBuilder().as_cpu().with_duration(-100).build()

        result = profiling_service.start_session(config)

        assert result["status"] == "started"

    def test_very_short_session(self, profiling_service: MockProfilingService):
        config = ProfilingSessionBuilder().as_cpu().with_duration(1).build()

        result = profiling_service.start_session(config)
        session_id = result["sessionId"]
        profiling_service.stop_session(session_id)

        flamegraph = profiling_service.get_flamegraph(session_id)
        assert len(flamegraph) > 0

    def test_session_id_uniqueness(self, profiling_service: MockProfilingService):
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()
        session_ids = []

        for _ in range(100):
            result = profiling_service.start_session(config)
            session_ids.append(result["sessionId"])
            profiling_service.stop_session(result["sessionId"])

        assert len(session_ids) == len(set(session_ids))

    def test_max_sessions_recovery(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(1)
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        result = profiling_service.start_session(config)
        assert result["status"] == "started"
        profiling_service.stop_session(result["sessionId"])
        assert profiling_service.active_sessions == 0

        result2 = profiling_service.start_session(config)
        assert result2["status"] == "started"
        profiling_service.stop_session(result2["sessionId"])
        assert profiling_service.active_sessions == 0

    def test_degraded_session_handling(
        self,
        profiling_service: MockProfilingService
    ):
        profiling_service.set_max_concurrent(1)
        config = ProfilingSessionBuilder().as_cpu().with_duration(1000).build()

        r1 = profiling_service.start_session(config)
        r2 = profiling_service.start_session(config)

        assert r1["status"] == "started"
        assert r2["status"] == "degraded"
        assert "actualDuration" in r2

        r2_session = profiling_service.get_session(r2["sessionId"])
        assert r2_session is None
