import * as d3 from 'd3';
import {
  getClusterColor,
  calculateNodeDegree,
  getNodeNeighbors,
  calculateGraphStats,
  detectClusters,
  validateGraphData,
  isSimulationStable,
  calculateNodePositionStability,
  calculateViewportBounds,
  exportSVG,
  validatePNGExportDimensions,
  calculateGraphPerformanceMetrics,
} from '@renderer/utils/graphUtils';
import {
  createMockGraphData,
  generateLargeGraph,
} from '../__fixtures__/testFixtures';
import type { GraphNode, GraphEdge } from '@shared/types';

describe('Graph Utils - Cluster Colors', () => {
  it('should return correct color for known clusters', () => {
    expect(getClusterColor('work')).toBe('#ed8936');
    expect(getClusterColor('study')).toBe('#9f7aea');
    expect(getClusterColor('personal')).toBe('#4299e1');
    expect(getClusterColor('project')).toBe('#f56565');
  });

  it('should return default color for unknown clusters', () => {
    expect(getClusterColor('nonexistent')).toBe('#667eea');
    expect(getClusterColor('')).toBe('#667eea');
  });

  it('should return default color for undefined/null cluster', () => {
    expect(getClusterColor(undefined as any)).toBe('#667eea');
    expect(getClusterColor(null as any)).toBe('#667eea');
  });
});

describe('Graph Utils - Node Degree & Neighbors', () => {
  const mockEdges: GraphEdge[] = [
    { id: 'e1', source: 'n1', target: 'n2', weight: 1 },
    { id: 'e2', source: 'n1', target: 'n3', weight: 1 },
    { id: 'e3', source: 'n1', target: 'n4', weight: 2 },
    { id: 'e4', source: 'n2', target: 'n3', weight: 1 },
    { id: 'e5', source: 'n5', target: 'n6', weight: 1 },
  ];

  it('should calculate correct node degree', () => {
    expect(calculateNodeDegree('n1', mockEdges)).toBe(3);
    expect(calculateNodeDegree('n2', mockEdges)).toBe(2);
    expect(calculateNodeDegree('n6', mockEdges)).toBe(1);
    expect(calculateNodeDegree('isolated', mockEdges)).toBe(0);
  });

  it('should get correct node neighbors', () => {
    expect(getNodeNeighbors('n1', mockEdges).sort()).toEqual(['n2', 'n3', 'n4']);
    expect(getNodeNeighbors('n3', mockEdges).sort()).toEqual(['n1', 'n2']);
    expect(getNodeNeighbors('isolated', mockEdges)).toEqual([]);
  });

  it('should handle object-type edge sources/targets', () => {
    const objectEdges = [
      { id: 'e1', source: { id: 'n1' } as any, target: { id: 'n2' } as any, weight: 1 },
    ];
    expect(getNodeNeighbors('n1', objectEdges)).toEqual(['n2']);
  });
});

describe('Graph Utils - Statistics', () => {
  it('should calculate statistics for simple graph', () => {
    const data = createMockGraphData(4, 4);
    const stats = calculateGraphStats(data);
    
    expect(stats.nodeCount).toBe(4);
    expect(stats.edgeCount).toBe(4);
    expect(stats.avgDegree).toBeCloseTo(2, 1);
    expect(stats.maxDegree).toBeGreaterThanOrEqual(1);
    expect(stats.density).toBeGreaterThan(0);
    expect(stats.density).toBeLessThanOrEqual(1);
  });

  it('should handle empty graph', () => {
    const stats = calculateGraphStats({ nodes: [], edges: [] });
    
    expect(stats.nodeCount).toBe(0);
    expect(stats.edgeCount).toBe(0);
    expect(stats.avgDegree).toBe(0);
    expect(stats.maxDegree).toBe(0);
    expect(stats.density).toBe(0);
    expect(stats.connectedComponents).toBe(0);
  });

  it('should count connected components correctly', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n3', label: 'N3', path: '', tags: [], size: 10, cluster: 'b' },
        { id: 'n4', label: 'N4', path: '', tags: [], size: 10, cluster: 'b' },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'n2', weight: 1 },
        { id: 'e2', source: 'n3', target: 'n4', weight: 1 },
      ],
    };
    
    const stats = calculateGraphStats(data);
    expect(stats.connectedComponents).toBe(2);
  });
});

