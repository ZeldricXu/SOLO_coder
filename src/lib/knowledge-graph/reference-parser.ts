import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import type { ReferenceInfo } from './types';

interface LinkNode {
  type: string;
  url?: string;
  value?: string;
  children?: Array<{ value?: string }>;
  position?: unknown;
}

export class ReferenceParser {
  private markdownParser: ReturnType<typeof unified>;

  constructor() {
    this.markdownParser = unified().use(remarkParse).use(remarkGfm);
  }

  public parseReferences(
    sourceDocumentId: string,
    markdownContent: string,
    documentResolver?: (path: string) => string | null
  ): ReferenceInfo[] {
    const references: ReferenceInfo[] = [];
    
    const markdownLinks = this.parseMarkdownLinks(sourceDocumentId, markdownContent, documentResolver);
    references.push(...markdownLinks);

    const wikiLinks = this.parseWikiLinks(sourceDocumentId, markdownContent, documentResolver);
    references.push(...wikiLinks);

    const tagLinks = this.parseTagLinks(sourceDocumentId, markdownContent);
    references.push(...tagLinks);

    return this.deduplicateReferences(references);
  }

  private parseMarkdownLinks(
    sourceDocumentId: string,
    content: string,
    documentResolver?: (path: string) => string | null
  ): ReferenceInfo[] {
    const references: ReferenceInfo[] = [];
    const tree = this.markdownParser.parse(content);

    visit(tree, 'link', (node: LinkNode) => {
      const url = node.url || '';
      const linkText = this.getLinkText(node);

      if (this.isInternalLink(url)) {
        const isWikiLink = this.isWikiLinkFormat(url);
        const targetPath = this.resolvePath(sourceDocumentId, url);
        const targetDocumentId = documentResolver
          ? documentResolver(targetPath)
          : this.pathToDocumentId(targetPath);

        if (targetDocumentId) {
          references.push({
            sourceDocumentId,
            targetDocumentId,
            linkText,
            isInternal: true,
            isWikiLink,
            rawPath: url,
          });
        }
      }
    });

    return references;
  }

  private parseWikiLinks(
    sourceDocumentId: string,
    content: string,
    documentResolver?: (path: string) => string | null
  ): ReferenceInfo[] {
    const references: ReferenceInfo[] = [];
    
    const wikiLinkRegex = /\[\[([^\[\]\|]+)(?:\|([^\[\]]+))?\]\]/g;
    let match: RegExpExecArray | null;

    while ((match = wikiLinkRegex.exec(content)) !== null) {
      const target = match[1].trim();
      const linkText = match[2]?.trim() || target;

      const isInternal = !this.isExternalUrl(target);
      const targetDocumentId = isInternal && documentResolver
        ? documentResolver(target)
        : this.pathToDocumentId(target);

      if (targetDocumentId) {
        references.push({
          sourceDocumentId,
          targetDocumentId,
          linkText,
          isInternal: true,
          isWikiLink: true,
          rawPath: target,
        });
      }
    }

    return references;
  }

  private parseTagLinks(
    sourceDocumentId: string,
    content: string
  ): ReferenceInfo[] {
    const references: ReferenceInfo[] = [];
    
    const tagRegex = /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g;
    let match: RegExpExecArray | null;

    while ((match = tagRegex.exec(content)) !== null) {
      const tag = match[1];
      
      references.push({
        sourceDocumentId,
        targetDocumentId: `tag:${tag}`,
        linkText: `#${tag}`,
        isInternal: true,
        isWikiLink: false,
        rawPath: `#${tag}`,
      });
    }

    return references;
  }

  public parseAllLinks(content: string): Array<{
    url: string;
    text: string;
    isInternal: boolean;
    isWikiLink: boolean;
  }> {
    const links: Array<{
      url: string;
      text: string;
      isInternal: boolean;
      isWikiLink: boolean;
    }> = [];

    const tree = this.markdownParser.parse(content);

    visit(tree, 'link', (node: LinkNode) => {
      const url = node.url || '';
      const text = this.getLinkText(node);
      
      links.push({
        url,
        text,
        isInternal: this.isInternalLink(url),
        isWikiLink: false,
      });
    });

    const wikiLinkRegex = /\[\[([^\[\]\|]+)(?:\|([^\[\]]+))?\]\]/g;
    let match: RegExpExecArray | null;

    while ((match = wikiLinkRegex.exec(content)) !== null) {
      const target = match[1].trim();
      const text = match[2]?.trim() || target;
      
      links.push({
        url: target,
        text,
        isInternal: !this.isExternalUrl(target),
        isWikiLink: true,
      });
    }

    return links;
  }

