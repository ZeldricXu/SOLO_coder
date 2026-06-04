import {
  DocumentType,
  ClassificationResult,
  ClassificationExample,
  DocumentFeatures,
} from './types';
import { CLASSIFICATION_PATTERNS, DOCUMENT_TYPE_KEYWORDS, DOCUMENT_TYPE_REGEX, DOCUMENT_TYPE_TITLE_PATTERNS, REQUIRED_SECTIONS } from './patterns';
import { extractFeatures } from './FeatureExtractor';

export class DocumentClassifier {
  private static instance: DocumentClassifier;
  private learnedPatterns: Map<DocumentType, { keywords: Set<string>; regexPatterns: RegExp[] }>;
  private trainedExamples: ClassificationExample[] = [];

  private constructor() {
    this.learnedPatterns = new Map();
    Object.values(DocumentType).forEach((type) => {
      this.learnedPatterns.set(type, {
        keywords: new Set(),
        regexPatterns: [],
      });
    });
  }

  public static getInstance(): DocumentClassifier {
    if (!DocumentClassifier.instance) {
      DocumentClassifier.instance = new DocumentClassifier();
    }
    return DocumentClassifier.instance;
  }

  public classifyDocument(
    title: string,
    content: string,
    features?: DocumentFeatures
  ): ClassificationResult {
    const docFeatures = features || extractFeatures(title, content);
    const fullText = title + '\n' + content;
    const allScores = this.calculateAllScores(title, content, fullText, docFeatures);

    let bestType = DocumentType.OTHER;
    let bestScore = 0;

    Object.entries(allScores).forEach(([type, score]) => {
      if (score > bestScore) {
        bestScore = score;
        bestType = type as DocumentType;
      }
    });

    if (bestScore < 0.15) {
      bestType = DocumentType.OTHER;
    }

    const confidence = this.normalizeConfidence(bestScore);
    const reasons = this.generateReasons(bestType, title, content, fullText, docFeatures, allScores);

    const matchedKeywords = this.getMatchedKeywords(bestType, fullText);
    const matchedPatterns = this.getMatchedPatterns(bestType, title, content);

    return {
      type: bestType,
      confidence,
      reasons,
      matchedKeywords,
      matchedPatterns,
      allScores,
    };
  }

  public getClassificationConfidence(
    title: string,
    content: string,
    type: DocumentType
  ): number {
    const result = this.classifyDocument(title, content);
    return result.allScores[type] || 0;
  }

  public trainFromExamples(examples: ClassificationExample[]): void {
    this.trainedExamples.push(...examples);

    examples.forEach((example) => {
      const type = example.type;
      const patterns = this.learnedPatterns.get(type)!;

      const fullText = example.title + ' ' + example.content;
      const words = fullText.toLowerCase().split(/[\s,。，。]/);

      const wordFreq = new Map<string, number>();
      words.forEach((word) => {
        if (word.length >= 2) {
          wordFreq.set(word, (wordFreq.get(word) || 0) + 1);
        }
      });

      const sortedWords = Array.from(wordFreq.entries())
        .filter(([word]) => !this.isCommonWord(word))
        .sort((a, b) => b[1] - a[1])
        .slice(0, 20);

      sortedWords.forEach(([word]) => {
        if (!DOCUMENT_TYPE_KEYWORDS[type].some(
          (kw) => kw.toLowerCase() === word.toLowerCase()
        )) {
          patterns.keywords.add(word);
        }
      });
    });
  }

  private calculateAllScores(
    title: string,
    content: string,
    fullText: string,
    features: DocumentFeatures
  ): Record<DocumentType, number> {
    const scores: Record<DocumentType, number> = {
      [DocumentType.TECH_PROPOSAL]: 0,
      [DocumentType.MEETING_NOTES]: 0,
      [DocumentType.WEEKLY_REPORT]: 0,
      [DocumentType.POST_MORTEM]: 0,
      [DocumentType.PRODUCT_REQUIREMENT]: 0,
      [DocumentType.OTHER]: 0,
    };

    CLASSIFICATION_PATTERNS.forEach((pattern) => {
      const type = pattern.type;
      let score = 0;

      score += this.calculateTitleScore(title, type);
      score += this.calculateKeywordScore(fullText, type);
      score += this.calculateRegexScore(title, content, type);
      score += this.calculateStructureScore(features, type);
      score += this.calculateLearnedScore(fullText, type);

      scores[type] = score * pattern.weight;
    });

    const maxScore = Math.max(...Object.values(scores), 1);
    Object.keys(scores).forEach((key) => {
      scores[key as DocumentType] = scores[key as DocumentType] / maxScore;
    });

    return scores;
  }

