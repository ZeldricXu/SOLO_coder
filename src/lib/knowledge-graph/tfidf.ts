import { tokenize } from './tokenizer';
import type { DocumentVector, SparseVector, TokenizedResult } from './types';

export interface TfIdfOptions {
  topKKeywords?: number;
  chunkSize?: number;
  chunkOverlap?: number;
  useChunking?: boolean;
}

const DEFAULT_OPTIONS: Required<TfIdfOptions> = {
  topKKeywords: 100,
  chunkSize: 5000,
  chunkOverlap: 200,
  useChunking: true,
};

export interface ChunkResult {
  id: string;
  content: string;
  startIndex: number;
  endIndex: number;
}

export class TfIdfVectorizer {
  private idfMap: Map<string, number> = new Map();
  private termIndex: Map<string, number> = new Map();
  private documentCount = 0;
  private docFrequencies: Map<string, number> = new Map();
  private options: Required<TfIdfOptions>;

  constructor(options?: TfIdfOptions) {
    this.options = { ...DEFAULT_OPTIONS, ...options };
  }

  public splitIntoChunks(
    content: string,
    options?: Partial<TfIdfOptions>
  ): ChunkResult[] {
    const { chunkSize, chunkOverlap } = { ...this.options, ...options };
    
    if (!content || content.length === 0) {
      return [];
    }

    if (content.length <= chunkSize) {
      return [{
        id: 'chunk-0',
        content,
        startIndex: 0,
        endIndex: content.length,
      }];
    }

    const chunks: ChunkResult[] = [];
    let startIndex = 0;
    let chunkIndex = 0;

    while (startIndex < content.length) {
      let endIndex = Math.min(startIndex + chunkSize, content.length);
      
      if (endIndex < content.length) {
        const lastSentence = content.lastIndexOf('。', endIndex);
        const lastPeriod = content.lastIndexOf('.', endIndex);
        const lastNewline = content.lastIndexOf('\n', endIndex);
        
        const candidateEnd = Math.max(
          lastSentence > startIndex ? lastSentence + 1 : endIndex,
          lastPeriod > startIndex ? lastPeriod + 1 : endIndex,
          lastNewline > startIndex ? lastNewline + 1 : endIndex
        );
        
        if (candidateEnd > startIndex && candidateEnd <= startIndex + chunkSize + chunkOverlap) {
          endIndex = candidateEnd;
        }
      }

      chunks.push({
        id: `chunk-${chunkIndex}`,
        content: content.substring(startIndex, endIndex),
        startIndex,
        endIndex,
      });

      if (endIndex >= content.length) {
        break;
      }

      const nextStart = endIndex - chunkOverlap;
      if (nextStart <= startIndex || nextStart >= content.length) {
        break;
      }
      
      startIndex = nextStart;
      chunkIndex++;

      if (chunks.length > 100) {
        break;
      }
    }

    return chunks;
  }

  public extractTopKeywords(
    content: string,
    topN?: number
  ): Array<{ term: string; tfidf: number }> {
    const tokenized = tokenize(content);
    const { termFreq, filteredTokens, wordCount } = this.calculateMetrics(tokenized);
    
    const keywords: Array<{ term: string; tfidf: number }> = [];
    
    for (const [term, tf] of termFreq) {
      const idf = this.idfMap.get(term) || 1;
      const tfidf = (tf / wordCount) * idf;
      keywords.push({ term, tfidf });
    }

    return keywords
      .sort((a, b) => b.tfidf - a.tfidf)
      .slice(0, topN || this.options.topKKeywords);
  }

