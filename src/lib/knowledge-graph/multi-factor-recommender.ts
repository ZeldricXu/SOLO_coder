import { PrismaClient } from '@prisma/client';
import { KnowledgeGraph, RecommendResult, RecommendOptions, KnowledgeNode } from './types';
import { Recommender } from './recommender';
import { ViewLogService } from '@/server/services/ViewLogService';

export interface RecommendWeights {
  contentSimilarity: number;
  sameAuthor: number;
  sameSpace: number;
  collaborativeFiltering: number;
}

export const DEFAULT_WEIGHTS: RecommendWeights = {
  contentSimilarity: 0.4,
  sameAuthor: 0.2,
  sameSpace: 0.2,
  collaborativeFiltering: 0.2,
};

export interface MultiFactorRecommendResult extends RecommendResult {
  factorScores: {
    contentSimilarity: number;
    sameAuthor: number;
    sameSpace: number;
    collaborativeFiltering: number;
  };
}

export class MultiFactorRecommender {
  private prisma: PrismaClient;
  private graph: KnowledgeGraph;
  private baseRecommender: Recommender;
  private viewLogService: ViewLogService;
  private weights: RecommendWeights;

  constructor(
    prisma: PrismaClient,
    graph: KnowledgeGraph,
    weights?: Partial<RecommendWeights>
  ) {
    this.prisma = prisma;
    this.graph = graph;
    this.baseRecommender = new Recommender(graph);
    this.viewLogService = new ViewLogService(prisma);
    this.weights = { ...DEFAULT_WEIGHTS, ...weights };
  }

  setWeights(weights: Partial<RecommendWeights>): void {
    this.weights = { ...this.weights, ...weights };
  }

  getWeights(): RecommendWeights {
    return { ...this.weights };
  }

  async getRelatedDocuments(
    documentId: string,
    options: RecommendOptions & {
      userId?: string;
      weights?: Partial<RecommendWeights>;
    } = {}
  ): Promise<MultiFactorRecommendResult[]> {
    const weights = { ...this.weights, ...options.weights };
    const sourceNode = this.graph.nodes.get(documentId);

    if (!sourceNode) {
      return [];
    }

    const excludeIds = [documentId, ...(options.excludeIds || [])];
    const candidateScores = new Map<
      string,
      {
        contentSimilarity: number;
        sameAuthor: number;
        sameSpace: number;
        collaborativeFiltering: number;
        totalScore: number;
        reasons: string[];
      }
    >();

    const contentResults = this.baseRecommender.getRelatedDocuments(documentId, {
      limit: options.limit ? options.limit * 3 : 30,
      excludeIds,
    });

    for (const result of contentResults) {
      candidateScores.set(result.documentId, {
        contentSimilarity: result.similarityScore,
        sameAuthor: 0,
        sameSpace: 0,
        collaborativeFiltering: 0,
        totalScore: 0,
        reasons: result.reasons,
      });
    }

    const allCandidateIds = Array.from(candidateScores.keys());
    await this.calculateSameAuthorScores(documentId, allCandidateIds, candidateScores);
    await this.calculateSameSpaceScores(documentId, allCandidateIds, candidateScores);
    await this.calculateCollaborativeFilteringScores(
      documentId,
      allCandidateIds,
      candidateScores,
      options.userId
    );

    for (const [id, scores] of candidateScores) {
      scores.totalScore =
        scores.contentSimilarity * weights.contentSimilarity +
        scores.sameAuthor * weights.sameAuthor +
        scores.sameSpace * weights.sameSpace +
        scores.collaborativeFiltering * weights.collaborativeFiltering;
    }

    const results: MultiFactorRecommendResult[] = [];

    for (const [id, scores] of candidateScores) {
      const node = this.graph.nodes.get(id);
      if (!node) continue;

      const reasons = [...scores.reasons];

      if (scores.sameAuthor >= 1) {
        reasons.push('同一作者');
      }
      if (scores.sameSpace >= 1) {
        reasons.push('同一空间');
      }
      if (scores.collaborativeFiltering > 0.3) {
        reasons.push(
          `浏览此文档的人也浏览了 (${(scores.collaborativeFiltering * 100).toFixed(0)}%匹配)`
        );
      }

      results.push({
        documentId: id,
        title: node.title,
        score: scores.totalScore,
        similarityScore: scores.contentSimilarity,
        referenceScore: 0,
        popularityScore: 0,
        reasons: Array.from(new Set(reasons)),
        factorScores: {
          contentSimilarity: scores.contentSimilarity,
          sameAuthor: scores.sameAuthor,
          sameSpace: scores.sameSpace,
          collaborativeFiltering: scores.collaborativeFiltering,
        },
      });
    }

    results.sort((a, b) => b.score - a.score);
    return results.slice(0, options.limit || 5);
  }

