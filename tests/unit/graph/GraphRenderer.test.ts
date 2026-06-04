import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import * as d3 from 'd3';
import { GraphRenderer } from '@/core/graph/GraphRenderer';
import type { SimulationNode, SimulationLink } from '@/core/graph/forceLayout';

describe('GraphRenderer', () => {
  let svg: SVGSVGElement;
  let g: SVGGElement;
  let renderer: GraphRenderer;

  beforeEach(() => {
    svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    svg.appendChild(g);
    document.body.appendChild(svg);

    renderer = new GraphRenderer(svg, g);
  });

  afterEach(() => {
    renderer.destroy();
    document.body.removeChild(svg);
  });

  const createMockNodes = (count: number): SimulationNode[] => {
    return Array.from({ length: count }, (_, i) => ({
      id: `node-${i}`,
      label: `Node ${i}`,
      type: 'document' as const,
      x: 100 + i * 50,
      y: 100 + i * 30,
    }));
  };

  const createMockLinks = (nodes: SimulationNode[]): SimulationLink[] => {
    return nodes.slice(0, -1).map((node, i) => ({
      source: node.id,
      target: nodes[i + 1].id,
      type: 'link' as const,
      value: 1,
    }));
  };

  describe('初始化', () => {
    it('应该正确初始化SVG结构', () => {
      expect(svg.querySelector('.graph-container')).not.toBeNull();
      expect(svg.querySelector('.links')).not.toBeNull();
      expect(svg.querySelector('.nodes')).not.toBeNull();
      expect(svg.querySelector('.labels')).not.toBeNull();
    });
  });

  describe('增量渲染', () => {
    it('应该正确渲染初始节点和链接', () => {
      const nodes = createMockNodes(3);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);

      const nodeElements = svg.querySelectorAll('.nodes g.node');
      const linkElements = svg.querySelectorAll('.links line');

      expect(nodeElements.length).toBe(3);
      expect(linkElements.length).toBe(2);
    });

    it('应该使用enter动画添加新节点', () => {
      const nodes = createMockNodes(2);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);

      const newNodes = createMockNodes(4);
      const newLinks = createMockLinks(newNodes);
      const newGraph = { nodes: newNodes, links: newLinks };

      renderer.render(newNodes, newLinks, newGraph);

      const nodeElements = svg.querySelectorAll('.nodes g.node');
      expect(nodeElements.length).toBe(4);
    });

    it('应该使用exit动画移除节点', () => {
      const nodes = createMockNodes(4);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);
      expect(svg.querySelectorAll('.nodes g.node').length).toBe(4);

      const newNodes = nodes.slice(0, 2);
      const newLinks = createMockLinks(newNodes);
      const newGraph = { nodes: newNodes, links: newLinks };

      renderer.render(newNodes, newLinks, newGraph);

      const allNodes = Array.from(svg.querySelectorAll('.nodes g.node'));
      expect(allNodes.length).toBe(4);

      const exitingNodes = allNodes.filter(n => n.getAttribute('opacity') === '0');
      const activeNodes = allNodes.filter(n => n.getAttribute('opacity') !== '0');

      expect(exitingNodes.length).toBe(2);
      expect(activeNodes.length).toBe(2);

      activeNodes.forEach(node => {
        expect(node.getAttribute('opacity')).not.toBe('0');
      });
    });

    it('应该保留现有节点的DOM元素', () => {
      const nodes = createMockNodes(3);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);

      const firstNodeBefore = svg.querySelector('.nodes g.node') as Element;

      const updatedNodes = [...nodes, {
        id: 'node-3',
        label: 'Node 3',
        type: 'document' as const,
        x: 300,
        y: 200,
      } as SimulationNode];
      const updatedLinks = createMockLinks(updatedNodes);
      const updatedGraph = { nodes: updatedNodes, links: updatedLinks };

      renderer.render(updatedNodes, updatedLinks, updatedGraph);

      const firstNodeAfter = svg.querySelector('.nodes g.node') as Element;
      expect(firstNodeBefore).toBe(firstNodeAfter);
    });
  });

  describe('位置更新', () => {
    it('应该只更新transform属性而不重建DOM', () => {
      const nodes = createMockNodes(2);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);

      const nodeElement = svg.querySelector('.nodes g.node') as SVGGElement;
      const initialTransform = nodeElement.getAttribute('transform');

      const updatedNodes = nodes.map(n => ({ ...n, x: n.x! + 100, y: n.y! + 50 }));
      renderer.updatePositions(updatedNodes, links);

      const newTransform = nodeElement.getAttribute('transform');
      expect(newTransform).not.toBe(initialTransform);
      expect(newTransform).toContain('translate(200,150)');
    });
  });

  describe('高亮状态', () => {
    it('应该正确更新节点高亮样式', () => {
      const nodes = createMockNodes(3);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);

      const highlightedIds = new Set(['node-0', 'node-1']);
      renderer.updateHighlight({
        selectedNodeId: 'node-0',
        highlightedNodeIds: highlightedIds,
      });

      const allNodes = svg.querySelectorAll('.nodes g.node');
      const firstCircle = allNodes[0].querySelector('.node-circle') as SVGCircleElement;
      const thirdCircle = allNodes[2].querySelector('.node-circle') as SVGCircleElement;

      expect(parseFloat(firstCircle.getAttribute('opacity') || '1')).toBe(1);
      expect(parseFloat(thirdCircle.getAttribute('opacity') || '1')).toBeLessThan(1);
    });
  });

  describe('临时链接', () => {
    it('应该显示和隐藏临时链接', () => {
      renderer.showTempLink(0, 0, 100, 100);

      const tempLink = svg.querySelector('.temp-links line');
      expect(tempLink).not.toBeNull();
      expect(tempLink?.getAttribute('x1')).toBe('0');
      expect(tempLink?.getAttribute('y1')).toBe('0');
      expect(tempLink?.getAttribute('x2')).toBe('100');
      expect(tempLink?.getAttribute('y2')).toBe('100');

      renderer.hideTempLink();
      expect(svg.querySelector('.temp-links line')).toBeNull();
    });
  });

  describe('清理', () => {
    it('应该正确清理所有事件监听器', () => {
      const nodes = createMockNodes(2);
      const links = createMockLinks(nodes);
      const graph = { nodes, links };

      renderer.render(nodes, links, graph);
      renderer.destroy();

      expect(svg.querySelector('.graph-container')).toBeNull();
    });
  });
});
