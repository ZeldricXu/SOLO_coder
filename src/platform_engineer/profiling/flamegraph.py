from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .profiler import ProfileSnapshot


@dataclass
class FlameNode:
    name: str
    value: float = 0.0
    children: Dict[str, "FlameNode"] = field(default_factory=dict)
    parent: Optional["FlameNode"] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "value": self.value,
            "children": [child.to_dict() for child in self.children.values()],
        }


class FlameGraphGenerator:
    def __init__(self, min_sample_count: int = 1):
        self._min_sample_count = min_sample_count

    def generate_from_call_stacks(self, call_stacks: List[Dict[str, Any]]) -> FlameNode:
        root = FlameNode(name="root")
        for stack in call_stacks:
            frames = self._parse_stack(stack)
            if not frames:
                continue
            self._add_stack(root, frames, stack.get("size_bytes", 1))
        self._collapse_min_nodes(root)
        return root

    def _parse_stack(self, stack: Dict[str, Any]) -> List[str]:
        frames = []
        if "file" in stack:
            file_name = stack["file"]
            line = stack.get("line", 0)
            frames.append(f"{file_name}:{line}")
        return frames

    def _add_stack(self, root: FlameNode, frames: List[str], value: float) -> None:
        node = root
        node.value += value
        for frame in frames:
            if frame not in node.children:
                node.children[frame] = FlameNode(name=frame, parent=node)
            node = node.children[frame]
            node.value += value

    def _collapse_min_nodes(self, node: FlameNode) -> None:
        to_remove = []
        for name, child in node.children.items():
            if child.value < self._min_sample_count and node.parent:
                to_remove.append(name)
            else:
                self._collapse_min_nodes(child)
        for name in to_remove:
            del node.children[name]

    def generate_from_snapshot(self, snapshot: ProfileSnapshot) -> FlameNode:
        return self.generate_from_call_stacks(snapshot.call_stack_samples)

    def to_svg(self, root: FlameNode, width: int = 1200, height: int = 600) -> str:
        max_value = root.value if root.value > 0 else 1
        total_height = height
        level_height = total_height / self._get_max_depth(root)

        svg_parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
            '<rect width="100%" height="100%" fill="#fafafa"/>',
        ]

        def render_node(node: FlameNode, x: float, y: float, node_width: float):
            if node_width < 2:
                return
            node_height = level_height
            color = self._get_color(node.value, max_value)
            svg_parts.append(
                f'<rect x="{x}" y="{y}" width="{node_width}" height="{node_height - 1}" '
                f'fill="{color}" stroke="#fff" stroke-width="0.5"/>'
            )
            if node_width > 30:
                label = node.name.split("/")[-1].split("\\")[-1]
                if len(label) > int(node_width / 6):
                    label = label[:int(node_width / 6) - 3] + "..."
                svg_parts.append(
                    f'<text x="{x + 2}" y="{y + node_height / 2}" font-size="10" '
                    f'fill="#333" dominant-baseline="middle">{label}</text>'
                )
            child_x = x
            for child in node.children.values():
                child_width = (child.value / max_value) * width
                render_node(child, child_x, y - node_height, child_width)
                child_x += child_width

        root_width = (root.value / max_value) * width
        render_node(root, 0, height - level_height, root_width)
        svg_parts.append('</svg>')
        return "\n".join(svg_parts)

    def _get_color(self, value: float, max_value: float) -> str:
        ratio = value / max_value if max_value > 0 else 0
        r = int(200 + 55 * (1 - ratio))
        g = int(100 + 155 * (1 - ratio))
        b = int(100 + 55 * (1 - ratio))
        return f"rgb({r},{g},{b})"

    def _get_max_depth(self, node: FlameNode, depth: int = 0) -> int:
        if not node.children:
            return depth + 1
        return max(self._get_max_depth(child, depth + 1) for child in node.children.values())

    def to_dict(self, root: FlameNode) -> Dict[str, Any]:
        return root.to_dict()


