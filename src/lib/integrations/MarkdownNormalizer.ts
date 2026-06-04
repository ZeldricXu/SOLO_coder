import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import {
  NormalizedContent,
  HeadingNode,
  CodeBlock,
  SourceType,
} from './types';

interface NormalizerOptions {
  sourceType?: SourceType;
  extractHeadings?: boolean;
  extractLinks?: boolean;
  extractCodeBlocks?: boolean;
  extractTags?: boolean;
  convertMath?: boolean;
  resolveInternalLinks?: (url: string) => string | null;
}

const DEFAULT_OPTIONS: Required<NormalizerOptions> = {
  sourceType: 'feishu',
  extractHeadings: true,
  extractLinks: true,
  extractCodeBlocks: true,
  extractTags: true,
  convertMath: true,
  resolveInternalLinks: (url: string) => url,
};

export class MarkdownNormalizer {
  private options: Required<NormalizerOptions>;

  constructor(options?: NormalizerOptions) {
    this.options = { ...DEFAULT_OPTIONS, ...options };
  }

  async normalize(markdown: string): Promise<NormalizedContent> {
    const processed = this.preprocess(markdown);
    const result: NormalizedContent = {
      markdown: '',
      headings: [],
      internalLinks: [],
      externalLinks: [],
      tags: [],
      codeBlocks: [],
    };

    const processor = unified()
      .use(remarkParse, { gfm: true })
      .use(remarkGfm)
      .use(() => (tree: unknown) => {
        this.extractFromTree(tree, result);
      })
      .use(remarkStringify, {
        bullet: '-',
        fence: '`',
        fences: true,
        incrementListMarker: false,
        gfm: true,
      });

    const file = await processor.process(processed);
    result.markdown = this.postprocess(String(file));

    return result;
  }

  private preprocess(markdown: string): string {
    let processed = markdown;

    processed = this.normalizeFeishuMarkdown(processed);
    processed = this.normalizeNotionMarkdown(processed);
    processed = this.normalizeConfluenceMarkdown(processed);
    processed = this.normalizeGithubMarkdown(processed);

    return processed;
  }

