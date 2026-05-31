from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

from streamsql.core.models import generate_id
from streamsql.modules.streaming_query.logical_plan import LogicalPlan, LogicalNode, NodeType


class PhysicalNodeType(str, Enum):
    TABLE_SCAN = "table_scan"
    INDEX_SCAN = "index_scan"
    SEQ_SCAN = "seq_scan"
    HASH_FILTER = "hash_filter"
    MERGE_FILTER = "merge_filter"
    HASH_PROJECT = "hash_project"
    HASH_JOIN = "hash_join"
    SORT_MERGE_JOIN = "sort_merge_join"
    NESTED_LOOP_JOIN = "nested_loop_join"
    HASH_AGGREGATE = "hash_aggregate"
    SORT_AGGREGATE = "sort_aggregate"
    TUMBLING_WINDOW = "tumbling_window"
    HOPPING_WINDOW = "hopping_window"
    SESSION_WINDOW = "session_window"
    EXTERNAL_SORT = "external_sort"
    TOP_N = "top_n"
    STREAM_RECEIVER = "stream_receiver"
    WATERMARK_GENERATOR = "watermark_generator"
    LOCAL_LIMIT = "local_limit"
    GLOBAL_LIMIT = "global_limit"
    PARTITION_BY = "partition_by"
    BROADCAST = "broadcast"


class ExecutionMode(str, Enum):
    BATCH = "batch"
    STREAMING = "streaming"
    HYBRID = "hybrid"


@dataclass
class PhysicalNode:
    node_id: str = field(default_factory=lambda: generate_id("pn"))
    node_type: PhysicalNodeType = PhysicalNodeType.TABLE_SCAN
    children: list["PhysicalNode"] = field(default_factory=list)
    properties: dict[str, Any] = field(default_factory=dict)
    parallelism: int = 1
    memory_mb: int = 128
    cpu_cores: float = 1.0
    estimated_throughput: float = 0.0
    stateful: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "node_id": self.node_id,
            "node_type": self.node_type.value,
            "children": [c.to_dict() for c in self.children],
            "properties": self.properties,
            "parallelism": self.parallelism,
            "memory_mb": self.memory_mb,
            "cpu_cores": self.cpu_cores,
            "estimated_throughput": self.estimated_throughput,
            "stateful": self.stateful,
        }


@dataclass
class PhysicalPlan:
    plan_id: str = field(default_factory=lambda: generate_id("pp"))
    root: Optional[PhysicalNode] = None
    execution_mode: ExecutionMode = ExecutionMode.BATCH
    parallelism: int = 1
    total_memory_mb: int = 1024
    total_cpu_cores: float = 4.0
    estimated_duration_ms: int = 0
    source_logical_plan: Optional[str] = None
    stages: list[dict[str, Any]] = field(default_factory=list)
    dependencies: list[tuple[str, str]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "plan_id": self.plan_id,
            "root": self.root.to_dict() if self.root else None,
            "execution_mode": self.execution_mode.value,
            "parallelism": self.parallelism,
            "total_memory_mb": self.total_memory_mb,
            "total_cpu_cores": self.total_cpu_cores,
            "estimated_duration_ms": self.estimated_duration_ms,
            "stages": self.stages,
            "dependencies": self.dependencies,
        }


