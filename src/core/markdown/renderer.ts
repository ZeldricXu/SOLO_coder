import { parseMarkdownToHtml, sanitizeHtml } from './parser';
import mermaid from 'mermaid';

let mermaidInitialized = false;

function initMermaid(): void {
  if (mermaidInitialized) return;
  
  mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
    securityLevel: 'strict',
    deterministicIds: true,
    fontFamily: 'JetBrains Mono, monospace',
    flowchart: {
      useMaxWidth: true,
      htmlLabels: true,
      curve: 'basis',
    },
    sequence: {
      useMaxWidth: true,
      showSequenceNumbers: true,
    },
    gantt: {
      useMaxWidth: true,
      locale: 'en-US',
    },
  });
  
  mermaidInitialized = true;
}

export async function renderMarkdown(
  markdown: string,
  options?: {
    sanitize?: boolean;
    renderMermaid?: boolean;
  }
): Promise<string> {
  const { sanitize = true, renderMermaid = true } = options || {};
  
  let html = await parseMarkdownToHtml(markdown);
  
  if (renderMermaid) {
    html = await renderMermaidBlocks(html);
  }
  
  if (sanitize) {
    html = sanitizeHtml(html);
  }
  
  return html;
}

async function renderMermaidBlocks(html: string): Promise<string> {
  initMermaid();
  
  const mermaidRegex = /<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g;
  const promises: Promise<string>[] = [];
  
  html = html.replace(mermaidRegex, (match, code) => {
    const promise = (async () => {
      try {
        const cleanCode = code
          .replace(/&lt;/g, '<')
          .replace(/&gt;/g, '>')
          .replace(/&amp;/g, '&')
          .replace(/&quot;/g, '"')
          .replace(/&#39;/g, "'")
          .trim();
        
        const { svg } = await mermaid.render(`mermaid-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, cleanCode);
        return `<div class="mermaid-container bg-slate-800/50 rounded-lg p-4 my-4 overflow-x-auto">${svg}</div>`;
      } catch (e) {
        console.error('Mermaid render error:', e);
        return `<div class="bg-red-900/20 border border-red-500/30 rounded-lg p-4 my-4 text-red-400">
          <p class="font-semibold mb-2">Mermaid 渲染错误</p>
          <pre class="text-sm opacity-80 whitespace-pre-wrap">${code}</pre>
        </div>`;
      }
    })();
    
    promises.push(promise);
    return `__MERMAID_PLACEHOLDER_${promises.length - 1}__`;
  });
  
  const renderedBlocks = await Promise.all(promises);
  
  for (let i = 0; i < renderedBlocks.length; i++) {
    html = html.replace(`__MERMAID_PLACEHOLDER_${i}__`, renderedBlocks[i]);
  }
  
  return html;
}

export function createMarkdownProcessor() {
  const cache = new Map<string, { html: string; timestamp: number }>();
  const CACHE_TTL = 5000;

  return {
    async render(markdown: string, hash?: string): Promise<string> {
      const cacheKey = hash || markdown.slice(0, 1000);
      const cached = cache.get(cacheKey);
      
      if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
        return cached.html;
      }
      
      const html = await renderMarkdown(markdown);
      cache.set(cacheKey, { html, timestamp: Date.now() });
      
      if (cache.size > 100) {
        const oldestKey = cache.keys().next().value;
        if (oldestKey) cache.delete(oldestKey);
      }
      
      return html;
    },
    
    clearCache(): void {
      cache.clear();
    },
  };
}

export function applyHeadingIds(html: string): string {
  return html.replace(/<h([1-6])>(.+?)<\/h[1-6]>/g, (match, level, text) => {
    const id = text
      .toLowerCase()
      .replace(/<[^>]+>/g, '')
      .replace(/[^\w\u4e00-\u9fa5]+/g, '-')
      .replace(/^-+|-+$/g, '');
    
    return `<h${level} id="${id}" class="scroll-mt-20">${text}</h${level}>`;
  });
}

export function addTargetBlankToLinks(html: string): string {
  return html.replace(
    /<a([^>]+href="https?:\/\/[^"]+"[^>]*)>/g,
    '<a$1 target="_blank" rel="noopener noreferrer">'
  );
}