  private async calculateSameAuthorScores(
    sourceDocumentId: string,
    candidateIds: string[],
    scoresMap: Map<string, any>
  ): Promise<void> {
    const sourceDoc = await this.prisma.document.findUnique({
      where: { id: sourceDocumentId },
      select: { createdById: true },
    });

    if (!sourceDoc) return;

    const candidateDocs = await this.prisma.document.findMany({
      where: {
        id: { in: candidateIds },
      },
      select: { id: true, createdById: true },
    });

    for (const doc of candidateDocs) {
      const scores = scoresMap.get(doc.id);
      if (scores && doc.createdById === sourceDoc.createdById) {
        scores.sameAuthor = 1;
      }
    }
  }

  private async calculateSameSpaceScores(
    sourceDocumentId: string,
    candidateIds: string[],
    scoresMap: Map<string, any>
  ): Promise<void> {
    const sourceDoc = await this.prisma.document.findUnique({
      where: { id: sourceDocumentId },
      select: { spaceId: true },
    });

    if (!sourceDoc) return;

    const candidateDocs = await this.prisma.document.findMany({
      where: {
        id: { in: candidateIds },
      },
      select: { id: true, spaceId: true },
    });

    for (const doc of candidateDocs) {
      const scores = scoresMap.get(doc.id);
      if (scores && doc.spaceId === sourceDoc.spaceId) {
        scores.sameSpace = 1;
      }
    }
  }

  private async calculateCollaborativeFilteringScores(
    sourceDocumentId: string,
    candidateIds: string[],
    scoresMap: Map<string, any>,
    userId?: string
  ): Promise<void> {
    try {
      const cooccurrences = await this.viewLogService.getCooccurringDocuments(
        sourceDocumentId,
        candidateIds.length,
        90,
        [sourceDocumentId]
      );

      for (const co of cooccurrences) {
        const scores = scoresMap.get(co.documentId);
        if (scores) {
          scores.collaborativeFiltering = Math.min(co.confidence, 1);
        }
      }
    } catch (error) {
      console.warn('Collaborative filtering calculation failed:', error);
    }
  }

