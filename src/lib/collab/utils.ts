import * as Y from 'yjs';
import type { MarkdownConvertOptions, UserColor, AwarenessState, CollabUser } from './types';

const USER_COLORS = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4', '#FFEAA7',
  '#DDA0DD', '#98D8C8', '#F7DC6F', '#BB8FCE', '#85C1E9',
  '#F8B500', '#00CED1', '#FF69B4', '#32CD32', '#FF7F50',
  '#9370DB', '#20B2AA', '#FFB347', '#87CEEB', '#DEA5A4',
];

export function generateUserColor(userId: string): UserColor {
  const hash = userId.split('').reduce((acc, char) => {
    return char.charCodeAt(0) + ((acc << 5) - acc);
  }, 0);
  const primary = USER_COLORS[Math.abs(hash) % USER_COLORS.length];
  
  const hexToRgb = (hex: string) => {
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    return result ? {
      r: parseInt(result[1], 16),
      g: parseInt(result[2], 16),
      b: parseInt(result[3], 16)
    } : { r: 0, g: 0, b: 0 };
  };
  
  const rgb = hexToRgb(primary);
  const light = `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.2)`;
  const dark = `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.8)`;
  
  return { primary, light, dark };
}

export function encodeYDocState(doc: Y.Doc): Uint8Array {
  return Y.encodeStateAsUpdate(doc);
}

export function decodeYDocState(state: Uint8Array, doc?: Y.Doc): Y.Doc {
  const ydoc = doc || new Y.Doc();
  if (state.length > 0) {
    Y.applyUpdate(ydoc, state);
  }
  return ydoc;
}

export function mergeYDocStates(states: Uint8Array[]): Uint8Array {
  const mergedDoc = new Y.Doc();
  states.forEach(state => {
    if (state.length > 0) {
      Y.applyUpdate(mergedDoc, state);
    }
  });
  return encodeYDocState(mergedDoc);
}

export function yDocToMarkdown(doc: Y.Doc, options: MarkdownConvertOptions = {}): string {
  const xmlFragment = doc.getXmlFragment('prosemirror');
  const textContent = doc.getText('content');
  
  if (options.ignoreEmpty && xmlFragment.length === 0 && textContent.length === 0) {
    return '';
  }
  
  return xmlFragmentToString(xmlFragment);
}

function xmlFragmentToString(fragment: Y.XmlFragment): string {
  let result = '';
  const array = fragment.toArray();
  
  for (const item of array) {
    result += xmlNodeToString(item);
  }
  
  return result;
}

function xmlNodeToString(node: Y.XmlElement | Y.XmlText | Y.XmlFragment | Y.XmlHook): string {
  if (node instanceof Y.XmlText) {
    return textNodeToMarkdown(node);
  }
  
  if (node instanceof Y.XmlElement) {
    const nodeName = node.nodeName;
    const children = node.toArray();
    let content = '';
    
    for (const child of children) {
      content += xmlNodeToString(child as Y.XmlElement | Y.XmlText | Y.XmlFragment | Y.XmlHook);
    }
    
    return elementToMarkdown(nodeName, content, node);
  }
  
  if (node instanceof Y.XmlFragment) {
    return xmlFragmentToString(node);
  }
  
  return '';
}

function textNodeToMarkdown(text: Y.XmlText): string {
  let result = '';
  const deltas = text.toDelta();
  
  for (const delta of deltas) {
    let formatted = delta.insert;
    
    if (delta.attributes) {
      if (delta.attributes.bold) {
        formatted = `**${formatted}**`;
      }
      if (delta.attributes.italic) {
        formatted = `*${formatted}*`;
      }
      if (delta.attributes.underline) {
        formatted = `<u>${formatted}</u>`;
      }
      if (delta.attributes.strike) {
        formatted = `~~${formatted}~~`;
      }
      if (delta.attributes.code) {
        formatted = `\`${formatted}\``;
      }
      if (delta.attributes.link) {
        formatted = `[${formatted}](${delta.attributes.link})`;
      }
    }
    
    result += formatted;
  }
  
  return result;
}

