import { tokenize, extractKeywords as jiebaExtractKeywords } from '../knowledge-graph/tokenizer';
import { TfIdfVectorizer } from '../knowledge-graph/tfidf';
import {
  KeywordExtractionResult,
  TagSuggestion,
  TextSegment,
  DocumentType,
  DocumentTypeColors,
} from './types';
import { extractSegments } from './FeatureExtractor';

export class KeywordExtractor {
  private static instance: KeywordExtractor;
  private tfidfVectorizer: TfIdfVectorizer | null = null;

  private constructor() {}

  public static getInstance(): KeywordExtractor {
    if (!KeywordExtractor.instance) {
      KeywordExtractor.instance = new KeywordExtractor();
    }
    return KeywordExtractor.instance;
  }

  public extractKeywords(
    title: string,
    content: string,
    options: {
      method?: 'tfidf' | 'textrank' | 'jieba' | 'hybrid';
      topN?: number;
      usePositionWeight?: boolean;
    } = {}
  ): KeywordExtractionResult[] {
    const { method = 'hybrid', topN = 20, usePositionWeight = true } = options;

    const segments = extractSegments(title, content);

    switch (method) {
      case 'tfidf':
        return this.extractKeywordsTFIDF(title, content, segments, topN, usePositionWeight);
      case 'textrank':
        return this.extractKeywordsTextRank(title, content, segments, topN, usePositionWeight);
      case 'jieba':
        return this.extractKeywordsJieba(title, content, segments, topN, usePositionWeight);
      case 'hybrid':
      default:
        return this.extractKeywordsHybrid(title, content, segments, topN, usePositionWeight);
    }
  }

  public extractKeywordsTFIDF(
    title: string,
    content: string,
    segments: TextSegment[],
    topN: number,
    usePositionWeight: boolean
  ): KeywordExtractionResult[] {
    const fullText = title + ' ' + content;
    const tokenized = tokenize(fullText);
    const termFrequency = tokenized.termFreq;

    if (!this.tfidfVectorizer) {
      this.tfidfVectorizer = new TfIdfVectorizer();
      this.tfidfVectorizer.fit([{ id: 'current', content: fullText }]);
    }

    const positionScores = usePositionWeight
      ? this.calculatePositionScores(segments)
      : new Map<string, number>();

    const tfidfScores = new Map<string, number>();
    const keywordPositions = new Map<string, number[]>();

    segments.forEach((segment, segIdx) => {
      const segmentTokens = tokenize(segment.text).filteredTokens;
      segmentTokens.forEach((token, tokIdx) => {
        const normalized = token.toLowerCase();
        if (normalized.length < 2) return;

        const tf = (termFrequency.get(normalized) || 0) / Math.max(tokenized.filteredTokens.length, 1);
        const idf = this.tfidfVectorizer!.getIdf(normalized) || 1;
        const tfidf = tf * idf;

        const positionWeight = positionScores.get(normalized) || segment.weight;
        const finalScore = tfidf * positionWeight;

        const currentScore = tfidfScores.get(normalized) || 0;
        tfidfScores.set(normalized, Math.max(currentScore, finalScore));

        const positions = keywordPositions.get(normalized) || [];
        positions.push(segment.position + tokIdx);
        keywordPositions.set(normalized, positions);
      });
    });

    return this.scoresToResults(tfidfScores, termFrequency, keywordPositions, topN, 'tfidf');
  }

  public extractKeywordsTextRank(
    title: string,
    content: string,
    segments: TextSegment[],
    topN: number,
    usePositionWeight: boolean
  ): KeywordExtractionResult[] {
    const fullText = title + ' ' + content;
    const tokenized = tokenize(fullText);
    const tokens = tokenized.filteredTokens;

    const windowSize = 5;
    const graph = new Map<string, Set<string>>();
    const termFrequency = tokenized.termFreq;
    const keywordPositions = new Map<string, number[]>();

    tokens.forEach((token, idx) => {
      const normalized = token.toLowerCase();
      if (normalized.length < 2) return;

      if (!graph.has(normalized)) {
        graph.set(normalized, new Set());
      }

      for (let j = Math.max(0, idx - windowSize); j < Math.min(tokens.length, idx + windowSize + 1); j++) {
        if (j === idx) continue;
        const neighbor = tokens[j].toLowerCase();
        if (neighbor.length >= 2) {
          graph.get(normalized)!.add(neighbor);
          if (!graph.has(neighbor)) {
            graph.set(neighbor, new Set());
          }
          graph.get(neighbor)!.add(normalized);
        }
      }

      const positions = keywordPositions.get(normalized) || [];
      positions.push(idx);
      keywordPositions.set(normalized, positions);
    });

    const scores = this.textRankAlgorithm(graph, 0.85, 20);

    if (usePositionWeight) {
      const positionScores = this.calculatePositionScores(segments);
      scores.forEach((score, term) => {
        const positionWeight = positionScores.get(term) || 1;
        scores.set(term, score * positionWeight);
      });
    }

    return this.scoresToResults(scores, termFrequency, keywordPositions, topN, 'textrank');
  }

