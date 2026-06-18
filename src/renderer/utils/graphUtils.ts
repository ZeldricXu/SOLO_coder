import type { GraphData, GraphNode, GraphEdge } from '@shared/types';

export const CLUSTER_COLORS: Record<string, string> = {
  default: '#667eea',
  hub: '#f093fb',
  'quick-note': '#48bb78',
  work: '#ed8936',
  personal: '#4299e1',
  study: '#9f7aea',
  project: '#f56565',
  idea: '#38b2ac',
  ideas: '#667eea',
};

export function getClusterColor(cluster: string): string {
  return CLUSTER_COLORS[cluster] || CLUSTER_COLORS.default;
}

export function calculateNodeDegree(nodeId: string, edges: GraphEdge[]): number {
  return edges.filter(
    e => (e.source === nodeId || e.target === nodeId)
  ).length;
}

export function getNodeNeighbors(nodeId: string, edges: GraphEdge[]): string[] {
  const neighbors = new Set<string>();
  
  for (const edge of edges) {
    const sourceId = typeof edge.source === 'object' ? (edge.source as any).id : edge.source;
    const targetId = typeof edge.target === 'object' ? (edge.target as any).id : edge.target;
    
    if (sourceId === nodeId) {
      neighbors.add(targetId);
    } else if (targetId === nodeId) {
      neighbors.add(sourceId);
    }
  }
  
  return Array.from(neighbors);
}

export function calculateGraphStats(data: GraphData): {
  nodeCount: number;
  edgeCount: number;
  avgDegree: number;
  maxDegree: number;
  density: number;
  connectedComponents: number;
} {
  const { nodes, edges } = data;
  const nodeCount = nodes.length;
  const edgeCount = edges.length;
  
  const degrees = nodes.map(n => calculateNodeDegree(n.id, edges));
  const avgDegree = degrees.reduce((a, b) => a + b, 0) / Math.max(1, nodeCount);
  const maxDegree = Math.max(...degrees, 0);
  
  const maxPossibleEdges = nodeCount * (nodeCount - 1) / 2;
  const density = maxPossibleEdges > 0 ? edgeCount / maxPossibleEdges : 0;
  
  const visited = new Set<string>();
  let connectedComponents = 0;
  
  for (const node of nodes) {
    if (!visited.has(node.id)) {
      connectedComponents++;
      const queue = [node.id];
      visited.add(node.id);
      
      while (queue.length > 0) {
        const current = queue.shift()!;
        const neighbors = getNodeNeighbors(current, edges);
        for (const neighbor of neighbors) {
          if (!visited.has(neighbor)) {
            visited.add(neighbor);
            queue.push(neighbor);
          }
        }
      }
    }
  }
  
  return {
    nodeCount,
    edgeCount,
    avgDegree,
    maxDegree,
    density,
    connectedComponents,
  };
}

export function detectClusters(data: GraphData): Record<string, number> {
  const clusters: Record<string, number> = {};
  let clusterId = 0;
  const visited = new Set<string>();
  
  for (const node of data.nodes) {
    if (!visited.has(node.id)) {
      const queue = [node.id];
      visited.add(node.id);
      clusters[node.id] = clusterId;
      
      while (queue.length > 0) {
        const current = queue.shift()!;
        const neighbors = getNodeNeighbors(current, data.edges);
        
        for (const neighbor of neighbors) {
          if (!visited.has(neighbor)) {
            visited.add(neighbor);
            clusters[neighbor] = clusterId;
            queue.push(neighbor);
          }
        }
      }
      
      clusterId++;
    }
  }
  
  return clusters;
}

export function validateGraphData(data: GraphData): {
  valid: boolean;
  errors: string[];
  warnings: string[];
} {
  const errors: string[] = [];
  const warnings: string[] = [];
  
  if (!data.nodes || data.nodes.length === 0) {
    errors.push('Graph has no nodes');
  }
  
  if (!data.edges) {
    errors.push('Graph edges is null or undefined');
  }
  
  const nodeIds = new Set(data.nodes?.map(n => n.id) || []);
  
  for (const edge of data.edges || []) {
    const sourceId = typeof edge.source === 'object' ? (edge.source as any).id : edge.source;
    const targetId = typeof edge.target === 'object' ? (edge.target as any).id : edge.target;
    
    if (!nodeIds.has(sourceId)) {
      errors.push(`Edge ${edge.id} references non-existent source node: ${sourceId}`);
    }
    
    if (!nodeIds.has(targetId)) {
      errors.push(`Edge ${edge.id} references non-existent target node: ${targetId}`);
    }
    
    if (sourceId === targetId) {
      warnings.push(`Edge ${edge.id} is a self-loop on node ${sourceId}`);
    }
  }
  
  const nodeIdSet = new Set<string>();
  for (const node of data.nodes || []) {
    if (nodeIdSet.has(node.id)) {
      errors.push(`Duplicate node id: ${node.id}`);
    }
    nodeIdSet.add(node.id);
    
    if (!node.label || node.label.trim() === '') {
      warnings.push(`Node ${node.id} has empty label`);
    }
  }
  
  const edgeIdSet = new Set<string>();
  for (const edge of data.edges || []) {
    if (edgeIdSet.has(edge.id)) {
      errors.push(`Duplicate edge id: ${edge.id}`);
    }
    edgeIdSet.add(edge.id);
  }
  
  const isolatedNodes = data.nodes?.filter(
    n => calculateNodeDegree(n.id, data.edges || []) === 0
  ).length || 0;
  
  if (isolatedNodes > 0) {
    warnings.push(`Graph has ${isolatedNodes} isolated nodes`);
  }
  
  return {
    valid: errors.length === 0,
    errors,
    warnings,
  };
}

