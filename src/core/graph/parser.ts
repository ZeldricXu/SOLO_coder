import type { Document } from '@shared/types/document';

export interface GraphNode {
  id: string;
  type: 'document' | 'tag';
  label: string;
  path?: string;
  tags?: string[];
  x?: number;
  y?: number;
  vx?: number;
  vy?: number;
  fx?: number | null;
  fy?: number | null;
}

export interface GraphLink {
  source: string;
  target: string;
  type: 'link' | 'tag';
  value?: number;
}

export interface GraphData {
  nodes: GraphNode[];
  links: GraphLink[];
}

export function parseDocumentLinks(content: string): { links: string[]; tags: string[] } {
  const wikiLinkRegex = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g;
  const tagRegex = /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g;
  
  const links = new Set<string>();
  const tags = new Set<string>();
  
  let match;
  while ((match = wikiLinkRegex.exec(content)) !== null) {
    const target = match[1].trim();
    if (target) links.add(target);
  }
  
  while ((match = tagRegex.exec(content)) !== null) {
    const tag = match[1].trim();
    if (tag) tags.add(tag);
  }
  
  return { links: Array.from(links), tags: Array.from(tags) };
}

export function buildGraphFromDocuments(documents: Array<Document & { content?: string }>): GraphData {
  const nodes: GraphNode[] = [];
  const links: GraphLink[] = [];
  const nodeMap = new Map<string, GraphNode>();
  const tagSet = new Set<string>();

  for (const doc of documents) {
    const docNode: GraphNode = {
      id: doc.id,
      type: 'document',
      label: doc.title,
      path: doc.filePath,
      tags: doc.tags,
    };
    nodes.push(docNode);
    nodeMap.set(doc.id, docNode);
    
    for (const tag of doc.tags) {
      tagSet.add(tag);
    }
  }

  for (const tag of tagSet) {
    const tagNode: GraphNode = {
      id: `tag-${tag.toLowerCase()}`,
      type: 'tag',
      label: tag,
    };
    nodes.push(tagNode);
    nodeMap.set(tagNode.id, tagNode);
  }

  for (const doc of documents) {
    if (!doc.content) continue;
    
    const { links: wikiLinks, tags } = parseDocumentLinks(doc.content);
    
    for (const targetTitle of wikiLinks) {
      const targetDoc = documents.find(
        d => d.title.toLowerCase() === targetTitle.toLowerCase() ||
             d.filePath.toLowerCase().includes(targetTitle.toLowerCase())
      );
      
      if (targetDoc && targetDoc.id !== doc.id) {
        const existingLink = links.find(
          l => (l.source === doc.id && l.target === targetDoc.id) ||
               (l.source === targetDoc.id && l.target === doc.id)
        );
        
        if (!existingLink) {
          links.push({
            source: doc.id,
            target: targetDoc.id,
            type: 'link',
            value: 1,
          });
        }
      }
    }
    
    for (const tag of tags) {
      const tagNodeId = `tag-${tag.toLowerCase()}`;
      if (nodeMap.has(tagNodeId)) {
        links.push({
          source: doc.id,
          target: tagNodeId,
          type: 'tag',
          value: 0.5,
        });
      }
    }
  }

  return { nodes, links };
}

export function filterGraphByTags(
  graph: GraphData,
  selectedTags: string[]
): GraphData {
  if (selectedTags.length === 0) return graph;

  const docIdsWithTags = new Set<string>();
  
  for (const node of graph.nodes) {
    if (node.type === 'document' && node.tags) {
      const hasTag = selectedTags.some(t => 
        node.tags!.some(docTag => docTag.toLowerCase() === t.toLowerCase())
      );
      if (hasTag) {
        docIdsWithTags.add(node.id);
      }
    }
  }

  const tagIds = selectedTags.map(t => `tag-${t.toLowerCase()}`);
  const linkDocIds = new Set<string>();
  
  for (const link of graph.links) {
    if (link.type === 'tag') {
      const tagId = typeof link.target === 'string' ? link.target : link.target.id;
      const docId = typeof link.source === 'string' ? link.source : link.source.id;
      
      if (tagIds.includes(tagId) && docIdsWithTags.has(docId)) {
        linkDocIds.add(docId);
      }
    } else if (link.type === 'link') {
      const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
      const targetId = typeof link.target === 'string' ? link.target : link.target.id;
      
      if (docIdsWithTags.has(sourceId) || docIdsWithTags.has(targetId)) {
        linkDocIds.add(sourceId);
        linkDocIds.add(targetId);
      }
    }
  }

  const visibleIds = new Set([...docIdsWithTags, ...linkDocIds, ...tagIds]);
  
  const filteredNodes = graph.nodes.filter(n => visibleIds.has(n.id));
  const filteredLinks = graph.links.filter(l => {
    const sourceId = typeof l.source === 'string' ? l.source : l.source.id;
    const targetId = typeof l.target === 'string' ? l.target : l.target.id;
    return visibleIds.has(sourceId) && visibleIds.has(targetId);
  });

  return { nodes: filteredNodes, links: filteredLinks };
}