  private normalizeFeishuMarkdown(markdown: string): string {
    if (this.options.sourceType !== 'feishu') return markdown;

    let processed = markdown;

    processed = processed.replace(/\u00A0/g, ' ');

    processed = processed.replace(/^>\s*\[!NOTE\]/gm, '> **Note:**');
    processed = processed.replace(/^>\s*\[!TIP\]/gm, '> **Tip:**');
    processed = processed.replace(/^>\s*\[!WARNING\]/gm, '> **Warning:**');
    processed = processed.replace(/^>\s*\[!CAUTION\]/gm, '> **Caution:**');
    processed = processed.replace(/^>\s*\[!IMPORTANT\]/gm, '> **Important:**');

    processed = processed.replace(
      /\[([^\]]+)\]\(([^)]+)\.docx\)/g,
      '[$1]($2)'
    );

    processed = processed.replace(/\\\$/g, '$$$');

    return processed;
  }

  private normalizeNotionMarkdown(markdown: string): string {
    if (this.options.sourceType !== 'notion') return markdown;

    let processed = markdown;

    processed = processed.replace(
      /^(\s*)- \[ \](.*)$/gm,
      '$1- [ ]$2'
    );
    processed = processed.replace(
      /^(\s*)- \[x\](.*)$/gim,
      '$1- [x]$2'
    );

    processed = processed.replace(
      /```\s*([\w+-]+)\s*\n([\s\S]*?)```/g,
      (_, lang, code) => {
        const normalizedLang = this.normalizeLanguage(lang);
        return `\`\`\`${normalizedLang}\n${code}\`\`\``;
      }
    );

    return processed;
  }

  private normalizeConfluenceMarkdown(markdown: string): string {
    if (this.options.sourceType !== 'confluence') return markdown;

    let processed = markdown;

    processed = processed.replace(/\{code:([^}]*)\}([\s\S]*?)\{code\}/g, (_, lang, code) => {
      const normalizedLang = this.normalizeLanguage(lang);
      return `\`\`\`${normalizedLang}\n${code}\n\`\`\``;
    });

    processed = processed.replace(/\{note\}([\s\S]*?)\{note\}/g, '> **Note:** $1');
    processed = processed.replace(/\{warning\}([\s\S]*?)\{warning\}/g, '> **Warning:** $1');
    processed = processed.replace(/\{info\}([\s\S]*?)\{info\}/g, '> **Info:** $1');
    processed = processed.replace(/\{tip\}([\s\S]*?)\{tip\}/g, '> **Tip:** $1');

    processed = processed.replace(
      /\|(.*?)\|/g,
      (match) => match.replace(/\\\|/g, '|')
    );

    processed = processed.replace(/h([1-6])\. /g, (_, level) => '#'.repeat(parseInt(level)) + ' ');

    processed = processed.replace(/\{anchor:([^}]+)\}/g, '<a id="$1"></a>');

    return processed;
  }

  private normalizeGithubMarkdown(markdown: string): string {
    if (this.options.sourceType !== 'github_wiki') return markdown;

    let processed = markdown;

    processed = processed.replace(
      /\[\[([^\]]+)\|([^\]]+)\]\]/g,
      '[$1]($2)'
    );

    processed = processed.replace(/\[\[([^\]]+)\]\]/g, '[$1]($1)');

    return processed;
  }

  private normalizeLanguage(lang: string): string {
    const langMap: Record<string, string> = {
      js: 'javascript',
      ts: 'typescript',
      py: 'python',
      rb: 'ruby',
      go: 'go',
      golang: 'go',
      rs: 'rust',
      java: 'java',
      cpp: 'cpp',
      'c++': 'cpp',
      cs: 'csharp',
      'c#': 'csharp',
      sh: 'bash',
      shell: 'bash',
      yml: 'yaml',
      md: 'markdown',
      '': '',
    };
    return langMap[lang.toLowerCase().trim()] || lang.toLowerCase().trim();
  }

  private postprocess(markdown: string): string {
    let processed = markdown;

    processed = processed.replace(/\n{4,}/g, '\n\n\n');

    processed = processed.replace(/[ \t]+$/gm, '');

    processed = processed.replace(/^\n{3,}/, '\n\n');
    processed = processed.replace(/\n{3,}$/, '\n');

    if (this.options.convertMath) {
      processed = processed.replace(/\$\$([\s\S]*?)\$\$/g, (_, math) => {
        return `$$\n${math.trim()}\n$$`;
      });
    }

    return processed;
  }

  private extractFromTree(tree: unknown, result: NormalizedContent): void {
    const headings: HeadingNode[] = [];
    const headingStack: HeadingNode[] = [];

    visit(tree, 'heading', (node: unknown) => {
      if (!this.options.extractHeadings) return;

      const headingNode = node as {
        depth: number;
        children: Array<{ type: string; value?: string }>;
        position?: { start: { offset: number } };
      };

      const text = this.getTextFromNode(headingNode);
      const id = this.slugify(text);

      const newHeading: HeadingNode = {
        level: headingNode.depth,
        text,
        id,
        children: [],
      };

      while (
        headingStack.length > 0 &&
        headingStack[headingStack.length - 1].level >= newHeading.level
      ) {
        headingStack.pop();
      }

      if (headingStack.length === 0) {
        headings.push(newHeading);
      } else {
        headingStack[headingStack.length - 1].children.push(newHeading);
      }

      headingStack.push(newHeading);
    });

    result.headings = headings;

    visit(tree, 'link', (node: unknown) => {
      if (!this.options.extractLinks) return;

      const linkNode = node as {
        url: string;
        children: Array<{ type: string; value?: string }>;
      };

      const url = linkNode.url;

      if (this.isInternalLink(url)) {
        const resolved = this.options.resolveInternalLinks(url);
        if (resolved) {
          result.internalLinks.push(resolved);
        }
      } else {
        result.externalLinks.push(url);
      }
    });

    visit(tree, 'code', (node: unknown) => {
      if (!this.options.extractCodeBlocks) return;

      const codeNode = node as {
        lang?: string;
        value: string;
        position?: { start: { offset: number }; end: { offset: number } };
      };

      const codeBlock: CodeBlock = {
        language: codeNode.lang,
        code: codeNode.value,
        position: {
          start: codeNode.position?.start?.offset ?? 0,
          end: codeNode.position?.end?.offset ?? codeNode.value.length,
        },
      };

      result.codeBlocks.push(codeBlock);
    });

    if (this.options.extractTags) {
      result.tags = this.extractTagsFromTree(tree);
    }
  }

  private getTextFromNode(node: {
    children?: Array<{ type: string; value?: string; children?: unknown[] }>;
    value?: string;
  }): string {
    if (node.value) return node.value;
    if (!node.children) return '';

    return node.children
      .map((child) => {
        if ('value' in child && typeof child.value === 'string') {
          return child.value;
        }
        if ('children' in child && Array.isArray(child.children)) {
          return this.getTextFromNode(child as {
            children?: Array<{ type: string; value?: string; children?: unknown[] }>;
          });
        }
        return '';
      })
      .join('');
  }

  private slugify(text: string): string {
    return text
      .toLowerCase()
      .replace(/[^\w\s-]/g, '')
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '');
  }

  private isInternalLink(url: string): boolean {
    if (url.startsWith('http://') || url.startsWith('https://')) {
      return false;
    }
    if (url.startsWith('#')) {
      return true;
    }
    if (url.startsWith('/')) {
      return true;
    }
    if (url.startsWith('./') || url.startsWith('../')) {
      return true;
    }
    if (!url.includes('://') && !url.startsWith('mailto:')) {
      return true;
    }
    return false;
  }

  private extractTagsFromTree(tree: unknown): string[] {
    const tags: Set<string> = new Set();

    visit(tree, 'text', (node: unknown) => {
      const textNode = node as { value: string };
      const tagMatches = textNode.value.match(/#[\w\u4e00-\u9fa5-]+/g);
      if (tagMatches) {
        tagMatches.forEach((tag) => tags.add(tag.slice(1)));
      }
    });

    return Array.from(tags);
  }

  static async normalize(
    markdown: string,
    options?: NormalizerOptions
  ): Promise<NormalizedContent> {
    const normalizer = new MarkdownNormalizer(options);
    return normalizer.normalize(markdown);
  }
}