class PhysicalPlanTranslator:
    def __init__(self, default_parallelism: int = 1):
        self.default_parallelism = default_parallelism

    def translate(self, logical_plan: LogicalPlan, execution_mode: ExecutionMode = ExecutionMode.BATCH) -> PhysicalPlan:
        plan = PhysicalPlan(
            execution_mode=execution_mode,
            source_logical_plan=logical_plan.plan_id,
            parallelism=self.default_parallelism,
        )

        if logical_plan.root:
            plan.root = self._translate_node(logical_plan.root, execution_mode)
            plan.stages = self._build_stages(plan.root)
            plan.dependencies = self._build_dependencies(plan.root)
            self._estimate_resources(plan)

        return plan

    def _translate_node(self, logical_node: LogicalNode, execution_mode: ExecutionMode) -> PhysicalNode:
        physical_node: Optional[PhysicalNode] = None
        children = [self._translate_node(c, execution_mode) for c in logical_node.children]

        node_type = logical_node.node_type

        if node_type == NodeType.SCAN:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.TABLE_SCAN,
                properties=logical_node.properties,
                children=children,
            )
        elif node_type == NodeType.FILTER:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.HASH_FILTER,
                properties=logical_node.properties,
                children=children,
            )
        elif node_type == NodeType.PROJECT:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.HASH_PROJECT,
                properties=logical_node.properties,
                children=children,
            )
        elif node_type == NodeType.JOIN:
            join_type = logical_node.properties.get("join_type", "inner")
            if join_type in ["inner", "left"]:
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.HASH_JOIN,
                    properties=logical_node.properties,
                    children=children,
                    memory_mb=512,
                    stateful=True,
                )
            else:
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.SORT_MERGE_JOIN,
                    properties=logical_node.properties,
                    children=children,
                    memory_mb=1024,
                    stateful=True,
                )
        elif node_type == NodeType.AGGREGATE:
            if execution_mode == ExecutionMode.STREAMING:
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.HASH_AGGREGATE,
                    properties=logical_node.properties,
                    children=children,
                    stateful=True,
                    memory_mb=256,
                )
            else:
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.SORT_AGGREGATE,
                    properties=logical_node.properties,
                    children=children,
                    memory_mb=256,
                )
        elif node_type == NodeType.WINDOW:
            window_type = logical_node.properties.get("type", "")
            if window_type == "TUMBLE":
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.TUMBLING_WINDOW,
                    properties=logical_node.properties,
                    children=children,
                    stateful=True,
                    memory_mb=384,
                )
            elif window_type == "HOP":
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.HOPPING_WINDOW,
                    properties=logical_node.properties,
                    children=children,
                    stateful=True,
                    memory_mb=512,
                )
            else:
                physical_node = PhysicalNode(
                    node_type=PhysicalNodeType.SESSION_WINDOW,
                    properties=logical_node.properties,
                    children=children,
                    stateful=True,
                    memory_mb=512,
                )
        elif node_type == NodeType.SORT:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.EXTERNAL_SORT,
                properties=logical_node.properties,
                children=children,
                memory_mb=1024,
            )
        elif node_type == NodeType.LIMIT:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.GLOBAL_LIMIT,
                properties=logical_node.properties,
                children=children,
            )
        elif node_type == NodeType.STREAM:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.STREAM_RECEIVER,
                properties=logical_node.properties,
                children=children,
            )
        else:
            physical_node = PhysicalNode(
                node_type=PhysicalNodeType.TABLE_SCAN,
                properties=logical_node.properties,
                children=children,
            )

        physical_node.parallelism = self._calculate_parallelism(physical_node)
        physical_node.estimated_throughput = self._estimate_throughput(physical_node)

        return physical_node

    def _calculate_parallelism(self, node: PhysicalNode) -> int:
        if node.stateful:
            return min(self.default_parallelism, 4)
        if node.node_type in [PhysicalNodeType.HASH_JOIN, PhysicalNodeType.SORT_MERGE_JOIN]:
            return min(self.default_parallelism, 8)
        return self.default_parallelism

    def _estimate_throughput(self, node: PhysicalNode) -> float:
        throughputs = {
            PhysicalNodeType.TABLE_SCAN: 100000.0,
            PhysicalNodeType.HASH_FILTER: 80000.0,
            PhysicalNodeType.HASH_PROJECT: 90000.0,
            PhysicalNodeType.HASH_JOIN: 20000.0,
            PhysicalNodeType.SORT_MERGE_JOIN: 15000.0,
            PhysicalNodeType.HASH_AGGREGATE: 30000.0,
            PhysicalNodeType.SORT_AGGREGATE: 25000.0,
            PhysicalNodeType.TUMBLING_WINDOW: 15000.0,
            PhysicalNodeType.HOPPING_WINDOW: 10000.0,
            PhysicalNodeType.SESSION_WINDOW: 8000.0,
            PhysicalNodeType.EXTERNAL_SORT: 10000.0,
        }
        return throughputs.get(node.node_type, 50000.0)

    def _build_stages(self, root: Optional[PhysicalNode]) -> list[dict[str, Any]]:
        if not root:
            return []

        stages: list[dict[str, Any]] = []
        self._collect_stages(root, stages, 0)
        return stages

    def _collect_stages(self, node: PhysicalNode, stages: list[dict[str, Any]], stage_id: int) -> int:
        current_stage_id = stage_id
        stages.append({
            "stage_id": f"stage_{current_stage_id}",
            "node_id": node.node_id,
            "node_type": node.node_type.value,
            "parallelism": node.parallelism,
            "memory_mb": node.memory_mb,
            "cpu_cores": node.cpu_cores,
        })

        for child in node.children:
            current_stage_id += 1
            current_stage_id = self._collect_stages(child, stages, current_stage_id)

        return current_stage_id

    def _build_dependencies(self, root: Optional[PhysicalNode]) -> list[tuple[str, str]]:
        if not root:
            return []

        dependencies: list[tuple[str, str]] = []
        self._collect_dependencies(root, dependencies)
        return dependencies

    def _collect_dependencies(self, node: PhysicalNode, dependencies: list[tuple[str, str]]) -> None:
        for child in node.children:
            dependencies.append((child.node_id, node.node_id))
            self._collect_dependencies(child, dependencies)

    def _estimate_resources(self, plan: PhysicalPlan) -> None:
        if not plan.root:
            return

        total_memory = 0
        total_cpu = 0.0
        max_throughput = float("inf")

        def collect(node: PhysicalNode) -> None:
            nonlocal total_memory, total_cpu, max_throughput
            total_memory += node.memory_mb * node.parallelism
            total_cpu += node.cpu_cores * node.parallelism
            if node.estimated_throughput > 0:
                max_throughput = min(max_throughput, node.estimated_throughput)
            for child in node.children:
                collect(child)

        collect(plan.root)

        plan.total_memory_mb = total_memory
        plan.total_cpu_cores = total_cpu

        if max_throughput != float("inf"):
            plan.estimated_duration_ms = int(10000 / max_throughput * 1000) if max_throughput > 0 else 0

    def explain(self, plan: PhysicalPlan) -> str:
        if not plan.root:
            return "Empty physical plan"

        lines: list[str] = []
        lines.append(f"Execution Mode: {plan.execution_mode.value}")
        lines.append(f"Parallelism: {plan.parallelism}")
        lines.append(f"Estimated Memory: {plan.total_memory_mb}MB")
        lines.append(f"Estimated CPU: {plan.total_cpu_cores} cores")
        lines.append("")
        lines.append("Physical Plan:")
        self._explain_node(plan.root, lines, 0)
        return "\n".join(lines)

    def _explain_node(self, node: PhysicalNode, lines: list[str], level: int) -> None:
        indent = "  " * level
        props = ", ".join(f"{k}={v}" for k, v in node.properties.items())
        lines.append(f"{indent}{node.node_type.value} [parallelism={node.parallelism}, memory={node.memory_mb}MB]")
        if props:
            lines.append(f"{indent}  properties: {{{props}}}")

        for child in node.children:
            self._explain_node(child, lines, level + 1)
