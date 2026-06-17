import networkx as nx
from typing import List, Dict, Set, Optional, Tuple
from dataclasses import dataclass, field

from config.pipeline_config import PipelineStep, StepStatus


@dataclass
class DAGNode:
    step: PipelineStep
    status: StepStatus = StepStatus.PENDING
    retry_count: int = 0
    error: Optional[str] = None
    output_files: List[str] = field(default_factory=list)


class PipelineDAG:
    """Directed Acyclic Graph for pipeline step orchestration."""

    def __init__(self, steps: List[PipelineStep]):
        self.graph = nx.DiGraph()
        self.nodes: Dict[str, DAGNode] = {}
        self._build_graph(steps)
        self._validate()

    def _build_graph(self, steps: List[PipelineStep]) -> None:
        for step in steps:
            self.nodes[step.step_id] = DAGNode(step=step)
            self.graph.add_node(step.step_id)

        for step in steps:
            for dep_id in step.dependencies:
                if dep_id in self.nodes:
                    self.graph.add_edge(dep_id, step.step_id)

    def _validate(self) -> None:
        if not nx.is_directed_acyclic_graph(self.graph):
            cycles = list(nx.simple_cycles(self.graph))
            raise ValueError(f"Pipeline DAG contains cycles: {cycles}")

        step_ids = set(self.nodes.keys())
        for step_id, node in self.nodes.items():
            for dep_id in node.step.dependencies:
                if dep_id not in step_ids:
                    raise ValueError(f"Step '{step_id}' has unknown dependency '{dep_id}'")

    def get_step(self, step_id: str) -> Optional[DAGNode]:
        return self.nodes.get(step_id)

    def set_step_status(self, step_id: str, status: StepStatus, error: Optional[str] = None) -> None:
        if step_id in self.nodes:
            self.nodes[step_id].status = status
            if error:
                self.nodes[step_id].error = error

    def set_step_outputs(self, step_id: str, output_files: List[str]) -> None:
        if step_id in self.nodes:
            self.nodes[step_id].output_files = output_files

    def get_ready_steps(self) -> List[PipelineStep]:
        ready = []
        for step_id, node in self.nodes.items():
            if node.status == StepStatus.PENDING:
                predecessors = list(self.graph.predecessors(step_id))
                if all(
                    self.nodes[pred_id].status == StepStatus.COMPLETED
                    for pred_id in predecessors
                ):
                    ready.append(node.step)
        return ready

    def get_running_steps(self) -> List[PipelineStep]:
        return [
            node.step for node in self.nodes.values()
            if node.status == StepStatus.RUNNING
        ]

    def get_completed_steps(self) -> List[PipelineStep]:
        return [
            node.step for node in self.nodes.values()
            if node.status == StepStatus.COMPLETED
        ]

    def get_failed_steps(self) -> List[PipelineStep]:
        return [
            node.step for node in self.nodes.values()
            if node.status == StepStatus.FAILED
        ]

    def is_complete(self) -> bool:
        return all(
            node.status in (StepStatus.COMPLETED, StepStatus.SKIPPED, StepStatus.FAILED)
            for node in self.nodes.values()
        )

    def is_success(self) -> bool:
        return all(
            node.status in (StepStatus.COMPLETED, StepStatus.SKIPPED)
            for node in self.nodes.values()
        )

    def has_failed(self) -> bool:
        return any(node.status == StepStatus.FAILED for node in self.nodes.values())

    def topo_sort(self) -> List[str]:
        return list(nx.topological_sort(self.graph))

    def get_parallel_groups(self) -> Dict[str, List[str]]:
        groups: Dict[str, List[str]] = {}
        for step_id, node in self.nodes.items():
            if node.step.parallel_group:
                group = node.step.parallel_group
                if group not in groups:
                    groups[group] = []
                groups[group].append(step_id)
        return groups

    def get_dependencies(self, step_id: str) -> List[str]:
        if step_id not in self.graph:
            return []
        return list(self.graph.predecessors(step_id))

    def get_descendants(self, step_id: str) -> Set[str]:
        if step_id not in self.graph:
            return set()
        return set(nx.descendants(self.graph, step_id))

    def get_progress(self) -> Tuple[int, int]:
        total = len(self.nodes)
        completed = sum(
            1 for node in self.nodes.values()
            if node.status in (StepStatus.COMPLETED, StepStatus.SKIPPED)
        )
        return completed, total

    def get_progress_percent(self) -> float:
        completed, total = self.get_progress()
        if total == 0:
            return 100.0
        return (completed / total) * 100

    def reset_failed_steps(self) -> int:
        count = 0
        for node in self.nodes.values():
            if node.status == StepStatus.FAILED:
                node.status = StepStatus.PENDING
                node.error = None
                count += 1
        return count

    def skip_step(self, step_id: str) -> bool:
        if step_id in self.nodes:
            self.nodes[step_id].status = StepStatus.SKIPPED
            return True
        return False

    def get_step_outputs_map(self) -> Dict[str, List[str]]:
        return {
            step_id: node.output_files
            for step_id, node in self.nodes.items()
            if node.output_files
        }
