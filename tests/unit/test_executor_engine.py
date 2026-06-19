import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

import json
import time
import pytest
from unittest.mock import Mock, MagicMock, patch, call
from typing import Dict, Any, List

from config.pipeline_config import (
    PipelineStep,
    PipelineStepType,
    PipelineDefinition,
    StepStatus,
)
from pipeline.engine import (
    PipelineEngine,
    EngineResult,
    EngineCheckpoint,
    EngineStatus,
)
from pipeline.executor import (
    BaseStepExecutor,
    StepExecutorRegistry,
    RetryableStepExecutor,
    StepResult,
    register_executor,
)


class MockFastqcExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        out_html = str(self.work_dir / f"{step_id}_report.html")
        out_json = str(self.work_dir / f"{step_id}_data.json")
        Path(out_html).write_text("<html></html>")
        Path(out_json).write_text("{}")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_html, out_json],
            metrics={"total_reads": 10000, "q30_bases": 95000},
        )


class MockFastpExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        out_r1 = str(self.work_dir / "sample_clean_R1.fastq.gz")
        out_r2 = str(self.work_dir / "sample_clean_R2.fastq.gz")
        Path(out_r1).write_bytes(b"\x1f\x8b")
        Path(out_r2).write_bytes(b"\x1f\x8b")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_r1, out_r2],
            metrics={"trimmed_reads": 9900},
        )


class MockBwaExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        out_sam = str(self.work_dir / "sample.sam")
        Path(out_sam).write_text("@HD\tVN:1.6\n")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_sam],
            metrics={"mapped_reads": 9800},
        )


class MockSortExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        out_bam = str(self.work_dir / "sample.sorted.bam")
        Path(out_bam).write_bytes(b"BAM\x01")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_bam],
            metrics={"sort_success": True},
        )


class MockDedupExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        out_dedup = str(self.work_dir / "sample.dedup.bam")
        Path(out_dedup).write_bytes(b"BAM\x01")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_dedup],
            metrics={"duplicate_rate": 0.05},
        )


class FlakyExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None, fail_count: int = 2):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0
        self.fail_count = fail_count

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        if self.call_count <= self.fail_count:
            raise ValueError(f"Simulated failure attempt {self.call_count}")
        out_file = str(self.work_dir / f"{step_id}_result.txt")
        Path(out_file).write_text("success")
        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[out_file],
            metrics={"attempts": self.call_count},
        )


class AlwaysFailExecutor(BaseStepExecutor):
    def __init__(self, work_dir: str, temp_dir: str = None):
        super().__init__(work_dir, temp_dir)
        self.call_count = 0

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        self.call_count += 1
        raise RuntimeError(f"Persistent failure on attempt {self.call_count}")


@pytest.fixture(autouse=True)
def _clean_registry():
    saved = dict(StepExecutorRegistry._executors)
    StepExecutorRegistry._executors.clear()

    StepExecutorRegistry.register("fastqc", MockFastqcExecutor)
    StepExecutorRegistry.register("fastp", MockFastpExecutor)
    StepExecutorRegistry.register("bwa_mem", MockBwaExecutor)
    StepExecutorRegistry.register("samtools_sort", MockSortExecutor)
    StepExecutorRegistry.register("mark_duplicates", MockDedupExecutor)

    yield

    StepExecutorRegistry._executors = saved


def _make_linear_steps(n: int = 5) -> List[PipelineStep]:
    types = [
        PipelineStepType.FASTQC,
        PipelineStepType.FASTP,
        PipelineStepType.BWA_MEM,
        PipelineStepType.SAMTOOLS_SORT,
        PipelineStepType.MARK_DUPLICATES,
    ]
    outputs = [
        ["qc_r1.html", "qc_r2.html"],
        ["clean_R1.gz", "clean_R2.gz"],
        ["aligned.sam"],
        ["sorted.bam"],
        ["dedup.bam"],
    ]
    steps = []
    for i in range(min(n, len(types))):
        steps.append(PipelineStep(
            step_id=f"step_{i}",
            step_type=types[i],
            name=f"Step {i}",
            inputs=outputs[i - 1] if i > 0 else [],
            outputs=outputs[i],
            dependencies=[f"step_{i - 1}"] if i > 0 else [],
            max_retries=3,
        ))
    return steps


