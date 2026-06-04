import type {
  KnowledgeGraph,
  RecommendResult,
  RecommendOptions,
  KnowledgeNode,
  GraphEdge,
} from './types';

const DEFAULT_OPTIONS: Required<RecommendOptions> = {
  limit: 5,
  similarityWeight: 0.6,
  referenceWeight: 0.3,
  popularityWeight: 0.1,
  excludeIds: [],
};

export class Recommender {
  private graph: KnowledgeGraph;

  constructor(graph: KnowledgeGraph) {
    this.graph = graph;
  }

  public getRelatedDocuments(
    documentId: string,
    options: RecommendOptions = {}
  ): RecommendResult[] {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    const sourceNode = this.graph.nodes.get(documentId);

    if (!sourceNode) {
      return [];
    }

    const candidateScores = new Map<string, {
      similarityScore: number;
      referenceScore: number;
      popularityScore: number;
      totalScore: number;
      reasons: string[];
    }>();

    const allEdges = this.graph.edges.filter(
      (e) => e.sourceId === documentId || e.targetId === documentId
    );

    for (const edge of allEdges) {
      const otherId = edge.sourceId === documentId ? edge.targetId : edge.sourceId;
      const otherNode = this.graph.nodes.get(otherId);

      if (!otherNode) continue;
      if (otherNode.isDeleted || otherNode.isArchived) continue;
      if (otherId === documentId) continue;
      if (opts.excludeIds.includes(otherId)) continue;

      const existing = candidateScores.get(otherId) || {
        similarityScore: 0,
        referenceScore: 0,
        popularityScore: 0,
        totalScore: 0,
        reasons: [],
      };

      if (edge.type === 'similarity') {
        existing.similarityScore = Math.max(existing.similarityScore, edge.weight);
        if (edge.weight > 0.5) {
          existing.reasons.push(`内容高度相似 (${(edge.weight * 100).toFixed(0)}%)`);
        } else if (edge.weight > 0.3) {
          existing.reasons.push(`内容相关 (${(edge.weight * 100).toFixed(0)}%)`);
        }
      }

      if (edge.type === 'reference') {
        existing.referenceScore = Math.max(existing.referenceScore, edge.weight);
        if (edge.sourceId === documentId) {
          existing.reasons.push('本文引用了该文档');
        } else {
          existing.reasons.push('该文档引用了本文');
        }
      }

      if (edge.type === 'citation') {
        existing.referenceScore = Math.max(existing.referenceScore, edge.weight);
        existing.reasons.push('存在引用关系');
      }

      candidateScores.set(otherId, existing);
    }

    const maxViewCount = this.getMaxViewCount();
    
    for (const [id, scores] of candidateScores) {
      const node = this.graph.nodes.get(id);
      if (node && maxViewCount > 0) {
        scores.popularityScore = (node.viewCount || 0) / maxViewCount;
        if (scores.popularityScore > 0.7) {
          scores.reasons.push('热门文档');
        }
      }

      scores.totalScore =
        scores.similarityScore * opts.similarityWeight +
        scores.referenceScore * opts.referenceWeight +
        scores.popularityScore * opts.popularityWeight;
    }

    const results: RecommendResult[] = [];
    
    for (const [id, scores] of candidateScores) {
      const node = this.graph.nodes.get(id);
      if (!node) continue;

      results.push({
        documentId: id,
        title: node.title,
        score: scores.totalScore,
        similarityScore: scores.similarityScore,
        referenceScore: scores.referenceScore,
        popularityScore: scores.popularityScore,
        reasons: scores.reasons,
      });
    }

    results.sort((a, b) => b.score - a.score);

    return results.slice(0, opts.limit);
  }

