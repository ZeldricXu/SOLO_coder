import {
  forceSimulation,
  forceLink,
  forceManyBody,
  forceCenter,
  forceCollide,
} from 'd3-force';

interface WorkerNode {
  id: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  fx: number | null;
  fy: number | null;
  size: number;
}

interface WorkerEdge {
  source: string | WorkerNode;
  target: string | WorkerNode;
  weight: number;
}

interface InitMessage {
  type: 'init';
  nodes: Array<{ id: string; x?: number; y?: number; size: number }>;
  edges: Array<{ source: string; target: string; weight: number }>;
  width: number;
  height: number;
}

interface TickMessage {
  type: 'tick';
}

interface UpdateAlphaMessage {
  type: 'updateAlpha';
  alpha: number;
}

interface DragStartMessage {
  type: 'dragStart';
  nodeId: string;
  x: number;
  y: number;
}

interface DragMessage {
  type: 'drag';
  nodeId: string;
  x: number;
  y: number;
}

interface DragEndMessage {
  type: 'dragEnd';
  nodeId: string;
}

type WorkerMessage = InitMessage | TickMessage | UpdateAlphaMessage | DragStartMessage | DragMessage | DragEndMessage;

let simulation: ReturnType<typeof forceSimulation<WorkerNode, WorkerEdge>> | null = null;
let currentNodes: WorkerNode[] = [];

self.onmessage = function (e: MessageEvent<WorkerMessage>) {
  const msg = e.data;

  if (msg.type === 'init') {
    const { nodes, edges, width, height } = msg;

    currentNodes = nodes.map((n) => ({
      id: n.id,
      x: n.x ?? width / 2 + (Math.random() - 0.5) * 100,
      y: n.y ?? height / 2 + (Math.random() - 0.5) * 100,
      vx: 0,
      vy: 0,
      fx: null,
      fy: null,
      size: n.size,
    }));

    const workerEdges: WorkerEdge[] = edges.map((e) => ({
      source: e.source,
      target: e.target,
      weight: e.weight,
    }));

    simulation = forceSimulation<WorkerNode, WorkerEdge>(currentNodes)
      .force(
        'link',
        forceLink<WorkerNode, WorkerEdge>(workerEdges)
          .id((d: any) => d.id)
          .distance((d: any) => {
            const baseDistance = 120;
            return baseDistance / (d.weight || 1);
          })
          .strength(0.6)
      )
      .force('charge', forceManyBody().strength(-300))
      .force('center', forceCenter(width / 2, height / 2))
      .force('collision', forceCollide<WorkerNode>().radius((d) => d.size + 5))
      .alphaDecay(0.02)
      .velocityDecay(0.4)
      .on('tick', onTick)
      .on('end', onEnd);
  }

  if (msg.type === 'tick') {
    if (simulation) {
      simulation.tick(1);
      postPositions('tick');
    }
  }

  if (msg.type === 'updateAlpha') {
    if (simulation) {
      simulation.alpha(msg.alpha).restart();
    }
  }

  if (msg.type === 'dragStart') {
    if (simulation) {
      simulation.alphaTarget(0.3).restart();
      const node = currentNodes.find((n) => n.id === msg.nodeId);
      if (node) {
        node.fx = node.x;
        node.fy = node.y;
      }
    }
  }

  if (msg.type === 'drag') {
    const node = currentNodes.find((n) => n.id === msg.nodeId);
    if (node) {
      node.fx = msg.x;
      node.fy = msg.y;
    }
  }

  if (msg.type === 'dragEnd') {
    if (simulation) {
      simulation.alphaTarget(0);
    }
    const node = currentNodes.find((n) => n.id === msg.nodeId);
    if (node) {
      node.fx = null;
      node.fy = null;
    }
  }
};

function onTick() {
  postPositions('tick');
}

function onEnd() {
  postPositions('stable');
}

function postPositions(type: 'tick' | 'stable') {
  const positions = new Map<string, { x: number; y: number; vx: number; vy: number }>();
  for (const node of currentNodes) {
    positions.set(node.id, { x: node.x, y: node.y, vx: node.vx, vy: node.vy });
  }
  self.postMessage({ type, positions: Array.from(positions.entries()) });
}