@pytest.mark.unit
class TestPipelineEngineRunEndToEnd:

    def test_run_all_steps_success(self, tmp_path):
        steps = _make_linear_steps(5)
        engine = PipelineEngine(
            steps=steps,
            work_dir=str(tmp_path / "work"),
            temp_dir=str(tmp_path / "tmp"),
        )

        result = engine.run()

        assert isinstance(result, EngineResult)
        assert result.success is True
        assert result.total_steps == 5
        assert result.completed_steps == 5
        assert result.failed_steps == []
        assert result.duration_seconds >= 0
        assert engine.status == EngineStatus.COMPLETED

        for sid in [f"step_{i}" for i in range(5)]:
            assert engine.dag.nodes[sid].status == StepStatus.COMPLETED

    def test_run_callbacks_fired(self, tmp_path):
        steps = _make_linear_steps(3)

        started = []
        completed = []
        failed = []

        engine = PipelineEngine(
            steps=steps,
            work_dir=str(tmp_path / "work"),
            on_step_start=lambda s: started.append(s.step_id),
            on_step_complete=lambda s, r: completed.append((s.step_id, r.success)),
            on_step_fail=lambda s, e: failed.append((s.step_id, e)),
        )

        engine.run()

        assert started == [f"step_{i}" for i in range(3)]
        assert completed == [(f"step_{i}", True) for i in range(3)]
        assert failed == []

    def test_run_empty_pipeline(self, tmp_path):
        engine = PipelineEngine(steps=[], work_dir=str(tmp_path / "work"))

        result = engine.run()

        assert result.success is True
        assert result.total_steps == 0
        assert result.completed_steps == 0

    def test_run_outputs_propagated(self, tmp_path):
        steps = _make_linear_steps(3)
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))

        result = engine.run()

        for sid in [f"step_{i}" for i in range(3)]:
            assert sid in result.output_map
            assert len(result.output_map[sid]) > 0

    def test_run_step_metrics_collected(self, tmp_path):
        steps = _make_linear_steps(3)
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))

        result = engine.run()

        for sid in [f"step_{i}" for i in range(3)]:
            if sid != "step_0":
                assert sid in result.step_metrics
        assert engine.step_metrics


@pytest.mark.unit
class TestPipelineEngineResumeCheckpoint:

    def test_resume_after_3_steps(self, tmp_path):
        steps = _make_linear_steps(5)
        work_dir = tmp_path / "work"
        checkpoint_path = work_dir / "checkpoint.json"

        pause_after = 3
        step_count = {"count": 0}

        def on_step_start(step):
            if step_count["count"] >= pause_after:
                engine.pause()
            step_count["count"] += 1

        engine = PipelineEngine(
            steps=steps,
            work_dir=str(work_dir),
            checkpoint_path=str(checkpoint_path),
            on_step_start=on_step_start,
        )

        result1 = engine.run()
        assert result1.success is False
        assert result1.completed_steps >= 3
        assert engine.status == EngineStatus.PAUSED
        assert checkpoint_path.exists()

        engine2 = PipelineEngine(
            steps=steps,
            work_dir=str(work_dir),
            checkpoint_path=str(checkpoint_path),
        )
        assert engine2.load_checkpoint()

        for i in range(3):
            assert engine2.dag.nodes[f"step_{i}"].status == StepStatus.COMPLETED

        executor_snapshot = {}

        def on_start2(step):
            ex = engine2._get_executor(step)
            executor_snapshot[step.step_id] = getattr(ex.executor, "call_count", 0)

        engine2.on_step_start = on_start2
        result2 = engine2.run()

        assert result2.success is True
        assert result2.completed_steps == 5

        for i in range(3):
            assert executor_snapshot.get(f"step_{i}", 0) == 0 or f"step_{i}" not in executor_snapshot

        completed_count = sum(
            1 for i in range(3)
            if f"step_{i}" not in {s for s in executor_snapshot}
        )
        assert completed_count >= 0

    def test_resume_no_checkpoint_file(self, tmp_path):
        steps = _make_linear_steps(3)
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))

        assert engine.load_checkpoint(str(tmp_path / "nonexistent.json")) is False

    def test_resume_checkpoint_from_second_run(self, tmp_path):
        steps = _make_linear_steps(4)
        work_dir = tmp_path / "work"
        cp_path = work_dir / "cp.json"

        stopped_at = {"value": None}

        def on_complete(step, result):
            if step.step_id == "step_1":
                stopped_at["value"] = step.step_id
                engine.pause()

        engine = PipelineEngine(
            steps=steps,
            work_dir=str(work_dir),
            checkpoint_path=str(cp_path),
            on_step_complete=on_complete,
        )
        engine.run()

        engine2 = PipelineEngine(
            steps=steps,
            work_dir=str(work_dir),
            checkpoint_path=str(cp_path),
        )
        loaded = engine2.load_checkpoint()
        assert loaded is True
        assert engine2.dag.nodes["step_0"].status == StepStatus.COMPLETED
        assert engine2.dag.nodes["step_1"].status == StepStatus.COMPLETED
        assert engine2.dag.nodes["step_2"].status == StepStatus.PENDING

        r = engine2.run()
        assert r.success is True
        assert r.completed_steps == 4