  public extractBacklinks(
    targetDocumentId: string,
    allDocuments: Array<{ id: string; content: string }>
  ): ReferenceInfo[] {
    const backlinks: ReferenceInfo[] = [];

    for (const doc of allDocuments) {
      if (doc.id === targetDocumentId) continue;

      const references = this.parseReferences(doc.id, doc.content, (path) =>
        this.resolveDocumentId(path, allDocuments)
      );

      const matchingRefs = references.filter(
        (ref) => ref.targetDocumentId === targetDocumentId
      );
      
      backlinks.push(...matchingRefs);
    }

    return backlinks;
  }

  public buildReferenceGraph(
    documents: Array<{ id: string; content: string }>
  ): Map<string, ReferenceInfo[]> {
    const graph = new Map<string, ReferenceInfo[]>();

    for (const doc of documents) {
      const references = this.parseReferences(doc.id, doc.content, (path) =>
        this.resolveDocumentId(path, documents)
      );
      graph.set(doc.id, references);
    }

    return graph;
  }

  private getLinkText(node: LinkNode): string {
    if (node.children && node.children.length > 0) {
      return node.children.map((c) => c.value || '').join('') || node.value || '';
    }
    return node.value || '';
  }

  private isInternalLink(url: string): boolean {
    if (!url) return false;
    if (this.isExternalUrl(url)) return false;
    if (url.startsWith('mailto:')) return false;
    if (url.startsWith('tel:')) return false;
    return true;
  }

  private isExternalUrl(url: string): boolean {
    return /^https?:\/\//i.test(url) || /^\/\//.test(url);
  }

  private isWikiLinkFormat(url: string): boolean {
    return url.includes('[[') && url.includes(']]');
  }

  private resolvePath(sourceId: string, targetPath: string): string {
    if (targetPath.startsWith('/')) {
      return targetPath.slice(1);
    }

    if (targetPath.startsWith('./') || targetPath.startsWith('../')) {
      const sourceParts = sourceId.split('/');
      sourceParts.pop();
      
      const targetParts = targetPath.split('/');
      
      for (const part of targetParts) {
        if (part === '..') {
          sourceParts.pop();
        } else if (part !== '.' && part !== '') {
          sourceParts.push(part);
        }
      }
      
      return sourceParts.join('/');
    }

    return targetPath;
  }

  private pathToDocumentId(path: string): string {
    const cleaned = path
      .replace(/\.md$/i, '')
      .replace(/\.markdown$/i, '')
      .replace(/^\/+/, '')
      .replace(/\/+$/, '');
    
    return cleaned || path;
  }

  private resolveDocumentId(
    path: string,
    documents: Array<{ id: string; content: string }>
  ): string | null {
    const normalizedPath = this.pathToDocumentId(path);
    
    const exactMatch = documents.find(
      (d) => d.id.toLowerCase() === normalizedPath.toLowerCase()
    );
    if (exactMatch) return exactMatch.id;

    const baseNameMatch = documents.find((d) => {
      const dBase = d.id.split('/').pop()?.toLowerCase();
      const pBase = normalizedPath.split('/').pop()?.toLowerCase();
      return dBase === pBase;
    });
    if (baseNameMatch) return baseNameMatch.id;

    return null;
  }

  private deduplicateReferences(references: ReferenceInfo[]): ReferenceInfo[] {
    const seen = new Set<string>();
    return references.filter((ref) => {
      const key = `${ref.sourceDocumentId}-${ref.targetDocumentId}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  public static extractHeadings(content: string): Array<{
    level: number;
    text: string;
    anchor: string;
  }> {
    const headings: Array<{ level: number; text: string; anchor: string }> = [];
    const headingRegex = /^(#{1,6})\s+(.+)$/gm;
    
    let match: RegExpExecArray | null;
    while ((match = headingRegex.exec(content)) !== null) {
      const level = match[1].length;
      const text = match[2].trim();
      const anchor = text
        .toLowerCase()
        .replace(/[^\w\u4e00-\u9fa5]+/g, '-')
        .replace(/^-|-$/g, '');
      
      headings.push({ level, text, anchor });
    }

    return headings;
  }
}

export function parseReferences(
  sourceDocumentId: string,
  markdownContent: string,
  documentResolver?: (path: string) => string | null
): ReferenceInfo[] {
  const parser = new ReferenceParser();
  return parser.parseReferences(sourceDocumentId, markdownContent, documentResolver);
}

export function extractLinks(
  content: string
): Array<{ url: string; text: string; isInternal: boolean; isWikiLink: boolean }> {
  const parser = new ReferenceParser();
  return parser.parseAllLinks(content);
}

export function findBacklinks(
  targetDocumentId: string,
  allDocuments: Array<{ id: string; content: string }>
): ReferenceInfo[] {
  const parser = new ReferenceParser();
  return parser.extractBacklinks(targetDocumentId, allDocuments);
}
