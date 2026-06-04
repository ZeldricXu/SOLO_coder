import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { createForceSimulation, type SimulationHandle } from '@/core/graph/forceLayout';
import type { GraphData, SimulationNode, SimulationLink } from '@/core/graph/forceLayout';

describe('Force Simulation 增量更新', () => {
  let simulationHandle: SimulationHandle | null = null;

  afterEach(() => {
    if (simulationHandle) {
      simulationHandle.destroy();
      simulationHandle = null;
    }
  });

  const createMockGraph = (nodeCount: number, startId: number = 0): GraphData => {
    const nodes = Array.from({ length: nodeCount }, (_, i) => ({
      id: `node-${startId + i}`,
      label: `Node ${startId + i}`,
      type: 'document' as const,
    }));

    const links = nodeCount > 1
      ? nodes.slice(0, -1).map((node, i) => ({
          source: node.id,
          target: nodes[i + 1].id,
          type: 'link' as const,
          value: 1,
        }))
      : [];

    return { nodes, links };
  };

  describe('初始化', () => {
    it('应该创建SimulationHandle并包含nodes和links', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600 },
        () => {}
      );

      expect(simulationHandle.nodes).toHaveLength(5);
      expect(simulationHandle.links).toHaveLength(4);
      expect(simulationHandle.simulation).toBeDefined();
      expect(typeof simulationHandle.updateData).toBe('function');
      expect(typeof simulationHandle.updateOptions).toBe('function');
      expect(typeof simulationHandle.destroy).toBe('function');
    });
  });

  describe('updateData - 增量数据更新', () => {
    it('应该添加新节点并保留现有节点', () => {
      const initialGraph = createMockGraph(3);
      simulationHandle = createForceSimulation(
        initialGraph,
        { width: 800, height: 600 },
        () => {}
      );

      const initialNodeIds = new Set(simulationHandle.nodes.map(n => n.id));

      const updatedGraph = createMockGraph(5);
      simulationHandle.updateData(updatedGraph);

      expect(simulationHandle.nodes).toHaveLength(5);

      for (const node of simulationHandle.nodes) {
        if (initialNodeIds.has(node.id)) {
          expect(node.x).toBeDefined();
          expect(node.y).toBeDefined();
        }
      }
    });

    it('应该移除不再存在的节点', () => {
      const initialGraph = createMockGraph(5);
      simulationHandle = createForceSimulation(
        initialGraph,
        { width: 800, height: 600 },
        () => {}
      );

      const updatedGraph = createMockGraph(2);
      simulationHandle.updateData(updatedGraph);

      expect(simulationHandle.nodes).toHaveLength(2);
      expect(simulationHandle.links).toHaveLength(1);

      const nodeIds = simulationHandle.nodes.map(n => n.id);
      expect(nodeIds).toContain('node-0');
      expect(nodeIds).toContain('node-1');
    });

    it('应该保留现有节点的位置', () => {
      const initialGraph = createMockGraph(3);
      simulationHandle = createForceSimulation(
        initialGraph,
        { width: 800, height: 600 },
        () => {}
      );

      simulationHandle.simulation.tick(100);

      const firstNodeBefore = simulationHandle.nodes[0];
      const savedX = firstNodeBefore.x;
      const savedY = firstNodeBefore.y;

      const updatedGraph = {
        ...initialGraph,
        nodes: [...initialGraph.nodes, {
          id: 'node-3',
          label: 'Node 3',
          type: 'document' as const,
        }],
      };
      simulationHandle.updateData(updatedGraph);

      const firstNodeAfter = simulationHandle.nodes.find(n => n.id === 'node-0');
      expect(firstNodeAfter?.x).toBe(savedX);
      expect(firstNodeAfter?.y).toBe(savedY);
    });

    it('应该更新链接数据', () => {
      const initialGraph = createMockGraph(3);
      simulationHandle = createForceSimulation(
        initialGraph,
        { width: 800, height: 600 },
        () => {}
      );

      const updatedGraph = {
        nodes: initialGraph.nodes,
        links: [
          { source: 'node-0', target: 'node-2', type: 'link' as const, value: 1 },
        ],
      };
      simulationHandle.updateData(updatedGraph);

      expect(simulationHandle.links).toHaveLength(1);
      const link = simulationHandle.links[0];
      const sourceId = typeof link.source === 'object' ? link.source.id : link.source;
      const targetId = typeof link.target === 'object' ? link.target.id : link.target;
      expect(sourceId).toBe('node-0');
      expect(targetId).toBe('node-2');
    });
  });

  describe('updateOptions - 参数更新', () => {
    it('应该更新力导向图参数而不重建', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600, linkDistance: 100, chargeStrength: -300 },
        () => {}
      );

      simulationHandle.simulation.tick(50);
      const nodesBefore = simulationHandle.nodes.map(n => ({ id: n.id, x: n.x, y: n.y }));

      simulationHandle.updateOptions({
        linkDistance: 200,
        chargeStrength: -500,
      });

      const nodesAfter = simulationHandle.nodes.map(n => ({ id: n.id }));
      expect(nodesAfter).toEqual(nodesBefore.map(n => ({ id: n.id })));
    });

    it('应该更新画布尺寸', () => {
      const graph = createMockGraph(3);
      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600 },
        () => {}
      );

      simulationHandle.updateOptions({ width: 1200, height: 800 });
      simulationHandle.simulation.tick(10);

      for (const node of simulationHandle.nodes) {
        expect(node.x!).toBeGreaterThanOrEqual(8);
        expect(node.x!).toBeLessThanOrEqual(1192);
        expect(node.y!).toBeGreaterThanOrEqual(8);
        expect(node.y!).toBeLessThanOrEqual(792);
      }
    });
  });

  describe('tick回调', () => {
    it('应该在每个tick调用回调函数', () => {
      const graph = createMockGraph(3);
      let tickCount = 0;
      let lastNodes: SimulationNode[] = [];
      let lastLinks: SimulationLink[] = [];

      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600 },
        (nodes, links) => {
          tickCount++;
          lastNodes = nodes;
          lastLinks = links;
        }
      );

      simulationHandle.simulation.tick(5);

      simulationHandle.simulation.stop();
      simulationHandle.simulation.on('tick')!();

      expect(tickCount).toBe(1);
      expect(lastNodes).toHaveLength(3);
      expect(lastLinks).toHaveLength(2);
    });

    it('应该在updateData后继续调用回调', () => {
      const graph = createMockGraph(2);
      let tickCount = 0;

      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600 },
        () => { tickCount++; }
      );

      simulationHandle.simulation.stop();
      simulationHandle.simulation.on('tick')!();
      const countAfterFirst = tickCount;

      const updatedGraph = createMockGraph(4);
      simulationHandle.updateData(updatedGraph);

      simulationHandle.simulation.stop();
      simulationHandle.simulation.on('tick')!();

      expect(tickCount).toBe(countAfterFirst + 1);
    });
  });

  describe('destroy', () => {
    it('应该停止模拟并清理事件', () => {
      const graph = createMockGraph(3);
      let tickCount = 0;

      simulationHandle = createForceSimulation(
        graph,
        { width: 800, height: 600 },
        () => { tickCount++; }
      );

      simulationHandle.destroy();
      simulationHandle.simulation.tick(5);

      expect(tickCount).toBe(0);
    });
  });
});
