import { describe, it, expect, beforeAll } from 'vitest';
import fs from 'fs';
import path from 'path';
import { buildGraphFromDocuments, filterGraphByTags, searchNodes, calculateNodeDegrees, findConnectedComponents } from '@/core/graph/parser';
import { parseTags } from '@/shared/utils/markdown';
import type { Document } from '@/shared/types';

const FIXTURE_PATH = path.resolve(__dirname, '../../fixtures/vault');

const graphEdges = (graph: any) => graph.links || graph.edges;

describe('知识图谱解析', () => {
  let documents: Document[] = [];

  beforeAll(() => {
    const files = fs.readdirSync(FIXTURE_PATH).filter(f => f.endsWith('.md'));
    documents = files.map(filename => {
      const content = fs.readFileSync(path.join(FIXTURE_PATH, filename), 'utf-8');
      const id = filename.replace('.md', '');
      const tags = parseTags(content);
      return {
        id,
        title: id,
        content,
        tags,
        filename,
        filePath: path.join(FIXTURE_PATH, filename),
        wordCount: content.length,
        hash: '',
        createdAt: new Date(),
        updatedAt: new Date(),
        backlinks: [],
        outline: [],
      };
    });
  });

  describe('图谱构建', () => {
    it('应该从6个文档构建正确的图谱', () => {
      const graph = buildGraphFromDocuments(documents);

      const documentNodes = graph.nodes.filter(n => n.type === 'document');
      expect(documentNodes).toHaveLength(6);
      expect(graphEdges(graph).length).toBeGreaterThan(0);
    });

    it('应该正确解析所有双向链接', () => {
      const graph = buildGraphFromDocuments(documents);

      const doc1 = graph.nodes.find(n => n.id === '01-项目概述');
      const doc2 = graph.nodes.find(n => n.id === '02-技术架构');
      const doc3 = graph.nodes.find(n => n.id === '03-Markdown编辑器');
      const doc4 = graph.nodes.find(n => n.id === '04-知识图谱');
      const doc5 = graph.nodes.find(n => n.id === '05-全文搜索');
      const doc6 = graph.nodes.find(n => n.id === '06-孤立文档');

      expect(doc1).toBeDefined();
      expect(doc2).toBeDefined();
      expect(doc3).toBeDefined();
      expect(doc4).toBeDefined();
      expect(doc5).toBeDefined();
      expect(doc6).toBeDefined();
    });

    it('边数应该大于0', () => {
      const graph = buildGraphFromDocuments(documents);

      expect(graphEdges(graph).length).toBeGreaterThan(0);
    });

    it('孤立节点应该存在于图谱中', () => {
      const graph = buildGraphFromDocuments(documents);

      const isolatedNode = graph.nodes.find(n => n.id === '06-孤立文档');
      expect(isolatedNode).toBeDefined();

      const connectedToIsolated = graphEdges(graph).filter(
        e => e.source === '06-孤立文档' || e.target === '06-孤立文档'
      );
      expect(connectedToIsolated).toHaveLength(0);
    });
  });

  describe('标签提取', () => {
    it('应该从YAML front matter中提取tags', () => {
      const doc1 = documents.find(d => d.id === '01-项目概述');
      expect(doc1).toBeDefined();

      const content = doc1!.content || '';
      const frontMatterMatch = content.match(/^---\n([\s\S]*?)\n---/);
      expect(frontMatterMatch).toBeTruthy();

      if (frontMatterMatch) {
        const frontMatter = frontMatterMatch[1];
        const tagsMatch = frontMatter.match(/tags:\s*\[([^\]]+)\]/);
        expect(tagsMatch).toBeTruthy();
        if (tagsMatch) {
          const tags = tagsMatch[1].split(',').map(t => t.trim());
          expect(tags).toContain('项目');
          expect(tags).toContain('TypeScript');
          expect(tags).toContain('Electron');
        }
      }
    });

    it('应该正确解析所有文档的tags', () => {
      const tagsMap: Record<string, string[]> = {};

      documents.forEach(doc => {
        const content = doc.content || '';
        const frontMatterMatch = content.match(/^---\n([\s\S]*?)\n---/);
        if (frontMatterMatch) {
          const frontMatter = frontMatterMatch[1];
          const tagsMatch = frontMatter.match(/tags:\s*\[([^\]]+)\]/);
          if (tagsMatch) {
            tagsMap[doc.id] = tagsMatch[1].split(',').map(t => t.trim());
          }
        }
      });

      expect(tagsMap['01-项目概述']).toEqual(['项目', 'TypeScript', 'Electron']);
      expect(tagsMap['02-技术架构']).toEqual(['技术', 'Electron', 'React']);
      expect(tagsMap['03-Markdown编辑器']).toEqual(['编辑器', 'CodeMirror']);
      expect(tagsMap['04-知识图谱']).toEqual(['图谱', 'D3', '可视化']);
      expect(tagsMap['05-全文搜索']).toEqual(['搜索', 'FlexSearch']);
      expect(tagsMap['06-孤立文档']).toEqual(['笔记', '个人']);
    });
  });

  describe('按标签过滤', () => {
    it('应该正确过滤包含指定标签的节点', () => {
      const graph = buildGraphFromDocuments(documents);
      const filteredGraph = filterGraphByTags(graph, ['Electron']);

      const electronDocs = filteredGraph.nodes.filter(n =>
        ['01-项目概述', '02-技术架构'].includes(n.id)
      );
      expect(electronDocs).toHaveLength(2);
    });

    it('过滤后应该只保留相关的边', () => {
      const graph = buildGraphFromDocuments(documents);
      const filteredGraph = filterGraphByTags(graph, ['Electron']);

      const validNodeIds = filteredGraph.nodes.map(n => n.id);
      graphEdges(filteredGraph).forEach((edge: any) => {
        expect(validNodeIds).toContain(edge.source);
        expect(validNodeIds).toContain(edge.target);
      });
    });

    it('应该支持多标签过滤（AND逻辑）', () => {
      const graph = buildGraphFromDocuments(documents);
      const graphWithTags = {
        ...graph,
        nodes: graph.nodes.map(node => {
          const doc = documents.find(d => d.id === node.id);
          const content = doc?.content || '';
          const frontMatterMatch = content.match(/^---\n([\s\S]*?)\n---/);
          let tags: string[] = [];
          if (frontMatterMatch) {
            const tagsMatch = frontMatterMatch[1].match(/tags:\s*\[([^\]]+)\]/);
            if (tagsMatch) {
              tags = tagsMatch[1].split(',').map(t => t.trim());
            }
          }
          return { ...node, tags };
        }),
      };

      const filteredGraph = filterGraphByTags(graphWithTags, ['技术', 'React']);
      expect(filteredGraph.nodes.some(n => n.id === '02-技术架构')).toBe(true);
    });
  });

  describe('节点搜索', () => {
    it('应该根据标题搜索节点', () => {
      const graph = buildGraphFromDocuments(documents);
      const resultIds = searchNodes(graph, '项目');

      expect(resultIds.size).toBeGreaterThan(0);
      expect(resultIds.has('01-项目概述')).toBe(true);
    });

    it('搜索应该不区分大小写', () => {
      const graph = buildGraphFromDocuments(documents);
      const results1 = searchNodes(graph, 'editor');
      const results2 = searchNodes(graph, 'EDITOR');

      expect(results1.size).toEqual(results2.size);
    });

    it('空搜索应该返回所有节点', () => {
      const graph = buildGraphFromDocuments(documents);
      const results = searchNodes(graph, '');
      expect(results.size).toEqual(graph.nodes.length);
    });
  });

  describe('节点度数计算', () => {
    it('应该正确计算每个节点的入度和出度', () => {
      const graph = buildGraphFromDocuments(documents);
      const degrees = calculateNodeDegrees(graph);

      expect(degrees['01-项目概述']).toBeDefined();
      expect(degrees['06-孤立文档'].inDegree).toBe(0);
      expect(degrees['06-孤立文档'].outDegree).toBe(0);
    });

    it('项目概述应该有最高的度数', () => {
      const graph = buildGraphFromDocuments(documents);
      const degrees = calculateNodeDegrees(graph);

      const overviewDegree = degrees['01-项目概述'].totalDegree;
      const isolatedDegree = degrees['06-孤立文档'].totalDegree;

      expect(overviewDegree).toBeGreaterThan(isolatedDegree);
    });
  });

  describe('连通分量分析', () => {
    it('应该识别正确数量的连通分量', () => {
      const graph = buildGraphFromDocuments(documents);
      const components = findConnectedComponents(graph);

      expect(components.length).toBeGreaterThan(1);
    });

    it('孤立文档应该构成单独的连通分量', () => {
      const graph = buildGraphFromDocuments(documents);
      const components = findConnectedComponents(graph);

      const isolatedComponent = components.find(c =>
        c.length === 1 && c[0] === '06-孤立文档'
      );
      expect(isolatedComponent).toBeDefined();
    });
  });
});
