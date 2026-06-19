import { Descendant } from 'slate';
import { withReact, ReactEditor } from 'slate-react';

export interface WikiLinkElement {
  type: 'wiki-link';
  target: string;
  displayText: string;
  children: { text: string }[];
}

export interface CodeBlockElement {
  type: 'code-block';
  language?: string;
  children: { text: string }[];
}

export interface HeadingElement {
  type: 'heading';
  level: number;
  children: { text: string }[];
}

export interface BlockquoteElement {
  type: 'blockquote';
  children: { text: string }[];
}

export interface ListElement {
  type: 'bulleted-list' | 'numbered-list';
  children: { text: string }[];
}

export interface ListItemElement {
  type: 'list-item';
  children: { text: string }[];
}

export interface ParagraphElement {
  type: 'paragraph';
  children: { text: string }[];
}

export interface ImageElement {
  type: 'image';
  src: string;
  alt: string;
  children: { text: string }[];
}

export type CustomElement =
  | WikiLinkElement
  | CodeBlockElement
  | HeadingElement
  | BlockquoteElement
  | ListElement
  | ListItemElement
  | ParagraphElement
  | ImageElement;

export const withWikiLinks = (editor: ReactEditor) => {
  const { isInline, isVoid } = editor;

  editor.isInline = (element: any) => {
    if (element.type === 'image') return true;
    return element.type === 'wiki-link' ? true : isInline(element);
  };

  editor.isVoid = (element: any) => {
    if (element.type === 'image') return true;
    return element.type === 'wiki-link' ? false : isVoid(element);
  };

  return editor;
};

export function parseMarkdownToSlate(markdown: string): Descendant[] {
  const lines = markdown.split('\n');
  const nodes: Descendant[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (line.startsWith('# ')) {
      nodes.push({
        type: 'heading',
        level: 1,
        children: [{ text: line.slice(2) }],
      } as any);
      i++;
      continue;
    }

    if (line.startsWith('## ')) {
      nodes.push({
        type: 'heading',
        level: 2,
        children: [{ text: line.slice(3) }],
      } as any);
      i++;
      continue;
    }

    if (line.startsWith('### ')) {
      nodes.push({
        type: 'heading',
        level: 3,
        children: [{ text: line.slice(4) }],
      } as any);
      i++;
      continue;
    }

    if (line.startsWith('> ')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].startsWith('> ')) {
        quoteLines.push(lines[i].slice(2));
        i++;
      }
      nodes.push({
        type: 'blockquote',
        children: [{ text: quoteLines.join('\n') }],
      } as any);
      continue;
    }

    if (line.startsWith('```')) {
      const language = line.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      i++;
      nodes.push({
        type: 'code-block',
        language,
        children: [{ text: codeLines.join('\n') }],
      } as any);
      continue;
    }

    if (line.startsWith('- ') || line.startsWith('* ')) {
      const items: string[] = [];
      while (i < lines.length && (lines[i].startsWith('- ') || lines[i].startsWith('* '))) {
        items.push(lines[i].slice(2));
        i++;
      }
      nodes.push({
        type: 'bulleted-list',
        children: items.map(item => ({
          type: 'list-item',
          children: parseInlineText(item),
        })),
      } as any);
      continue;
    }

    if (/^\d+\.\s/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s/, ''));
        i++;
      }
      nodes.push({
        type: 'numbered-list',
        children: items.map(item => ({
          type: 'list-item',
          children: parseInlineText(item),
        })),
      } as any);
      continue;
    }

    const imageMatch = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imageMatch && imageMatch[0] === line.trim()) {
      nodes.push({
        type: 'image',
        src: imageMatch[2],
        alt: imageMatch[1],
        children: [{ text: '' }],
      } as any);
      i++;
      continue;
    }

    if (line.trim() === '') {
      nodes.push({ type: 'paragraph', children: [{ text: '' }] } as any);
      i++;
      continue;
    }

    nodes.push({
      type: 'paragraph',
      children: parseInlineText(line),
    } as any);
    i++;
  }

  if (nodes.length === 0) {
    nodes.push({ type: 'paragraph', children: [{ text: '' }] } as any);
  }

  return nodes;
}

export function parseInlineText(text: string): any[] {
  const children: any[] = [];
  const wikiLinkRegex = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;
  let lastIndex = 0;
  let match;

  while ((match = wikiLinkRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      children.push({ text: text.slice(lastIndex, match.index) });
    }

    const target = match[1].trim();
    const displayText = (match[2] || match[1]).trim();

    children.push({
      type: 'wiki-link',
      target,
      displayText,
      children: [{ text: displayText }],
    });

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    children.push({ text: text.slice(lastIndex) });
  }

  if (children.length === 0) {
    children.push({ text: '' });
  }

  return children;
}

export function slateToMarkdown(nodes: Descendant[]): string {
  const lines: string[] = [];

  for (const node of nodes) {
    const element = node as any;

    switch (element.type) {
      case 'heading':
        const prefix = '#'.repeat(element.level);
        lines.push(`${prefix} ${element.children.map((c: any) => c.text).join('')}`);
        break;
      case 'paragraph':
        lines.push(inlineToMarkdown(element.children));
        break;
      case 'blockquote':
        const quoteText = element.children.map((c: any) => c.text).join('\n');
        lines.push(quoteText.split('\n').map((l: string) => `> ${l}`).join('\n'));
        break;
      case 'code-block':
        lines.push(`\`\`\`${element.language || ''}`);
        lines.push(element.children[0].text);
        lines.push('```');
        break;
      case 'bulleted-list':
        for (const item of element.children) {
          lines.push(`- ${inlineToMarkdown(item.children)}`);
        }
        break;
      case 'numbered-list':
        element.children.forEach((item: any, idx: number) => {
          lines.push(`${idx + 1}. ${inlineToMarkdown(item.children)}`);
        });
        break;
      case 'image':
        lines.push(`![${element.alt || ''}](${element.src})`);
        break;
      default:
        if (element.text !== undefined) {
          lines.push(element.text);
        } else if (element.children) {
          lines.push(inlineToMarkdown(element.children));
        }
    }
  }

  return lines.join('\n');
}

export function inlineToMarkdown(children: any[]): string {
  return children.map(child => {
    if (child.type === 'wiki-link') {
      if (child.displayText !== child.target) {
        return `[[${child.target}|${child.displayText}]]`;
      }
      return `[[${child.target}]]`;
    }
    return child.text || '';
  }).join('');
}