describe('Graph Utils - Cluster Detection', () => {
  it('should detect two separate clusters', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n3', label: 'N3', path: '', tags: [], size: 10, cluster: 'b' },
        { id: 'n4', label: 'N4', path: '', tags: [], size: 10, cluster: 'b' },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'n2', weight: 1 },
        { id: 'e2', source: 'n3', target: 'n4', weight: 1 },
      ],
    };
    
    const clusters = detectClusters(data);
    expect(clusters['n1']).toBe(clusters['n2']);
    expect(clusters['n3']).toBe(clusters['n4']);
    expect(clusters['n1']).not.toBe(clusters['n3']);
  });

  it('should assign unique clusters to isolated nodes', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'b' },
      ],
      edges: [],
    };
    
    const clusters = detectClusters(data);
    expect(clusters['n1']).not.toBe(clusters['n2']);
  });
});

describe('Graph Utils - Validation', () => {
  it('should validate a correct graph', () => {
    const data = createMockGraphData(3, 2);
    const result = validateGraphData(data);
    
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('should detect missing nodes', () => {
    const result = validateGraphData({ nodes: [], edges: [] });
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Graph has no nodes');
  });

  it('should detect edges referencing non-existent nodes', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'nonexistent', weight: 1 },
      ],
    };
    
    const result = validateGraphData(data);
    expect(result.valid).toBe(false);
    expect(result.errors.length).toBeGreaterThan(0);
    expect(result.errors[0]).toContain('non-existent target node');
  });

  it('should warn about self-loops', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'n1', weight: 1 },
      ],
    };
    
    const result = validateGraphData(data);
    expect(result.valid).toBe(true);
    expect(result.warnings.length).toBeGreaterThan(0);
  });

  it('should detect duplicate node IDs', () => {
    const data = {
      nodes: [
        { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
        { id: 'n1', label: 'N2', path: '', tags: [], size: 10, cluster: 'b' },
      ],
      edges: [],
    };
    
    const result = validateGraphData(data);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Duplicate node id: n1');
  });

  it('should warn about empty labels', () => {
    const data = {
      nodes: [
        { id: 'n1', label: '', path: '', tags: [], size: 10, cluster: 'a' },
      ],
      edges: [],
    };
    
    const result = validateGraphData(data);
    expect(result.valid).toBe(true);
    expect(result.warnings).toContain('Node n1 has empty label');
  });
});