class FlameGraphComparison:
    def __init__(self):
        self._generator = FlameGraphGenerator()

    def compare(self, snapshot_a: ProfileSnapshot, snapshot_b: ProfileSnapshot) -> Dict[str, Any]:
        root_a = self._generator.generate_from_snapshot(snapshot_a)
        root_b = self._generator.generate_from_snapshot(snapshot_b)

        diff = self._calculate_diff(root_a, root_b)

        return {
            "snapshot_a_id": snapshot_a.snapshot_id,
            "snapshot_b_id": snapshot_b.snapshot_id,
            "a_total_value": root_a.value,
            "b_total_value": root_b.value,
            "absolute_change": root_b.value - root_a.value,
            "percent_change": ((root_b.value - root_a.value) / root_a.value * 100) if root_a.value > 0 else 0,
            "diff_tree": diff,
            "top_changes": self._get_top_changes(diff, top_n=10),
        }

    def _calculate_diff(self, a: FlameNode, b: FlameNode) -> Dict[str, Any]:
        a_children = {c.name: c for c in a.children.values()}
        b_children = {c.name: c for c in b.children.values()}

        all_names = set(a_children.keys()) | set(b_children.keys())
        children_diff = []

        for name in all_names:
            child_a = a_children.get(name)
            child_b = b_children.get(name)

            a_val = child_a.value if child_a else 0
            b_val = child_b.value if child_b else 0
            absolute = b_val - a_val
            percent = (absolute / a_val * 100) if a_val > 0 else (100 if b_val > 0 else 0)

            child_diff = {
                "name": name,
                "a_value": a_val,
                "b_value": b_val,
                "absolute_change": absolute,
                "percent_change": percent,
                "children": [],
            }

            if child_a and child_b:
                child_diff["children"] = [
                    self._calculate_diff(child_a, child_b)
                ]

            children_diff.append(child_diff)

        children_diff.sort(key=lambda x: abs(x["absolute_change"]), reverse=True)

        return {
            "name": a.name,
            "a_value": a.value,
            "b_value": b.value,
            "absolute_change": b.value - a.value,
            "percent_change": ((b.value - a.value) / a.value * 100) if a.value > 0 else 0,
            "children": children_diff,
        }

    def _get_top_changes(self, diff_tree: Dict[str, Any], top_n: int = 10) -> List[Dict[str, Any]]:
        changes = []

        def collect(node: Dict[str, Any], path: str = ""):
            current_path = f"{path}/{node['name']}" if path else node["name"]
            if abs(node.get("absolute_change", 0)) > 0:
                changes.append({
                    "path": current_path,
                    "absolute_change": node["absolute_change"],
                    "percent_change": node["percent_change"],
                    "a_value": node["a_value"],
                    "b_value": node["b_value"],
                })
            for child in node.get("children", []):
                collect(child, current_path)

        collect(diff_tree)
        changes.sort(key=lambda x: abs(x["absolute_change"]), reverse=True)
        return changes[:top_n]

    def compare_stats(self, snapshot_a: ProfileSnapshot, snapshot_b: ProfileSnapshot) -> Dict[str, Any]:
        return {
            "cpu": {
                "avg_change": snapshot_b.cpu_usage_avg - snapshot_a.cpu_usage_avg,
                "avg_change_pct": ((snapshot_b.cpu_usage_avg - snapshot_a.cpu_usage_avg) / snapshot_a.cpu_usage_avg * 100) if snapshot_a.cpu_usage_avg > 0 else 0,
                "max_change": snapshot_b.cpu_usage_max - snapshot_a.cpu_usage_max,
                "a_avg": snapshot_a.cpu_usage_avg,
                "b_avg": snapshot_b.cpu_usage_avg,
                "a_max": snapshot_a.cpu_usage_max,
                "b_max": snapshot_b.cpu_usage_max,
            },
            "memory": {
                "avg_change": snapshot_b.memory_usage_avg - snapshot_a.memory_usage_avg,
                "avg_change_pct": ((snapshot_b.memory_usage_avg - snapshot_a.memory_usage_avg) / snapshot_a.memory_usage_avg * 100) if snapshot_a.memory_usage_avg > 0 else 0,
                "peak_change": snapshot_b.memory_peak - snapshot_a.memory_peak,
                "a_avg": snapshot_a.memory_usage_avg,
                "b_avg": snapshot_b.memory_usage_avg,
                "a_peak": snapshot_a.memory_peak,
                "b_peak": snapshot_b.memory_peak,
            },
            "duration": {
                "a_duration_seconds": (snapshot_a.ended_at - snapshot_a.started_at).total_seconds(),
                "b_duration_seconds": (snapshot_b.ended_at - snapshot_b.started_at).total_seconds(),
            },
        }
