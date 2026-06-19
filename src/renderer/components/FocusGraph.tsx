import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import * as d3 from 'd3';
import type { GraphData, GraphNode, GraphEdge, Note } from '@shared/types';
import './focusGraph.css';

interface FocusGraphProps {
  centerNote: Note | null;
  onNodeClick?: (nodeId: string) => void;
  defaultDepth?: number;
  maxDepth?: number;
  height?: number;
}

const NODE_TYPE_COLORS: Record<string, string> = {
  note: '#667eea',
  tag: '#48bb78',
  external: '#f56565',
  center: '#f093fb',
};

const NODE_TYPE_LABELS: Record<string, string> = {
  note: '笔记',
  tag: '标签',
  external: '外部链接',
  center: '当前笔记',
};

function getNodeTypeColor(node: GraphNode, isCenter: boolean): string {
  if (isCenter) return NODE_TYPE_COLORS.center;
  return NODE_TYPE_COLORS[node.nodeType || 'note'] || NODE_TYPE_COLORS.note;
}

export const FocusGraph: React.FC<FocusGraphProps> = ({
  centerNote,
  onNodeClick,
  defaultDepth = 1,
  maxDepth = 3,
  height = 350,
}) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [depth, setDepth] = useState(defaultDepth);
  const [includeTags, setIncludeTags] = useState(true);
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], edges: [] });
  const [loading, setLoading] = useState(false);
  const [dimensions, setDimensions] = useState({ width: 300, height });
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  
  const simulationRef = useRef<d3.Simulation<GraphNode, GraphEdge> | null>(null);
  const gRef = useRef<d3.Selection<SVGGElement, unknown, null, undefined> | null>(null);

  const fetchFocusGraphData = useCallback(async () => {
    if (!centerNote) {
      setGraphData({ nodes: [], edges: [] });
      return;
    }

    setLoading(true);
    try {
      const data = await window.api.graph.getFocusGraphData({
        centerNoteId: centerNote.id,
        depth,
        includeTags,
        includeExternal: false,
      });
      setGraphData(data);
    } catch (err) {
      console.error('Error fetching focus graph data:', err);
      setGraphData({ nodes: [], edges: [] });
    } finally {
      setLoading(false);
    }
  }, [centerNote?.id, depth, includeTags]);

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
    fetchFocusGraphData();
  }, [fetchFocusGraphData]);

  useEffect(() => {
    if (!svgRef.current || graphData.nodes.length === 0) return;

    const { width, height } = dimensions;
    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const g = svg.append('g');
    gRef.current = g;

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.5, 3])
      .on('zoom', (event) => {
        g.attr('transform', event.transform);
      });

    svg.call(zoom);

    const defs = svg.append('defs');

    const filter = defs.append('filter')
      .attr('id', 'focus-glow')
      .attr('x', '-50%')
      .attr('y', '-50%')
      .attr('width', '200%')
      .attr('height', '200%');
    
    filter.append('feGaussianBlur')
      .attr('stdDeviation', '3')
      .attr('result', 'coloredBlur');
    
    const feMerge = filter.append('feMerge');
    feMerge.append('feMergeNode').attr('in', 'coloredBlur');
    feMerge.append('feMergeNode').attr('in', 'SourceGraphic');

    const linkGroup = g.append('g').attr('class', 'focus-links');
    const nodeGroup = g.append('g').attr('class', 'focus-nodes');
    const labelGroup = g.append('g').attr('class', 'focus-labels');

    const nodes: GraphNode[] = graphData.nodes.map(d => ({ ...d }));
    const edges: GraphEdge[] = graphData.edges.map(d => ({ ...d }));

    const centerNodeId = centerNote?.id;

    const simulation = d3.forceSimulation<GraphNode>(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges)
        .id((d: any) => d.id)
        .distance((d: any) => {
          const isCenterLink = d.source.id === centerNodeId || d.target.id === centerNodeId;
          return isCenterLink ? 80 : 100;
        })
        .strength(0.8))
      .force('charge', d3.forceManyBody().strength(-150))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide().radius((d: any) => d.size + 8))
      .alphaDecay(0.03)
      .velocityDecay(0.4);

    simulationRef.current = simulation;

    const link = linkGroup.selectAll('line')
      .data(edges)
      .enter()
      .append('line')
      .attr('class', 'focus-edge')
      .attr('stroke', 'var(--focus-edge-color, #4a5568)')
      .attr('stroke-opacity', (d: any) => {
        const isCenterEdge = d.source.id === centerNodeId || d.target.id === centerNodeId;
        return isCenterEdge ? 0.8 : 0.4;
      })
      .attr('stroke-width', (d: any) => {
        const isCenterEdge = d.source.id === centerNodeId || d.target.id === centerNodeId;
        return isCenterEdge ? 2 : 1;
      });

    const node = nodeGroup.selectAll('circle')
      .data(nodes)
      .enter()
      .append('circle')
      .attr('class', 'focus-node')
      .attr('r', d => d.size)
      .attr('fill', d => getNodeTypeColor(d, d.id === centerNodeId))
      .attr('stroke', d => d.id === centerNodeId ? '#fff' : 'transparent')
      .attr('stroke-width', d => d.id === centerNodeId ? 3 : 0)
      .attr('filter', d => d.id === centerNodeId ? 'url(#focus-glow)' : null)
      .style('cursor', 'pointer')
      .on('mouseenter', function(event, d) {
        setHoveredNode(d.id);
        d3.select(this).transition().duration(150).attr('r', d.size * 1.2);
      })
      .on('mouseleave', function(event, d) {
        setHoveredNode(null);
        d3.select(this).transition().duration(150).attr('r', d.size);
      })
      .on('click', function(event, d) {
        if (d.nodeType === 'note' && onNodeClick) {
          onNodeClick(d.id);
        }
      })
      .call(d3.drag<SVGCircleElement, GraphNode>()
        .on('start', (event, d) => {
          if (!event.active) simulation.alphaTarget(0.3).restart();
          (d as any).fx = d.x;
          (d as any).fy = d.y;
        })
        .on('drag', (event, d) => {
          (d as any).fx = event.x;
          (d as any).fy = event.y;
        })
        .on('end', (event, d) => {
          if (!event.active) simulation.alphaTarget(0);
          (d as any).fx = null;
          (d as any).fy = null;
        })
      );

    const label = labelGroup.selectAll('text')
      .data(nodes)
      .enter()
      .append('text')
      .attr('class', 'focus-label')
      .attr('text-anchor', 'middle')
      .attr('dy', (d: any) => d.size + 14)
      .attr('fill', 'var(--focus-label-color, #e2e8f0)')
      .attr('font-size', (d: any) => d.id === centerNodeId ? '12px' : '10px')
      .attr('font-weight', (d: any) => d.id === centerNodeId ? 'bold' : 'normal')
      .text(d => d.label.length > 12 ? d.label.slice(0, 10) + '...' : d.label);

    simulation.on('tick', () => {
      link
        .attr('x1', (d: any) => d.source.x)
        .attr('y1', (d: any) => d.source.y)
        .attr('x2', (d: any) => d.target.x)
        .attr('y2', (d: any) => d.target.y);

      node
        .attr('cx', d => d.x || 0)
        .attr('cy', d => d.y || 0);

      label
        .attr('x', d => d.x || 0)
        .attr('y', d => d.y || 0);
    });

    return () => {
      simulation.stop();
    };
  }, [graphData, dimensions, centerNote?.id, onNodeClick, hoveredNode]);

  const legend = useMemo(() => {
    const types = new Set<string>();
    types.add('center');
    graphData.nodes.forEach(n => types.add(n.nodeType || 'note'));
    return Array.from(types);
  }, [graphData]);

  const stats = useMemo(() => {
    const noteCount = graphData.nodes.filter(n => n.nodeType === 'note' || !n.nodeType).length;
    const tagCount = graphData.nodes.filter(n => n.nodeType === 'tag').length;
    return {
      totalNodes: graphData.nodes.length,
      noteCount,
      tagCount,
      edgeCount: graphData.edges.length,
    };
  }, [graphData]);

  if (!centerNote) {
    return (
      <div className="focus-graph-empty">
        <p>选择一篇笔记查看聚焦图谱</p>
      </div>
    );
  }

  return (
    <div className="focus-graph-container" ref={containerRef}>
      <div className="focus-graph-header">
        <h4>🎯 聚焦图谱</h4>
        <div className="focus-graph-controls">
          <div className="depth-control">
            <label>深度:</label>
            <div className="depth-buttons">
              {Array.from({ length: maxDepth }, (_, i) => i + 1).map(d => (
                <button
                  key={d}
                  className={`depth-btn ${depth === d ? 'active' : ''}`}
                  onClick={() => setDepth(d)}
                  title={`显示 ${d} 层邻居`}
                >
                  {d}
                </button>
              ))}
            </div>
          </div>
          <label className="toggle-control">
            <input
              type="checkbox"
              checked={includeTags}
              onChange={(e) => setIncludeTags(e.target.checked)}
            />
            <span>显示标签</span>
          </label>
        </div>
      </div>

      {loading ? (
        <div className="focus-graph-loading">
          <div className="spinner"></div>
          <span>加载中...</span>
        </div>
      ) : (
        <>
          <svg
            ref={svgRef}
            width={dimensions.width}
            height={height}
            className="focus-graph-svg"
          />
          
          <div className="focus-graph-legend">
            {legend.map(type => (
              <div key={type} className="legend-item">
                <span 
                  className="legend-dot"
                  style={{ backgroundColor: type === 'center' ? NODE_TYPE_COLORS.center : NODE_TYPE_COLORS[type] }}
                />
                <span className="legend-label">{NODE_TYPE_LABELS[type]}</span>
              </div>
            ))}
          </div>

          <div className="focus-graph-stats">
            <span>📝 笔记: {stats.noteCount}</span>
            <span>🏷️ 标签: {stats.tagCount}</span>
            <span>🔗 链接: {stats.edgeCount}</span>
          </div>
        </>
      )}

      {hoveredNode && (
        <div className="node-tooltip">
          {graphData.nodes.find(n => n.id === hoveredNode)?.label}
        </div>
      )}
    </div>
  );
};

export default FocusGraph;