  public fit(documents: Array<{ id: string; content: string }>): void {
    this.documentCount = documents.length;
    this.docFrequencies.clear();
    this.termIndex.clear();

    const allTerms = new Set<string>();
    
    for (const doc of documents) {
      const processedContent = this.options.useChunking
        ? this.extractTopKeywords(doc.content, this.options.topKKeywords)
            .map(k => k.term)
            .join(' ')
        : doc.content;
      
      const tokenized = tokenize(processedContent || doc.content);
      const uniqueTerms = new Set(tokenized.filteredTokens);
      
      for (const term of uniqueTerms) {
        allTerms.add(term);
        this.docFrequencies.set(term, (this.docFrequencies.get(term) || 0) + 1);
      }
    }

    const sortedTerms = Array.from(allTerms).sort();
    sortedTerms.forEach((term, index) => {
      this.termIndex.set(term, index);
    });

    for (const [term, docFreq] of this.docFrequencies) {
      const idf = Math.log((this.documentCount + 1) / (docFreq + 1)) + 1;
      this.idfMap.set(term, idf);
    }
  }

  public transform(documents: Array<{ id: string; content: string }>): DocumentVector[] {
    return documents.map((doc) => this.transformDocument(doc.id, doc.content));
  }

  public fitTransform(documents: Array<{ id: string; content: string }>): DocumentVector[] {
    this.fit(documents);
    return this.transform(documents);
  }

  public transformDocument(documentId: string, content: string): DocumentVector {
    if (!this.options.useChunking || content.length <= this.options.chunkSize) {
      const tokenized = tokenize(content);
      return this.createVector(documentId, tokenized);
    }

    const chunks = this.splitIntoChunks(content);
    const chunkVectors: DocumentVector[] = [];

    for (const chunk of chunks) {
      const topKeywords = this.extractTopKeywords(chunk.content, this.options.topKKeywords);
      const filteredContent = topKeywords.map(k => k.term).join(' ');
      
      const tokenized = tokenize(filteredContent || chunk.content);
      const chunkVector = this.createVector(`${documentId}-${chunk.id}`, tokenized);
      chunkVectors.push(chunkVector);
    }

    return this.averageVectors(documentId, chunkVectors);
  }

  private averageVectors(documentId: string, vectors: DocumentVector[]): DocumentVector {
    if (vectors.length === 0) {
      const tokenized = tokenize('');
      return this.createVector(documentId, tokenized);
    }

    if (vectors.length === 1) {
      return {
        ...vectors[0],
        documentId,
      };
    }

    const aggregatedValues = new Map<number, number>();
    const allTerms = new Set<string>();
    const combinedTermFreq = new Map<string, number>();
    let totalWordCount = 0;

    for (const vec of vectors) {
      for (let i = 0; i < vec.vector.indices.length; i++) {
        const index = vec.vector.indices[i];
        const value = vec.vector.values[i];
        aggregatedValues.set(index, (aggregatedValues.get(index) || 0) + value);
      }
      
      for (const term of vec.terms) {
        allTerms.add(term);
      }
      
      for (const [term, freq] of vec.termFrequencies) {
        combinedTermFreq.set(term, (combinedTermFreq.get(term) || 0) + freq);
      }
      
      totalWordCount += vec.wordCount;
    }

    const vectorCount = vectors.length;
    const indices: number[] = [];
    const values: number[] = [];

    for (const [index, sum] of aggregatedValues) {
      indices.push(index);
      values.push(sum / vectorCount);
    }

    const sortedPairs = indices
      .map((idx, i) => ({ index: idx, value: values[i] }))
      .sort((a, b) => a.index - b.index);

    const vector: SparseVector = {
      indices: sortedPairs.map((p) => p.index),
      values: sortedPairs.map((p) => p.value),
      dimension: this.termIndex.size,
    };

    return {
      documentId,
      vector,
      terms: Array.from(allTerms),
      termFrequencies: combinedTermFreq,
      wordCount: Math.round(totalWordCount / vectorCount),
    };
  }

