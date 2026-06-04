import * as d3 from 'd3';
import {
  getNodeColor,
  getNodeRadius,
  getLinkColor,
  getLinkWidth,
  getNodeDegree,
  type SimulationNode,
  type SimulationLink,
} from './forceLayout';

export interface GraphRendererOptions {
  nodeRadius?: number;
  showLabels?: boolean;
  animationDuration?: number;
}

export interface RendererHandlers {
  onNodeClick?: (event: d3.D3Event, node: SimulationNode) => void;
  onNodeDblClick?: (event: d3.D3Event, node: SimulationNode) => void;
  onNodeContextMenu?: (event: d3.D3Event, node: SimulationNode) => void;
  onNodeMouseEnter?: (event: d3.D3Event, node: SimulationNode) => void;
  onNodeMouseLeave?: (event: d3.D3Event, node: SimulationNode) => void;
  onSvgClick?: () => void;
  onSvgDblClick?: (event: d3.D3Event) => void;
  onSvgMouseMove?: (event: d3.D3Event) => void;
}

export interface HighlightState {
  selectedNodeId: string | null;
  highlightedNodeIds: Set<string>;
}

export class GraphRenderer {
  private svg: d3.Selection<SVGSVGElement, unknown, null, undefined>;
  private g: d3.Selection<SVGGElement, unknown, null, undefined>;
  private linkGroup: d3.Selection<SVGGElement, unknown, null, undefined>;
  private nodeGroup: d3.Selection<SVGGElement, unknown, null, undefined>;
  private labelGroup: d3.Selection<SVGGElement, unknown, null, undefined>;
  private tempLinkGroup: d3.Selection<SVGGElement, unknown, null, undefined>;
  private tempLink: d3.Selection<SVGLineElement, unknown, null, undefined> | null = null;

  private options: Required<GraphRendererOptions>;
  private handlers: RendererHandlers;
  private highlightState: HighlightState = {
    selectedNodeId: null,
    highlightedNodeIds: new Set(),
  };

  private dragBehavior: d3.DragBehavior<SVGGElement, SimulationNode, unknown> | null = null;

  constructor(
    svg: SVGSVGElement,
    g: SVGGElement,
    options: GraphRendererOptions = {},
    handlers: RendererHandlers = {}
  ) {
    this.options = {
      nodeRadius: options.nodeRadius ?? 8,
      showLabels: options.showLabels ?? true,
      animationDuration: options.animationDuration ?? 300,
    };
    this.handlers = handlers;

    const d3Svg = d3.select(svg);
    const d3G = d3.select(g);

    d3Svg.selectAll('*').remove();
    d3G.remove();

    const newG = d3Svg.append('g').attr('class', 'graph-container');
    this.svg = d3Svg;
    this.g = newG;

    this.linkGroup = this.g.append('g').attr('class', 'links');
    this.nodeGroup = this.g.append('g').attr('class', 'nodes');
    this.labelGroup = this.g.append('g').attr('class', 'labels');
    this.tempLinkGroup = this.g.append('g').attr('class', 'temp-links');

    this.bindSvgEvents();
  }

  private bindSvgEvents(): void {
    if (this.handlers.onSvgClick) {
      this.svg.on('click', this.handlers.onSvgClick);
    }
    if (this.handlers.onSvgDblClick) {
      this.svg.on('dblclick', this.handlers.onSvgDblClick);
    }
    if (this.handlers.onSvgMouseMove) {
      this.svg.on('mousemove', this.handlers.onSvgMouseMove);
    }
  }

  setDragBehavior(dragBehavior: d3.DragBehavior<SVGGElement, SimulationNode, unknown>): void {
    this.dragBehavior = dragBehavior;
  }

  updateHighlight(state: Partial<HighlightState>): void {
    this.highlightState = { ...this.highlightState, ...state };
    this.updateNodeStyles();
    this.updateLinkStyles();
  }

  render(
    nodes: SimulationNode[],
    links: SimulationLink[],
    graph: { nodes: any[]; links: any[] }
  ): void {
    this.renderLinks(links, graph);
    this.renderNodes(nodes, graph);
    this.updatePositions(nodes, links);
  }

