export interface Document {
  id: string;
  title: string;
  content?: string;
  tags: string[];
  filename: string;
  filePath: string;
  path?: string;
  wordCount: number;
  hash: string;
  createdAt: Date;
  updatedAt: Date;
  backlinks: Backlink[];
  outline: OutlineItem[];
}

export interface DocumentCreateInput {
  title: string;
  content: string;
  tags?: string[];
  filePath?: string;
  filename?: string;
}

export interface DocumentUpdateInput {
  id: string;
  title?: string;
  content?: string;
  tags?: string[];
}

export interface Tag {
  id: string;
  name: string;
  documentCount: number;
}

export interface Backlink {
  id: string;
  fromDocId: string;
  toDocId: string;
  fromTitle: string;
  anchorText: string;
  context: string;
  lineNumber: number;
}

export interface SearchResult {
  id: string;
  title: string;
  content: string;
  tags: string[];
  updatedAt: Date;
  highlights: string[];
  score: number;
  filePath: string;
  wordCount: number;
}

export type EditorMode = 'split' | 'source' | 'preview';

export interface OutlineItem {
  level: number;
  text: string;
  line: number;
  id: string;
}
