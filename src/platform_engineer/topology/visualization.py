from typing import Any, Dict, List, Optional

from .builder import ServiceTopology


class TopologyVisualizer:
    def __init__(self, topology: Optional[ServiceTopology] = None):
        self._topology = topology or ServiceTopology()

    def set_topology(self, topology: ServiceTopology) -> None:
        self._topology = topology

    def to_mermaid(self) -> str:
        lines = ["graph TD"]
        for name, node in self._topology.nodes.items():
            label = f"{name}\\n({node.call_count} calls)"
            shape = "[" if node.active else ">"
            lines.append(f'    {name}{shape}"{label}"]')
        for edge in self._topology.edges.values():
            color = ";lineColor:#ff0000" if edge.error_count > 0 else ""
            label = f"{edge.call_count}" if edge.call_count > 1 else ""
            lines.append(f'    {edge.source_service} -->|{label}{color}| {edge.target_service}')
        return "\n".join(lines)

    def to_graphviz(self) -> str:
        lines = ["digraph ServiceTopology {"]
        lines.append('    node [shape=box, style=rounded, fontname="Arial"];')
        lines.append('    edge [fontname="Arial"];')
        for name, node in self._topology.nodes.items():
            color = "lightblue" if node.active else "lightgray"
            lines.append(
                f'    "{name}" [label="{name}\\ncalls: {node.call_count}\\nerrors: {node.error_count}", '
                f'fillcolor={color}, style=filled];'
            )
        for edge in self._topology.edges.values():
            color = "red" if edge.error_count > 0 else "black"
            penwidth = max(1, min(5, int(edge.call_count / 1000) + 1))
            lines.append(
                f'    "{edge.source_service}" -> "{edge.target_service}" ['
                f'label="{edge.call_count}", color={color}, penwidth={penwidth}];'
            )
        lines.append("}")
        return "\n".join(lines)

    def to_json(self) -> Dict[str, Any]:
        return self._topology.to_dict()

    def to_cytoscape(self) -> Dict[str, Any]:
        elements = {"nodes": [], "edges": []}
        for name, node in self._topology.nodes.items():
            elements["nodes"].append({
                "data": {
                    "id": name,
                    "label": name,
                    "call_count": node.call_count,
                    "error_count": node.error_count,
                    "active": node.active,
                }
            })
        for edge in self._topology.edges.values():
            elements["edges"].append({
                "data": {
                    "id": edge.edge_id,
                    "source": edge.source_service,
                    "target": edge.target_service,
                    "call_count": edge.call_count,
                    "error_count": edge.error_count,
                    "avg_duration_ms": edge.avg_duration_ms,
                }
            })
        return {
            "elements": elements,
            "style": [
                {"selector": "node", "style": {"label": "data(label)"}},
                {"selector": "node[active]", "style": {"background-color": "#66ccff"}},
                {"selector": "node[!active]", "style": {"background-color": "#999999"}},
                {"selector": "edge", "style": {"label": "data(call_count)"}},
            ],
        }
