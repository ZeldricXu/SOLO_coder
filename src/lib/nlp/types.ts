export enum DocumentType {
  TECH_PROPOSAL = 'TECH_PROPOSAL',
  MEETING_NOTES = 'MEETING_NOTES',
  WEEKLY_REPORT = 'WEEKLY_REPORT',
  POST_MORTEM = 'POST_MORTEM',
  PRODUCT_REQUIREMENT = 'PRODUCT_REQUIREMENT',
  OTHER = 'OTHER',
}

export const DocumentTypeLabels: Record<DocumentType, string> = {
  [DocumentType.TECH_PROPOSAL]: '技术方案',
  [DocumentType.MEETING_NOTES]: '会议纪要',
  [DocumentType.WEEKLY_REPORT]: '周报',
  [DocumentType.POST_MORTEM]: '项目复盘',
  [DocumentType.PRODUCT_REQUIREMENT]: '产品需求',
  [DocumentType.OTHER]: '其他',
};

export const DocumentTypeColors: Record<DocumentType, string> = {
  [DocumentType.TECH_PROPOSAL]: '#3b82f6',
  [DocumentType.MEETING_NOTES]: '#10b981',
  [DocumentType.WEEKLY_REPORT]: '#f59e0b',
  [DocumentType.POST_MORTEM]: '#ef4444',
  [DocumentType.PRODUCT_REQUIREMENT]: '#8b5cf6',
  [DocumentType.OTHER]: '#6b7280',
};

export interface KeywordExtractionResult {
  keyword: string;
  score: number;
  frequency: number;
  positions: number[];
  source: 'tfidf' | 'textrank' | 'jieba';
}

export interface ClassificationResult {
  type: DocumentType;
  confidence: number;
  reasons: string[];
  matchedKeywords: string[];
  matchedPatterns: string[];
  allScores: Record<DocumentType, number>;
}

export interface TagSuggestion {
  name: string;
  confidence: number;
  source: 'keyword' | 'classification' | 'trending';
  color?: string;
}

export interface TextSegment {
  text: string;
  type: 'title' | 'heading' | 'bold' | 'paragraph' | 'list';
  position: number;
  weight: number;
}

export interface TitleFeatures {
  hasTitle: boolean;
  titleLength: number;
  titleKeywords: string[];
  containsDocumentType: DocumentType | null;
}

export interface StructureFeatures {
  headingCount: number;
  headingLevels: number[];
  listCount: number;
  paragraphCount: number;
  averageParagraphLength: number;
  hasTable: boolean;
  hasCodeBlock: boolean;
}

export interface KeywordFeatures {
  termFrequency: Map<string, number>;
  topKeywords: KeywordExtractionResult[];
  keywordDensity: number;
  uniqueKeywordCount: number;
}

export interface EntityFeatures {
  dates: string[];
  people: string[];
  projects: string[];
  emails: string[];
  urls: string[];
}

export interface DocumentFeatures {
  title: TitleFeatures;
  structure: StructureFeatures;
  keywords: KeywordFeatures;
  entities: EntityFeatures;
  segments: TextSegment[];
  wordCount: number;
  readTime: number;
}

export interface ClassificationPattern {
  type: DocumentType;
  keywords: string[];
  regexPatterns: RegExp[];
  titlePatterns: RegExp[];
  structurePatterns: {
    minHeadings?: number;
    requiredSections?: string[];
  };
  weight: number;
}

export interface ClassificationExample {
  title: string;
  content: string;
  type: DocumentType;
}

export interface TagUsageStats {
  tagId: string;
  tagName: string;
  color: string | null;
  usageCount: number;
  lastUsedAt: Date | null;
  trend: 'up' | 'down' | 'stable';
}

export interface TrendingTag {
  tagId: string;
  tagName: string;
  color: string | null;
  usageCount: number;
  growthRate: number;
}

export interface DocumentForAnalysis {
  id?: string;
  title: string;
  content: string;
  spaceId: string;
}