export function isSimulationStable(
  alpha: number,
  velocityDecay: number,
  threshold: number = 0.05
): boolean {
  return alpha < threshold;
}

export function calculateNodePositionStability(
  nodes: GraphNode[],
  previousPositions: Map<string, { x: number; y: number }>
): number {
  let totalMovement = 0;
  let movedNodes = 0;
  
  for (const node of nodes) {
    const prev = previousPositions.get(node.id);
    if (prev && node.x !== undefined && node.y !== undefined) {
      const dx = node.x - prev.x;
      const dy = node.y - prev.y;
      const distance = Math.sqrt(dx * dx + dy * dy);
      totalMovement += distance;
      if (distance > 0.1) {
        movedNodes++;
      }
    }
  }
  
  return totalMovement / Math.max(1, nodes.length);
}

export function calculateViewportBounds(
  nodes: GraphNode[]
): { minX: number; maxX: number; minY: number; maxY: number; width: number; height: number } {
  const positions = nodes.filter(n => n.x !== undefined && n.y !== undefined) as Array<GraphNode & { x: number; y: number }>;
  
  if (positions.length === 0) {
    return { minX: 0, maxX: 0, minY: 0, maxY: 0, width: 0, height: 0 };
  }
  
  const xs = positions.map(n => n.x);
  const ys = positions.map(n => n.y);
  
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  
  return {
    minX,
    maxX,
    minY,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
}

export function exportSVG(
  data: GraphData,
  width: number,
  height: number,
  options: {
    showLabels?: boolean;
    backgroundColor?: string;
    scale?: number;
  } = {}
): string {
  const { showLabels = true, backgroundColor = '#1a1a2e', scale = 1 } = options;
  
  const bounds = calculateViewportBounds(data.nodes);
  const centerX = (bounds.minX + bounds.maxX) / 2 || width / 2;
  const centerY = (bounds.minY + bounds.maxY) / 2 || height / 2;
  const translateX = width / 2 - centerX * scale;
  const translateY = height / 2 - centerY * scale;
  
  let svg = `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <rect width="100%" height="100%" fill="${backgroundColor}"/>
  <g transform="translate(${translateX}, ${translateY}) scale(${scale})">
`;
  
  for (const edge of data.edges) {
    const sourceNode = data.nodes.find(n => n.id === edge.source);
    const targetNode = data.nodes.find(n => n.id === edge.target);
    
    if (sourceNode?.x !== undefined && targetNode?.x !== undefined) {
      const opacity = Math.min(1, 0.3 + (edge.weight || 1) * 0.15);
      svg += `    <line x1="${sourceNode.x}" y1="${sourceNode.y}" x2="${targetNode.x}" y2="${targetNode.y}" 
          stroke="#667eea" stroke-opacity="${opacity}" stroke-width="${Math.max(1, (edge.weight || 1))}"/>
`;
    }
  }
  
  for (const node of data.nodes) {
    if (node.x !== undefined && node.y !== undefined) {
      const color = getClusterColor(node.cluster);
      svg += `    <circle cx="${node.x}" cy="${node.y}" r="${node.size}" fill="${color}">
      <title>${node.label}</title>
    </circle>
`;
      
      if (showLabels) {
        svg += `    <text x="${node.x}" y="${node.y + node.size + 14}" text-anchor="middle" 
          fill="#e8e8e8" font-size="11" font-family="sans-serif">${node.label.length > 20 ? node.label.slice(0, 18) + '...' : node.label}</text>
`;
      }
    }
  }
  
  svg += `  </g>
</svg>`;
  
  return svg;
}

export function validatePNGExportDimensions(
  containerWidth: number,
  containerHeight: number,
  exportScale: number = 2
): {
  exportWidth: number;
  exportHeight: number;
  withinLimit: boolean;
  maxDimension: number;
} {
  const MAX_DIMENSION = 8192;
  
  const exportWidth = Math.round(containerWidth * exportScale);
  const exportHeight = Math.round(containerHeight * exportScale);
  const maxDimension = Math.max(exportWidth, exportHeight);
  
  return {
    exportWidth,
    exportHeight,
    withinLimit: maxDimension <= MAX_DIMENSION,
    maxDimension: MAX_DIMENSION,
  };
}

export function calculateGraphPerformanceMetrics(
  nodeCount: number,
  edgeCount: number
): {
  complexity: string;
  expectedFPS: number;
  shouldThrottle: boolean;
  recommendedTickCount: number;
} {
  const complexity = nodeCount * Math.log(nodeCount) + edgeCount;
  
  let expectedFPS = 60;
  let shouldThrottle = false;
  let recommendedTickCount = 300;
  
  if (nodeCount > 2000 || edgeCount > 5000) {
    expectedFPS = 15;
    shouldThrottle = true;
    recommendedTickCount = 150;
  } else if (nodeCount > 1000 || edgeCount > 2500) {
    expectedFPS = 30;
    shouldThrottle = false;
    recommendedTickCount = 200;
  } else if (nodeCount > 500 || edgeCount > 1000) {
    expectedFPS = 45;
    shouldThrottle = false;
    recommendedTickCount = 250;
  }
  
  return {
    complexity: complexity.toFixed(0),
    expectedFPS,
    shouldThrottle,
    recommendedTickCount,
  };
}
