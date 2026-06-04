import { tokenize, extractKeywords } from '../knowledge-graph/tokenizer';
import {
  DocumentFeatures,
  TitleFeatures,
  StructureFeatures,
  KeywordFeatures,
  EntityFeatures,
  TextSegment,
  KeywordExtractionResult,
  DocumentType,
} from './types';
import {
  SECTION_HEADING_PATTERN,
  BOLD_TEXT_PATTERN,
  LIST_ITEM_PATTERN,
  TABLE_PATTERN,
  CODE_BLOCK_PATTERN,
  DATE_PATTERNS,
  EMAIL_PATTERN,
  URL_PATTERN,
  PEOPLE_NAME_PATTERNS,
  PROJECT_NAME_PATTERNS,
  DOCUMENT_TYPE_TITLE_PATTERNS,
  DOCUMENT_TYPE_KEYWORDS,
} from './patterns';

export class FeatureExtractor {
  private static instance: FeatureExtractor;

  private constructor() {}

  public static getInstance(): FeatureExtractor {
    if (!FeatureExtractor.instance) {
      FeatureExtractor.instance = new FeatureExtractor();
    }
    return FeatureExtractor.instance;
  }

  public extractFeatures(title: string, content: string): DocumentFeatures {
    const segments = this.extractSegments(title, content);
    const wordCount = this.calculateWordCount(content);

    return {
      title: this.extractTitleFeatures(title),
      structure: this.extractStructureFeatures(content),
      keywords: this.extractKeywordFeatures(title, content, segments),
      entities: this.extractEntityFeatures(title + ' ' + content),
      segments,
      wordCount,
      readTime: this.calculateReadTime(wordCount),
    };
  }

  public extractTitleFeatures(title: string): TitleFeatures {
    const trimmedTitle = title.trim();
    const hasTitle = trimmedTitle.length > 0;
    const titleLength = trimmedTitle.length;

    const tokenized = tokenize(title);
    const titleKeywords = tokenized.filteredTokens.filter(
      (t) => t.length >= 2 && !/^[0-9]+$/.test(t)
    );

    let containsDocumentType: DocumentType | null = null;
    for (const [docType, patterns] of Object.entries(DOCUMENT_TYPE_TITLE_PATTERNS)) {
      for (const pattern of patterns) {
        if (pattern.test(title)) {
          containsDocumentType = docType as DocumentType;
          break;
        }
      }
      if (containsDocumentType) break;
    }

    return {
      hasTitle,
      titleLength,
      titleKeywords,
      containsDocumentType,
    };
  }

