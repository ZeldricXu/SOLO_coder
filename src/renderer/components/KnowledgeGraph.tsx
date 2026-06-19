import React, { useEffect, useRef, useState, useCallback } from 'react';
import * as d3 from 'd3';
import type { GraphData, GraphNode, GraphEdge, Note } from '@shared/types';
import { computeGraphHash, saveLayout, loadLayout } from '@renderer/utils/graphLayoutCache';
import './graph.css';

interface KnowledgeGraphProps {
  data: GraphData;
  currentNoteId?: string;
  onNodeClick?: (node: GraphNode) => void;
  onNodeHover?: (node: GraphNode | null) => void;
  height?: number;
}

const CLUSTER_COLORS: Record<string, string> = {
  default: '#667eea',
  hub: '#f093fb',
  'quick-note': '#48bb78',
  work: '#ed8936',
  personal: '#4299e1',
  study: '#9f7aea',
  project: '#f56565',
  idea: '#38b2ac',
};

function getClusterColor(cluster: string): string {
  return CLUSTER_COLORS[cluster] || CLUSTER_COLORS.default;
}

type WorkerPositions = Array<[string, { x: number; y: number; vx: number; vy: number }]>;

const KnowledgeGraph: React.FC<KnowledgeGraphProps> = ({
  data,
  currentNoteId,
  onNodeClick,
  onNodeHover,
  height = 500,
}) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [hoveredNode, setHoveredNode] = useState<GraphNode | null>(null);
  const [dimensions, setDimensions] = useState({ width: 600, height });
  const [zoomLevel, setZoomLevel] = useState(1);

  const workerRef = useRef<Worker | null>(null);
  const zoomRef = useRef<any>(null);
  const gRef = useRef<d3.Selection<SVGGElement, unknown, null, undefined> | null>(null);
  const nodesRef = useRef<GraphNode[]>([]);
  const edgesRef = useRef<GraphEdge[]>([]);
  const linkSelRef = useRef<d3.Selection<SVGLineElement, GraphEdge, SVGGElement, unknown> | null>(null);
  const nodeSelRef = useRef<d3.Selection<SVGCircleElement, GraphNode, SVGGElement, unknown> | null>(null);
  const labelSelRef = useRef<d3.Selection<SVGTextElement, GraphNode, SVGGElement, unknown> | null>(null);
  const positionsRef = useRef<Map<string, { x: number; y: number; vx: number; vy: number }>>(new Map());

  useEffect(() => {
    const updateDimensions = () => {
      if (containerRef.current) {
        const { width } = containerRef.current.getBoundingClientRect();
        setDimensions({ width, height });
      }
    };

    updateDimensions();
    window.addEventListener('resize', updateDimensions);
    return () => window.removeEventListener('resize', updateDimensions);
  }, [height]);

  useEffect(() => {
    if (!svgRef.current || data.nodes.length === 0) return;

    const { width, height: h } = dimensions;
    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const g = svg.append('g');
    gRef.current = g;

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 4])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
        setZoomLevel(event.transform.k);
      });

    svg.call(zoom);
    zoomRef.current = zoom;

    const defs = svg.append('defs');

    const gradient = defs.append('radialGradient')
      .attr('id', 'node-glow')
      .attr('cx', '50%')
      .attr('cy', '50%')
      .attr('r', '50%');

    gradient.append('stop')
      .attr('offset', '0%')
      .attr('stop-color', 'var(--graph-node-color)')
      .attr('stop-opacity', 0.3);

    gradient.append('stop')
      .attr('offset', '100%')
      .attr('stop-color', 'var(--graph-node-color)')
      .attr('stop-opacity', 0);

    const linkGroup = g.append('g').attr('class', 'links');
    const nodeGroup = g.append('g').attr('class', 'nodes');
    const labelGroup = g.append('g').attr('class', 'labels');

    const nodes: GraphNode[] = data.nodes.map(d => ({ ...d }));
    const edges: GraphEdge[] = data.edges.map(d => ({ ...d }));
    nodesRef.current = nodes;
    edgesRef.current = edges;

    const link = linkGroup.selectAll('line')
      .data(edges)
      .enter()
      .append('line')
      .attr('class', 'graph-link')
      .attr('stroke', 'var(--graph-edge-color)')
      .attr('stroke-width', (d: any) => Math.max(1, Math.min(4, d.weight || 1)))
      .attr('stroke-opacity', 0.6);
    linkSelRef.current = link;

    const node = nodeGroup.selectAll('circle')
      .data(nodes)
      .enter()
      .append('circle')
      .attr('class', 'graph-node')
      .attr('r', (d: any) => d.size)
      .attr('fill', (d: any) => getClusterColor(d.cluster))
      .attr('stroke', (d: any) => d.id === currentNoteId ? '#fff' : 'transparent')
      .attr('stroke-width', (d: any) => d.id === currentNoteId ? 3 : 0)
      .style('cursor', 'pointer')
      .style('filter', (d: any) => d.id === currentNoteId ? 'drop-shadow(0 0 8px rgba(255,255,255,0.5))' : 'none')
      .call(d3.drag<SVGCircleElement, GraphNode>()
        .on('start', (event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) => {
          workerRef.current?.postMessage({ type: 'dragStart', nodeId: d.id, x: d.x ?? 0, y: d.y ?? 0 });
        })
        .on('drag', (event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) => {
          workerRef.current?.postMessage({ type: 'drag', nodeId: d.id, x: event.x, y: event.y });
        })
        .on('end', (event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) => {
          workerRef.current?.postMessage({ type: 'dragEnd', nodeId: d.id });
        }));
    nodeSelRef.current = node;

    const label = labelGroup.selectAll('text')
      .data(nodes)
      .enter()
      .append('text')
      .attr('class', 'graph-label')
      .attr('text-anchor', 'middle')
      .attr('dy', (d: any) => d.size + 14)
      .attr('fill', 'var(--text-primary)')
      .attr('font-size', '11px')
      .attr('font-weight', '500')
      .attr('pointer-events', 'none')
      .attr('opacity', 0.8)
      .text((d: any) => d.label.length > 15 ? d.label.slice(0, 13) + '...' : d.label);
    labelSelRef.current = label;

    node
      .on('mouseover', (event, d) => {
        setHoveredNode(d);
        onNodeHover?.(d);

        node.attr('opacity', n => {
          const isConnected = edges.some(e =>
            (e.source as any).id === d.id || (e.target as any).id === d.id ||
            (e.source as any).id === n.id || (e.target as any).id === n.id
          );
          return isConnected || n.id === d.id ? 1 : 0.2;
        });

        link.attr('opacity', l => {
          const sourceId = (l.source as any).id || l.source;
          const targetId = (l.target as any).id || l.target;
          return sourceId === d.id || targetId === d.id ? 1 : 0.1;
        });

        label.attr('opacity', n => {
          const isConnected = edges.some(e =>
            (e.source as any).id === d.id || (e.target as any).id === d.id ||
            (e.source as any).id === n.id || (e.target as any).id === n.id
          );
          return isConnected || n.id === d.id ? 1 : 0.1;
        });
      })
      .on('mouseout', () => {
        setHoveredNode(null);
        onNodeHover?.(null);

        node.attr('opacity', 1);
        link.attr('opacity', 0.6);
        label.attr('opacity', 0.8);
      })
      .on('click', (event, d) => {
        event.stopPropagation();
        onNodeClick?.(d);
      });

    const hash = computeGraphHash(data.nodes, data.edges);
    const worker = new Worker(new URL('../workers/forceWorker.ts', import.meta.url), { type: 'module' });
    workerRef.current = worker;

    worker.onmessage = (e: MessageEvent) => {
      const { type, positions: rawPositions } = e.data as { type: 'tick' | 'stable'; positions: WorkerPositions };
      const positions = new Map<string, { x: number; y: number; vx: number; vy: number }>(rawPositions);
      positionsRef.current = positions;

      for (const n of nodes) {
        const pos = positions.get(n.id);
        if (pos) {
          n.x = pos.x;
          n.y = pos.y;
          n.vx = pos.vx;
          n.vy = pos.vy;
        }
      }

      if (linkSelRef.current) {
        linkSelRef.current
          .attr('x1', (d: any) => {
            const sId = typeof d.source === 'object' ? d.source.id : d.source;
            const sp = positions.get(sId);
            return sp ? sp.x : 0;
          })
          .attr('y1', (d: any) => {
            const sId = typeof d.source === 'object' ? d.source.id : d.source;
            const sp = positions.get(sId);
            return sp ? sp.y : 0;
          })
          .attr('x2', (d: any) => {
            const tId = typeof d.target === 'object' ? d.target.id : d.target;
            const tp = positions.get(tId);
            return tp ? tp.x : 0;
          })
          .attr('y2', (d: any) => {
            const tId = typeof d.target === 'object' ? d.target.id : d.target;
            const tp = positions.get(tId);
            return tp ? tp.y : 0;
          });
      }

      if (nodeSelRef.current) {
        nodeSelRef.current
          .attr('cx', (d: any) => {
            const pos = positions.get(d.id);
            return pos ? pos.x : 0;
          })
          .attr('cy', (d: any) => {
            const pos = positions.get(d.id);
            return pos ? pos.y : 0;
          });
      }

      if (labelSelRef.current) {
        labelSelRef.current
          .attr('x', (d: any) => {
            const pos = positions.get(d.id);
            return pos ? pos.x : 0;
          })
          .attr('y', (d: any) => {
            const pos = positions.get(d.id);
            return pos ? pos.y : 0;
          });
      }

      if (type === 'stable') {
        const cachePositions = new Map<string, { x: number; y: number }>();
        for (const n of nodes) {
          if (n.x !== undefined && n.y !== undefined) {
            cachePositions.set(n.id, { x: n.x, y: n.y });
          }
        }
        saveLayout(hash, cachePositions).catch(() => {});
      }
    };

    loadLayout(hash).then((cached) => {
      const initNodes = data.nodes.map((n) => {
        if (cached) {
          const cp = cached.get(n.id);
          if (cp) {
            return { id: n.id, x: cp.x, y: cp.y, size: n.size };
          }
        }
        return { id: n.id, size: n.size };
      });

      const initEdges = data.edges.map((e) => ({
        source: e.source,
        target: e.target,
        weight: e.weight,
      }));

      worker.postMessage({
        type: 'init',
        nodes: initNodes,
        edges: initEdges,
        width,
        height: h,
      });

      if (cached) {
        worker.postMessage({ type: 'updateAlpha', alpha: 0.05 });
      }
    }).catch(() => {
      const initNodes = data.nodes.map((n) => ({ id: n.id, size: n.size }));
      const initEdges = data.edges.map((e) => ({
        source: e.source,
        target: e.target,
        weight: e.weight,
      }));

      worker.postMessage({
        type: 'init',
        nodes: initNodes,
        edges: initEdges,
        width,
        height: h,
      });
    });

    return () => {
      worker.terminate();
      workerRef.current = null;
    };
  }, [data, dimensions, currentNoteId]);

  const zoomIn = useCallback(() => {
    if (!svgRef.current || !zoomRef.current) return;
    const svg = d3.select(svgRef.current);
    svg.transition().duration(300).call(zoomRef.current.scaleBy, 1.3);
  }, []);

  const zoomOut = useCallback(() => {
    if (!svgRef.current || !zoomRef.current) return;
    const svg = d3.select(svgRef.current);
    svg.transition().duration(300).call(zoomRef.current.scaleBy, 0.7);
  }, []);

  const resetZoom = useCallback(() => {
    if (!svgRef.current || !zoomRef.current) return;
    const svg = d3.select(svgRef.current);
    svg.transition().duration(500).call(zoomRef.current.transform, d3.zoomIdentity);
  }, []);

  const centerOnNode = useCallback((nodeId: string) => {
    if (!svgRef.current || !gRef.current) return;

    const pos = positionsRef.current.get(nodeId);
    if (pos) {
      const svg = d3.select(svgRef.current);
      const { width, height: h } = dimensions;

      const scale = 1.5;
      const transform = d3.zoomIdentity
        .translate(width / 2 - pos.x * scale, h / 2 - pos.y * scale)
        .scale(scale);

      svg.transition().duration(500).call(zoomRef.current.transform, transform);
    }
  }, [dimensions]);

  const exportPNG = useCallback(async () => {
    if (!svgRef.current) return;

    const serializer = new XMLSerializer();
    let svgString = serializer.serializeToString(svgRef.current);

    const { width, height: h } = dimensions;
    svgString = `<?xml version="1.0" encoding="UTF-8"?>${svgString}`;

    const result = await window.api.export.exportGraphPNG(svgString);
    return result;
  }, [dimensions]);

  const exportSVG = useCallback(() => {
    if (!svgRef.current) return;

    const serializer = new XMLSerializer();
    let svgString = serializer.serializeToString(svgRef.current);
    svgString = `<?xml version="1.0" encoding="UTF-8"?>${svgString}`;

    const blob = new Blob([svgString], { type: 'image/svg+xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'knowledge-graph.svg';
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  const clusters = [...new Set(data.nodes.map(n => n.cluster))];

  return (
    <div className="knowledge-graph-container" ref={containerRef}>
      <div className="graph-toolbar">
        <div className="graph-title">知识图谱</div>
        <div className="graph-controls">
          <button className="graph-btn" onClick={zoomIn} title="放大">
            +
          </button>
          <button className="graph-btn" onClick={zoomOut} title="缩小">
            −
          </button>
          <button className="graph-btn" onClick={resetZoom} title="重置视图">
            ⟳
          </button>
          <button className="graph-btn" onClick={exportSVG} title="导出 SVG">
            SVG
          </button>
          <button className="graph-btn" onClick={exportPNG} title="导出 PNG">
            PNG
          </button>
        </div>
      </div>

      <div className="graph-viewport">
        <svg
          ref={svgRef}
          width={dimensions.width}
          height={dimensions.height}
          className="graph-svg"
        />

        {hoveredNode && (
          <div className="graph-tooltip">
            <div className="tooltip-title">{hoveredNode.label}</div>
            <div className="tooltip-meta">
              <span>关联: {data.edges.filter(e =>
                (e.source as any)?.id === hoveredNode.id ||
                (e.target as any)?.id === hoveredNode.id ||
                e.source === hoveredNode.id ||
                e.target === hoveredNode.id
              ).length}</span>
              {hoveredNode.tags.length > 0 && (
                <span>标签: {hoveredNode.tags.slice(0, 3).join(', ')}</span>
              )}
            </div>
          </div>
        )}
      </div>

      <div className="graph-legend">
        <div className="legend-title">聚类</div>
        <div className="legend-items">
          {clusters.map(cluster => (
            <div key={cluster} className="legend-item">
              <span
                className="legend-color"
                style={{ backgroundColor: getClusterColor(cluster) }}
              />
              <span className="legend-label">{cluster}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="graph-stats">
        <span>{data.nodes.length} 节点</span>
        <span>{data.edges.length} 连接</span>
        <span>{Math.round(zoomLevel * 100)}%</span>
      </div>
    </div>
  );
};

export default KnowledgeGraph;
export { KnowledgeGraph, getClusterColor };
