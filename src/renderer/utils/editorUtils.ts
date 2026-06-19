import matter from 'gray-matter';
import type { Descendant } from 'slate';
import type { LinkSuggestion, Note } from '../../shared/types';

const WIKILINK_REGEX = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;

export interface ParsedWikiLink {
  target: string;
  displayText: string;
  startIndex: number;
  endIndex: number;
}

export interface FrontmatterParseResult {
  frontmatter: Record<string, any>;
  content: string;
  hasFrontmatter: boolean;
}

export function parseFrontmatter(content: string): FrontmatterParseResult {
  try {
    if (!content.trim().startsWith('---')) {
      return {
        frontmatter: {},
        content,
        hasFrontmatter: false,
      };
    }
    
    const result = matter(content);
    return {
      frontmatter: result.data || {},
      content: result.content,
      hasFrontmatter: Object.keys(result.data || {}).length > 0,
    };
  } catch (err) {
    console.warn('Failed to parse frontmatter:', err);
    return {
      frontmatter: {},
      content,
      hasFrontmatter: false,
    };
  }
}

export function extractWikiLinks(content: string): ParsedWikiLink[] {
  const links: ParsedWikiLink[] = [];
  let match;
  
  WIKILINK_REGEX.lastIndex = 0;
  
  while ((match = WIKILINK_REGEX.exec(content)) !== null) {
    const target = match[1].trim();
    const displayText = (match[2] || match[1]).trim();
    
    links.push({
      target,
      displayText,
      startIndex: match.index,
      endIndex: match.index + match[0].length,
    });
  }
  
  return links;
}

