import nodejieba from 'nodejieba';
import { eng } from 'stopword';
import type { TokenizedResult } from './types';

export class ChineseTokenizer {
  private static instance: ChineseTokenizer;
  private isInitialized = false;
  private stopwords: Set<string>;

  private constructor() {
    this.stopwords = new Set([
      ...eng,
      '的', '了', '和', '是', '就', '都', '而', '及', '与',
      '在', '有', '这', '那', '也', '要', '会', '能', '可以',
      '我', '你', '他', '她', '它', '我们', '你们', '他们',
      '这个', '那个', '什么', '怎么', '为什么', '如何',
      'a', 'an', 'the', 'and', 'or', 'but', 'is', 'are',
      'was', 'were', 'be', 'been', 'being',
      'to', 'of', 'in', 'on', 'at', 'by', 'for',
      'with', 'about', 'against', 'between', 'into',
      'through', 'during', 'before', 'after', 'above', 'below',
      'from', 'up', 'down', 'out', 'off', 'over', 'under',
      'again', 'further', 'then', 'once',
      '这里', '那里', '现在', '以后', '以前',
      '时候', '时间', '地方', '方式', '方法',
      '一些', '很多', '许多', '大量', '部分',
    ]);
  }

  public static getInstance(): ChineseTokenizer {
    if (!ChineseTokenizer.instance) {
      ChineseTokenizer.instance = new ChineseTokenizer();
    }
    return ChineseTokenizer.instance;
  }

  public initialize(dictPath?: string): void {
    if (this.isInitialized) return;
    
    if (dictPath) {
      nodejieba.load({
        dict: dictPath,
      });
    } else {
      nodejieba.load();
    }
    this.isInitialized = true;
  }

  public tokenize(text: string, withStopwords = false): TokenizedResult {
    this.ensureInitialized();

    const tokens = this.cut(text);
    const filteredTokens = withStopwords ? tokens : this.filterStopwords(tokens);
    const termFreq = this.calculateTermFrequency(filteredTokens);
    const keywords = this.extractKeywords(text, 20);

    return {
      tokens,
      filteredTokens,
      termFreq,
      keywords,
    };
  }

  public cut(text: string, mode: 'cut' | 'cutHMM' | 'cutAll' | 'cutForSearch' = 'cut'): string[] {
    this.ensureInitialized();

    const cleanedText = this.preprocess(text);
    
    switch (mode) {
      case 'cutHMM':
        return nodejieba.cutHMM(cleanedText);
      case 'cutAll':
        return nodejieba.cutAll(cleanedText);
      case 'cutForSearch':
        return nodejieba.cutForSearch(cleanedText);
      default:
        return nodejieba.cut(cleanedText);
    }
  }

  public extractKeywords(text: string, topN = 10): string[] {
    this.ensureInitialized();

    const cleanedText = this.preprocess(text);
    const result = nodejieba.extract(cleanedText, topN);
    return result.map((item) => item.word);
  }

  public filterStopwords(tokens: string[]): string[] {
    return tokens.filter((token) => {
      const trimmed = token.trim().toLowerCase();
      return (
        trimmed.length > 0 &&
        !this.stopwords.has(trimmed) &&
        this.isMeaningfulToken(trimmed)
      );
    });
  }

  public calculateTermFrequency(tokens: string[]): Map<string, number> {
    const termFreq = new Map<string, number>();
    
    for (const token of tokens) {
      const normalized = token.toLowerCase();
      termFreq.set(normalized, (termFreq.get(normalized) || 0) + 1);
    }
    
    return termFreq;
  }

  public getWordFrequency(text: string, topN?: number): Array<{ word: string; count: number }> {
    const tokens = this.tokenize(text);
    const sorted = Array.from(tokens.termFreq.entries())
      .sort((a, b) => b[1] - a[1]);
    
    return topN 
      ? sorted.slice(0, topN).map(([word, count]) => ({ word, count }))
      : sorted.map(([word, count]) => ({ word, count }));
  }

  public addCustomStopwords(words: string[]): void {
    words.forEach((word) => this.stopwords.add(word.toLowerCase()));
  }

  public removeCustomStopwords(words: string[]): void {
    words.forEach((word) => this.stopwords.delete(word.toLowerCase()));
  }

  private preprocess(text: string): string {
    return text
      .replace(/[\r\n\t]+/g, ' ')
      .replace(/\s+/g, ' ')
      .replace(/[^\u4e00-\u9fa5a-zA-Z0-9\s]/g, ' ')
      .trim();
  }

  private isMeaningfulToken(token: string): boolean {
    if (token.length < 2) return false;
    
    if (/^[0-9]+$/.test(token)) return token.length >= 4;
    
    if (/^[a-zA-Z]+$/.test(token)) return token.length >= 3;
    
    return true;
  }

  private ensureInitialized(): void {
    if (!this.isInitialized) {
      this.initialize();
    }
  }
}

export function tokenize(text: string): TokenizedResult {
  return ChineseTokenizer.getInstance().tokenize(text);
}

export function extractKeywords(text: string, topN = 10): string[] {
  return ChineseTokenizer.getInstance().extractKeywords(text, topN);
}

export function calculateTermFrequency(tokens: string[]): Map<string, number> {
  return ChineseTokenizer.getInstance().calculateTermFrequency(tokens);
}

export function removeStopwordsFromTokens(tokens: string[]): string[] {
  return ChineseTokenizer.getInstance().filterStopwords(tokens);
}
