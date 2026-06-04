import { randomUUID } from 'crypto';
import { OutlineItem } from '@shared/types/document';

export function parseTitle(content: string, fallback: string): string {
  const match = content.match(/^#\s+(.+)$/m);
  return match ? match[1].trim() : fallback;
}

export function parseTags(content: string): string[] {
  const tags = new Set<string>();

  const frontMatterMatch = content.match(/^---\n([\s\S]*?)\n---/);
  if (frontMatterMatch) {
    const frontMatter = frontMatterMatch[1];
    const tagsMatch = frontMatter.match(/tags:\s*\[([^\]]+)\]/);
    if (tagsMatch) {
      tagsMatch[1].split(',').forEach(t => {
        const tag = t.trim().replace(/^['"]|['"]$/g, '');
        if (tag) tags.add(tag);
      });
    }
  }

  const tagRegex = /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g;
  let match;
  while ((match = tagRegex.exec(content)) !== null) {
    tags.add(match[1]);
  }

  return Array.from(tags);
}

export function parseWikiLinks(content: string): Array<{ target: string; anchor: string; line: number }> {
  const wikiLinkRegex = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g;
  const lines = content.split('\n');
  const links: Array<{ target: string; anchor: string; line: number }> = [];

  for (let i = 0; i < lines.length; i++) {
    let match;
    wikiLinkRegex.lastIndex = 0;
    while ((match = wikiLinkRegex.exec(lines[i])) !== null) {
      links.push({
        target: match[1].trim(),
        anchor: match[2]?.trim() || match[1].trim(),
        line: i + 1,
      });
    }
  }

  return links;
}

export function resolveWikilinkTarget(
  target: string,
  documents: Array<{ id: string; title: string; filePath: string }>
): { id: string; actualTitle: string } | null {
  const lowerTarget = target.toLowerCase();
  
  const exactMatch = documents.find(d => d.title === target);
  if (exactMatch) {
    return { id: exactMatch.id, actualTitle: exactMatch.title };
  }

  const caseInsensitiveMatch = documents.find(d => d.title.toLowerCase() === lowerTarget);
  if (caseInsensitiveMatch) {
    return { id: caseInsensitiveMatch.id, actualTitle: caseInsensitiveMatch.title };
  }

  return null;
}

export function normalizeWikilinks(
  content: string,
  documents: Array<{ id: string; title: string; filePath: string }>
): string {
  const wikiLinkRegex = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g;
  return content.replace(wikiLinkRegex, (match, target, alias) => {
    const trimmedTarget = target.trim();
    const resolved = resolveWikilinkTarget(trimmedTarget, documents);
    if (resolved && resolved.actualTitle !== trimmedTarget) {
      if (alias) {
        return `[[${resolved.actualTitle}|${alias.trim()}]]`;
      }
      return `[[${resolved.actualTitle}]]`;
    }
    return match;
  });
}

export function parseOutline(content: string): OutlineItem[] {
  const headingRegex = /^(#{1,6})\s+(.+)$/gm;
  const outline: OutlineItem[] = [];
  let match;

  while ((match = headingRegex.exec(content)) !== null) {
    const level = match[1].length;
    const text = match[2].trim();
    const line = content.substring(0, match.index).split('\n').length;
    
    outline.push({
      level,
      text,
      line,
      id: `heading-${randomUUID().slice(0, 8)}`,
    });
  }

  return outline;
}

export function countWords(content: string): number {
  const textOnly = content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`]*`/g, ' ')
    .replace(/^\s*#+\s/gm, ' ')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\[\[([^\]]+)\]\]/g, '$1')
    .replace(/[*_~>#-]/g, ' ');

  const chineseChars = (textOnly.match(/[\u4e00-\u9fa5]/g) || []).length;
  const englishWords = textOnly
    .replace(/[\u4e00-\u9fa5]/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length > 0 && /[a-zA-Z0-9]/.test(w)).length;

  return chineseChars + englishWords;
}

export function generateDocId(): string {
  return `doc-${Date.now()}-${randomUUID().slice(0, 8)}`;
}

export function generateHash(content: string): string {
  const encoder = new TextEncoder();
  const data = encoder.encode(content);
  let hash = 0;
  for (let i = 0; i < data.length; i++) {
    hash = ((hash << 5) - hash + data[i]) | 0;
  }
  return Math.abs(hash).toString(16).padStart(16, '0');
}

export function sanitizeFilename(name: string): string {
  return name
    .replace(/[<>:"/\\|?*]/g, '-')
    .replace(/\s+/g, '-')
    .toLowerCase()
    .slice(0, 100);
}

export function highlightSearch(text: string, keywords: string[]): string {
  if (!keywords.length) return text;
  
  const regex = new RegExp(`(${keywords.join('|')})`, 'gi');
  return text.replace(regex, '<mark class="bg-yellow-300 dark:bg-yellow-600 text-inherit rounded px-0.5">$1</mark>');
}

export function getSearchSnippet(content: string, keywords: string[], length: number = 150): string {
  if (!keywords.length) return content.slice(0, length) + '...';
  
  const lowerContent = content.toLowerCase();
  let bestPos = -1;
  let maxMatches = 0;

  for (let i = 0; i <= content.length - length; i += 50) {
    const snippet = lowerContent.slice(i, i + length);
    let matches = 0;
    for (const kw of keywords) {
      if (snippet.includes(kw.toLowerCase())) matches++;
    }
    if (matches > maxMatches) {
      maxMatches = matches;
      bestPos = i;
    }
  }

  if (bestPos === -1) {
    bestPos = Math.max(0, content.indexOf(keywords[0]) - length / 2);
  }

  const start = Math.max(0, bestPos - 20);
  const snippet = content.slice(start, start + length).trim();
  
  return (start > 0 ? '...' : '') + snippet + (start + length < content.length ? '...' : '');
}