export function extractTitleFromMarkdown(content: string): string {
  const trimmed = content.trim();
  
  const frontmatterMatch = trimmed.match(/^---[\s\S]*?---\s*(.*)/);
  const afterFrontmatter = frontmatterMatch ? frontmatterMatch[1] : trimmed;
  
  const headingMatch = afterFrontmatter.match(/^#\s+(.+)$/m);
  if (headingMatch) {
    return headingMatch[1].trim().slice(0, 100);
  }
  
  const firstLine = afterFrontmatter.split('\n').find(line => line.trim().length > 0);
  return firstLine ? firstLine.trim().slice(0, 100) : 'Untitled';
}

export function extractTags(content: string): string[] {
  const { frontmatter } = parseFrontmatter(content);
  let tags: string[] = [];
  
  if (frontmatter.tags) {
    tags = Array.isArray(frontmatter.tags) 
      ? frontmatter.tags.map(t => String(t).trim())
      : [String(frontmatter.tags).trim()];
  }
  
  const inlineTagRegex = /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g;
  let inlineMatch;
  while ((inlineMatch = inlineTagRegex.exec(content)) !== null) {
    if (!tags.includes(inlineMatch[1])) {
      tags.push(inlineMatch[1]);
    }
  }
  
  return tags.filter(t => t.length > 0);
}

export function getWikiLinkAutocomplete(
  searchText: string,
  availableNotes: Array<{ id: string; title: string; path: string; tags: string[] }>,
  limit: number = 10
): Array<{ id: string; title: string; path: string; tags: string[]; score: number }> {
  if (!searchText.trim()) {
    return availableNotes.slice(0, limit).map(n => ({ ...n, score: 1 }));
  }
  
  const searchLower = searchText.toLowerCase();
  const results: Array<{ id: string; title: string; path: string; tags: string[]; score: number }> = [];
  
  for (const note of availableNotes) {
    let score = 0;
    const titleLower = note.title.toLowerCase();
    const pathLower = note.path.toLowerCase();
    
    if (titleLower === searchLower) {
      score += 100;
    } else if (titleLower.startsWith(searchLower)) {
      score += 50;
    } else if (titleLower.includes(searchLower)) {
      score += 25;
    }
    
    if (pathLower.includes(searchLower)) {
      score += 10;
    }
    
    for (const tag of note.tags) {
      if (tag.toLowerCase().includes(searchLower)) {
        score += 15;
      }
    }
    
    if (score > 0) {
      results.push({ ...note, score });
    }
  }
  
  results.sort((a, b) => b.score - a.score);
  
  return results.slice(0, limit);
}

export function extractLinkContext(
  content: string,
  linkIndex: number,
  surroundingChars: number = 80
): string {
  const start = Math.max(0, linkIndex - surroundingChars);
  const end = Math.min(content.length, linkIndex + surroundingChars);
  
  let context = content.slice(start, end);
  context = context.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
  
  if (start > 0) context = '...' + context;
  if (end < content.length) context = context + '...';
  
  return context;
}

export function serializeToMarkdown(nodes: Descendant[]): string {
  const lines: string[] = [];
  
  for (const node of nodes) {
    const el = node as any;
    
    switch (el.type) {
      case 'heading':
        const prefix = '#'.repeat(el.level || 1);
        lines.push(`${prefix} ${serializeInline(el.children)}`);
        break;
      case 'paragraph':
        lines.push(serializeInline(el.children));
        break;
      case 'blockquote':
        const quoteText = serializeInline(el.children);
        lines.push(...quoteText.split('\n').map(l => `> ${l}`));
        break;
      case 'code-block':
        lines.push(`\`\`\`${el.language || ''}`);
        lines.push(el.children[0]?.text || '');
        lines.push('```');
        break;
      case 'bulleted-list':
        for (const item of el.children) {
          lines.push(`- ${serializeInline(item.children)}`);
        }
        break;
      case 'numbered-list':
        el.children.forEach((item: any, idx: number) => {
          lines.push(`${idx + 1}. ${serializeInline(item.children)}`);
        });
        break;
      default:
        if (el.text !== undefined) {
          lines.push(el.text);
        } else if (el.children) {
          lines.push(serializeInline(el.children));
        }
    }
  }
  
  return lines.join('\n');
}

function serializeInline(children: any[]): string {
  return children.map(child => {
    if (child.type === 'wiki-link') {
      if (child.displayText !== child.target) {
        return `[[${child.target}|${child.displayText}]]`;
      }
      return `[[${child.target}]]`;
    }
    
    let text = child.text || '';
    
    if (child.bold) text = `**${text}**`;
    if (child.italic) text = `*${text}*`;
    if (child.code) text = `\`${text}\``;
    if (child.underline) text = `<u>${text}</u>`;
    
    return text;
  }).join('');
}

export function levenshteinDistance(str1: string, str2: string): number {
  const s1 = str1.toLowerCase();
  const s2 = str2.toLowerCase();
  
  if (s1 === s2) return 0;
  if (s1.length === 0) return s2.length;
  if (s2.length === 0) return s1.length;
  
  const matrix: number[][] = [];
  
  for (let i = 0; i <= s2.length; i++) {
    matrix[i] = [i];
  }
  
  for (let j = 0; j <= s1.length; j++) {
    matrix[0][j] = j;
  }
  
  for (let i = 1; i <= s2.length; i++) {
    for (let j = 1; j <= s1.length; j++) {
      const cost = s1[j - 1] === s2[i - 1] ? 0 : 1;
      matrix[i][j] = Math.min(
        matrix[i - 1][j] + 1,
        matrix[i][j - 1] + 1,
        matrix[i - 1][j - 1] + cost
      );
    }
  }
  
  return matrix[s2.length][s1.length];
}

export function levenshteinSimilarity(str1: string, str2: string): number {
  const distance = levenshteinDistance(str1, str2);
  const maxLength = Math.max(str1.length, str2.length);
  if (maxLength === 0) return 1;
  return 1 - distance / maxLength;
}

export function jaccardSimilarity(str1: string, str2: string): number {
  const s1 = new Set(str1.toLowerCase().split(/\s+/).filter(w => w.length > 0));
  const s2 = new Set(str2.toLowerCase().split(/\s+/).filter(w => w.length > 0));
  
  if (s1.size === 0 && s2.size === 0) return 1;
  if (s1.size === 0 || s2.size === 0) return 0;
  
  const intersection = new Set([...s1].filter(x => s2.has(x)));
  const union = new Set([...s1, ...s2]);
  
  return intersection.size / union.size;
}

export function combinedSimilarity(str1: string, str2: string): number {
  const levenshtein = levenshteinSimilarity(str1, str2);
  const jaccard = jaccardSimilarity(str1, str2);
  return levenshtein * 0.6 + jaccard * 0.4;
}

export function findSimilarNotes(
  targetTitle: string,
  allNotes: Array<{ id: string; title: string; path: string; tags: string[] }>,
  threshold: number = 0.6,
  limit: number = 5
): LinkSuggestion[] {
  const results: LinkSuggestion[] = [];
  
  for (const note of allNotes) {
    const similarity = combinedSimilarity(targetTitle, note.title);
    
    if (similarity >= threshold) {
      results.push({
        noteId: note.id,
        title: note.title,
        path: note.path,
        similarity,
        similarityType: 'combined',
      });
    }
  }
  
  results.sort((a, b) => b.similarity - a.similarity);
  return results.slice(0, limit);
}

export function checkBrokenLink(
  linkTarget: string,
  allNotes: Note[],
  currentNotePath: string
): { isBroken: boolean; targetNote: Note | null } {
  const noteDir = currentNotePath.split('/').slice(0, -1).join('/');
  let targetPath = '';
  
  if (linkTarget.endsWith('.md')) {
    targetPath = noteDir ? `${noteDir}/${linkTarget}` : linkTarget;
  } else {
    targetPath = noteDir ? `${noteDir}/${linkTarget}.md` : `${linkTarget}.md`;
  }
  
  const targetNote = allNotes.find(n => 
    n.path === targetPath || 
    n.title.toLowerCase() === linkTarget.toLowerCase()
  ) || null;
  
  return {
    isBroken: !targetNote,
    targetNote,
  };
}

export interface BrokenLinkInfo {
  target: string;
  displayText: string;
  startIndex: number;
  endIndex: number;
  originalLink: string;
  context: string;
  suggestions: LinkSuggestion[];
}

export function scanNoteForBrokenLinks(
  content: string,
  allNotes: Array<{ id: string; title: string; path: string; tags: string[] }>,
  currentNotePath: string,
  threshold: number = 0.6
): BrokenLinkInfo[] {
  const links = extractWikiLinks(content);
  const brokenLinks: BrokenLinkInfo[] = [];
  
  for (const link of links) {
    const { isBroken } = checkBrokenLink(link.target, allNotes as any, currentNotePath);
    
    if (isBroken) {
      const suggestions = findSimilarNotes(link.target, allNotes, threshold);
      const context = extractLinkContext(content, link.startIndex, 80);
      
      brokenLinks.push({
        target: link.target,
        displayText: link.displayText,
        startIndex: link.startIndex,
        endIndex: link.endIndex,
        originalLink: link.displayText !== link.target 
          ? `[[${link.target}|${link.displayText}]]` 
          : `[[${link.target}]]`,
        context,
        suggestions,
      });
    }
  }
  
  return brokenLinks;
}

export function replaceLinkInContent(
  content: string,
  oldLink: BrokenLinkInfo,
  newTargetTitle: string
): { newContent: string; newLinkText: string } {
  const newDisplayText = oldLink.displayText !== oldLink.target 
    ? `[[${newTargetTitle}|${oldLink.displayText}]]` 
    : `[[${newTargetTitle}]]`;
  
  const newContent = 
    content.slice(0, oldLink.startIndex) + 
    newDisplayText + 
    content.slice(oldLink.endIndex);
  
  return {
    newContent,
    newLinkText: newDisplayText,
  };
}

export { WIKILINK_REGEX };
