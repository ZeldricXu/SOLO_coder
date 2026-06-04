import { TfIdfVectorizer } from './tfidf';
import { findMostSimilarWithVectors, cosineSimilarity } from './similarity';
import { ReferenceParser } from './reference-parser';
import type {
  KnowledgeGraph,
  KnowledgeNode,
  GraphEdge,
  DocumentVector,
  GraphBuildOptions,
  EdgeType,
} from './types';

const DEFAULT_SIMILARITY_THRESHOLD = 0.3;
const DEFAULT_MAX_EDGES_PER_NODE = 20;

export class KnowledgeGraphBuilder {
  private graph: KnowledgeGraph;
  private vectorizer: TfIdfVectorizer;
  private referenceParser: ReferenceParser;
  private options: Required<GraphBuildOptions>;

  constructor(options: GraphBuildOptions = {}) {
    this.options = {
      similarityThreshold: options.similarityThreshold ?? DEFAULT_SIMILARITY_THRESHOLD,
      maxEdgesPerNode: options.maxEdgesPerNode ?? DEFAULT_MAX_EDGES_PER_NODE,
      includeContent: options.includeContent ?? true,
    };

    this.graph = {
      nodes: new Map(),
      edges: [],
      vectors: new Map(),
      idfMap: new Map(),
    };

    this.vectorizer = new TfIdfVectorizer();
    this.referenceParser = new ReferenceParser();
  }

  public buildGraph(
    documents: Array<{
      id: string;
      title: string;
      content: string;
      metadata?: Record<string, unknown>;
      createdAt?: Date;
      updatedAt?: Date;
      isDeleted?: boolean;
      isArchived?: boolean;
      viewCount?: number;
      referenceCount?: number;
    }>
  ): KnowledgeGraph {
    this.graph = {
      nodes: new Map(),
      edges: [],
      vectors: new Map(),
      idfMap: new Map(),
    };

    const validDocs = documents.filter(
      (doc) => !doc.isDeleted && !doc.isArchived
    );

    for (const doc of validDocs) {
      this.addNode(doc);
    }

    this.buildVectors(validDocs);
    this.updateSimilarityEdges();
    this.updateReferenceEdges(validDocs);

    return this.getGraph();
  }

  public addDocument(
    document: {
      id: string;
      title: string;
      content: string;
      metadata?: Record<string, unknown>;
      createdAt?: Date;
      updatedAt?: Date;
      isDeleted?: boolean;
      isArchived?: boolean;
      viewCount?: number;
      referenceCount?: number;
    },
    allDocuments?: Array<{ id: string; content: string }>
  ): KnowledgeGraph {
    if (document.isDeleted || document.isArchived) {
      return this.getGraph();
    }

    this.addNode(document);

    if (allDocuments) {
      const validDocs = allDocuments.filter(
        (d) => d.id !== document.id
      );
      
      const allDocsForVectorize = [...validDocs, document];
      this.vectorizer.fit(allDocsForVectorize);
      
      for (const doc of allDocsForVectorize) {
        const vector = this.vectorizer.transformDocument(doc.id, doc.content);
        this.graph.vectors.set(doc.id, vector);
      }
      
      this.graph.idfMap = this.vectorizer.getIdfMap();
    } else {
      const existingDocs = Array.from(this.graph.nodes.values()).map((node) => ({
        id: node.id,
        content: node.content || '',
      }));
      
      const allDocs = [...existingDocs, { id: document.id, content: document.content }];
      this.vectorizer.fit(allDocs);
      
      for (const doc of allDocs) {
        const vector = this.vectorizer.transformDocument(doc.id, doc.content);
        this.graph.vectors.set(doc.id, vector);
      }
      
      this.graph.idfMap = this.vectorizer.getIdfMap();
    }

    this.updateSimilarityEdges();
    
    const allDocs = allDocuments || Array.from(this.graph.nodes.values()).map((n) => ({
      id: n.id,
      content: n.content || '',
    }));
    this.updateReferenceEdges(allDocs);

    return this.getGraph();
  }

