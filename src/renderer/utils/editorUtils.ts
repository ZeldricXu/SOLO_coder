import matter from 'gray-matter';
import type { Descendant } from 'slate';

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

export { WIKILINK_REGEX };