  public getPersonalizedRecommendations(
    userId: string,
    viewedDocumentIds: string[],
    options: RecommendOptions = {}
  ): RecommendResult[] {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    const allScores = new Map<string, {
      similarityScore: number;
      referenceScore: number;
      popularityScore: number;
      totalScore: number;
      reasons: string[];
      sourceCount: number;
    }>();

    for (const docId of viewedDocumentIds) {
      const related = this.getRelatedDocuments(docId, {
        ...options,
        limit: opts.limit * 2,
        excludeIds: [...viewedDocumentIds, ...(opts.excludeIds || [])],
      });

      for (const rec of related) {
        const existing = allScores.get(rec.documentId) || {
          similarityScore: 0,
          referenceScore: 0,
          popularityScore: 0,
          totalScore: 0,
          reasons: [],
          sourceCount: 0,
        };

        existing.similarityScore = Math.max(existing.similarityScore, rec.similarityScore);
        existing.referenceScore = Math.max(existing.referenceScore, rec.referenceScore);
        existing.popularityScore = Math.max(existing.popularityScore, rec.popularityScore);
        existing.sourceCount++;
        existing.reasons.push(...rec.reasons);

        allScores.set(rec.documentId, existing);
      }
    }

    const results: RecommendResult[] = [];
    
    for (const [id, scores] of allScores) {
      const node = this.graph.nodes.get(id);
      if (!node) continue;

      const boostFactor = 1 + Math.log(scores.sourceCount + 1) * 0.2;
      const totalScore = (
        scores.similarityScore * opts.similarityWeight +
        scores.referenceScore * opts.referenceWeight +
        scores.popularityScore * opts.popularityWeight
      ) * boostFactor;

      const uniqueReasons = Array.from(new Set(scores.reasons));
      if (scores.sourceCount > 1) {
        uniqueReasons.push(`基于 ${scores.sourceCount} 篇浏览历史推荐`);
      }

      results.push({
        documentId: id,
        title: node.title,
        score: totalScore,
        similarityScore: scores.similarityScore,
        referenceScore: scores.referenceScore,
        popularityScore: scores.popularityScore,
        reasons: uniqueReasons,
      });
    }

    results.sort((a, b) => b.score - a.score);

    return results.slice(0, opts.limit);
  }

  public getTrendingDocuments(
    options: RecommendOptions = {}
  ): RecommendResult[] {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    const results: RecommendResult[] = [];

    const maxViewCount = this.getMaxViewCount();
    const maxRefCount = this.getMaxReferenceCount();

    for (const [id, node] of this.graph.nodes) {
      if (node.isDeleted || node.isArchived) continue;
      if (opts.excludeIds.includes(id)) continue;

      const viewScore = maxViewCount > 0 ? (node.viewCount || 0) / maxViewCount : 0;
      const refScore = maxRefCount > 0 ? (node.referenceCount || 0) / maxRefCount : 0;
      
      const popularityScore = viewScore * 0.6 + refScore * 0.4;
      
      const reasons: string[] = [];
      if (viewScore > 0.7) reasons.push('高浏览量');
      if (refScore > 0.7) reasons.push('高引用量');

      results.push({
        documentId: id,
        title: node.title,
        score: popularityScore,
        similarityScore: 0,
        referenceScore: refScore,
        popularityScore,
        reasons,
      });
    }

    results.sort((a, b) => b.popularityScore - a.popularityScore);

    return results.slice(0, opts.limit);
  }

  public getSimilarDocuments(
    documentId: string,
    options: RecommendOptions = {}
  ): RecommendResult[] {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    const results: RecommendResult[] = [];

    const similarityEdges = this.graph.edges.filter(
      (e) =>
        (e.sourceId === documentId || e.targetId === documentId) &&
        e.type === 'similarity'
    );

    for (const edge of similarityEdges) {
      const otherId = edge.sourceId === documentId ? edge.targetId : edge.sourceId;
      const node = this.graph.nodes.get(otherId);

      if (!node) continue;
      if (node.isDeleted || node.isArchived) continue;
      if (otherId === documentId) continue;
      if (opts.excludeIds.includes(otherId)) continue;

      const reasons: string[] = [];
      if (edge.weight > 0.5) {
        reasons.push(`内容高度相似 (${(edge.weight * 100).toFixed(0)}%)`);
      } else {
        reasons.push(`内容相关 (${(edge.weight * 100).toFixed(0)}%)`);
      }

      results.push({
        documentId: otherId,
        title: node.title,
        score: edge.weight,
        similarityScore: edge.weight,
        referenceScore: 0,
        popularityScore: 0,
        reasons,
      });
    }

    results.sort((a, b) => b.similarityScore - a.similarityScore);

    return results.slice(0, opts.limit);
  }