  public updateSimilarityEdges(): void {
    this.graph.edges = this.graph.edges.filter((e) => e.type !== 'similarity');

    const vectors = Array.from(this.graph.vectors.entries()).map(
      ([documentId, docVector]) => ({
        documentId,
        vector: docVector.vector,
      })
    );

    for (const [sourceId, sourceVector] of this.graph.vectors) {
      const similarDocs = findMostSimilarWithVectors(
        sourceVector.vector,
        vectors.filter((v) => v.documentId !== sourceId),
        this.options.maxEdgesPerNode,
        this.options.similarityThreshold
      );

      for (const similar of similarDocs) {
        const edge: GraphEdge = {
          id: `sim:${sourceId}:${similar.documentId}`,
          sourceId,
          targetId: similar.documentId,
          type: 'similarity',
          weight: similar.similarity,
          metadata: {
            rank: similar.rank,
          },
        };
        this.graph.edges.push(edge);
      }
    }
  }

  public updateReferenceEdges(
    documents: Array<{ id: string; content: string }>
  ): void {
    this.graph.edges = this.graph.edges.filter(
      (e) => e.type !== 'reference' && e.type !== 'citation'
    );

    const referenceGraph = this.referenceParser.buildReferenceGraph(documents);

    for (const [sourceId, references] of referenceGraph) {
      const sourceNode = this.graph.nodes.get(sourceId);
      if (!sourceNode) continue;

      for (const ref of references) {
        const targetNode = this.graph.nodes.get(ref.targetDocumentId);
        if (!targetNode) continue;

        const existingEdges = this.graph.edges.filter(
          (e) =>
            (e.sourceId === sourceId && e.targetId === ref.targetDocumentId) ||
            (e.sourceId === ref.targetDocumentId && e.targetId === sourceId)
        );

        const hasSimilarityEdge = existingEdges.some((e) => e.type === 'similarity');
        const weight = hasSimilarityEdge ? 0.7 : 0.5;

        const edge: GraphEdge = {
          id: `ref:${sourceId}:${ref.targetDocumentId}`,
          sourceId,
          targetId: ref.targetDocumentId,
          type: 'reference',
          weight,
          metadata: {
            linkText: ref.linkText,
            isWikiLink: ref.isWikiLink,
            rawPath: ref.rawPath,
          },
        };
        this.graph.edges.push(edge);
      }
    }
  }

  public removeDocument(documentId: string): void {
    this.graph.nodes.delete(documentId);
    this.graph.vectors.delete(documentId);
    this.graph.edges = this.graph.edges.filter(
      (e) => e.sourceId !== documentId && e.targetId !== documentId
    );
  }

  public getNode(documentId: string): KnowledgeNode | undefined {
    return this.graph.nodes.get(documentId);
  }

  public getEdges(
    documentId: string,
    type?: EdgeType
  ): GraphEdge[] {
    return this.graph.edges.filter(
      (e) =>
        (e.sourceId === documentId || e.targetId === documentId) &&
        (!type || e.type === type)
    );
  }

  public getSimilarDocuments(
    documentId: string,
    topN = 10
  ): Array<{ documentId: string; similarity: number; rank: number }> {
    const edges = this.getEdges(documentId, 'similarity')
      .filter((e) => e.type === 'similarity')
      .sort((a, b) => b.weight - a.weight)
      .slice(0, topN);

    return edges.map((e, index) => ({
      documentId: e.sourceId === documentId ? e.targetId : e.sourceId,
      similarity: e.weight,
      rank: index + 1,
    }));
  }

  public getRelatedDocuments(
    documentId: string,
    topN = 10
  ): Array<{ documentId: string; weight: number; types: EdgeType[] }> {
    const edgeMap = new Map<string, { weight: number; types: EdgeType[] }>();

    const edges = this.getEdges(documentId);
    for (const edge of edges) {
      const otherId = edge.sourceId === documentId ? edge.targetId : edge.sourceId;
      const existing = edgeMap.get(otherId) || { weight: 0, types: [] };
      
      existing.weight = Math.max(existing.weight, edge.weight);
      if (!existing.types.includes(edge.type)) {
        existing.types.push(edge.type);
      }
      
      edgeMap.set(otherId, existing);
    }

    return Array.from(edgeMap.entries())
      .map(([id, data]) => ({
        documentId: id,
        weight: data.weight,
        types: data.types,
      }))
      .sort((a, b) => b.weight - a.weight)
      .slice(0, topN);
  }

