from __future__ import annotations

import os
import json
from typing import Any, Dict, List, Optional, Set, Tuple

try:
    import graphviz
    HAS_GRAPHVIZ = True
except ImportError:
    HAS_GRAPHVIZ = False

from .lineage_graph import LineageGraph, LineageNode, LineageEdge, NodeType, EdgeType


class LineageVisualizer:
    def __init__(self, graph: LineageGraph):
        self.graph = graph
        self.node_colors = {
            NodeType.TABLE: "#4CAF50",
            NodeType.COLUMN: "#2196F3",
            NodeType.CTE: "#FF9800",
            NodeType.SUBQUERY: "#9C27B0",
            NodeType.VIEW: "#00BCD4",
        }
        self.edge_colors = {
            EdgeType.SELECT_FROM: "#4CAF50",
            EdgeType.INSERT_INTO: "#FF5722",
            EdgeType.CREATE_AS: "#9C27B0",
            EdgeType.JOIN_ON: "#FF9800",
            EdgeType.WHERE_FILTER: "#F44336",
            EdgeType.WINDOW_PARTITION: "#00BCD4",
            EdgeType.WINDOW_ORDER: "#009688",
            EdgeType.AGGREGATE: "#E91E63",
            EdgeType.COMPUTED: "#673AB7",
            EdgeType.TRANSFORM: "#795548",
        }

    def to_graphviz(self, filename: str = "lineage", format: str = "png",
                   include_columns: bool = True, highlight_nodes: Optional[Set[str]] = None,
                   rankdir: str = "LR") -> "graphviz.Digraph":
        if not HAS_GRAPHVIZ:
            raise ImportError("graphviz 未安装，请先安装 graphviz")

        dot = graphviz.Digraph(comment="Data Lineage Graph", format=format)
        dot.attr(rankdir=rankdir, fontname="Arial", fontsize="12")
        dot.attr("node", shape="box", style="rounded,filled", fontname="Arial", fontsize="10")
        dot.attr("edge", fontname="Arial", fontsize="8")

        highlight_nodes = highlight_nodes or set()

        for node in self.graph.nodes:
            if node.node_type == NodeType.COLUMN and not include_columns:
                continue

            color = self.node_colors.get(node.node_type, "#CCCCCC")
            style = "rounded,filled"
            if node.id in highlight_nodes:
                style += ",bold"
                color = "#FFD700"

            label = self._get_node_label(node)
            tooltip = self._get_node_tooltip(node)

            dot.node(
                node.id,
                label=label,
                fillcolor=color,
                fontcolor="white",
                style=style,
                tooltip=tooltip,
                URL=f"#node:{node.id}",
            )

        for edge in self.graph.edges:
            src_node = self.graph.get_node(edge.source_id)
            tgt_node = self.graph.get_node(edge.target_id)

            if not src_node or not tgt_node:
                continue

            if (src_node.node_type == NodeType.COLUMN or tgt_node.node_type == NodeType.COLUMN) and not include_columns:
                continue

            color = self.edge_colors.get(edge.edge_type, "#999999")
            label = self._get_edge_label(edge)
            tooltip = self._get_edge_tooltip(edge)

            dot.edge(
                edge.source_id,
                edge.target_id,
                label=label,
                color=color,
                tooltip=tooltip,
                penwidth="2" if edge.edge_type in [EdgeType.CREATE_AS, EdgeType.INSERT_INTO] else "1",
            )

        dot.render(filename, cleanup=True)
        return dot

    def _get_node_label(self, node: LineageNode) -> str:
        type_label = node.node_type.value.upper()
        if node.node_type == NodeType.TABLE:
            return f"{{ {type_label} | {node.full_name} }}"
        elif node.node_type == NodeType.COLUMN:
            return f"{{ {type_label} | {node.name} }}"
        elif node.node_type == NodeType.CTE:
            return f"{{ {type_label} | {node.name} }}"
        elif node.node_type == NodeType.SUBQUERY:
            return f"{{ {type_label} | {node.name} }}"
        else:
            return f"{{ {type_label} | {node.full_name} }}"

    def _get_node_tooltip(self, node: LineageNode) -> str:
        parts = [f"Type: {node.node_type.value}", f"Name: {node.full_name}"]
        if node.expression:
            parts.append(f"Expression: {node.expression[:50]}...")
        if node.alias:
            parts.append(f"Alias: {node.alias}")
        if node.metadata:
            for k, v in node.metadata.items():
                parts.append(f"{k}: {v}")
        return "&#10;".join(parts)

    def _get_edge_label(self, edge: LineageEdge) -> str:
        if edge.edge_type in [EdgeType.WHERE_FILTER, EdgeType.JOIN_ON]:
            if edge.expression:
                return edge.expression[:30] + "..." if len(edge.expression) > 30 else edge.expression
        return edge.edge_type.value.replace("_", " ").title()

    def _get_edge_tooltip(self, edge: LineageEdge) -> str:
        parts = [f"Type: {edge.edge_type.value}"]
        if edge.expression:
            parts.append(f"Expression: {edge.expression}")
        if edge.metadata:
            for k, v in edge.metadata.items():
                parts.append(f"{k}: {v}")
        return "&#10;".join(parts)

    def to_html(self, filename: str = "lineage.html", title: str = "Data Lineage Visualization",
               include_columns: bool = True) -> str:
        nodes_data = []
        edges_data = []

        for node in self.graph.nodes:
            if node.node_type == NodeType.COLUMN and not include_columns:
                continue

            nodes_data.append({
                "id": node.id,
                "label": node.full_name,
                "type": node.node_type.value,
                "color": self.node_colors.get(node.node_type, "#CCCCCC"),
                "expression": node.expression,
                "metadata": node.metadata,
            })

        for edge in self.graph.edges:
            src_node = self.graph.get_node(edge.source_id)
            tgt_node = self.graph.get_node(edge.target_id)

            if not src_node or not tgt_node:
                continue

            if (src_node.node_type == NodeType.COLUMN or tgt_node.node_type == NodeType.COLUMN) and not include_columns:
                continue

            edges_data.append({
                "source": edge.source_id,
                "target": edge.target_id,
                "type": edge.edge_type.value,
                "color": self.edge_colors.get(edge.edge_type, "#999999"),
                "label": edge.edge_type.value.replace("_", " ").title(),
                "expression": edge.expression,
            })

        html_content = self._generate_html(title, nodes_data, edges_data)

        if filename:
            with open(filename, "w", encoding="utf-8") as f:
                f.write(html_content)

        return html_content

    def _generate_html(self, title: str, nodes: List[Dict[str, Any]], edges: List[Dict[str, Any]]) -> str:
        nodes_json = json.dumps(nodes, ensure_ascii=False)
        edges_json = json.dumps(edges, ensure_ascii=False)
        node_colors_json = json.dumps({k.value: v for k, v in self.node_colors.items()})
        edge_colors_json = json.dumps({k.value: v for k, v in self.edge_colors.items()})

        return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title}</title>
    <script src="https://unpkg.com/vis-network@9.1.9/dist/vis-network.min.js"></script>
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{ font-family: 'Arial', sans-serif; background: #f5f5f5; }}
        .container {{ display: flex; height: 100vh; }}
        .sidebar {{ width: 300px; background: white; padding: 20px; box-shadow: 2px 0 5px rgba(0,0,0,0.1); overflow-y: auto; }}
        .sidebar h2 {{ margin-bottom: 20px; color: #333; }}
        .sidebar h3 {{ margin: 15px 0 10px; color: #666; font-size: 14px; }}
        .filter-group {{ margin-bottom: 15px; }}
        .filter-group label {{ display: block; margin: 5px 0; cursor: pointer; font-size: 13px; }}
        .filter-group input {{ margin-right: 8px; }}
        .legend {{ margin-top: 20px; }}
        .legend-item {{ display: flex; align-items: center; margin: 5px 0; font-size: 12px; }}
        .legend-color {{ width: 16px; height: 16px; margin-right: 8px; border-radius: 3px; }}
        .stats {{ margin-top: 20px; padding: 10px; background: #f0f0f0; border-radius: 5px; font-size: 12px; }}
        .stats p {{ margin: 3px 0; }}
        .graph-container {{ flex: 1; position: relative; }}
        #graph {{ width: 100%; height: 100%; }}
        .toolbar {{ position: absolute; top: 10px; right: 10px; background: white; padding: 10px; border-radius: 5px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }}
        .toolbar button {{ margin: 2px; padding: 5px 10px; cursor: pointer; border: 1px solid #ddd; background: #f9f9f9; border-radius: 3px; }}
        .toolbar button:hover {{ background: #eee; }}
        .info-panel {{ position: absolute; bottom: 10px; left: 10px; background: white; padding: 15px; border-radius: 5px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); max-width: 400px; max-height: 300px; overflow-y: auto; display: none; }}
        .info-panel h3 {{ margin-bottom: 10px; color: #333; }}
        .info-panel pre {{ white-space: pre-wrap; word-wrap: break-word; font-size: 12px; background: #f5f5f5; padding: 10px; border-radius: 3px; }}
        .search-box {{ margin-bottom: 15px; }}
        .search-box input {{ width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="sidebar">
            <h2>🔍 数据血缘</h2>
            
            <div class="search-box">
                <input type="text" id="searchInput" placeholder="搜索节点...">
            </div>

            <h3>节点类型</h3>
            <div class="filter-group" id="nodeFilters">
                <label><input type="checkbox" value="table" checked> 表 (Table)</label>
                <label><input type="checkbox" value="column" checked> 字段 (Column)</label>
                <label><input type="checkbox" value="cte" checked> CTE</label>
                <label><input type="checkbox" value="subquery" checked> 子查询</label>
                <label><input type="checkbox" value="view" checked> 视图</label>
            </div>

            <h3>边类型</h3>
            <div class="filter-group" id="edgeFilters">
                <label><input type="checkbox" value="select_from" checked> SELECT</label>
                <label><input type="checkbox" value="insert_into" checked> INSERT</label>
                <label><input type="checkbox" value="create_as" checked> CREATE AS</label>
                <label><input type="checkbox" value="join_on" checked> JOIN</label>
                <label><input type="checkbox" value="where_filter" checked> WHERE</label>
                <label><input type="checkbox" value="computed" checked> 计算字段</label>
            </div>

            <div class="legend">
                <h3>图例</h3>
                <div id="nodeLegend"></div>
            </div>

            <div class="stats">
                <h3>统计信息</h3>
                <p>节点总数: <span id="nodeCount">0</span></p>
                <p>边总数: <span id="edgeCount">0</span></p>
                <p>表数量: <span id="tableCount">0</span></p>
                <p>字段数量: <span id="columnCount">0</span></p>
            </div>
        </div>

        <div class="graph-container">
            <div id="graph"></div>
            <div class="toolbar">
                <button onclick="network.fit()">🔄 重置视图</button>
                <button onclick="togglePhysics()">⚡ 切换物理引擎</button>
                <button onclick="exportImage()">📷 导出图片</button>
            </div>
            <div class="info-panel" id="infoPanel">
                <h3>节点详情</h3>
                <div id="infoContent"></div>
                <button onclick="closeInfoPanel()" style="margin-top: 10px; padding: 5px 15px; cursor: pointer;">关闭</button>
            </div>
        </div>
    </div>

    <script>
        const nodesData = {nodes_json};
        const edgesData = {edges_json};
        const nodeColors = {node_colors_json};
        const edgeColors = {edge_colors_json};

        let nodes = new vis.DataSet(nodesData.map(n => ({{
            id: n.id,
            label: n.label,
            color: n.color,
            font: {{ color: 'white', size: 12 }},
            shape: 'box',
            size: 25,
            type: n.type,
            expression: n.expression,
            metadata: n.metadata,
        }})));

        let edges = new vis.DataSet(edgesData.map(e => ({{
            from: e.source,
            to: e.target,
            label: e.label,
            color: e.color,
            type: e.type,
            expression: e.expression,
            arrows: 'to',
            font: {{ size: 10 }},
            smooth: {{ type: 'cubicBezier', forceDirection: 'horizontal', roundness: 0.4 }},
        }})));

        const container = document.getElementById('graph');
        const data = {{ nodes: nodes, edges: edges }};
        const options = {{
            layout: {{
                hierarchical: {{
                    enabled: true,
                    direction: 'LR',
                    sortMethod: 'directed',
                    levelSeparation: 200,
                    nodeSpacing: 100,
                }}
            }},
            physics: {{
                enabled: false,
            }},
            interaction: {{
                hover: true,
                tooltipDelay: 200,
                zoomView: true,
                dragView: true,
            }},
        }};

        let network = new vis.Network(container, data, options);

        function updateStats() {{
            document.getElementById('nodeCount').textContent = nodes.length;
            document.getElementById('edgeCount').textContent = edges.length;
            document.getElementById('tableCount').textContent = nodes.get().filter(n => n.type === 'table').length;
            document.getElementById('columnCount').textContent = nodes.get().filter(n => n.type === 'column').length;
        }}

        function generateLegend() {{
            const legendDiv = document.getElementById('nodeLegend');
            for (const [type, color] of Object.entries(nodeColors)) {{
                const item = document.createElement('div');
                item.className = 'legend-item';
                item.innerHTML = `<div class="legend-color" style="background: ${{color}}"></div>${{type}}`;
                legendDiv.appendChild(item);
            }}
        }}

        function setupFilters() {{
            document.querySelectorAll('#nodeFilters input').forEach(checkbox => {{
                checkbox.addEventListener('change', filterGraph);
            }});
            document.querySelectorAll('#edgeFilters input').forEach(checkbox => {{
                checkbox.addEventListener('change', filterGraph);
            }});
        }}

        function filterGraph() {{
            const enabledNodeTypes = new Set(
                Array.from(document.querySelectorAll('#nodeFilters input:checked')).map(cb => cb.value)
            );
            const enabledEdgeTypes = new Set(
                Array.from(document.querySelectorAll('#edgeFilters input:checked')).map(cb => cb.value)
            );

            nodes.forEach(node => {{
                const visible = enabledNodeTypes.has(node.type);
                nodes.update({{ id: node.id, hidden: !visible }});
            }});

            edges.forEach(edge => {{
                const visible = enabledEdgeTypes.has(edge.type);
                edges.update({{ id: edge.id, hidden: !visible }});
            }});
        }}

        function setupSearch() {{
            const searchInput = document.getElementById('searchInput');
            searchInput.addEventListener('input', (e) => {{
                const query = e.target.value.toLowerCase();
                nodes.forEach(node => {{
                    const matches = query === '' || 
                        node.label.toLowerCase().includes(query) ||
                        (node.expression && node.expression.toLowerCase().includes(query));
                    nodes.update({{ 
                        id: node.id, 
                        color: matches ? '#FFD700' : nodeColors[node.type],
                        font: {{ color: matches ? '#000' : 'white', size: matches ? 14 : 12 }},
                    }});
                }});
            }});
        }}

        network.on('click', function(params) {{
            if (params.nodes.length > 0) {{
                const nodeId = params.nodes[0];
                const node = nodes.get(nodeId);
                showInfoPanel(node);
            }}
        }});

        function showInfoPanel(node) {{
            const panel = document.getElementById('infoPanel');
            const content = document.getElementById('infoContent');
            
            let html = `<p><strong>ID:</strong> ${{node.id}}</p>`;
            html += `<p><strong>类型:</strong> ${{node.type}}</p>`;
            html += `<p><strong>名称:</strong> ${{node.label}}</p>`;
            if (node.expression) {{
                html += `<p><strong>表达式:</strong></p><pre>${{node.expression}}</pre>`;
            }}
            if (node.metadata && Object.keys(node.metadata).length > 0) {{
                html += `<p><strong>元数据:</strong></p><pre>${{JSON.stringify(node.metadata, null, 2)}}</pre>`;
            }}
            
            content.innerHTML = html;
            panel.style.display = 'block';
        }}

        function closeInfoPanel() {{
            document.getElementById('infoPanel').style.display = 'none';
        }}

        function togglePhysics() {{
            const physicsEnabled = network.physics.options.enabled;
            network.setOptions({{ physics: {{ enabled: !physicsEnabled }} }});
        }}

        function exportImage() {{
            const canvas = document.querySelector('#graph canvas');
            if (canvas) {{
                const link = document.createElement('a');
                link.download = 'lineage-graph.png';
                link.href = canvas.toDataURL('image/png');
                link.click();
            }}
        }}

        updateStats();
        generateLegend();
        setupFilters();
        setupSearch();
    </script>
</body>
</html>
"""

    def export_to_json(self, filename: str = "lineage.json") -> str:
        data = {
            "graph": self.graph.to_dict(),
            "metadata": {
                "generated_at": __import__("datetime").datetime.now().isoformat(),
                "node_count": len(self.graph.nodes),
                "edge_count": len(self.graph.edges),
            }
        }
        json_str = json.dumps(data, ensure_ascii=False, indent=2)
        if filename:
            with open(filename, "w", encoding="utf-8") as f:
                f.write(json_str)
        return json_str

    def export_to_csv(self, nodes_file: str = "lineage_nodes.csv",
                     edges_file: str = "lineage_edges.csv") -> Tuple[str, str]:
        import csv
        from io import StringIO

        nodes_buffer = StringIO()
        nodes_writer = csv.writer(nodes_buffer)
        nodes_writer.writerow(["id", "name", "type", "schema", "database", "expression", "alias", "metadata"])
        for node in self.graph.nodes:
            nodes_writer.writerow([
                node.id,
                node.name,
                node.node_type.value,
                node.schema or "",
                node.database or "",
                node.expression or "",
                node.alias or "",
                json.dumps(node.metadata, ensure_ascii=False),
            ])
        nodes_csv = nodes_buffer.getvalue()

        edges_buffer = StringIO()
        edges_writer = csv.writer(edges_buffer)
        edges_writer.writerow(["source_id", "target_id", "type", "expression", "metadata"])
        for edge in self.graph.edges:
            edges_writer.writerow([
                edge.source_id,
                edge.target_id,
                edge.edge_type.value,
                edge.expression or "",
                json.dumps(edge.metadata, ensure_ascii=False),
            ])
        edges_csv = edges_buffer.getvalue()

        if nodes_file:
            with open(nodes_file, "w", encoding="utf-8") as f:
                f.write(nodes_csv)
        if edges_file:
            with open(edges_file, "w", encoding="utf-8") as f:
                f.write(edges_csv)

        return nodes_csv, edges_csv

    def to_mermaid(self, include_columns: bool = True) -> str:
        lines = ["flowchart LR"]

        for node in self.graph.nodes:
            if node.node_type == NodeType.COLUMN and not include_columns:
                continue

            label = node.full_name.replace('"', "'")
            if node.node_type == NodeType.TABLE:
                lines.append(f'    {node.id}["🗄️ {label}"]')
            elif node.node_type == NodeType.COLUMN:
                lines.append(f'    {node.id}["📊 {label}"]')
            elif node.node_type == NodeType.CTE:
                lines.append(f'    {node.id}["🔄 {label}"]')
            elif node.node_type == NodeType.SUBQUERY:
                lines.append(f'    {node.id}["📋 {label}"]')
            else:
                lines.append(f'    {node.id}["{label}"]')

        for edge in self.graph.edges:
            src_node = self.graph.get_node(edge.source_id)
            tgt_node = self.graph.get_node(edge.target_id)

            if not src_node or not tgt_node:
                continue

            if (src_node.node_type == NodeType.COLUMN or tgt_node.node_type == NodeType.COLUMN) and not include_columns:
                continue

            arrow = "-->"
            if edge.edge_type == EdgeType.CREATE_AS:
                arrow = "-.->"
            elif edge.edge_type == EdgeType.COMPUTED:
                arrow = "-.->"

            label = edge.edge_type.value.replace("_", " ")
            lines.append(f'    {edge.source_id} {arrow}|{label}| {edge.target_id}')

        return "\n".join(lines)

    def export_to_mermaid_file(self, filename: str = "lineage.mmd", include_columns: bool = True) -> str:
        mermaid = self.to_mermaid(include_columns=include_columns)
        if filename:
            with open(filename, "w", encoding="utf-8") as f:
                f.write(mermaid)
        return mermaid

    def to_ascii(self, max_width: int = 80) -> str:
        if len(self.graph) == 0:
            return "Empty lineage graph"

        root_nodes = self.graph.get_root_nodes()
        if not root_nodes:
            return "No root nodes found"

        lines = []
        visited = set()

        def print_node(node_id: str, prefix: str = "", is_last: bool = True):
            if node_id in visited:
                return
            visited.add(node_id)

            node = self.graph.get_node(node_id)
            if not node:
                return

            connector = "└── " if is_last else "├── "
            type_icon = {
                NodeType.TABLE: "🗄️",
                NodeType.COLUMN: "📊",
                NodeType.CTE: "🔄",
                NodeType.SUBQUERY: "📋",
                NodeType.VIEW: "👁️",
            }.get(node.node_type, "📦")

            line = f"{prefix}{connector}{type_icon} {node.full_name}"
            if len(line) > max_width:
                line = line[:max_width - 3] + "..."
            lines.append(line)

            successors = self.graph.get_successors(node_id)
            for i, succ in enumerate(successors):
                extension = "    " if is_last else "│   "
                print_node(succ.id, prefix + extension, i == len(successors) - 1)

        for i, root in enumerate(root_nodes):
            print_node(root.id, "", i == len(root_nodes) - 1)

        return "\n".join(lines)
