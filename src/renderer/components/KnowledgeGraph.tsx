import React, { useEffect, useRef, useState, useCallback } from 'react';
import * as d3 from 'd3';
import type { GraphData, GraphNode, GraphEdge, Note } from '@shared/types';
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
  
  const simulationRef = useRef<d3.Simulation<GraphNode, GraphEdge> | null>(null);
  const zoomRef = useRef<any>(null);
  const gRef = useRef<d3.Selection<SVGGElement, unknown, null, undefined> | null>(null);
  
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
    
    const { width, height } = dimensions;
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
    
    const simulation = d3.forceSimulation<GraphNode>(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges)
        .id((d: any) => d.id)
        .distance((d: any) => {
          const baseDistance = 120;
          return baseDistance / (d.weight || 1);
        })
        .strength(0.6))
      .force('charge', d3.forceManyBody().strength(-300))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide().radius((d: any) => d.size + 5))
      .alphaDecay(0.02)
      .velocityDecay(0.4);
    
    simulationRef.current = simulation;
    
    const link = linkGroup.selectAll('line')
      .data(edges)
      .enter()
      .append('line')
      .attr('class', 'graph-link')
      .attr('stroke', 'var(--graph-edge-color)')
      .attr('stroke-width', (d: any) => Math.max(1, Math.min(4, d.weight || 1)))
      .attr('stroke-opacity', 0.6);
    
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
        .on('start', dragstarted)
        .on('drag', dragged)
        .on('end', dragended));
    
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
    
    simulation.on('tick', () => {
      link
        .attr('x1', (d: any) => d.source.x)
        .attr('y1', (d: any) => d.source.y)
        .attr('x2', (d: any) => d.target.x)
        .attr('y2', (d: any) => d.target.y);
      
      node
        .attr('cx', (d: any) => d.x)
        .attr('cy', (d: any) => d.y);
      
      label
        .attr('x', (d: any) => d.x)
        .attr('y', (d: any) => d.y);
    });
    
    function dragstarted(event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) {
      if (!event.active) simulation.alphaTarget(0.3).restart();
      d.fx = d.x;
      d.fy = d.y;
    }
    
    function dragged(event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) {
      d.fx = event.x;
      d.fy = event.y;
    }
    
    function dragended(event: d3.D3DragEvent<SVGCircleElement, GraphNode, GraphNode>, d: GraphNode) {
      if (!event.active) simulation.alphaTarget(0);
      d.fx = null;
      d.fy = null;
    }
    
    return () => {
      simulation.stop();
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
    if (!svgRef.current || !gRef.current || !simulationRef.current) return;
    
    const nodes = simulationRef.current.nodes();
    const targetNode = nodes.find((n: any) => n.id === nodeId);
    
    if (targetNode && targetNode.x !== undefined && targetNode.y !== undefined) {
      const svg = d3.select(svgRef.current);
      const { width, height: h } = dimensions;
      
      const scale = 1.5;
      const transform = d3.zoomIdentity
        .translate(width / 2 - targetNode.x * scale, h / 2 - targetNode.y * scale)
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