  private calculateTitleScore(title: string, type: DocumentType): number {
    const patterns = DOCUMENT_TYPE_TITLE_PATTERNS[type];
    if (!patterns || patterns.length === 0) return 0;

    let matches = 0;
    patterns.forEach((pattern) => {
      if (pattern.test(title)) {
        matches++;
      }
    });

    return matches > 0 ? 0.3 * (1 + matches / patterns.length) : 0;
  }

  private calculateKeywordScore(fullText: string, type: DocumentType): number {
    const keywords = DOCUMENT_TYPE_KEYWORDS[type];
    if (!keywords || keywords.length === 0) return 0;

    const lowerText = fullText.toLowerCase();
    let matches = 0;
    const matchedWords: string[] = [];

    keywords.forEach((keyword) => {
      if (lowerText.includes(keyword.toLowerCase())) {
        matches++;
        matchedWords.push(keyword);
      }
    });

    const baseScore = matches / Math.max(keywords.length, 1);
    const bonus = matchedWords.length > 5 ? 0.1 : matchedWords.length > 3 ? 0.05 : 0;

    return 0.4 * baseScore + bonus;
  }

  private calculateRegexScore(title: string, content: string, type: DocumentType): number {
    const patterns = DOCUMENT_TYPE_REGEX[type];
    if (!patterns || patterns.length === 0) return 0;

    const fullText = title + '\n' + content;
    let matches = 0;

    patterns.forEach((pattern) => {
      if (pattern.test(fullText)) {
        matches++;
      }
    });

    return matches > 0 ? 0.2 * (1 + matches / patterns.length) : 0;
  }

  private calculateStructureScore(features: DocumentFeatures, type: DocumentType): number {
    let score = 0;
    const pattern = CLASSIFICATION_PATTERNS.find((p) => p.type === type);

    if (!pattern) return 0;

    if (pattern.structurePatterns.minHeadings) {
      if (features.structure.headingCount >= pattern.structurePatterns.minHeadings) {
        score += 0.05;
      }
    }

    if (pattern.structurePatterns.requiredSections) {
      const headings = features.segments
        .filter((s) => s.type === 'heading')
        .map((s) => s.text.toLowerCase());

      let sectionMatches = 0;
      pattern.structurePatterns.requiredSections.forEach((section) => {
        if (headings.some((h) => h.includes(section.toLowerCase()))) {
          sectionMatches++;
        }
      });

      const sectionRatio = sectionMatches / pattern.structurePatterns.requiredSections.length;
      score += 0.05 * sectionRatio;
    }

    if (type === DocumentType.TECH_PROPOSAL) {
      if (features.structure.hasCodeBlock) score += 0.03;
      if (features.structure.hasTable) score += 0.02;
    }

    if (type === DocumentType.MEETING_NOTES) {
      if (features.structure.listCount >= 3) score += 0.03;
    }

    if (type === DocumentType.WEEKLY_REPORT) {
      if (features.structure.headingCount >= 2) score += 0.03;
    }

    return score;
  }

  private calculateLearnedScore(fullText: string, type: DocumentType): number {
    const patterns = this.learnedPatterns.get(type);
    if (!patterns || patterns.keywords.size === 0) return 0;

    const lowerText = fullText.toLowerCase();
    let matches = 0;

    patterns.keywords.forEach((keyword) => {
      if (lowerText.includes(keyword.toLowerCase())) {
        matches++;
      }
    });

    return matches > 0 ? 0.1 * (matches / Math.min(patterns.keywords.size, 10)) : 0;
  }

  private normalizeConfidence(score: number): number {
    return Math.min(Math.max(score, 0), 1);
  }

