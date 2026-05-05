import { Descendant, Element as SlateElement, Text } from 'slate';

const LIST_TYPES = ['numbered-list', 'bulleted-list'];
const TEXT_ALIGN_TYPES = ['left', 'center', 'right', 'justify'];

const initialValue: Descendant[] = [
  {
    type: 'paragraph',
    children: [{ text: '' }],
  },
];

export interface ParserOptions {
  preserveFormatting?: boolean;
  enableInlineStyles?: boolean;
  enableBlockStyles?: boolean;
}

export const DEFAULT_PARSER_OPTIONS: ParserOptions = {
  preserveFormatting: true,
  enableInlineStyles: true,
  enableBlockStyles: true,
};

export interface BlockToken {
  type: 'paragraph' | 'heading-one' | 'heading-two' | 'heading-three' | 
        'block-quote' | 'code-block' | 'list-item' | 'numbered-list-item' |
        'horizontal-rule';
  content: string;
  raw: string;
  level?: number;
  language?: string;
}

export interface InlineToken {
  type: 'text' | 'bold' | 'italic' | 'code' | 'link' | 'image' | 'strikethrough';
  content: string;
  raw: string;
  url?: string;
  alt?: string;
}

export interface ParseResult {
  tokens: BlockToken[];
  slateValue: Descendant[];
}

export interface SerializeResult {
  markdown: string;
  plainText: string;
  wordCount: number;
}

export class MarkdownParser {
  private options: ParserOptions;

  constructor(options: ParserOptions = DEFAULT_PARSER_OPTIONS) {
    this.options = { ...DEFAULT_PARSER_OPTIONS, ...options };
  }

  parse(markdown: string): ParseResult {
    if (!markdown) {
      return {
        tokens: [],
        slateValue: initialValue,
      };
    }

    const lines = markdown.split('\n');
    const tokens: BlockToken[] = [];
    let inCodeBlock = false;
    let codeBlockContent = '';
    let codeBlockLanguage = '';

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      
      if (inCodeBlock) {
        if (line.match(/^```/)) {
          tokens.push({
            type: 'code-block',
            content: codeBlockContent.trim(),
            raw: `\`\`\`${codeBlockLanguage}\n${codeBlockContent}\`\`\``,
            language: codeBlockLanguage || undefined,
          });
          inCodeBlock = false;
          codeBlockContent = '';
          codeBlockLanguage = '';
        } else {
          codeBlockContent += (codeBlockContent ? '\n' : '') + line;
        }
        continue;
      }

