import sys
from pathlib import Path
from typing import List, Dict, Any

import pytest
import networkx as nx

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from pipeline.dag import PipelineDAG, DAGNode
from config.pipeline_config import PipelineStep, PipelineStepType, StepStatus, PipelineDefinition


pytestmark = [pytest.mark.unit, pytest.mark.dag]


def _make_steps(steps_def: List[Dict[str, Any]]) -> List[PipelineStep]:
    steps = []
    for s in steps_def:
        step_type = s.get("step_type", "fastqc")
        if isinstance(step_type, str):
            step_type = PipelineStepType(step_type)
        steps.append(PipelineStep(
            step_id=s["step_id"],
            step_type=step_type,
            name=s.get("name", s["step_id"]),
            description=s.get("description", ""),
            inputs=s.get("inputs", []),
            outputs=s.get("outputs", []),
            dependencies=s.get("dependencies", []),
            params=s.get("params", {}),
            max_retries=s.get("max_retries", 3),
            parallel_group=s.get("parallel_group"),
            is_parallel=s.get("is_parallel", False),
        ))
    return steps


class TestDAGTopologicalSort:
    def test_linear_pipeline_topological_order(self):
        steps_def = [
            {"step_id": "step1_fastqc", "step_type": "fastqc", "name": "FastQC", "dependencies": []},
            {"step_id": "step2_fastp", "step_type": "fastp", "name": "fastp", "dependencies": ["step1_fastqc"]},
            {"step_id": "step3_bwa", "step_type": "bwa_mem", "name": "BWA-MEM", "dependencies": ["step2_fastp"]},
            {"step_id": "step4_sort", "step_type": "samtools_sort", "name": "Samtools Sort", "dependencies": ["step3_bwa"]},
            {"step_id": "step5_dedup", "step_type": "mark_duplicates", "name": "MarkDuplicates", "dependencies": ["step4_sort"]},
            {"step_id": "step6_hc", "step_type": "haplotype_caller", "name": "HaplotypeCaller", "dependencies": ["step5_dedup"]},
            {"step_id": "step7_vep", "step_type": "vep_annotation", "name": "VEP", "dependencies": ["step6_hc"]},
            {"step_id": "step8_report", "step_type": "report_generation", "name": "Report", "dependencies": ["step7_vep"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        order = dag.topo_sort()

        assert order.index("step1_fastqc") < order.index("step2_fastp")
        assert order.index("step2_fastp") < order.index("step3_bwa")
        assert order.index("step3_bwa") < order.index("step4_sort")
        assert order.index("step4_sort") < order.index("step5_dedup")
        assert order.index("step5_dedup") < order.index("step6_hc")
        assert order.index("step6_hc") < order.index("step7_vep")
        assert order.index("step7_vep") < order.index("step8_report")

    def test_real_pipeline_topological_order(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST001")
        dag = PipelineDAG(steps)
        order = dag.topo_sort()

        fastqc_idx = order.index("TEST001_fastqc")
        fastp_idx = order.index("TEST001_fastp")
        bwa_idx = order.index("TEST001_bwa_mem")
        sort_idx = order.index("TEST001_samtools_sort")
        dedup_idx = order.index("TEST001_mark_duplicates")
        bqsr_idx = order.index("TEST001_apply_bqsr")
        hc_idx = order.index("TEST001_haplotype_caller")
        vep_idx = order.index("TEST001_vep_annotation")
        report_idx = order.index("TEST001_report_generation")

        assert fastqc_idx < fastp_idx
        assert fastp_idx < bwa_idx
        assert bwa_idx < sort_idx
        assert sort_idx < dedup_idx
        assert dedup_idx < order.index("TEST001_base_recalibrator")
        assert order.index("TEST001_base_recalibrator") < bqsr_idx
        assert bqsr_idx < hc_idx
        assert hc_idx < vep_idx
        assert vep_idx < order.index("TEST001_dbnsfp_annotation")
        assert order.index("TEST001_dbnsfp_annotation") < order.index("TEST001_clinvar_annotation")
        assert order.index("TEST001_clinvar_annotation") < order.index("TEST001_acmg_classification")
        assert order.index("TEST001_acmg_classification") < report_idx

    def test_topological_sort_all_steps_present(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST002")
        dag = PipelineDAG(steps)
        order = dag.topo_sort()
        assert len(order) == len(steps)
        assert set(order) == {s.step_id for s in steps}

    def test_topological_order_respects_all_dependencies(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
            {"step_id": "D", "step_type": "samtools_sort", "name": "D", "dependencies": ["C"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)
        order = dag.topo_sort()

        for i, step_id in enumerate(order):
            deps = dag.get_dependencies(step_id)
            for dep_id in deps:
                assert order.index(dep_id) < i


class TestCyclicDependencyDetection:
    def test_simple_cycle_A_B_A(self):
        steps_def = [
            {"step_id": "step_A", "step_type": "fastqc", "name": "A", "dependencies": ["step_B"]},
            {"step_id": "step_B", "step_type": "fastp", "name": "B", "dependencies": ["step_A"]},
        ]
        steps = _make_steps(steps_def)

        with pytest.raises(ValueError, match="cycle|cycles"):
            PipelineDAG(steps)

    def test_three_node_cycle(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": ["C"]},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
        ]
        steps = _make_steps(steps_def)

        with pytest.raises(ValueError, match="cycle"):
            PipelineDAG(steps)

    def test_self_loop_dependency(self):
        steps_def = [
            {"step_id": "step_A", "step_type": "fastqc", "name": "A", "dependencies": ["step_A"]},
        ]
        steps = _make_steps(steps_def)

        with pytest.raises(ValueError, match="cycle"):
            PipelineDAG(steps)

    def test_complex_cycle_with_valid_nodes(self):
        steps_def = [
            {"step_id": "valid_start", "step_type": "fastqc", "name": "Start", "dependencies": []},
            {"step_id": "A", "step_type": "fastp", "name": "A", "dependencies": ["valid_start", "C"]},
            {"step_id": "B", "step_type": "bwa_mem", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "samtools_sort", "name": "C", "dependencies": ["B"]},
            {"step_id": "valid_end", "step_type": "mark_duplicates", "name": "End", "dependencies": ["C"]},
        ]
        steps = _make_steps(steps_def)

        with pytest.raises(ValueError, match="cycle"):
            PipelineDAG(steps)

    def test_no_cycle_valid_pipeline(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST003")
        dag = PipelineDAG(steps)
        assert nx.is_directed_acyclic_graph(dag.graph)

    def test_unknown_dependency_raises_error(self):
        steps_def = [
            {"step_id": "step_A", "step_type": "fastqc", "name": "A", "dependencies": ["nonexistent_step"]},
        ]
        steps = _make_steps(steps_def)

        with pytest.raises(ValueError, match="unknown|dependency"):
            PipelineDAG(steps)


class TestParallelBranchDetection:
    def test_parallel_group_by_config(self):
        steps_def = [
            {"step_id": "S", "step_type": "fastqc", "name": "S", "dependencies": []},
            {
                "step_id": "chr1_bwa", "step_type": "bwa_mem", "name": "chr1 BWA",
                "dependencies": ["S"], "parallel_group": "by_chromosome", "is_parallel": True,
            },
            {
                "step_id": "chr2_bwa", "step_type": "bwa_mem", "name": "chr2 BWA",
                "dependencies": ["S"], "parallel_group": "by_chromosome", "is_parallel": True,
            },
            {
                "step_id": "chr3_bwa", "step_type": "bwa_mem", "name": "chr3 BWA",
                "dependencies": ["S"], "parallel_group": "by_chromosome", "is_parallel": True,
            },
            {"step_id": "merge", "step_type": "samtools_sort", "name": "Merge",
             "dependencies": ["chr1_bwa", "chr2_bwa", "chr3_bwa"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        groups = dag.get_parallel_groups()
        assert "by_chromosome" in groups
        assert set(groups["by_chromosome"]) == {"chr1_bwa", "chr2_bwa", "chr3_bwa"}

    def test_bwa_mem_parallel_group_config(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST004")
        dag = PipelineDAG(steps)

        bwa_node = dag.get_step("TEST004_bwa_mem")
        hc_node = dag.get_step("TEST004_haplotype_caller")

        assert bwa_node.step.parallel_group == "by_chromosome"
        assert bwa_node.step.is_parallel is True
        assert hc_node.step.parallel_group == "by_chromosome"
        assert hc_node.step.is_parallel is True

    def test_independent_steps_share_ready_queue(self):
        steps_def = [
            {"step_id": "bwa", "step_type": "bwa_mem", "name": "BWA", "dependencies": []},
            {"step_id": "sort", "step_type": "samtools_sort", "name": "Sort", "dependencies": ["bwa"]},
            {"step_id": "index", "step_type": "samtools_index", "name": "Index", "dependencies": ["sort"]},
            {"step_id": "qc_bam", "step_type": "fastqc", "name": "BAM QC", "dependencies": ["sort"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("bwa", StepStatus.COMPLETED)
        dag.set_step_status("sort", StepStatus.COMPLETED)

        ready_steps = dag.get_ready_steps()
        ready_ids = {s.step_id for s in ready_steps}

        assert "index" in ready_ids
        assert "qc_bam" in ready_ids
        assert len(ready_ids) == 2

    def test_diamond_dependency_parallel_paths(self):
        steps_def = [
            {"step_id": "S", "step_type": "fastqc", "name": "S", "dependencies": []},
            {"step_id": "A1", "step_type": "fastp", "name": "A1", "dependencies": ["S"]},
            {"step_id": "A2", "step_type": "bwa_mem", "name": "A2", "dependencies": ["S"]},
            {"step_id": "A3", "step_type": "samtools_sort", "name": "A3", "dependencies": ["S"]},
            {"step_id": "B1", "step_type": "mark_duplicates", "name": "B1", "dependencies": ["A1", "A2"]},
            {"step_id": "B2", "step_type": "base_recalibrator", "name": "B2", "dependencies": ["A3"]},
            {"step_id": "E", "step_type": "apply_bqsr", "name": "End", "dependencies": ["B1", "B2"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("S", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert ready == {"A1", "A2", "A3"}

        dag.set_step_status("A1", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert "A2" in ready and "A3" in ready

        dag.set_step_status("A2", StepStatus.COMPLETED)
        dag.set_step_status("A3", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert "B1" in ready and "B2" in ready


class TestReadyStepsAndProgress:
    def test_initial_ready_steps(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        ready = {s.step_id for s in dag.get_ready_steps()}
        assert ready == {"A"}

    def test_ready_steps_after_completion(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["A"]},
            {"step_id": "D", "step_type": "samtools_sort", "name": "D", "dependencies": ["B", "C"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("A", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert ready == {"B", "C"}

        dag.set_step_status("B", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert ready == {"C"}

        dag.set_step_status("C", StepStatus.COMPLETED)
        ready = {s.step_id for s in dag.get_ready_steps()}
        assert ready == {"D"}

    def test_progress_calculation(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
            {"step_id": "D", "step_type": "samtools_sort", "name": "D", "dependencies": ["C"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        completed, total = dag.get_progress()
        assert completed == 0 and total == 4
        assert dag.get_progress_percent() == 0.0

        dag.set_step_status("A", StepStatus.COMPLETED)
        assert dag.get_progress_percent() == 25.0

        dag.set_step_status("B", StepStatus.COMPLETED)
        assert dag.get_progress_percent() == 50.0

        dag.set_step_status("C", StepStatus.COMPLETED)
        dag.set_step_status("D", StepStatus.COMPLETED)
        assert dag.get_progress_percent() == 100.0

    def test_is_complete_detection(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        assert not dag.is_complete()
        assert not dag.is_success()

        dag.set_step_status("A", StepStatus.COMPLETED)
        dag.set_step_status("B", StepStatus.COMPLETED)
        assert not dag.is_complete()

        dag.set_step_status("C", StepStatus.COMPLETED)
        assert dag.is_complete()
        assert dag.is_success()

    def test_failed_steps_and_reset(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("A", StepStatus.COMPLETED)
        dag.set_step_status("B", StepStatus.FAILED, error="Test error")

        assert dag.has_failed()
        assert not dag.is_success()
        assert dag.is_complete()

        failed = dag.get_failed_steps()
        assert len(failed) == 1
        assert failed[0].step_id == "B"

        dag.reset_failed_steps()
        assert not dag.has_failed()
        assert dag.get_step("B").status == StepStatus.PENDING
        assert dag.get_step("B").error is None

    def test_skipped_steps_count_as_progress(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["B"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("A", StepStatus.COMPLETED)
        dag.skip_step("B")

        completed, total = dag.get_progress()
        assert completed == 2
        assert dag.is_success() is False

        dag.set_step_status("C", StepStatus.COMPLETED)
        assert dag.is_success() is True

    def test_descendants_detection(self):
        steps_def = [
            {"step_id": "S", "step_type": "fastqc", "name": "S", "dependencies": []},
            {"step_id": "A", "step_type": "fastp", "name": "A", "dependencies": ["S"]},
            {"step_id": "B", "step_type": "bwa_mem", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "samtools_sort", "name": "C", "dependencies": ["B"]},
            {"step_id": "D", "step_type": "mark_duplicates", "name": "D", "dependencies": ["B"]},
            {"step_id": "E", "step_type": "base_recalibrator", "name": "E", "dependencies": ["C", "D"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        descendants_of_S = dag.get_descendants("S")
        assert descendants_of_S == {"A", "B", "C", "D", "E"}

        descendants_of_A = dag.get_descendants("A")
        assert descendants_of_A == {"B", "C", "D", "E"}

        descendants_of_B = dag.get_descendants("B")
        assert descendants_of_B == {"C", "D", "E"}

        descendants_of_E = dag.get_descendants("E")
        assert descendants_of_E == set()


class TestDAGStateManagement:
    def test_step_status_transitions(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        node = dag.get_step("A")
        assert node.status == StepStatus.PENDING

        dag.set_step_status("A", StepStatus.RUNNING)
        assert dag.get_step("A").status == StepStatus.RUNNING

        dag.set_step_status("A", StepStatus.COMPLETED)
        assert dag.get_step("A").status == StepStatus.COMPLETED
        assert dag.get_step("A").retry_count == 0

    def test_set_step_outputs(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        outputs = ["/path/to/output1.txt", "/path/to/output2.bam"]
        dag.set_step_outputs("A", outputs)

        assert dag.get_step("A").output_files == outputs
        outputs_map = dag.get_step_outputs_map()
        assert "A" in outputs_map
        assert outputs_map["A"] == outputs

    def test_all_steps_in_nodes(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST005")
        dag = PipelineDAG(steps)

        for step in steps:
            assert step.step_id in dag.nodes
            node = dag.get_step(step.step_id)
            assert node is not None
            assert node.step.step_id == step.step_id

    def test_edges_match_dependencies(self):
        steps = PipelineDefinition.get_single_sample_pipeline("TEST006")
        dag = PipelineDAG(steps)

        total_deps = sum(len(s.dependencies) for s in steps)
        assert dag.graph.number_of_edges() == total_deps

        for step in steps:
            for dep_id in step.dependencies:
                assert dag.graph.has_edge(dep_id, step.step_id)

    def test_running_steps_tracking(self):
        steps_def = [
            {"step_id": "A", "step_type": "fastqc", "name": "A", "dependencies": []},
            {"step_id": "B", "step_type": "fastp", "name": "B", "dependencies": ["A"]},
            {"step_id": "C", "step_type": "bwa_mem", "name": "C", "dependencies": ["A"]},
        ]
        steps = _make_steps(steps_def)
        dag = PipelineDAG(steps)

        dag.set_step_status("A", StepStatus.COMPLETED)
        dag.set_step_status("B", StepStatus.RUNNING)
        dag.set_step_status("C", StepStatus.RUNNING)

        running = {s.step_id for s in dag.get_running_steps()}
        assert running == {"B", "C"}

        completed = {s.step_id for s in dag.get_completed_steps()}
        assert completed == {"A"}