  public extractKeywordsJieba(
    title: string,
    content: string,
    segments: TextSegment[],
    topN: number,
    usePositionWeight: boolean
  ): KeywordExtractionResult[] {
    const fullText = title + ' ' + content;
    const jiebaKeywords = jiebaExtractKeywords(fullText, 50);
    const tokenized = tokenize(fullText);
    const termFrequency = tokenized.termFreq;

    const scores = new Map<string, number>();
    const keywordPositions = new Map<string, number[]>();

    jiebaKeywords.forEach((keyword, idx) => {
      const normalized = keyword.toLowerCase();
      const baseScore = (jiebaKeywords.length - idx) / jiebaKeywords.length;
      scores.set(normalized, baseScore);

      const positions: number[] = [];
      let pos = fullText.toLowerCase().indexOf(normalized);
      while (pos !== -1) {
        positions.push(pos);
        pos = fullText.toLowerCase().indexOf(normalized, pos + 1);
      }
      keywordPositions.set(normalized, positions);
    });

    if (usePositionWeight) {
      const positionScores = this.calculatePositionScores(segments);
      scores.forEach((score, term) => {
        const positionWeight = positionScores.get(term) || 1;
        scores.set(term, score * positionWeight);
      });
    }

    return this.scoresToResults(scores, termFrequency, keywordPositions, topN, 'jieba');
  }

  public extractKeywordsHybrid(
    title: string,
    content: string,
    segments: TextSegment[],
    topN: number,
    usePositionWeight: boolean
  ): KeywordExtractionResult[] {
    const tfidfResults = this.extractKeywordsTFIDF(title, content, segments, 50, usePositionWeight);
    const textRankResults = this.extractKeywordsTextRank(title, content, segments, 50, usePositionWeight);
    const jiebaResults = this.extractKeywordsJieba(title, content, segments, 50, usePositionWeight);

    const combinedScores = new Map<string, { score: number; frequency: number; positions: number[]; sources: Set<string> }>();

    const maxTfidf = Math.max(...tfidfResults.map((r) => r.score), 1);
    const maxTextRank = Math.max(...textRankResults.map((r) => r.score), 1);
    const maxJieba = Math.max(...jiebaResults.map((r) => r.score), 1);

    tfidfResults.forEach((result) => {
      const normalized = result.keyword.toLowerCase();
      const normalizedScore = result.score / maxTfidf;
      const existing = combinedScores.get(normalized) || {
        score: 0,
        frequency: result.frequency,
        positions: result.positions,
        sources: new Set<string>(),
      };
      existing.score += normalizedScore * 0.4;
      existing.sources.add('tfidf');
      combinedScores.set(normalized, existing);
    });

    textRankResults.forEach((result) => {
      const normalized = result.keyword.toLowerCase();
      const normalizedScore = result.score / maxTextRank;
      const existing = combinedScores.get(normalized) || {
        score: 0,
        frequency: result.frequency,
        positions: result.positions,
        sources: new Set<string>(),
      };
      existing.score += normalizedScore * 0.3;
      existing.sources.add('textrank');
      combinedScores.set(normalized, existing);
    });

    jiebaResults.forEach((result) => {
      const normalized = result.keyword.toLowerCase();
      const normalizedScore = result.score / maxJieba;
      const existing = combinedScores.get(normalized) || {
        score: 0,
        frequency: result.frequency,
        positions: result.positions,
        sources: new Set<string>(),
      };
      existing.score += normalizedScore * 0.3;
      existing.sources.add('jieba');
      combinedScores.set(normalized, existing);
    });

    const results: KeywordExtractionResult[] = Array.from(combinedScores.entries())
      .sort((a, b) => b[1].score - a[1].score)
      .slice(0, topN)
      .map(([keyword, data]) => ({
        keyword,
        score: data.score,
        frequency: data.frequency,
        positions: data.positions,
        source: data.sources.has('jieba') ? 'jieba' : data.sources.has('tfidf') ? 'tfidf' : 'textrank',
      }));

    return results;
  }

