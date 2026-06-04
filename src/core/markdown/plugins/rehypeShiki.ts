import { visit } from 'unist-util-visit';
import type { Plugin } from 'unified';
import type { Root, Element, Text } from 'hast';
import { getHighlighter, type Highlighter } from 'shiki';

let highlighter: Highlighter | null = null;
let highlighterPromise: Promise<void> | null = null;

async function initHighlighter(): Promise<void> {
  if (highlighter) return;
  if (highlighterPromise) return highlighterPromise;

  highlighterPromise = getHighlighter({
    themes: ['github-dark', 'github-light'],
    langs: [
      'javascript', 'typescript', 'python', 'java', 'cpp', 'c', 'csharp',
      'go', 'rust', 'ruby', 'php', 'swift', 'kotlin', 'sql', 'bash',
      'json', 'yaml', 'markdown', 'html', 'css', 'scss', 'xml',
      'dockerfile', 'vue', 'svelte', 'tsx', 'jsx', 'graphql',
    ],
  }).then((h) => {
    highlighter = h;
  });

  return highlighterPromise;
}

export const rehypeShiki: Plugin<[], Root> = () => {
  return async (tree) => {
    await initHighlighter();
    if (!highlighter) return;

    visit(tree, 'element', (node: Element) => {
      if (node.tagName !== 'pre') return;
      
      const codeEl = node.children.find(
        (c): c is Element => c.type === 'element' && c.tagName === 'code'
      );
      
      if (!codeEl) return;

      const langClass = codeEl.properties?.className as string[] | undefined;
      let lang = 'text';
      
      if (langClass) {
        const match = langClass.find(c => c.startsWith('language-'));
        if (match) lang = match.replace('language-', '');
      }

      const textNode = codeEl.children.find((c): c is Text => c.type === 'text');
      if (!textNode) return;

      const code = textNode.value;
      
      try {
        const tokens = highlighter.codeToThemedTokens(code, lang, 'github-dark');
        const html = highlighter.renderToHtml(tokens, {
          elements: {
            pre({ children }) {
              return `<pre class="shiki dark:bg-slate-900 bg-slate-50 rounded-lg p-4 overflow-x-auto my-4" style="background-color: #0f172a">${children}</pre>`;
            },
            line({ children }) {
              return `<span class="line block">${children}</span>`;
            },
          },
        });

        const root = require('hast-util-from-html').fromHtml(html, { fragment: true });
        const preEl = root.children[0] as Element;
        
        node.tagName = preEl.tagName;
        node.properties = preEl.properties;
        node.children = preEl.children;
      } catch (e) {
        console.warn(`Shiki highlighting failed for lang ${lang}:`, e);
      }
    });
  };
};
