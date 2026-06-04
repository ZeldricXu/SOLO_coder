import * as d3 from 'd3';
import type { GraphData, GraphNode, GraphLink } from './parser';

export interface ForceSimulationOptions {
  width: number;
  height: number;
  nodeRadius?: number;
  linkDistance?: number;
  chargeStrength?: number;
  collideRadius?: number;
}

export interface SimulationNode extends d3.SimulationNodeDatum {
  id: string;
  type: 'document' | 'tag';
  label: string;
  path?: string;
  tags?: string[];
}

export interface SimulationLink extends d3.SimulationLinkDatum<SimulationNode> {
  type: 'link' | 'tag';
  value?: number;
}

export interface SimulationHandle {
  simulation: d3.Simulation<SimulationNode, SimulationLink>;
  nodes: SimulationNode[];
  links: SimulationLink[];
  updateData: (newGraph: GraphData) => void;
  updateOptions: (newOptions: Partial<ForceSimulationOptions>) => void;
  destroy: () => void;
}

export function createForceSimulation(
  graph: GraphData,
  options: ForceSimulationOptions,
  onTick: (nodes: SimulationNode[], links: SimulationLink[]) => void
): SimulationHandle {
  const {
    width,
    height,
    nodeRadius = 8,
    linkDistance = 100,
    chargeStrength = -300,
    collideRadius = 20,
  } = options;

  let currentWidth = width;
  let currentHeight = height;
  let currentNodeRadius = nodeRadius;

  let nodes: SimulationNode[] = graph.nodes.map(n => ({
    ...n,
    x: currentWidth / 2 + (Math.random() - 0.5) * 100,
    y: currentHeight / 2 + (Math.random() - 0.5) * 100,
  }));

  let links: SimulationLink[] = graph.links.map(l => ({
    ...l,
    source: l.source,
    target: l.target,
  }));

  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  const linkForce = d3.forceLink<SimulationNode, SimulationLink>(links)
    .id(d => d.id)
    .distance(linkDistance)
    .strength(d => d.type === 'tag' ? 0.3 : 0.6);

  const simulation = d3.forceSimulation<SimulationNode>(nodes)
    .force('link', linkForce)
    .force('charge', d3.forceManyBody().strength(chargeStrength))
    .force('center', d3.forceCenter(currentWidth / 2, currentHeight / 2))
    .force('collision', d3.forceCollide().radius(collideRadius))
    .force('x', d3.forceX(currentWidth / 2).strength(0.05))
    .force('y', d3.forceY(currentHeight / 2).strength(0.05))
    .alphaDecay(0.02)
    .velocityDecay(0.4);

  const handleTick = () => {
    for (const node of nodes) {
      node.x = Math.max(currentNodeRadius, Math.min(currentWidth - currentNodeRadius, node.x!));
      node.y = Math.max(currentNodeRadius, Math.min(currentHeight - currentNodeRadius, node.y!));
    }
    onTick(nodes, links);
  };

  simulation.on('tick', handleTick);

  const handle: SimulationHandle = {
    simulation,
    nodes,
    links,
    updateData: () => {},
    updateOptions: () => {},
    destroy: () => {},
  };

  const destroy = () => {
    simulation.stop();
    simulation.alpha(0);
    simulation.on('tick', null);
    simulation.on('end', null);
    (simulation as any).force('link', null);
    (simulation as any).force('charge', null);
    (simulation as any).force('center', null);
    (simulation as any).force('collision', null);
    (simulation as any).force('x', null);
    (simulation as any).force('y', null);
  };

  const updateData = (newGraph: GraphData) => {
    const existingNodeIds = new Set(nodes.map(n => n.id));
    const newNodeIds = new Set(newGraph.nodes.map(n => n.id));

    const exitingNodes = nodes.filter(n => !newNodeIds.has(n.id));
    const enteringNodes = newGraph.nodes.filter(n => !existingNodeIds.has(n.id));
    const stayingNodes = nodes.filter(n => newNodeIds.has(n.id));

    exitingNodes.forEach(node => {
      node.fx = node.x;
      node.fy = node.y;
    });

    const newNodes: SimulationNode[] = [
      ...stayingNodes,
      ...enteringNodes.map(n => ({
        ...n,
        x: currentWidth / 2 + (Math.random() - 0.5) * 50,
        y: currentHeight / 2 + (Math.random() - 0.5) * 50,
        vx: 0,
        vy: 0,
      })),
    ];

    const newLinks: SimulationLink[] = newGraph.links.map(l => ({
      ...l,
      source: l.source,
      target: l.target,
    }));

    nodes = newNodes;
    links = newLinks;

    nodeMap.clear();
    nodes.forEach(n => nodeMap.set(n.id, n));

    simulation.nodes(nodes);
    linkForce.links(links);

    simulation.alpha(0.3).restart();

    handle.nodes = nodes;
    handle.links = links;
  };

  const updateOptions = (newOptions: Partial<ForceSimulationOptions>) => {
    if (newOptions.width !== undefined) currentWidth = newOptions.width;
    if (newOptions.height !== undefined) currentHeight = newOptions.height;
    if (newOptions.nodeRadius !== undefined) currentNodeRadius = newOptions.nodeRadius;

    if (newOptions.linkDistance !== undefined) {
      linkForce.distance(newOptions.linkDistance);
    }
    if (newOptions.chargeStrength !== undefined) {
      (simulation.force('charge') as d3.ForceManyBody<SimulationNode>).strength(newOptions.chargeStrength);
    }
    if (newOptions.collideRadius !== undefined) {
      (simulation.force('collision') as d3.ForceCollide<SimulationNode>).radius(newOptions.collideRadius);
    }
    if (newOptions.width !== undefined || newOptions.height !== undefined) {
      (simulation.force('center') as d3.ForceCenter<SimulationNode>).x(currentWidth / 2).y(currentHeight / 2);
      (simulation.force('x') as d3.ForceX<SimulationNode>).x(currentWidth / 2);
      (simulation.force('y') as d3.ForceY<SimulationNode>).y(currentHeight / 2);
    }

    simulation.alpha(0.1).restart();
  };

  handle.updateData = updateData;
  handle.updateOptions = updateOptions;
  handle.destroy = destroy;

  return handle;
}