  public extractStructureFeatures(content: string): StructureFeatures {
    const headingLevels: number[] = [];
    let headingCount = 0;

    const headingMatch = content.match(SECTION_HEADING_PATTERN);
    if (headingMatch) {
      headingCount = headingMatch.length;
      headingMatch.forEach((line) => {
        const level = line.match(/^#+/)?.[0].length || 0;
        if (level > 0) {
          headingLevels.push(level);
        }
      });
    }

    const listMatch = content.match(LIST_ITEM_PATTERN);
    const listCount = listMatch ? listMatch.length : 0;

    const paragraphs = content
      .split(/\n\s*\n/)
      .filter((p) => p.trim().length > 0 && !SECTION_HEADING_PATTERN.test(p));
    const paragraphCount = paragraphs.length;

    const totalParagraphLength = paragraphs.reduce((sum, p) => sum + p.trim().length, 0);
    const averageParagraphLength = paragraphCount > 0 ? totalParagraphLength / paragraphCount : 0;

    const hasTable = TABLE_PATTERN.test(content);
    const hasCodeBlock = CODE_BLOCK_PATTERN.test(content);

    return {
      headingCount,
      headingLevels,
      listCount,
      paragraphCount,
      averageParagraphLength,
      hasTable,
      hasCodeBlock,
    };
  }

  public extractKeywordFeatures(
    title: string,
    content: string,
    segments: TextSegment[]
  ): KeywordFeatures {
    const tokenized = tokenize(content);
    const termFrequency = tokenized.termFreq;

    const titleTokens = tokenize(title).filteredTokens;
    titleTokens.forEach((token) => {
      const normalized = token.toLowerCase();
      termFrequency.set(normalized, (termFrequency.get(normalized) || 0) + 2);
    });

    const topKeywords = this.calculateTopKeywords(title, content, segments, termFrequency);
    const uniqueKeywordCount = termFrequency.size;
    const totalWords = tokenized.filteredTokens.length + titleTokens.length;
    const keywordDensity = totalWords > 0 ? uniqueKeywordCount / totalWords : 0;

    return {
      termFrequency,
      topKeywords,
      keywordDensity,
      uniqueKeywordCount,
    };
  }

  public extractEntityFeatures(text: string): EntityFeatures {
    const dates: string[] = [];
    DATE_PATTERNS.forEach((pattern) => {
      const matches = text.match(pattern);
      if (matches) {
        dates.push(...matches);
      }
    });

    const people: string[] = [];
    PEOPLE_NAME_PATTERNS.forEach((pattern) => {
      const matches = text.match(pattern);
      if (matches) {
        people.push(...matches);
      }
    });

    const projects: string[] = [];
    PROJECT_NAME_PATTERNS.forEach((pattern) => {
      const matches = text.match(pattern);
      if (matches) {
        projects.push(...matches);
      }
    });

    const emails = text.match(EMAIL_PATTERN) || [];
    const urls = text.match(URL_PATTERN) || [];

    return {
      dates: [...new Set(dates)],
      people: [...new Set(people)],
      projects: [...new Set(projects)],
      emails: [...new Set(emails)],
      urls: [...new Set(urls)],
    };
  }

  public extractSegments(title: string, content: string): TextSegment[] {
    const segments: TextSegment[] = [];
    let position = 0;

    if (title.trim()) {
      segments.push({
        text: title,
        type: 'title',
        position: 0,
        weight: 3.0,
      });
      position += title.length;
    }

    const lines = content.split('\n');
    let currentPosition = 0;

    for (const line of lines) {
      const trimmedLine = line.trim();
      if (!trimmedLine) {
        currentPosition += line.length + 1;
        continue;
      }

      if (SECTION_HEADING_PATTERN.test(line)) {
        const headingText = trimmedLine.replace(/^#+\s+/, '');
        const level = line.match(/^#+/)?.[0].length || 1;
        segments.push({
          text: headingText,
          type: 'heading',
          position: currentPosition,
          weight: Math.max(1.5, 3.5 - level * 0.5),
        });
      } else if (BOLD_TEXT_PATTERN.test(line)) {
        const boldMatches = line.match(BOLD_TEXT_PATTERN);
        if (boldMatches) {
          boldMatches.forEach((match) => {
            const boldText = match.replace(/\*\*|__/g, '');
            segments.push({
              text: boldText,
              type: 'bold',
              position: currentPosition + line.indexOf(match),
              weight: 2.0,
            });
          });
        }
      } else if (LIST_ITEM_PATTERN.test(line)) {
        const listText = trimmedLine.replace(/^[-*+]\s+/, '');
        segments.push({
          text: listText,
          type: 'list',
          position: currentPosition,
          weight: 1.2,
        });
      } else if (trimmedLine.length > 0) {
        const positionWeight = position < 500 ? 1.3 : position < 1500 ? 1.1 : 1.0;
        segments.push({
          text: trimmedLine,
          type: 'paragraph',
          position: currentPosition,
          weight: positionWeight,
        });
      }

      currentPosition += line.length + 1;
      position += line.length + 1;
    }

    return segments;
  }

  private calculateTopKeywords(
    title: string,
    content: string,
    segments: TextSegment[],
    termFrequency: Map<string, number>
  ): KeywordExtractionResult[] {
    const fullText = title + ' ' + content;
    const jiebaKeywords = extractKeywords(fullText, 30);

    const segmentKeywordScores = new Map<string, number>();
    const keywordPositions = new Map<string, number[]>();

    segments.forEach((segment) => {
      const segmentTokens = tokenize(segment.text).filteredTokens;
      segmentTokens.forEach((token, idx) => {
        const normalized = token.toLowerCase();
        if (normalized.length < 2) return;

        const currentScore = segmentKeywordScores.get(normalized) || 0;
        segmentKeywordScores.set(normalized, currentScore + segment.weight);

        const positions = keywordPositions.get(normalized) || [];
        positions.push(segment.position + idx);
        keywordPositions.set(normalized, positions);
      });
    });

    const combinedScores = new Map<string, { score: number; frequency: number; source: 'tfidf' | 'textrank' | 'jieba' }>();

    termFrequency.forEach((freq, term) => {
      if (term.length < 2) return;

      const segmentScore = segmentKeywordScores.get(term) || 1;
      const jiebaScore = jiebaKeywords.includes(term) ? 2 : 1;
      const totalScore = freq * segmentScore * jiebaScore;

      combinedScores.set(term, {
        score: totalScore,
        frequency: freq,
        source: jiebaKeywords.includes(term) ? 'jieba' : 'tfidf',
      });
    });

    const sortedKeywords = Array.from(combinedScores.entries())
      .sort((a, b) => b[1].score - a[1].score)
      .slice(0, 20)
      .map(([keyword, data]) => ({
        keyword,
        score: data.score,
        frequency: data.frequency,
        positions: keywordPositions.get(keyword) || [],
        source: data.source,
      }));

    return sortedKeywords;
  }

  private calculateWordCount(content: string): number {
    const tokenized = tokenize(content);
    return tokenized.filteredTokens.length;
  }

  private calculateReadTime(wordCount: number): number {
    const wordsPerMinute = 300;
    return Math.ceil(wordCount / wordsPerMinute);
  }

  public findMatchingKeywordTypes(text: string): Map<DocumentType, string[]> {
    const matches = new Map<DocumentType, string[]>();

    for (const [docType, keywords] of Object.entries(DOCUMENT_TYPE_KEYWORDS)) {
      const matchedKeywords: string[] = [];
      for (const keyword of keywords) {
        if (text.toLowerCase().includes(keyword.toLowerCase())) {
          matchedKeywords.push(keyword);
        }
      }
      if (matchedKeywords.length > 0) {
        matches.set(docType as DocumentType, matchedKeywords);
      }
    }

    return matches;
  }
}

export function extractFeatures(title: string, content: string): DocumentFeatures {
  return FeatureExtractor.getInstance().extractFeatures(title, content);
}

export function extractTitleFeatures(title: string): TitleFeatures {
  return FeatureExtractor.getInstance().extractTitleFeatures(title);
}

export function extractStructureFeatures(content: string): StructureFeatures {
  return FeatureExtractor.getInstance().extractStructureFeatures(content);
}

export function extractKeywordFeatures(
  title: string,
  content: string,
  segments: TextSegment[]
): KeywordFeatures {
  return FeatureExtractor.getInstance().extractKeywordFeatures(title, content, segments);
}

export function extractEntityFeatures(text: string): EntityFeatures {
  return FeatureExtractor.getInstance().extractEntityFeatures(text);
}

export function extractSegments(title: string, content: string): TextSegment[] {
  return FeatureExtractor.getInstance().extractSegments(title, content);
}