  public generateTagSuggestions(
    title: string,
    content: string,
    docType?: DocumentType,
    options: {
      maxTags?: number;
      minConfidence?: number;
      includeClassificationTags?: boolean;
    } = {}
  ): TagSuggestion[] {
    const { maxTags = 10, minConfidence = 0.3, includeClassificationTags = true } = options;

    const keywords = this.extractKeywords(title, content, { topN: 30 });
    const suggestions: TagSuggestion[] = [];

    keywords.forEach((kw) => {
      if (kw.score >= minConfidence && suggestions.length < maxTags) {
        suggestions.push({
          name: kw.keyword,
          confidence: kw.score,
          source: 'keyword',
        });
      }
    });

    if (includeClassificationTags && docType && docType !== DocumentType.OTHER) {
      suggestions.unshift({
        name: this.getDocTypeTagName(docType),
        confidence: 0.95,
        source: 'classification',
        color: DocumentTypeColors[docType],
      });
    }

    const maxScore = Math.max(...suggestions.map((s) => s.confidence), 1);
    return suggestions
      .map((s) => ({
        ...s,
        confidence: Math.min(s.confidence / maxScore, 1),
      }))
      .slice(0, maxTags);
  }

  public getWordFrequency(text: string, topN?: number): Array<{ word: string; count: number }> {
    const tokenized = tokenize(text);
    const sorted = Array.from(tokenized.termFreq.entries()).sort((a, b) => b[1] - a[1]);

    return topN
      ? sorted.slice(0, topN).map(([word, count]) => ({ word, count }))
      : sorted.map(([word, count]) => ({ word, count }));
  }

  private textRankAlgorithm(
    graph: Map<string, Set<string>>,
    dampingFactor: number,
    iterations: number
  ): Map<string, number> {
    const scores = new Map<string, number>();
    const nodeCount = graph.size;

    if (nodeCount === 0) return scores;

    graph.forEach((_, node) => {
      scores.set(node, 1 / nodeCount);
    });

    for (let i = 0; i < iterations; i++) {
      const newScores = new Map<string, number>();

      graph.forEach((neighbors, node) => {
        let newScore = (1 - dampingFactor) / nodeCount;

        neighbors.forEach((neighbor) => {
          const neighborNeighbors = graph.get(neighbor);
          if (neighborNeighbors && neighborNeighbors.size > 0) {
            newScore += dampingFactor * ((scores.get(neighbor) || 0) / neighborNeighbors.size);
          }
        });

        newScores.set(node, newScore);
      });

      scores.clear();
      newScores.forEach((score, node) => {
        scores.set(node, score);
      });
    }

    return scores;
  }

  private calculatePositionScores(segments: TextSegment[]): Map<string, number> {
    const positionScores = new Map<string, number>();

    segments.forEach((segment) => {
      const tokens = tokenize(segment.text).filteredTokens;
      tokens.forEach((token) => {
        const normalized = token.toLowerCase();
        if (normalized.length < 2) return;

        const current = positionScores.get(normalized) || 0;
        positionScores.set(normalized, Math.max(current, segment.weight));
      });
    });

    return positionScores;
  }

  private scoresToResults(
    scores: Map<string, number>,
    termFrequency: Map<string, number>,
    keywordPositions: Map<string, number[]>,
    topN: number,
    source: 'tfidf' | 'textrank' | 'jieba'
  ): KeywordExtractionResult[] {
    return Array.from(scores.entries())
      .filter(([term]) => term.length >= 2)
      .sort((a, b) => b[1] - a[1])
      .slice(0, topN)
      .map(([keyword, score]) => ({
        keyword,
        score,
        frequency: termFrequency.get(keyword) || 0,
        positions: keywordPositions.get(keyword) || [],
        source,
      }));
  }

  private getDocTypeTagName(docType: DocumentType): string {
    const tagNames: Record<DocumentType, string> = {
      [DocumentType.TECH_PROPOSAL]: '技术方案',
      [DocumentType.MEETING_NOTES]: '会议纪要',
      [DocumentType.WEEKLY_REPORT]: '周报',
      [DocumentType.POST_MORTEM]: '项目复盘',
      [DocumentType.PRODUCT_REQUIREMENT]: '产品需求',
      [DocumentType.OTHER]: '其他',
    };
    return tagNames[docType];
  }

  public updateCorpus(documents: Array<{ id: string; content: string }>): void {
    this.tfidfVectorizer = new TfIdfVectorizer();
    this.tfidfVectorizer.fit(documents);
  }
}

export function extractKeywords(
  title: string,
  content: string,
  options?: {
    method?: 'tfidf' | 'textrank' | 'jieba' | 'hybrid';
    topN?: number;
    usePositionWeight?: boolean;
  }
): KeywordExtractionResult[] {
  return KeywordExtractor.getInstance().extractKeywords(title, content, options);
}

export function generateTagSuggestions(
  title: string,
  content: string,
  docType?: DocumentType,
  options?: {
    maxTags?: number;
    minConfidence?: number;
    includeClassificationTags?: boolean;
  }
): TagSuggestion[] {
  return KeywordExtractor.getInstance().generateTagSuggestions(title, content, docType, options);
}

export function getWordFrequency(text: string, topN?: number): Array<{ word: string; count: number }> {
  return KeywordExtractor.getInstance().getWordFrequency(text, topN);
}