describe('Graph Utils - Force Simulation Stability', () => {
  let nodes: GraphNode[];
  let edges: GraphEdge[];

  beforeEach(() => {
    const data = createMockGraphData(20, 30);
    nodes = data.nodes.map(n => ({ ...n }));
    edges = data.edges.map(e => ({ ...e }));
  });

  it('should detect when simulation is stable', () => {
    expect(isSimulationStable(0.1, 0.4, 0.05)).toBe(false);
    expect(isSimulationStable(0.04, 0.4, 0.05)).toBe(true);
    expect(isSimulationStable(0.001, 0.4, 0.05)).toBe(true);
  });

  it('should use custom stability threshold', () => {
    expect(isSimulationStable(0.08, 0.4, 0.1)).toBe(true);
    expect(isSimulationStable(0.08, 0.4, 0.05)).toBe(false);
  });

  it('should calculate node position stability', () => {
    const testNodes = [
      { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a', x: 100, y: 100 },
      { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'a', x: 200, y: 200 },
    ];
    
    const prevPositions = new Map([
      ['n1', { x: 101, y: 100 }],
      ['n2', { x: 200, y: 200 }],
    ]);
    
    const avgMovement = calculateNodePositionStability(testNodes, prevPositions);
    expect(avgMovement).toBeCloseTo(0.5, 5);
  });

  it('should handle simulation without node positions', () => {
    const testNodes = [
      { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
      { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'a' },
    ];
    const prevPositions = new Map<string, { x: number; y: number }>();
    
    const avgMovement = calculateNodePositionStability(testNodes, prevPositions);
    expect(avgMovement).toBe(0);
  });
});

describe('Graph Utils - Force Simulation - Add/Remove Nodes', () => {
  jest.setTimeout(10000);

  it('should maintain stability after adding nodes', async () => {
    const data = createMockGraphData(10, 15);
    let nodes: GraphNode[] = data.nodes.map(n => ({ ...n, x: Math.random() * 500, y: Math.random() * 500 }));
    let edges: GraphEdge[] = [...data.edges];

    const simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges).id((d: any) => d.id).distance(80))
      .force('charge', d3.forceManyBody().strength(-200))
      .force('center', d3.forceCenter(300, 300))
      .alphaDecay(0.1);

    await new Promise<void>((resolve) => {
      simulation.on('end', () => resolve());
    });

    const alphaAfterInitial = simulation.alpha();
    expect(alphaAfterInitial).toBeLessThan(0.001);

    const newNode: GraphNode = {
      id: 'new-node',
      label: 'New Node',
      path: 'new.md',
      tags: [],
      size: 15,
      cluster: 'test',
      x: 300,
      y: 300,
    };
    
    nodes.push(newNode);
    edges.push({ id: 'new-edge', source: 'new-node', target: nodes[0].id, weight: 1 });

    simulation.nodes(nodes);
    (simulation.force('link') as any).links(edges);
    simulation.alpha(0.3).restart();

    await new Promise<void>((resolve) => {
      simulation.on('end', () => resolve());
    });

    const maxVelocity = Math.max(...nodes.map(n => Math.abs(n.vx || 0) + Math.abs(n.vy || 0)));
    expect(maxVelocity).toBeLessThan(0.5);

    for (const node of nodes) {
      expect(node.x).toBeGreaterThan(-100);
      expect(node.x).toBeLessThan(700);
      expect(node.y).toBeGreaterThan(-100);
      expect(node.y).toBeLessThan(700);
    }

    simulation.stop();
  });

  it('should remain stable after removing nodes', async () => {
    const data = createMockGraphData(15, 20);
    let nodes: GraphNode[] = data.nodes.map(n => ({ ...n, x: Math.random() * 500, y: Math.random() * 500 }));
    let edges: GraphEdge[] = [...data.edges];

    const simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges).id((d: any) => d.id).distance(80))
      .force('charge', d3.forceManyBody().strength(-200))
      .force('center', d3.forceCenter(300, 300))
      .alphaDecay(0.1);

    await new Promise<void>((resolve) => {
      simulation.on('end', () => resolve());
    });

    const removedNodeIds = new Set(nodes.slice(0, 3).map(n => n.id));
    nodes = nodes.filter(n => !removedNodeIds.has(n.id));
    edges = edges.filter(e => 
      !removedNodeIds.has(e.source as string) && 
      !removedNodeIds.has(e.target as string)
    );

    simulation.nodes(nodes);
    (simulation.force('link') as any).links(edges);
    simulation.alpha(0.3).restart();

    await new Promise<void>((resolve) => {
      simulation.on('end', () => resolve());
    });

    const positions = nodes.map(n => ({ x: n.x, y: n.y }));
    const hasNaN = positions.some(p => isNaN(p.x!) || isNaN(p.y!));
    expect(hasNaN).toBe(false);

    const maxCoordinate = Math.max(...positions.map(p => Math.max(Math.abs(p.x!), Math.abs(p.y!))));
    expect(maxCoordinate).toBeLessThan(2000);

    simulation.stop();
  });

  it('should not have nodes fly off-screen for large graphs', async () => {
    const data = generateLargeGraph(100);
    const nodes: GraphNode[] = data.nodes.map(n => ({ ...n, x: Math.random() * 500, y: Math.random() * 500 }));
    const edges: GraphEdge[] = [...data.edges];

    const simulation = d3.forceSimulation(nodes)
      .force('link', d3.forceLink<GraphNode, GraphEdge>(edges).id((d: any) => d.id).distance(60).strength(0.5))
      .force('charge', d3.forceManyBody().strength(-80))
      .force('center', d3.forceCenter(400, 400))
      .force('collision', d3.forceCollide().radius((d: any) => d.size + 5))
      .alphaDecay(0.05)
      .velocityDecay(0.4);

    let maxDistanceFromCenter = 0;
    let tickCount = 0;
    const maxTicks = 200;

    await new Promise<void>((resolve) => {
      simulation.on('tick', () => {
        tickCount++;
        for (const node of nodes) {
          const distance = Math.sqrt(
            Math.pow(node.x! - 400, 2) + Math.pow(node.y! - 400, 2)
          );
          maxDistanceFromCenter = Math.max(maxDistanceFromCenter, distance);
        }

        if (tickCount >= maxTicks || simulation.alpha() < 0.05) {
          simulation.stop();
          resolve();
        }
      });
    });

    expect(maxDistanceFromCenter).toBeLessThan(800);
    expect(tickCount).toBeLessThanOrEqual(maxTicks);

    const hasExtremeValues = nodes.some(n => 
      Math.abs(n.x!) > 2000 || Math.abs(n.y!) > 2000 ||
      isNaN(n.x!) || isNaN(n.y!)
    );
    expect(hasExtremeValues).toBe(false);
  }, 15000);
});