export function drag(simulation: d3.Simulation<SimulationNode, SimulationLink>) {
  function dragstarted(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    if (!event.active) simulation.alphaTarget(0.3).restart();
    event.subject.fx = event.subject.x;
    event.subject.fy = event.subject.y;
  }

  function dragged(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    event.subject.fx = event.x;
    event.subject.fy = event.y;
  }

  function dragended(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    if (!event.active) simulation.alphaTarget(0);
    event.subject.fx = null;
    event.subject.fy = null;
  }

  return d3.drag<SVGGElement, SimulationNode>()
    .on('start', dragstarted)
    .on('drag', dragged)
    .on('end', dragended);
}

export function zoom(
  svg: d3.Selection<SVGSVGElement, unknown, null, undefined>,
  g: d3.Selection<SVGGElement, unknown, null, undefined>
) {
  const zoomBehavior = d3.zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.1, 4])
    .on('zoom', (event) => {
      g.attr('transform', event.transform);
    });

  svg.call(zoomBehavior);

  return {
    zoomBehavior,
    reset: () => {
      svg.transition().duration(300).call(
        zoomBehavior.transform,
        d3.zoomIdentity
      );
    },
    fit: (width: number, height: number, padding: number = 50) => {
      const bounds = g.node()?.getBBox();
      if (!bounds) return;

      const fullWidth = width - padding * 2;
      const fullHeight = height - padding * 2;
      const widthScale = fullWidth / bounds.width;
      const heightScale = fullHeight / bounds.height;
      const scale = Math.min(widthScale, heightScale, 1);

      const translateX = width / 2 - (bounds.x + bounds.width / 2) * scale;
      const translateY = height / 2 - (bounds.y + bounds.height / 2) * scale;

      svg.transition().duration(500).call(
        zoomBehavior.transform,
        d3.zoomIdentity.translate(translateX, translateY).scale(scale)
      );
    },
  };
}

export function getNodeColor(node: SimulationNode, isSelected: boolean, isHighlighted: boolean): string {
  if (isSelected) return '#3b82f6';
  if (!isHighlighted) return '#475569';
  
  return node.type === 'document' ? '#059669' : '#7C3AED';
}

export function getNodeRadius(node: SimulationNode, degree: number, baseRadius: number = 8): number {
  return baseRadius + Math.min(degree * 2, 12);
}

export function getLinkColor(link: SimulationLink, isHighlighted: boolean): string {
  if (!isHighlighted) return '#334155';
  return link.type === 'link' ? '#05966955' : '#7C3AED55';
}

export function getLinkWidth(link: SimulationLink): number {
  return link.type === 'link' ? 1.5 : 1;
}

export function getNodeDegree(graph: { nodes: any[]; links: any[] }, nodeId: string): { in: number; out: number; total: number } {
  let inDegree = 0;
  let outDegree = 0;

  for (const link of graph.links) {
    const sourceId = typeof link.source === 'object' ? link.source.id : link.source;
    const targetId = typeof link.target === 'object' ? link.target.id : link.target;

    if (sourceId === nodeId) outDegree++;
    if (targetId === nodeId) inDegree++;
  }

  return { in: inDegree, out: outDegree, total: inDegree + outDegree };
}

export interface LinkCreationState {
  active: boolean;
  startNode: SimulationNode | null;
  mouseX: number;
  mouseY: number;
}

export function dragWithDrop(
  simulation: d3.Simulation<SimulationNode, SimulationLink>,
  onDrop: (draggedNode: SimulationNode, targetNode: SimulationNode) => void,
  isDragging: React.MutableRefObject<boolean>
) {
  let draggedNode: SimulationNode | null = null;
  let targetNode: SimulationNode | null = null;

  function dragstarted(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    if (!event.active) simulation.alphaTarget(0.3).restart();
    event.subject.fx = event.subject.x;
    event.subject.fy = event.subject.y;
    draggedNode = event.subject;
    isDragging.current = true;
  }

  function dragged(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    event.subject.fx = event.x;
    event.subject.fy = event.y;
  }

  function dragended(event: d3.D3DragEvent<SVGGElement, SimulationNode, SimulationNode>) {
    if (!event.active) simulation.alphaTarget(0);
    event.subject.fx = null;
    event.subject.fy = null;

    if (draggedNode && targetNode && draggedNode.id !== targetNode.id) {
      onDrop(draggedNode, targetNode);
    }

    draggedNode = null;
    targetNode = null;
    isDragging.current = false;
  }

  return {
    dragBehavior: d3.drag<SVGGElement, SimulationNode>()
      .on('start', dragstarted)
      .on('drag', dragged)
      .on('end', dragended),
    setTargetNode: (node: SimulationNode | null) => {
      targetNode = node;
    },
  };
}