function elementToMarkdown(tag: string, content: string, node: Y.XmlElement): string {
  switch (tag) {
    case 'paragraph':
      return `${content}\n\n`;
    case 'heading': {
      const levelAttr = node.getAttribute('level');
      const level = typeof levelAttr === 'number' ? levelAttr : Number(levelAttr) || 1;
      const prefix = '#'.repeat(level);
      return `${prefix} ${content}\n\n`;
    }
    case 'bulletList':
      return `${content}\n`;
    case 'orderedList':
      return `${content}\n`;
    case 'listItem': {
      let itemContent = content.replace(/\n\n$/, '');
      const parent = node.parent;
      if (parent instanceof Y.XmlElement && parent.nodeName === 'orderedList') {
        return `1. ${itemContent}\n`;
      }
      return `- ${itemContent}\n`;
    }
    case 'taskList':
      return `${content}\n`;
    case 'taskItem': {
      const checkedAttr = node.getAttribute('checked');
      const checked = typeof checkedAttr === 'boolean' ? checkedAttr : Boolean(checkedAttr);
      const checkbox = checked ? '[x]' : '[ ]';
      let itemContent = content.replace(/\n\n$/, '');
      return `- ${checkbox} ${itemContent}\n`;
    }
    case 'codeBlock': {
      const languageAttr = node.getAttribute('language');
      const language = typeof languageAttr === 'string' ? languageAttr : String(languageAttr || '');
      return `\`\`\`${language}\n${content}\n\`\`\`\n\n`;
    }
    case 'blockquote':
      const lines = content.split('\n').filter(l => l.trim());
      return lines.map(line => `> ${line}`).join('\n') + '\n\n';
    case 'horizontalRule':
      return `---\n\n`;
    case 'table':
      return tableToMarkdown(node);
    case 'image': {
      const srcAttr = node.getAttribute('src');
      const altAttr = node.getAttribute('alt');
      const src = typeof srcAttr === 'string' ? srcAttr : String(srcAttr || '');
      const alt = typeof altAttr === 'string' ? altAttr : String(altAttr || '');
      return `![${alt}](${src})\n\n`;
    }
    default:
      return content;
  }
}

function tableToMarkdown(tableNode: Y.XmlElement): string {
  const rows = tableNode.toArray().filter(
    (n): n is Y.XmlElement => n instanceof Y.XmlElement && n.nodeName === 'tableRow'
  );
  
  if (rows.length === 0) return '';
  
  const markdownRows: string[] = [];
  
  for (let i = 0; i < rows.length; i++) {
    const cells = rows[i].toArray().filter(
      (n): n is Y.XmlElement => n instanceof Y.XmlElement && 
        (n.nodeName === 'tableCell' || n.nodeName === 'tableHeader')
    );
    
    const cellContents = cells.map(cell => {
      let content = '';
      for (const child of cell.toArray()) {
        content += xmlNodeToString(child);
      }
      return content.replace(/\n\n$/, '').replace(/\|/g, '\\|');
    });
    
    markdownRows.push(`| ${cellContents.join(' | ')} |`);
    
    if (i === 0) {
      markdownRows.push(`| ${cellContents.map(() => '---').join(' | ')} |`);
    }
  }
  
  return markdownRows.join('\n') + '\n\n';
}

