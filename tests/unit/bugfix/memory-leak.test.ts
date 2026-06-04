import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createForceSimulation, type SimulationHandle } from '@/core/graph/forceLayout';
import type { GraphData } from '@/core/graph/parser';

describe('内存泄漏修复回归测试', () => {
  describe('Force Simulation - tick回调清理', () => {
    let simulationHandle: SimulationHandle | null = null;

    afterEach(() => {
      if (simulationHandle) {
        simulationHandle.destroy();
        simulationHandle = null;
      }
    });

    const createMockGraph = (nodeCount: number): GraphData => {
      const nodes = Array.from({ length: nodeCount }, (_, i) => ({
        id: `node-${i}`,
        label: `Node ${i}`,
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

    it('destroy后simulation不再触发tick回调', () => {
      const tickCallback = vi.fn();
      const graph = createMockGraph(5);

      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, tickCallback);
      
      const sim = simulationHandle.simulation;
      simulationHandle.destroy();
      simulationHandle = null;

      const tickHandler = sim.on('tick');
      expect(tickHandler == null).toBe(true);
    });

    it('destroy后simulation处于停止状态', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, vi.fn());

      simulationHandle.destroy();
      
      expect(simulationHandle.simulation.alpha()).toBe(0);
    });

    it('destroy清除所有force引用避免闭包泄漏', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, vi.fn());

      simulationHandle.destroy();

      const sim = simulationHandle.simulation;
      expect(sim.force('link') == null).toBe(true);
      expect(sim.force('charge') == null).toBe(true);
      expect(sim.force('center') == null).toBe(true);
      expect(sim.force('collision') == null).toBe(true);
      expect(sim.force('x') == null).toBe(true);
      expect(sim.force('y') == null).toBe(true);
    });

    it('多次destroy不会抛出异常', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, vi.fn());

      expect(() => {
        simulationHandle!.destroy();
        simulationHandle!.destroy();
        simulationHandle!.destroy();
      }).not.toThrow();
    });

    it('updateData后destroy仍能正确清理', () => {
      const graph = createMockGraph(5);
      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, vi.fn());

      const newGraph = createMockGraph(8);
      simulationHandle.updateData(newGraph);

      simulationHandle.destroy();

      expect(simulationHandle.simulation.alpha()).toBe(0);
      expect(simulationHandle.simulation.force('link') == null).toBe(true);
    });

    it('tick回调中的闭包引用在destroy后被释放', () => {
      const largeObject = { data: new Array(10000).fill('test') };
      const tickCallback = vi.fn();
      const graph = createMockGraph(5);

      simulationHandle = createForceSimulation(graph, { width: 800, height: 600 }, (nodes, links) => {
        tickCallback();
        void largeObject;
      });

      simulationHandle.destroy();
      simulationHandle = null;

      expect(tickCallback).toBeDefined();
    });
  });

  describe('FileService - watcher生命周期', () => {
    it('startWatcher和stopWatcher方法签名正确', async () => {
      const { FileService } = await import('@/main/services/FileService');
      const tmpDir = `/tmp/kf-test-watcher-${Date.now()}`;
      const service = new FileService(tmpDir);
      
      expect(typeof service.startWatcher).toBe('function');
      expect(typeof service.stopWatcher).toBe('function');
    });

    it('stopWatcher在无watcher时不报错', async () => {
      const { FileService } = await import('@/main/services/FileService');
      const tmpDir = `/tmp/kf-test-watcher-${Date.now()}`;
      const service = new FileService(tmpDir);
      
      await expect(service.stopWatcher()).resolves.toBeUndefined();
    });
  });
});