  private generateReasons(
    type: DocumentType,
    title: string,
    content: string,
    fullText: string,
    features: DocumentFeatures,
    allScores: Record<DocumentType, number>
  ): string[] {
    const reasons: string[] = [];

    if (type === DocumentType.OTHER) {
      reasons.push('未检测到明显的文档类型特征');
      const otherReasons: string[] = [];
      Object.entries(allScores).forEach(([docType, score]) => {
        if (docType !== DocumentType.OTHER && score > 0.1) {
          otherReasons.push(`${this.getTypeLabel(docType as DocumentType)}: ${(score * 100).toFixed(0)}%`);
        }
      });
      if (otherReasons.length > 0) {
        reasons.push('可能的类型包括：' + otherReasons.join('、'));
      }
      return reasons;
    }

    const titlePatterns = DOCUMENT_TYPE_TITLE_PATTERNS[type];
    if (titlePatterns.some((p) => p.test(title))) {
      reasons.push('标题包含典型的文档类型特征');
    }

    const keywords = DOCUMENT_TYPE_KEYWORDS[type];
    const matchedKeywords = keywords.filter((kw) =>
      fullText.toLowerCase().includes(kw.toLowerCase()));
    if (matchedKeywords.length > 0) {
      const topKeywords = matchedKeywords.slice(0, 5);
      reasons.push(`检测到关键词：${topKeywords.join('、')}`);
    }

    const structure = features.structure;
    const requiredSections = REQUIRED_SECTIONS[type];
    const headings = features.segments
      .filter((s) => s.type === 'heading')
      .map((s) => s.text);

    const matchedSections = requiredSections.filter((section) =>
      headings.some((h) => h.includes(section)));
    if (matchedSections.length > 0) {
      reasons.push(`包含${matchedSections.length}/${requiredSections.length} 个标准章节：${matchedSections.join('、')}`);
    }

    if (structure.hasCodeBlock && type === DocumentType.TECH_PROPOSAL) {
      reasons.push('包含代码块，符合技术方案特征');
    }

    if (structure.listCount >= 5 && type === DocumentType.MEETING_NOTES) {
      reasons.push('列表项较多，符合会议纪要特征');
    }

    if (features.entities.dates.length > 0 && type === DocumentType.MEETING_NOTES) {
      reasons.push('包含日期信息');
    }

    if (features.entities.people.length > 0 && type === DocumentType.MEETING_NOTES) {
      reasons.push('包含人员信息');
    }

    const otherScores = Object.entries(allScores)
      .filter(([t]) => t !== type && t !== DocumentType.OTHER)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 2);

    otherScores.forEach(([otherType, score]) => {
      if (score > 0.3) {
        reasons.push(`与${this.getTypeLabel(otherType as DocumentType)}相似度：${(score * 100).toFixed(0)}%`);
      }
    });

    return reasons;
  }

  private getMatchedKeywords(type: DocumentType, fullText: string): string[] {
    const keywords = DOCUMENT_TYPE_KEYWORDS[type];
    const lowerText = fullText.toLowerCase();

    return keywords.filter((kw) => lowerText.includes(kw.toLowerCase())).slice(0, 10);
  }

  private getMatchedPatterns(type: DocumentType, title: string, content: string): string[] {
    const patterns = DOCUMENT_TYPE_REGEX[type];
    const fullText = title + '\n' + content;
    const matched: string[] = [];

    patterns.forEach((pattern) => {
      const match = fullText.match(pattern);
      if (match) {
        matched.push(match[0]);
      }
    });

    return matched.slice(0, 5);
  }

  private getTypeLabel(type: DocumentType): string {
    const labels: Record<DocumentType, string> = {
      [DocumentType.TECH_PROPOSAL]: '技术方案',
      [DocumentType.MEETING_NOTES]: '会议纪要',
      [DocumentType.WEEKLY_REPORT]: '周报',
      [DocumentType.POST_MORTEM]: '项目复盘',
      [DocumentType.PRODUCT_REQUIREMENT]: '产品需求',
      [DocumentType.OTHER]: '其他',
    };
    return labels[type];
  }

  private isCommonWord(word: string): boolean {
    const commonWords = new Set([
      '的', '了', '和', '是', '就', '都', '而', '及', '与',
      '在', '有', '这', '那', '也', '要', '会', '能', '可以',
      '我们', '你们', '他们', '这个', '那个', '什么', '怎么', '如何',
      '一个', '一些', '很多', '进行', '工作', '项目', '需要',
      'the', 'a', 'an', 'and', 'or', 'but', 'is', 'are',
      'to', 'of', 'in', 'on', 'for', 'with',
    ]);
    return commonWords.has(word.toLowerCase());
  }

  public getTrainedExamples(): ClassificationExample[] {
    return [...this.trainedExamples];
  }

  public clearTrainedData(): void {
    this.trainedExamples = [];
    this.learnedPatterns.clear();
    Object.values(DocumentType).forEach((type) => {
      this.learnedPatterns.set(type, {
        keywords: new Set(),
        regexPatterns: [],
      });
    });
  }
}

export function classifyDocument(
  title: string,
  content: string,
  features?: DocumentFeatures
): ClassificationResult {
  return DocumentClassifier.getInstance().classifyDocument(title, content, features);
}

export function getClassificationConfidence(
  title: string,
  content: string,
  type: DocumentType
): number {
  return DocumentClassifier.getInstance().getClassificationConfidence(title, content, type);
}

export function trainFromExamples(examples: ClassificationExample[]): void {
  return DocumentClassifier.getInstance().trainFromExamples(examples);
}