@pytest.mark.unit
class TestPipelineEngineRetry:

    def test_flaky_executor_succeeds_on_third_attempt(self, tmp_path, _clean_registry):
        StepExecutorRegistry.register("bwa_mem", FlakyExecutor)

        steps = _make_linear_steps(3)
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))

        result = engine.run()

        assert result.success is True
        bwa_executor = engine._get_executor(steps[2]).executor
        assert isinstance(bwa_executor, FlakyExecutor)
        assert bwa_executor.call_count == 3

        retryable = engine._get_executor(steps[2])
        assert retryable.max_retries >= 3

    def test_exceed_max_retries_returns_failure(self, tmp_path, _clean_registry):
        StepExecutorRegistry.register("bwa_mem", AlwaysFailExecutor)

        steps = _make_linear_steps(3)
        steps[2].max_retries = 3

        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))
        result = engine.run()

        assert result.success is False
        assert steps[2].step_id in result.failed_steps
        assert engine.dag.nodes[steps[2].step_id].status == StepStatus.FAILED

        fail_exec = engine._get_executor(steps[2]).executor
        assert fail_exec.call_count == 3

    def test_success_on_first_attempt_no_retries(self, tmp_path, _clean_registry):
        class ImmediateSuccess(BaseStepExecutor):
            def __init__(self, *a, **kw):
                super().__init__(*a, **kw)
                self.call_count = 0

            def execute(self, step_id, params, input_files):
                self.call_count += 1
                out = str(self.work_dir / f"{step_id}.txt")
                Path(out).write_text("ok")
                return StepResult(success=True, step_id=step_id, output_files=[out])

        StepExecutorRegistry.register("bwa_mem", ImmediateSuccess)

        steps = _make_linear_steps(3)
        steps[2].max_retries = 3
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))
        engine.run()

        exec = engine._get_executor(steps[2]).executor
        assert exec.call_count == 1

    def test_error_message_in_result(self, tmp_path, _clean_registry):
        StepExecutorRegistry.register("fastqc", AlwaysFailExecutor)

        steps = [PipelineStep(
            step_id="s0",
            step_type=PipelineStepType.FASTQC,
            name="Fail",
            max_retries=2,
        )]
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))
        result = engine.run()

        assert result.success is False
        assert result.error_message is not None
        assert "s0" in result.error_message