  public getRecommendedByReferences(
    documentId: string,
    options: RecommendOptions = {}
  ): RecommendResult[] {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    const results: RecommendResult[] = [];

    const refEdges = this.graph.edges.filter(
      (e) =>
        (e.sourceId === documentId || e.targetId === documentId) &&
        (e.type === 'reference' || e.type === 'citation')
    );

    for (const edge of refEdges) {
      const otherId = edge.sourceId === documentId ? edge.targetId : edge.sourceId;
      const node = this.graph.nodes.get(otherId);

      if (!node) continue;
      if (node.isDeleted || node.isArchived) continue;
      if (otherId === documentId) continue;
      if (opts.excludeIds.includes(otherId)) continue;

      const reasons: string[] = [];
      if (edge.sourceId === documentId) {
        reasons.push('本文引用了该文档');
      } else {
        reasons.push('该文档引用了本文');
      }

      results.push({
        documentId: otherId,
        title: node.title,
        score: edge.weight,
        similarityScore: 0,
        referenceScore: edge.weight,
        popularityScore: 0,
        reasons,
      });
    }

    results.sort((a, b) => b.referenceScore - a.referenceScore);

    return results.slice(0, opts.limit);
  }

  public explainRecommendation(
    documentId: string,
    recommendedId: string
  ): string | null {
    const edges = this.graph.edges.filter(
      (e) =>
        (e.sourceId === documentId && e.targetId === recommendedId) ||
        (e.sourceId === recommendedId && e.targetId === documentId)
    );

    if (edges.length === 0) return null;

    const reasons: string[] = [];

    for (const edge of edges) {
      if (edge.type === 'similarity') {
        reasons.push(`内容相似度: ${(edge.weight * 100).toFixed(1)}%`);
      } else if (edge.type === 'reference') {
        if (edge.sourceId === documentId) {
          reasons.push('您查看的文档引用了此文档');
        } else {
          reasons.push('此文档引用了您查看的文档');
        }
      }
    }

    const node = this.graph.nodes.get(recommendedId);
    if (node?.viewCount && node.viewCount > 100) {
      reasons.push(`热门文档 (${node.viewCount} 次浏览)`);
    }

    return reasons.length > 0 ? reasons.join('；') : null;
  }

  private getMaxViewCount(): number {
    let max = 0;
    for (const node of this.graph.nodes.values()) {
      max = Math.max(max, node.viewCount || 0);
    }
    return max;
  }

  private getMaxReferenceCount(): number {
    let max = 0;
    for (const node of this.graph.nodes.values()) {
      max = Math.max(max, node.referenceCount || 0);
    }
    return max;
  }
}

export function getRelatedDocuments(
  graph: KnowledgeGraph,
  documentId: string,
  options?: RecommendOptions
): RecommendResult[] {
  const recommender = new Recommender(graph);
  return recommender.getRelatedDocuments(documentId, options);
}

export function getRecommendedDocuments(
  graph: KnowledgeGraph,
  documentId: string,
  options?: RecommendOptions
): RecommendResult[] {
  return getRelatedDocuments(graph, documentId, options);
}

export function getPersonalizedRecommendations(
  graph: KnowledgeGraph,
  userId: string,
  viewedDocumentIds: string[],
  options?: RecommendOptions
): RecommendResult[] {
  const recommender = new Recommender(graph);
  return recommender.getPersonalizedRecommendations(userId, viewedDocumentIds, options);
}

export function getTrendingDocuments(
  graph: KnowledgeGraph,
  options?: RecommendOptions
): RecommendResult[] {
  const recommender = new Recommender(graph);
  return recommender.getTrendingDocuments(options);
}