  public getGraph(): KnowledgeGraph {
    return {
      nodes: new Map(this.graph.nodes),
      edges: [...this.graph.edges],
      vectors: new Map(this.graph.vectors),
      idfMap: new Map(this.graph.idfMap),
    };
  }

  public getVectorizer(): TfIdfVectorizer {
    return this.vectorizer;
  }

  public calculateNodeSimilarity(
    docId1: string,
    docId2: string
  ): number {
    const vec1 = this.graph.vectors.get(docId1);
    const vec2 = this.graph.vectors.get(docId2);
    
    if (!vec1 || !vec2) return 0;
    
    return cosineSimilarity(vec1.vector, vec2.vector);
  }

  private addNode(doc: {
    id: string;
    title: string;
    content: string;
    metadata?: Record<string, unknown>;
    createdAt?: Date;
    updatedAt?: Date;
    isDeleted?: boolean;
    isArchived?: boolean;
    viewCount?: number;
    referenceCount?: number;
  }): void {
    const node: KnowledgeNode = {
      id: doc.id,
      type: 'document',
      title: doc.title,
      content: this.options.includeContent ? doc.content : undefined,
      metadata: doc.metadata,
      createdAt: doc.createdAt || new Date(),
      updatedAt: doc.updatedAt || new Date(),
      isDeleted: doc.isDeleted || false,
      isArchived: doc.isArchived || false,
      viewCount: doc.viewCount || 0,
      referenceCount: doc.referenceCount || 0,
    };

    this.graph.nodes.set(doc.id, node);
  }

  private buildVectors(
    documents: Array<{ id: string; content: string }>
  ): void {
    const vectors = this.vectorizer.fitTransform(documents);
    
    for (const vec of vectors) {
      this.graph.vectors.set(vec.documentId, vec);
    }
    
    this.graph.idfMap = this.vectorizer.getIdfMap();
  }

  public static mergeGraphs(
    graph1: KnowledgeGraph,
    graph2: KnowledgeGraph
  ): KnowledgeGraph {
    const merged: KnowledgeGraph = {
      nodes: new Map([...graph1.nodes, ...graph2.nodes]),
      edges: [...graph1.edges, ...graph2.edges],
      vectors: new Map([...graph1.vectors, ...graph2.vectors]),
      idfMap: new Map([...graph1.idfMap, ...graph2.idfMap]),
    };

    const seenEdges = new Set<string>();
    merged.edges = merged.edges.filter((e) => {
      const key = `${e.type}:${e.sourceId}:${e.targetId}`;
      if (seenEdges.has(key)) return false;
      seenEdges.add(key);
      return true;
    });

    return merged;
  }
}

export function buildKnowledgeGraph(
  documents: Array<{
    id: string;
    title: string;
    content: string;
    metadata?: Record<string, unknown>;
    createdAt?: Date;
    updatedAt?: Date;
    isDeleted?: boolean;
    isArchived?: boolean;
    viewCount?: number;
    referenceCount?: number;
  }>,
  options?: GraphBuildOptions
): KnowledgeGraph {
  const builder = new KnowledgeGraphBuilder(options);
  return builder.buildGraph(documents);
}

export function addDocumentToGraph(
  graph: KnowledgeGraph,
  document: {
    id: string;
    title: string;
    content: string;
    metadata?: Record<string, unknown>;
    createdAt?: Date;
    updatedAt?: Date;
    isDeleted?: boolean;
    isArchived?: boolean;
    viewCount?: number;
    referenceCount?: number;
  },
  options?: GraphBuildOptions
): KnowledgeGraph {
  const builder = new KnowledgeGraphBuilder(options);
  
  for (const [id, node] of graph.nodes) {
    (builder as unknown as { graph: KnowledgeGraph }).graph.nodes.set(id, node);
  }
  
  (builder as unknown as { graph: KnowledgeGraph }).graph.edges = [...graph.edges];
  (builder as unknown as { graph: KnowledgeGraph }).graph.vectors = new Map(graph.vectors);
  (builder as unknown as { graph: KnowledgeGraph }).graph.idfMap = new Map(graph.idfMap);

  const allDocs = Array.from(graph.nodes.values()).map((n) => ({
    id: n.id,
    content: n.content || '',
  }));

  return builder.addDocument(document, allDocs);
}