      const codeBlockMatch = line.match(/^```(\w*)/);
      if (codeBlockMatch) {
        inCodeBlock = true;
        codeBlockLanguage = codeBlockMatch[1] || '';
        continue;
      }

      if (line.startsWith('# ')) {
        tokens.push({
          type: 'heading-one',
          content: line.slice(2),
          raw: line,
          level: 1,
        });
      } else if (line.startsWith('## ')) {
        tokens.push({
          type: 'heading-two',
          content: line.slice(3),
          raw: line,
          level: 2,
        });
      } else if (line.startsWith('### ')) {
        tokens.push({
          type: 'heading-three',
          content: line.slice(4),
          raw: line,
          level: 3,
        });
      } else if (line.startsWith('> ')) {
        tokens.push({
          type: 'block-quote',
          content: line.slice(2),
          raw: line,
        });
      } else if (line.startsWith('- ') || line.startsWith('* ')) {
        tokens.push({
          type: 'list-item',
          content: line.slice(2),
          raw: line,
        });
      } else if (line.match(/^\d+\.\s/)) {
        tokens.push({
          type: 'numbered-list-item',
          content: line.replace(/^\d+\.\s/, ''),
          raw: line,
        });
      } else if (line === '---' || line === '___' || line === '***') {
        tokens.push({
          type: 'horizontal-rule',
          content: '',
          raw: line,
        });
      } else if (line === '') {
        tokens.push({
          type: 'paragraph',
          content: '',
          raw: '',
        });
      } else {
        tokens.push({
          type: 'paragraph',
          content: line,
          raw: line,
        });
      }
    }

    if (inCodeBlock && codeBlockContent) {
      tokens.push({
        type: 'code-block',
        content: codeBlockContent.trim(),
        raw: `\`\`\`${codeBlockLanguage}\n${codeBlockContent}`,
        language: codeBlockLanguage || undefined,
      });
    }

    return {
      tokens,
      slateValue: this.tokensToSlateValue(tokens),
    };
  }

  private tokensToSlateValue(tokens: BlockToken[]): Descendant[] {
    if (tokens.length === 0) {
      return initialValue;
    }

    const nodes: Descendant[] = [];

    for (const token of tokens) {
      const children = this.parseInlineStyles(token.content);
      
      switch (token.type) {
        case 'heading-one':
          nodes.push({ type: 'heading-one', children });
          break;
        case 'heading-two':
          nodes.push({ type: 'heading-two', children });
          break;
        case 'heading-three':
          nodes.push({ type: 'heading-three', children });
          break;
        case 'block-quote':
          nodes.push({ type: 'block-quote', children });
          break;
        case 'code-block':
          nodes.push({ type: 'code-block', children: [{ text: token.content }] });
          break;
        case 'list-item':
          nodes.push({ type: 'list-item', children });
          break;
        case 'numbered-list-item':
          nodes.push({ type: 'numbered-list-item', children });
          break;
        case 'horizontal-rule':
          nodes.push({ type: 'paragraph', children: [{ text: '---' }] });
          break;
        case 'paragraph':
        default:
          nodes.push({ type: 'paragraph', children });
          break;
      }
    }

    return nodes;
  }

  private parseInlineStyles(text: string): Array<Text & { text: string }> {
    if (!this.options.enableInlineStyles) {
      return [{ text }];
    }

    const results: Array<Text & { text: string }> = [];
    let remaining = text;

    const patterns = [
      { regex: /\*\*\*([^*]+)\*\*\*/, type: 'bold-italic' },
      { regex: /\*\*([^*]+)\*\*/, type: 'bold' },
      { regex: /\*([^*]+)\*/, type: 'italic' },
      { regex: /__([^_]+)__/, type: 'underline' },
      { regex: /`([^`]+)`/, type: 'code' },
      { regex: /~~([^~]+)~~/, type: 'strikethrough' },
    ];

    while (remaining.length > 0) {
      let earliestMatch: { index: number; text: string; type: string } | null = null;

      for (const pattern of patterns) {
        const match = remaining.match(pattern.regex);
        if (match && match.index !== undefined) {
          if (!earliestMatch || match.index < earliestMatch.index) {
            earliestMatch = {
              index: match.index,
              text: match[1],
              type: pattern.type,
            };
          }
        }
      }

      if (!earliestMatch) {
        results.push({ text: remaining });
        break;
      }

      if (earliestMatch.index > 0) {
        results.push({ text: remaining.slice(0, earliestMatch.index) });
      }

      const styledText: Text & { text: string } = { text: earliestMatch.text };
      if (earliestMatch.type === 'bold') {
        styledText.bold = true;
      } else if (earliestMatch.type === 'italic') {
        styledText.italic = true;
      } else if (earliestMatch.type === 'bold-italic') {
        styledText.bold = true;
        styledText.italic = true;
      } else if (earliestMatch.type === 'underline') {
        styledText.underline = true;
      } else if (earliestMatch.type === 'code') {
        styledText.code = true;
      } else if (earliestMatch.type === 'strikethrough') {
        styledText.strikethrough = true;
      }

      results.push(styledText);

      const matchLen = remaining.indexOf(earliestMatch.text, earliestMatch.index) + earliestMatch.text.length;
      const fullMatch = remaining.slice(earliestMatch.index, matchLen + (earliestMatch.type === 'bold' ? 4 : earliestMatch.type === 'italic' ? 2 : 2));
      remaining = remaining.slice(earliestMatch.index + fullMatch.length);
    }

    return results.length > 0 ? results : [{ text }];
  }

  serialize(nodes: Descendant[]): SerializeResult {
    const markdown = this.nodesToMarkdown(nodes);
    const plainText = this.nodesToPlainText(nodes);
    const wordCount = this.countWords(plainText);

    return {
      markdown: markdown.trim(),
      plainText,
      wordCount,
    };
  }

  private nodesToMarkdown(nodes: Descendant[]): string {
    let markdown = '';

    for (const node of nodes) {
      if (!SlateElement.isElement(node)) continue;

      const text = this.serializeElementContent(node);

      switch (node.type) {
        case 'heading-one':
          markdown += `# ${text}\n\n`;
          break;
        case 'heading-two':
          markdown += `## ${text}\n\n`;
          break;
        case 'heading-three':
          markdown += `### ${text}\n\n`;
          break;
        case 'block-quote':
          markdown += `> ${text}\n\n`;
          break;
        case 'code-block':
          markdown += `\`\`\`\n${text}\n\`\`\`\n\n`;
          break;
        case 'list-item':
          markdown += `- ${text}\n`;
          break;
        case 'numbered-list-item':
          markdown += `1. ${text}\n`;
          break;
        case 'paragraph':
        default:
          if (text === '') {
            markdown += '\n';
          } else {
            markdown += `${text}\n\n`;
          }
          break;
      }
    }

    return markdown;
  }

  private serializeElementContent(element: SlateElement): string {
    return element.children
      .map(child => {
        if (Text.isText(child)) {
          return this.serializeTextWithStyles(child);
        }
        return '';
      })
      .join('');
  }

  private serializeTextWithStyles(text: Text & { text: string }): string {
    if (!this.options.enableInlineStyles) {
      return text.text;
    }

    let result = text.text;

    if (text.code) {
      result = `\`${result}\``;
    }
    if (text.bold && text.italic) {
      result = `***${result}***`;
    } else if (text.bold) {
      result = `**${result}**`;
    } else if (text.italic) {
      result = `*${result}*`;
    }
    if (text.underline) {
      result = `__${result}__`;
    }
    if (text.strikethrough) {
      result = `~~${result}~~`;
    }

    return result;
  }

  private nodesToPlainText(nodes: Descendant[]): string {
    return nodes
      .map(node => {
        if (SlateElement.isElement(node)) {
          return node.children
            .map(child => (Text.isText(child) ? child.text : ''))
            .join('');
        }
        return '';
      })
      .join('\n');
  }

  private countWords(content: string): number {
    if (!content) return 0;
    
    const chineseChars = content.match(/[\u4e00-\u9fa5]/g) || [];
    const englishWords = content.match(/[a-zA-Z]+/g) || [];
    const numbers = content.match(/\d+/g) || [];
    
    return chineseChars.length + englishWords.length + numbers.length;
  }

  static parse(markdown: string, options?: ParserOptions): Descendant[] {
    const parser = new MarkdownParser(options);
    return parser.parse(markdown).slateValue;
  }

  static serialize(nodes: Descendant[], options?: ParserOptions): string {
    const parser = new MarkdownParser(options);
    return parser.serialize(nodes).markdown;
  }

  static toPlainText(nodes: Descendant[]): string {
    const parser = new MarkdownParser();
    return parser.serialize(nodes).plainText;
  }

  static countWords(content: string): number {
    const parser = new MarkdownParser();
    return parser.serialize([{ type: 'paragraph', children: [{ text: content }] }]).wordCount;
  }
}

export const parseMarkdownToSlate = (markdown: string): Descendant[] => {
  return MarkdownParser.parse(markdown);
};

export const serializeSlateToMarkdown = (nodes: Descendant[]): string => {
  return MarkdownParser.serialize(nodes);
};

export default MarkdownParser;
