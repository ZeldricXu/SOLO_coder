import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import remarkRehype from 'remark-rehype';
import rehypeKatex from 'rehype-katex';
import rehypeStringify from 'rehype-stringify';
import { remarkWikilink } from './plugins/remarkWikilink';
import { rehypeShiki } from './plugins/rehypeShiki';

let processor: any = null;
let processorPromise: Promise<void> | null = null;

async function initProcessor(): Promise<void> {
  if (processor) return;
  if (processorPromise) return processorPromise;

  processorPromise = (async () => {
    processor = unified()
      .use(remarkParse)
      .use(remarkGfm, {
        singleTilde: false,
        tablePipeAlign: true,
      })
      .use(remarkMath)
      .use(remarkWikilink)
      .use(remarkRehype, { allowDangerousHtml: true })
      .use(rehypeKatex, {
        output: 'html',
        strict: false,
        throwOnError: false,
        errorColor: '#ef4444',
      })
      .use(rehypeShiki)
      .use(rehypeStringify, { allowDangerousHtml: true });
  })();

  return processorPromise;
}

export async function parseMarkdownToHtml(markdown: string): Promise<string> {
  await initProcessor();
  if (!processor) return markdown;

  try {
    const file = await processor.process(markdown);
    return String(file);
  } catch (e) {
    console.error('Markdown parsing error:', e);
    return `<pre class="text-red-500">解析错误: ${(e as Error).message}</pre>`;
  }
}

export async function parseMarkdownToHtmlSync(markdown: string): Promise<string> {
  return parseMarkdownToHtml(markdown);
}

export function sanitizeHtml(html: string): string {
  return html
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, '')
    .replace(/on\w+="[^"]*"/gi, '')
    .replace(/on\w+='[^']*'/gi, '')
    .replace(/javascript:/gi, '');
}

export function extractHeadings(markdown: string): Array<{ level: number; text: string; id: string }> {
  const headingRegex = /^(#{1,6})\s+(.+)$/gm;
  const headings: Array<{ level: number; text: string; id: string }> = [];
  let match;

  while ((match = headingRegex.exec(markdown)) !== null) {
    const level = match[1].length;
    const text = match[2].trim();
    const id = text
      .toLowerCase()
      .replace(/[^\w\u4e00-\u9fa5]+/g, '-')
      .replace(/^-+|-+$/g, '');
    
    headings.push({ level, text, id });
  }

  return headings;
}

export function extractPlainText(markdown: string): string {
  return markdown
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/\*(.+?)\*/g, '$1')
    .replace(/`{1,3}[^`]*`{1,3}/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\[\[([^\]]+)\]\]/g, '$1')
    .replace(/^>+\s+/gm, '')
    .replace(/^[-*+]\s+/gm, '')
    .replace(/^\d+\.\s+/gm, '')
    .replace(/^---+$/gm, '')
    .replace(/<[^>]+>/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export function getWordCount(markdown: string): number {
  const plainText = extractPlainText(markdown);
  const chineseChars = (plainText.match(/[\u4e00-\u9fa5]/g) || []).length;
  const englishWords = plainText
    .replace(/[\u4e00-\u9fa5]/g, ' ')
    .split(/\s+/)
    .filter(w => w.length > 0).length;
  return chineseChars + englishWords;
}

export function getReadingTime(markdown: string): number {
  const words = getWordCount(markdown);
  const cpm = 300;
  return Math.max(1, Math.ceil(words / cpm));
}