export function markdownToYDoc(markdown: string, doc?: Y.Doc): Y.Doc {
  const ydoc = doc || new Y.Doc();
  const fragment = ydoc.getXmlFragment('prosemirror');
  
  if (!markdown.trim()) {
    return ydoc;
  }
  
  const lines = markdown.split('\n');
  let i = 0;
  
  while (i < lines.length) {
    const line = lines[i];
    
    if (line.startsWith('#')) {
      const level = line.match(/^#+/)?.[0].length || 1;
      const content = line.replace(/^#+\s*/, '');
      const heading = new Y.XmlElement('heading');
      heading.setAttribute('level', String(level));
      const text = new Y.XmlText(content);
      heading.push([text]);
      fragment.push([heading]);
      i++;
    } else if (line.startsWith('```')) {
      const language = line.slice(3).trim();
      let codeContent = '';
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeContent += (codeContent ? '\n' : '') + lines[i];
        i++;
      }
      i++;
      const codeBlock = new Y.XmlElement('codeBlock');
      if (language) {
        codeBlock.setAttribute('language', language);
      }
      const text = new Y.XmlText(codeContent);
      codeBlock.push([text]);
      fragment.push([codeBlock]);
    } else if (line.startsWith('- [ ]') || line.startsWith('- [x]')) {
      const taskList = new Y.XmlElement('taskList');
      while (i < lines.length && (lines[i].startsWith('- [ ]') || lines[i].startsWith('- [x]'))) {
        const checked = lines[i].startsWith('- [x]');
        const content = lines[i].replace(/^- \[[x ]\]\s*/, '');
        const taskItem = new Y.XmlElement('taskItem');
        taskItem.setAttribute('checked', String(checked));
        const paragraph = new Y.XmlElement('paragraph');
        const text = new Y.XmlText(content);
        paragraph.push([text]);
        taskItem.push([paragraph]);
        taskList.push([taskItem]);
        i++;
      }
      fragment.push([taskList]);
    } else if (line.match(/^\d+\.\s/)) {
      const orderedList = new Y.XmlElement('orderedList');
      while (i < lines.length && lines[i].match(/^\d+\.\s/)) {
        const content = lines[i].replace(/^\d+\.\s/, '');
        const listItem = new Y.XmlElement('listItem');
        const paragraph = new Y.XmlElement('paragraph');
        const text = new Y.XmlText(content);
        paragraph.push([text]);
        listItem.push([paragraph]);
        orderedList.push([listItem]);
        i++;
      }
      fragment.push([orderedList]);
    } else if (line.startsWith('- ') || line.startsWith('* ')) {
      const bulletList = new Y.XmlElement('bulletList');
      while (i < lines.length && (lines[i].startsWith('- ') || lines[i].startsWith('* '))) {
        const content = lines[i].replace(/^[-*]\s/, '');
        const listItem = new Y.XmlElement('listItem');
        const paragraph = new Y.XmlElement('paragraph');
        const text = new Y.XmlText(content);
        paragraph.push([text]);
        listItem.push([paragraph]);
        bulletList.push([listItem]);
        i++;
      }
      fragment.push([bulletList]);
    } else if (line.startsWith('>')) {
      const blockquote = new Y.XmlElement('blockquote');
      const linesCollector: string[] = [];
      while (i < lines.length && lines[i].startsWith('>')) {
        linesCollector.push(lines[i].replace(/^>\s?/, ''));
        i++;
      }
      const paragraph = new Y.XmlElement('paragraph');
      const text = new Y.XmlText(linesCollector.join(' '));
      paragraph.push([text]);
      blockquote.push([paragraph]);
      fragment.push([blockquote]);
    } else if (line.startsWith('---') || line.startsWith('***') || line.startsWith('___')) {
      const hr = new Y.XmlElement('horizontalRule');
      fragment.push([hr]);
      i++;
    } else if (line.startsWith('|')) {
      const tableLines: string[] = [];
      while (i < lines.length && lines[i].startsWith('|')) {
        if (!lines[i].match(/^\|[\s\-:]+\|$/)) {
          tableLines.push(lines[i]);
        }
        i++;
      }
      if (tableLines.length > 0) {
        const table = parseMarkdownTable(tableLines);
        fragment.push([table]);
      }
    } else if (line.startsWith('![')) {
      const match = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
      if (match) {
        const [, alt, src] = match;
        const image = new Y.XmlElement('image');
        image.setAttribute('src', src);
        image.setAttribute('alt', alt);
        fragment.push([image]);
      }
      i++;
    } else if (line.trim() !== '') {
      const paragraph = new Y.XmlElement('paragraph');
      const text = parseInlineMarkdown(line);
      paragraph.push([text]);
      fragment.push([paragraph]);
      i++;
    } else {
      i++;
    }
  }
  
  return ydoc;
}

function parseMarkdownTable(rows: string[]): Y.XmlElement {
  const table = new Y.XmlElement('table');
  
  for (let rowIdx = 0; rowIdx < rows.length; rowIdx++) {
    const cells = rows[rowIdx].slice(1, -1).split('|').map(c => c.trim());
    const tableRow = new Y.XmlElement('tableRow');
    
    for (const cellContent of cells) {
      const cell = new Y.XmlElement(rowIdx === 0 ? 'tableHeader' : 'tableCell');
      const paragraph = new Y.XmlElement('paragraph');
      const text = new Y.XmlText(cellContent);
      paragraph.push([text]);
      cell.push([paragraph]);
      tableRow.push([cell]);
    }
    
    table.push([tableRow]);
  }
  
  return table;
}

function parseInlineMarkdown(text: string): Y.XmlText {
  const xmlText = new Y.XmlText();
  let remaining = text;
  let position = 0;
  
  while (remaining.length > 0) {
    const linkMatch = remaining.match(/\[([^\]]+)\]\(([^)]+)\)/);
    const boldMatch = remaining.match(/\*\*([^*]+)\*\*/);
    const italicMatch = remaining.match(/\*([^*]+)\*/);
    const underlineMatch = remaining.match(/<u>([^<]+)<\/u>/);
    const strikeMatch = remaining.match(/~~([^~]+)~~/);
    const codeMatch = remaining.match(/`([^`]+)`/);
    
    const matches = [
      { type: 'link', match: linkMatch },
      { type: 'bold', match: boldMatch },
      { type: 'italic', match: italicMatch },
      { type: 'underline', match: underlineMatch },
      { type: 'strike', match: strikeMatch },
      { type: 'code', match: codeMatch },
    ].filter(m => m.match && m.match.index !== undefined).sort(
      (a, b) => (a.match!.index! - b.match!.index!)
    );
    
    if (matches.length === 0) {
      xmlText.insert(position, remaining);
      position += remaining.length;
      remaining = '';
    } else {
      const first = matches[0];
      const match = first.match!;
      const index = match.index!;
      
      if (index > 0) {
        const plainText = remaining.slice(0, index);
        xmlText.insert(position, plainText);
        position += plainText.length;
      }
      
      const content = match[1];
      const attrs: Record<string, any> = {};
      
      switch (first.type) {
        case 'link':
          attrs.link = match[2];
          break;
        case 'bold':
          attrs.bold = true;
          break;
        case 'italic':
          attrs.italic = true;
          break;
        case 'underline':
          attrs.underline = true;
          break;
        case 'strike':
          attrs.strike = true;
          break;
        case 'code':
          attrs.code = true;
          break;
      }
      
      xmlText.insert(position, content, attrs);
      position += content.length;
      remaining = remaining.slice(index + match[0].length);
    }
  }
  
  return xmlText;
}

export function createAwarenessState(user: CollabUser): AwarenessState {
  return {
    user,
    lastActive: Date.now(),
  };
}

export function validateJWT(token: string, secret: string): { userId: string; email: string; name: string } | null {
  try {
    const jwt = require('jsonwebtoken');
    const decoded = jwt.verify(token, secret) as any;
    return {
      userId: decoded.userId || decoded.sub,
      email: decoded.email,
      name: decoded.name,
    };
  } catch {
    return null;
  }
}

export function base64ToUint8Array(base64: string): Uint8Array {
  const binaryString = Buffer.from(base64, 'base64').toString('binary');
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

export function uint8ArrayToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return Buffer.from(binary, 'binary').toString('base64');
}

export function debounce<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: NodeJS.Timeout | null = null;
  
  return function(...args: Parameters<T>) {
    if (timeout) {
      clearTimeout(timeout);
    }
    timeout = setTimeout(() => func(...args), wait);
  };
}

export function throttle<T extends (...args: any[]) => any>(
  func: T,
  limit: number
): (...args: Parameters<T>) => void {
  let inThrottle = false;
  
  return function(...args: Parameters<T>) {
    if (!inThrottle) {
      func(...args);
      inThrottle = true;
      setTimeout(() => { inThrottle = false; }, limit);
    }
  };
}