  private renderLinks(links: SimulationLink[], graph: { nodes: any[]; links: any[] }): void {
    const linkSelection = this.linkGroup
      .selectAll<SVGLineElement, SimulationLink>('line')
      .data(links, (d) => {
        const sourceId = typeof d.source === 'object' ? d.source.id : d.source;
        const targetId = typeof d.target === 'object' ? d.target.id : d.target;
        return `${sourceId}-${targetId}`;
      });

    linkSelection
      .exit()
      .transition()
      .duration(this.options.animationDuration)
      .attr('stroke-opacity', 0)
      .remove();

    const linkEnter = linkSelection
      .enter()
      .append('line')
      .attr('stroke-opacity', 0)
      .attr('stroke-width', 0);

    linkEnter
      .transition()
      .duration(this.options.animationDuration)
      .attr('stroke-opacity', 0.6)
      .attr('stroke-width', (d) => getLinkWidth(d));

    const merged = linkEnter.merge(linkSelection);

    merged
      .attr('stroke', (d) => {
        const sourceId = typeof d.source === 'object' ? d.source.id : d.source;
        const targetId = typeof d.target === 'object' ? d.target.id : d.target;
        const isHighlighted = this.highlightState.highlightedNodeIds.has(sourceId) &&
          this.highlightState.highlightedNodeIds.has(targetId);
        return getLinkColor(d, isHighlighted);
      })
      .attr('stroke-width', (d) => getLinkWidth(d));
  }

  private renderNodes(nodes: SimulationNode[], graph: { nodes: any[]; links: any[] }): void {
    const nodeSelection = this.nodeGroup
      .selectAll<SVGGElement, SimulationNode>('g.node')
      .data(nodes, (d) => d.id);

    const exitGroup = nodeSelection
      .exit()
      .attr('opacity', 1);

    exitGroup
      .transition()
      .duration(this.options.animationDuration)
      .attr('opacity', 0)
      .attr('transform', (d) => {
        const scale = 0.1;
        return `translate(${d.x},${d.y}) scale(${scale})`;
      })
      .remove();

    const nodeEnter = nodeSelection
      .enter()
      .append('g')
      .attr('class', 'node')
      .attr('cursor', 'pointer')
      .attr('opacity', 0)
      .attr('transform', (d) => {
        const centerX = this.svg.node()?.clientWidth ? this.svg.node()!.clientWidth / 2 : 400;
        const centerY = this.svg.node()?.clientHeight ? this.svg.node()!.clientHeight / 2 : 300;
        return `translate(${centerX},${centerY}) scale(0.1)`;
      });

    nodeEnter
      .append('circle')
      .attr('class', 'node-circle')
      .attr('stroke', '#fff')
      .attr('stroke-width', 2)
      .attr('r', 0)
      .transition()
      .duration(this.options.animationDuration)
      .attr('r', (d) => getNodeRadius(d, getNodeDegree(graph, d.id).total, this.options.nodeRadius));

    nodeEnter
      .append('text')
      .attr('class', 'node-label')
      .attr('text-anchor', 'middle')
      .attr('dy', (d) => getNodeRadius(d, getNodeDegree(graph, d.id).total, this.options.nodeRadius) + 15)
      .attr('font-size', '11px')
      .attr('fill', 'var(--foreground-color)')
      .attr('pointer-events', 'none')
      .attr('opacity', 0)
      .text((d) => d.label)
      .transition()
      .duration(this.options.animationDuration)
      .attr('opacity', 1);

    nodeEnter
      .transition()
      .duration(this.options.animationDuration)
      .attr('opacity', 1)
      .attr('transform', (d) => `translate(${d.x},${d.y}) scale(1)`);

    const nodeMerge = nodeEnter.merge(nodeSelection);

    nodeMerge
      .select<SVGCircleElement>('.node-circle')
      .attr('r', (d) => getNodeRadius(d, getNodeDegree(graph, d.id).total, this.options.nodeRadius))
      .attr('fill', (d) =>
        getNodeColor(
          d,
          this.highlightState.selectedNodeId === d.id,
          this.highlightState.highlightedNodeIds.has(d.id)
        )
      )
      .attr('opacity', (d) => (this.highlightState.highlightedNodeIds.has(d.id) ? 1 : 0.3))
      .style('transition', 'fill 0.2s, opacity 0.2s');

    nodeMerge
      .select<SVGTextElement>('.node-label')
      .attr('opacity', (d) => (this.highlightState.highlightedNodeIds.has(d.id) ? 1 : 0.2))
      .style('transition', 'opacity 0.2s');

    this.bindNodeEvents(nodeMerge);

    if (this.dragBehavior) {
      nodeMerge.call(this.dragBehavior);
    }
  }