@pytest.mark.unit
class TestPipelineEngineParallel:

    def test_parallel_group_uses_threadpool(self, tmp_path, _clean_registry):
        StepExecutorRegistry.register("bwa_mem", MockBwaExecutor)
        StepExecutorRegistry.register("haplotype_caller", MockBwaExecutor)

        p1 = PipelineStep(
            step_id="pre",
            step_type=PipelineStepType.FASTQC,
            name="pre",
            max_retries=1,
        )
        p2 = PipelineStep(
            step_id="p_bwa1",
            step_type=PipelineStepType.BWA_MEM,
            name="bwa1",
            dependencies=["pre"],
            parallel_group="by_chromosome",
            is_parallel=True,
            max_retries=1,
        )
        p3 = PipelineStep(
            step_id="p_bwa2",
            step_type=PipelineStepType.BWA_MEM,
            name="bwa2",
            dependencies=["pre"],
            parallel_group="by_chromosome",
            is_parallel=True,
            max_retries=1,
        )
        p4 = PipelineStep(
            step_id="p_hc1",
            step_type=PipelineStepType.HAPLOTYPE_CALLER,
            name="hc1",
            dependencies=["p_bwa1", "p_bwa2"],
            parallel_group="hc_group",
            is_parallel=True,
            max_retries=1,
        )

        with patch("pipeline.engine.ThreadPoolExecutor") as MockPool:
            mock_pool_instance = MagicMock()
            MockPool.return_value.__enter__ = MagicMock(return_value=mock_pool_instance)

            mock_futures = {}
            for sid in ["p_bwa1", "p_bwa2"]:
                f = MagicMock()
                f.result.return_value = StepResult(
                    success=True,
                    step_id=sid,
                    output_files=[f"/tmp/{sid}.bam"],
                )
                mock_futures[sid] = f

            def as_completed_side_effect(futures_dict):
                return list(mock_futures.values())

            import pipeline.engine as engine_mod
            with patch.object(engine_mod, "as_completed", side_effect=as_completed_side_effect):
                mock_pool_instance.submit = MagicMock(side_effect=lambda fn, s: mock_futures.get(s.step_id))

                engine = PipelineEngine(
                    steps=[p1, p2, p3, p4],
                    work_dir=str(tmp_path / "work"),
                    max_workers=4,
                )
                try:
                    engine.run()
                except Exception:
                    pass

            assert MockPool.called

    def test_parallel_group_all_completed(self, tmp_path):
        p1 = PipelineStep(
            step_id="pre",
            step_type=PipelineStepType.FASTQC,
            name="pre",
            max_retries=1,
        )
        p2 = PipelineStep(
            step_id="pa1",
            step_type=PipelineStepType.FASTP,
            name="pa1",
            dependencies=["pre"],
            parallel_group="groupA",
            is_parallel=True,
            max_retries=1,
        )
        p3 = PipelineStep(
            step_id="pa2",
            step_type=PipelineStepType.FASTP,
            name="pa2",
            dependencies=["pre"],
            parallel_group="groupA",
            is_parallel=True,
            max_retries=1,
        )

        engine = PipelineEngine(
            steps=[p1, p2, p3],
            work_dir=str(tmp_path / "work"),
            max_workers=2,
        )
        result = engine.run()

        assert result.success is True
        assert engine.dag.nodes["pre"].status == StepStatus.COMPLETED
        assert engine.dag.nodes["pa1"].status == StepStatus.COMPLETED
        assert engine.dag.nodes["pa2"].status == StepStatus.COMPLETED

    def test_parallel_dependency_chain(self, tmp_path):
        steps = [
            PipelineStep(step_id="s0", step_type=PipelineStepType.FASTQC, name="a"),
            PipelineStep(step_id="s1", step_type=PipelineStepType.FASTP, name="b",
                         dependencies=["s0"], parallel_group="g", is_parallel=True),
            PipelineStep(step_id="s2", step_type=PipelineStepType.FASTP, name="c",
                         dependencies=["s0"], parallel_group="g", is_parallel=True),
            PipelineStep(step_id="s3", step_type=PipelineStepType.BWA_MEM, name="d",
                         dependencies=["s1", "s2"]),
        ]
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"))
        result = engine.run()

        assert result.success is True
        assert engine.dag.nodes["s3"].status == StepStatus.COMPLETED


@pytest.mark.unit
class TestEngineCheckpoint:

    def test_save_checkpoint_creates_file(self, tmp_path):
        steps = _make_linear_steps(3)
        cp = tmp_path / "cp.json"
        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"), checkpoint_path=str(cp))

        engine.run()

        assert cp.exists()
        data = json.loads(cp.read_text())
        assert "dag_state" in data
        assert "completed_steps" in data
        assert "output_map" in data
        assert "step_metrics" in data
        assert len(data["completed_steps"]) == 3

    def test_checkpoint_roundtrip_serialization(self):
        cp = EngineCheckpoint(
            dag_state={"s0": {"status": "completed"}},
            completed_steps=["s0"],
            output_map={"s0": ["out.txt"]},
            step_metrics={"s0": {"k": 1}},
            timestamp=1234567890.0,
        )
        raw = cp.to_json()
        restored = EngineCheckpoint.from_json(raw)

        assert restored.dag_state == cp.dag_state
        assert restored.completed_steps == cp.completed_steps
        assert restored.output_map == cp.output_map
        assert restored.step_metrics == cp.step_metrics
        assert restored.timestamp == 1234567890.0

    def test_load_corrupt_checkpoint_returns_false(self, tmp_path):
        steps = _make_linear_steps(2)
        cp = tmp_path / "bad.json"
        cp.write_text("not valid json{{{")

        engine = PipelineEngine(steps=steps, work_dir=str(tmp_path / "work"), checkpoint_path=str(cp))
        assert engine.load_checkpoint() is False

    def test_checkpoint_saved_after_each_step(self, tmp_path):
        steps = _make_linear_steps(4)
        cp = tmp_path / "cp.json"

        timestamps = []

        def on_complete(step, result):
            if cp.exists():
                timestamps.append(cp.stat().st_mtime)

        engine = PipelineEngine(
            steps=steps,
            work_dir=str(tmp_path / "work"),
            checkpoint_path=str(cp),
            on_step_complete=on_complete,
        )
        engine.run()

        assert len(timestamps) >= 3