export function searchGraphNodes(
  graph: GraphData,
  query: string
): Set<string> {
  if (!query.trim()) return new Set(graph.nodes.map(n => n.id));
  
  const lowerQuery = query.toLowerCase();
  const matchedIds = new Set<string>();
  
  for (const node of graph.nodes) {
    if (node.label.toLowerCase().includes(lowerQuery)) {
      matchedIds.add(node.id);
      
      for (const link of graph.links) {
        const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
        const targetId = typeof link.target === 'string' ? link.target : link.target.id;
        
        if (sourceId === node.id) matchedIds.add(targetId);
        if (targetId === node.id) matchedIds.add(sourceId);
      }
    }
  }
  
  return matchedIds;
}

export const searchNodes = searchGraphNodes;

export function getNodeDegree(graph: GraphData, nodeId: string): { in: number; out: number; total: number } {
  let inDegree = 0;
  let outDegree = 0;
  
  for (const link of graph.links) {
    const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
    const targetId = typeof link.target === 'string' ? link.target : link.target.id;
    
    if (sourceId === nodeId) outDegree++;
    if (targetId === nodeId) inDegree++;
  }
  
  return { in: inDegree, out: outDegree, total: inDegree + outDegree };
}

export function getConnectedComponents(graph: GraphData): GraphData[] {
  const visited = new Set<string>();
  const components: GraphData[] = [];
  
  function dfs(nodeId: string, componentNodes: Set<string>, componentLinks: Set<string>): void {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    componentNodes.add(nodeId);
    
    for (const link of graph.links) {
      const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
      const targetId = typeof link.target === 'string' ? link.target : link.target.id;
      const linkKey = `${sourceId}-${targetId}`;
      
      if (sourceId === nodeId && !visited.has(targetId)) {
        componentLinks.add(linkKey);
        dfs(targetId, componentNodes, componentLinks);
      } else if (targetId === nodeId && !visited.has(sourceId)) {
        componentLinks.add(linkKey);
        dfs(sourceId, componentNodes, componentLinks);
      } else if (sourceId === nodeId || targetId === nodeId) {
        componentLinks.add(linkKey);
      }
    }
  }
  
  for (const node of graph.nodes) {
    if (!visited.has(node.id)) {
      const componentNodes = new Set<string>();
      const componentLinks = new Set<string>();
      
      dfs(node.id, componentNodes, componentLinks);
      
      const nodes = graph.nodes.filter(n => componentNodes.has(n.id));
      const links = graph.links.filter(l => {
        const sourceId = typeof l.source === 'string' ? l.source : l.source.id;
        const targetId = typeof l.target === 'string' ? l.target : l.target.id;
        return componentLinks.has(`${sourceId}-${targetId}`);
      });
      
      components.push({ nodes, links });
    }
  }
  
  return components;
}

export function calculateNodeDegrees(graph: GraphData): Record<string, { inDegree: number; outDegree: number; totalDegree: number }> {
  const degrees: Record<string, { inDegree: number; outDegree: number; totalDegree: number }> = {};

  for (const node of graph.nodes) {
    degrees[node.id] = { inDegree: 0, outDegree: 0, totalDegree: 0 };
  }

  for (const link of graph.links) {
    const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
    const targetId = typeof link.target === 'string' ? link.target : link.target.id;

    if (degrees[sourceId]) {
      degrees[sourceId].outDegree++;
      degrees[sourceId].totalDegree++;
    }
    if (degrees[targetId]) {
      degrees[targetId].inDegree++;
      degrees[targetId].totalDegree++;
    }
  }

  return degrees;
}

export function findConnectedComponents(graph: GraphData): string[][] {
  const visited = new Set<string>();
  const components: string[][] = [];

  function dfs(nodeId: string, component: string[]): void {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    component.push(nodeId);

    for (const link of graph.links) {
      const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
      const targetId = typeof link.target === 'string' ? link.target : link.target.id;

      if (sourceId === nodeId && !visited.has(targetId)) {
        dfs(targetId, component);
      } else if (targetId === nodeId && !visited.has(sourceId)) {
        dfs(sourceId, component);
      }
    }
  }

  for (const node of graph.nodes) {
    if (!visited.has(node.id)) {
      const component: string[] = [];
      dfs(node.id, component);
      components.push(component);
    }
  }

  return components;
}