  private bindNodeEvents(
    selection: d3.Selection<SVGGElement, SimulationNode, SVGGElement, unknown>
  ): void {
    selection
      .on('click', (event, d) => {
        event.stopPropagation();
        this.handlers.onNodeClick?.(event, d);
      })
      .on('dblclick', (event, d) => {
        event.stopPropagation();
        this.handlers.onNodeDblClick?.(event, d);
      })
      .on('contextmenu', (event, d) => {
        event.preventDefault();
        event.stopPropagation();
        this.handlers.onNodeContextMenu?.(event, d);
      })
      .on('mouseenter', (event, d) => {
        this.handlers.onNodeMouseEnter?.(event, d);
      })
      .on('mouseleave', (event, d) => {
        this.handlers.onNodeMouseLeave?.(event, d);
      });
  }

  updatePositions(nodes: SimulationNode[], links: SimulationLink[]): void {
    this.linkGroup
      .selectAll<SVGLineElement, SimulationLink>('line')
      .data(links, (d) => {
        const sourceId = typeof d.source === 'object' ? d.source.id : d.source;
        const targetId = typeof d.target === 'object' ? d.target.id : d.target;
        return `${sourceId}-${targetId}`;
      })
      .attr('x1', (d) => (typeof d.source === 'object' ? d.source.x : 0)!)
      .attr('y1', (d) => (typeof d.source === 'object' ? d.source.y : 0)!)
      .attr('x2', (d) => (typeof d.target === 'object' ? d.target.x : 0)!)
      .attr('y2', (d) => (typeof d.target === 'object' ? d.target.y : 0)!);

    this.nodeGroup
      .selectAll<SVGGElement, SimulationNode>('g.node')
      .data(nodes, (d) => d.id)
      .attr('transform', (d) => `translate(${d.x},${d.y})`);
  }

  private updateNodeStyles(): void {
    this.nodeGroup
      .selectAll<SVGGElement, SimulationNode>('g.node')
      .select<SVGCircleElement>('.node-circle')
      .attr('fill', (d) =>
        getNodeColor(
          d,
          this.highlightState.selectedNodeId === d.id,
          this.highlightState.highlightedNodeIds.has(d.id)
        )
      )
      .attr('opacity', (d) => (this.highlightState.highlightedNodeIds.has(d.id) ? 1 : 0.3));

    this.nodeGroup
      .selectAll<SVGGElement, SimulationNode>('g.node')
      .select<SVGTextElement>('.node-label')
      .attr('opacity', (d) => (this.highlightState.highlightedNodeIds.has(d.id) ? 1 : 0.2));
  }

  private updateLinkStyles(): void {
    this.linkGroup
      .selectAll<SVGLineElement, SimulationLink>('line')
      .attr('stroke', (d) => {
        const sourceId = typeof d.source === 'object' ? d.source.id : d.source;
        const targetId = typeof d.target === 'object' ? d.target.id : d.target;
        const isHighlighted = this.highlightState.highlightedNodeIds.has(sourceId) &&
          this.highlightState.highlightedNodeIds.has(targetId);
        return getLinkColor(d, isHighlighted);
      })
      .attr('stroke-opacity', (d) => {
        const sourceId = typeof d.source === 'object' ? d.source.id : d.source;
        const targetId = typeof d.target === 'object' ? d.target.id : d.target;
        const isHighlighted = this.highlightState.highlightedNodeIds.has(sourceId) &&
          this.highlightState.highlightedNodeIds.has(targetId);
        return isHighlighted ? 0.6 : 0.15;
      });
  }

  showTempLink(x1: number, y1: number, x2: number, y2: number): void {
    if (!this.tempLink) {
      this.tempLink = this.tempLinkGroup
        .append('line')
        .attr('stroke', '#3b82f6')
        .attr('stroke-width', 2)
        .attr('stroke-dasharray', '5,5')
        .attr('opacity', 0.8);
    }

    this.tempLink
      .attr('x1', x1)
      .attr('y1', y1)
      .attr('x2', x2)
      .attr('y2', y2);
  }

  hideTempLink(): void {
    if (this.tempLink) {
      this.tempLink.remove();
      this.tempLink = null;
    }
  }

  getGElement(): SVGGElement | null {
    return this.g.node();
  }

  getSvgElement(): SVGSVGElement | null {
    return this.svg.node();
  }

  destroy(): void {
    this.hideTempLink();
    this.svg.on('click', null);
    this.svg.on('dblclick', null);
    this.svg.on('mousemove', null);
    this.nodeGroup.selectAll('*').interrupt();
    this.linkGroup.selectAll('*').interrupt();
    this.g.remove();
  }
}