  async getPersonalizedRecommendations(
    userId: string,
    viewedDocumentIds: string[],
    options: RecommendOptions & {
      weights?: Partial<RecommendWeights>;
    } = {}
  ): Promise<MultiFactorRecommendResult[]> {
    const weights = { ...this.weights, ...options.weights };
    const allScores = new Map<
      string,
      {
        contentSimilarity: number;
        sameAuthor: number;
        sameSpace: number;
        collaborativeFiltering: number;
        totalScore: number;
        reasons: string[];
        sourceCount: number;
      }
    >();

    for (const docId of viewedDocumentIds) {
      const related = await this.getRelatedDocuments(docId, {
        limit: options.limit ? options.limit * 2 : 20,
        excludeIds: [...viewedDocumentIds, ...(options.excludeIds || [])],
        userId,
        weights,
      });

      for (const rec of related) {
        const existing = allScores.get(rec.documentId) || {
          contentSimilarity: 0,
          sameAuthor: 0,
          sameSpace: 0,
          collaborativeFiltering: 0,
          totalScore: 0,
          reasons: [],
          sourceCount: 0,
        };

        existing.contentSimilarity = Math.max(
          existing.contentSimilarity,
          rec.factorScores.contentSimilarity
        );
        existing.sameAuthor = Math.max(existing.sameAuthor, rec.factorScores.sameAuthor);
        existing.sameSpace = Math.max(existing.sameSpace, rec.factorScores.sameSpace);
        existing.collaborativeFiltering = Math.max(
          existing.collaborativeFiltering,
          rec.factorScores.collaborativeFiltering
        );
        existing.sourceCount++;
        existing.reasons.push(...rec.reasons);

        allScores.set(rec.documentId, existing);
      }
    }

    const cfRecs = await this.viewLogService.getCollaborativeFilteringRecommendations(
      userId,
      options.limit ? options.limit * 2 : 20,
      90
    );

    for (const cf of cfRecs) {
      if (viewedDocumentIds.includes(cf.documentId)) continue;
      if (options.excludeIds?.includes(cf.documentId)) continue;

      const existing = allScores.get(cf.documentId) || {
        contentSimilarity: 0,
        sameAuthor: 0,
        sameSpace: 0,
        collaborativeFiltering: 0,
        totalScore: 0,
        reasons: [],
        sourceCount: 0,
      };

      existing.collaborativeFiltering = Math.max(
        existing.collaborativeFiltering,
        cf.confidence
      );
      existing.sourceCount++;

      if (cf.confidence > 0.3) {
        existing.reasons.push(`基于浏览历史推荐 (${(cf.confidence * 100).toFixed(0)}%匹配)`);
      }

      allScores.set(cf.documentId, existing);
    }

    const results: MultiFactorRecommendResult[] = [];

    for (const [id, scores] of allScores) {
      const node = this.graph.nodes.get(id);
      if (!node) continue;

      const boostFactor = 1 + Math.log(scores.sourceCount + 1) * 0.15;
      const totalScore =
        (scores.contentSimilarity * weights.contentSimilarity +
          scores.sameAuthor * weights.sameAuthor +
          scores.sameSpace * weights.sameSpace +
          scores.collaborativeFiltering * weights.collaborativeFiltering) *
        boostFactor;

      if (scores.sourceCount > 1) {
        scores.reasons.push(`基于 ${scores.sourceCount} 篇浏览历史推荐`);
      }

      results.push({
        documentId: id,
        title: node.title,
        score: totalScore,
        similarityScore: scores.contentSimilarity,
        referenceScore: 0,
        popularityScore: 0,
        reasons: Array.from(new Set(scores.reasons)),
        factorScores: {
          contentSimilarity: scores.contentSimilarity,
          sameAuthor: scores.sameAuthor,
          sameSpace: scores.sameSpace,
          collaborativeFiltering: scores.collaborativeFiltering,
        },
      });
    }

    results.sort((a, b) => b.score - a.score);
    return results.slice(0, options.limit || 5);
  }

  explainRecommendation(
    documentId: string,
    recommendedId: string,
    weights?: Partial<RecommendWeights>
  ): string | null {
    const finalWeights = { ...this.weights, ...weights };
    const edges = this.graph.edges.filter(
      (e) =>
        (e.sourceId === documentId && e.targetId === recommendedId) ||
        (e.sourceId === recommendedId && e.targetId === documentId)
    );

    if (edges.length === 0) return null;

    const reasons: string[] = [];

    for (const edge of edges) {
      if (edge.type === 'similarity') {
        reasons.push(
          `内容相似度: ${(edge.weight * 100).toFixed(1)}% (权重 ${(
            finalWeights.contentSimilarity * 100
          ).toFixed(0)}%)`
        );
      }
    }

    return reasons.length > 0 ? reasons.join('；') : null;
  }
}

export function createMultiFactorRecommender(
  prisma: PrismaClient,
  graph: KnowledgeGraph,
  weights?: Partial<RecommendWeights>
): MultiFactorRecommender {
  return new MultiFactorRecommender(prisma, graph, weights);
}