  private createVector(documentId: string, tokenized: TokenizedResult): DocumentVector {
    const { termFreq, filteredTokens, wordCount } = this.calculateMetrics(tokenized);
    
    const topKeywords = Array.from(termFreq.entries())
      .map(([term, tf]) => {
        const idf = this.idfMap.get(term) || 1;
        return { term, tf, tfidf: (tf / wordCount) * idf };
      })
      .sort((a, b) => b.tfidf - a.tfidf)
      .slice(0, this.options.topKKeywords);

    const filteredTermFreq = new Map(
      topKeywords.map(k => [k.term, k.tf])
    );

    const indices: number[] = [];
    const values: number[] = [];

    for (const { term, tf } of topKeywords) {
      const index = this.termIndex.get(term);
      if (index === undefined) continue;
      
      const idf = this.idfMap.get(term) || 0;
      const tfidf = (tf / wordCount) * idf;
      
      indices.push(index);
      values.push(tfidf);
    }

    const sortedPairs = indices
      .map((idx, i) => ({ index: idx, value: values[i] }))
      .sort((a, b) => a.index - b.index);

    const vector: SparseVector = {
      indices: sortedPairs.map((p) => p.index),
      values: sortedPairs.map((p) => p.value),
      dimension: this.termIndex.size,
    };

    return {
      documentId,
      vector,
      terms: topKeywords.map(k => k.term),
      termFrequencies: filteredTermFreq,
      wordCount,
    };
  }

  private calculateMetrics(tokenized: TokenizedResult): {
    termFreq: Map<string, number>;
    filteredTokens: string[];
    wordCount: number;
  } {
    const termFreq = new Map<string, number>();
    
    for (const token of tokenized.filteredTokens) {
      const normalized = token.toLowerCase();
      termFreq.set(normalized, (termFreq.get(normalized) || 0) + 1);
    }

    return {
      termFreq,
      filteredTokens: tokenized.filteredTokens,
      wordCount: tokenized.filteredTokens.length || 1,
    };
  }

  public getTermIndex(term: string): number | undefined {
    return this.termIndex.get(term.toLowerCase());
  }

  public getIdf(term: string): number {
    return this.idfMap.get(term.toLowerCase()) || 0;
  }

  public getVocabulary(): string[] {
    return Array.from(this.termIndex.keys());
  }

  public getVocabularySize(): number {
    return this.termIndex.size;
  }

  public getIdfMap(): Map<string, number> {
    return new Map(this.idfMap);
  }

  public getTopTermsByDocument(
    vector: DocumentVector,
    topN = 10
  ): Array<{ term: string; tfidf: number }> {
    const termScores: Array<{ term: string; tfidf: number }> = [];
    
    for (let i = 0; i < vector.vector.indices.length; i++) {
      const index = vector.vector.indices[i];
      const value = vector.vector.values[i];
      
      for (const [term, termIndex] of this.termIndex) {
        if (termIndex === index) {
          termScores.push({ term, tfidf: value });
          break;
        }
      }
    }

    return termScores
      .sort((a, b) => b.tfidf - a.tfidf)
      .slice(0, topN);
  }

  public static calculateTf(term: string, tokens: string[]): number {
    const normalized = term.toLowerCase();
    const count = tokens.filter((t) => t.toLowerCase() === normalized).length;
    return count / (tokens.length || 1);
  }

  public static calculateIdf(term: string, documents: string[][]): number {
    const normalized = term.toLowerCase();
    const docCount = documents.length;
    const containingDocs = documents.filter((doc) =>
      doc.some((t) => t.toLowerCase() === normalized)
    ).length;
    
    return Math.log((docCount + 1) / (containingDocs + 1)) + 1;
  }
}

export function buildTermFrequencyMatrix(
  documents: Array<{ id: string; content: string }>
): {
  matrix: number[][];
  terms: string[];
  documentIds: string[];
} {
  const vectorizer = new TfIdfVectorizer();
  const vectors = vectorizer.fitTransform(documents);
  const terms = vectorizer.getVocabulary();
  
  const matrix: number[][] = vectors.map((vec) => {
    const row: number[] = new Array(terms.length).fill(0);
    for (let i = 0; i < vec.vector.indices.length; i++) {
      row[vec.vector.indices[i]] = vec.vector.values[i];
    }
    return row;
  });

  return {
    matrix,
    terms,
    documentIds: documents.map((d) => d.id),
  };
}

export function generateDocumentVector(
  documentId: string,
  content: string,
  vectorizer: TfIdfVectorizer
): DocumentVector {
  return vectorizer.transformDocument(documentId, content);
}