describe('Graph Utils - Viewport & Resize', () => {
  it('should calculate correct viewport bounds', () => {
    const nodes: GraphNode[] = [
      { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a', x: 0, y: 0 },
      { id: 'n2', label: 'N2', path: '', tags: [], size: 10, cluster: 'a', x: 100, y: 200 },
      { id: 'n3', label: 'N3', path: '', tags: [], size: 10, cluster: 'a', x: -50, y: 50 },
    ];
    
    const bounds = calculateViewportBounds(nodes);
    expect(bounds.minX).toBe(-50);
    expect(bounds.maxX).toBe(100);
    expect(bounds.minY).toBe(0);
    expect(bounds.maxY).toBe(200);
    expect(bounds.width).toBe(150);
    expect(bounds.height).toBe(200);
  });

  it('should handle empty bounds', () => {
    const bounds = calculateViewportBounds([]);
    expect(bounds.width).toBe(0);
    expect(bounds.height).toBe(0);
  });

  it('should handle nodes without positions', () => {
    const nodes: GraphNode[] = [
      { id: 'n1', label: 'N1', path: '', tags: [], size: 10, cluster: 'a' },
    ];
    
    const bounds = calculateViewportBounds(nodes);
    expect(bounds.width).toBe(0);
    expect(bounds.height).toBe(0);
  });
});

describe('Graph Utils - SVG Export', () => {
  it('should generate valid SVG', () => {
    const data = createMockGraphData(3, 2);
    data.nodes.forEach((n, i) => {
      n.x = 100 + i * 100;
      n.y = 100 + i * 50;
    });
    
    const svg = exportSVG(data, 800, 600);
    
    expect(svg).toContain('<?xml version="1.0"');
    expect(svg).toContain('<svg');
    expect(svg).toContain('width="800"');
    expect(svg).toContain('height="600"');
    expect(svg).toContain('<line');
    expect(svg).toContain('<circle');
    expect(svg).toContain('</svg>');
  });

  it('should include node labels when enabled', () => {
    const data = createMockGraphData(2, 1);
    data.nodes[0].x = 100;
    data.nodes[0].y = 100;
    data.nodes[1].x = 200;
    data.nodes[1].y = 100;
    
    const svg = exportSVG(data, 800, 600, { showLabels: true });
    
    expect(svg).toContain('<text');
    expect(svg).toContain(data.nodes[0].label);
  });

  it('should respect scale parameter', () => {
    const data = createMockGraphData(2, 1);
    data.nodes[0].x = 100;
    data.nodes[0].y = 100;
    data.nodes[1].x = 200;
    data.nodes[1].y = 100;
    
    const svg1x = exportSVG(data, 800, 600, { scale: 1 });
    const svg2x = exportSVG(data, 800, 600, { scale: 2 });
    
    expect(svg1x).not.toBe(svg2x);
    expect(svg2x).toContain('scale(2)');
  });

  it('should handle missing node positions gracefully', () => {
    const data = createMockGraphData(3, 2);
    const svg = exportSVG(data, 800, 600);
    
    expect(svg).toContain('<svg');
    expect(svg).toContain('</svg>');
  });
});

describe('Graph Utils - PNG Export Dimensions', () => {
  it('should validate export dimensions within limits', () => {
    const result = validatePNGExportDimensions(1024, 768, 2);
    expect(result.exportWidth).toBe(2048);
    expect(result.exportHeight).toBe(1536);
    expect(result.withinLimit).toBe(true);
  });

  it('should reject dimensions exceeding max limit', () => {
    const result = validatePNGExportDimensions(5000, 5000, 2);
    expect(result.exportWidth).toBe(10000);
    expect(result.withinLimit).toBe(false);
  });

  it('should use default scale of 1', () => {
    const result = validatePNGExportDimensions(800, 600);
    expect(result.exportWidth).toBe(1600);
    expect(result.exportHeight).toBe(1200);
  });
});

describe('Graph Utils - Performance Metrics', () => {
  it('should return high performance for small graphs', () => {
    const metrics = calculateGraphPerformanceMetrics(100, 150);
    expect(metrics.expectedFPS).toBe(60);
    expect(metrics.shouldThrottle).toBe(false);
  });

  it('should recommend throttling for very large graphs', () => {
    const metrics = calculateGraphPerformanceMetrics(3000, 8000);
    expect(metrics.expectedFPS).toBe(15);
    expect(metrics.shouldThrottle).toBe(true);
    expect(metrics.recommendedTickCount).toBeLessThan(200);
  });

  it('should scale recommendations based on graph size', () => {
    const small = calculateGraphPerformanceMetrics(100, 100);
    const medium = calculateGraphPerformanceMetrics(800, 1500);
    const large = calculateGraphPerformanceMetrics(1500, 3000);
    
    expect(small.expectedFPS).toBeGreaterThan(medium.expectedFPS);
    expect(medium.expectedFPS).toBeGreaterThan(large.expectedFPS);
    expect(small.recommendedTickCount).toBeGreaterThan(large.recommendedTickCount);
  });

  it('should handle edge case with zero nodes', () => {
    const metrics = calculateGraphPerformanceMetrics(0, 0);
    expect(metrics.expectedFPS).toBe(60);
    expect(metrics.shouldThrottle).toBe(false);
  });
});

describe('Graph Utils - 2000 Nodes Performance Test', () => {
  jest.setTimeout(30000);

  it('should not block main thread for too long during initialization', async () => {
    const largeData = generateLargeGraph(2000);
    
    const startTime = performance.now();
    const metrics = calculateGraphPerformanceMetrics(largeData.nodes.length, largeData.edges.length);
    const stats = calculateGraphStats(largeData);
    const validation = validateGraphData(largeData);
    const duration = performance.now() - startTime;
    
    expect(validation.valid).toBe(true);
    expect(stats.nodeCount).toBe(2000);
    expect(stats.edgeCount).toBeGreaterThan(0);
    expect(duration).toBeLessThan(15000);
    expect(metrics.shouldThrottle).toBe(true);
    expect(Number.isFinite(duration)).toBe(true);
    expect(duration).toBeGreaterThan(0);
  });

  it('should efficiently validate large graphs', async () => {
    const largeData = generateLargeGraph(2000);
    
    const start = Date.now();
    const result = validateGraphData(largeData);
    const duration = Date.now() - start;
    
    expect(result.valid).toBe(true);
    expect(duration).toBeLessThan(5000);
    expect(Number.isFinite(duration)).toBe(true);
    expect(duration).toBeGreaterThanOrEqual(0);
  });
});
