import * as fs from 'fs/promises';
import * as path from 'path';
import type { Document } from '@shared/types';
import { createMarkdownProcessor } from '@core/markdown';
import { buildGraphFromDocuments } from '@core/graph/parser';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';

export interface ExportOptions {
  outputPath: string;
  includeDrafts?: boolean;
  includeTags?: string[];
  excludeTags?: string[];
  customDomain?: string;
  siteTitle?: string;
  siteDescription?: string;
}

export interface ExportProgress {
  current: number;
  total: number;
  message: string;
}

const generateId = (str: string): string => {
  return str.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
};

const escapeHtml = (text: string): string => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
};

const STATIC_CSS = `
:root {
  --primary: #3b82f6;
  --primary-hover: #2563eb;
  --background: #ffffff;
  --foreground: #1f2937;
  --muted: #6b7280;
  --border: #e5e7eb;
  --card: #f9fafb;
  --code-bg: #f3f4f6;
  --link: #2563eb;
}

.dark {
  --primary: #60a5fa;
  --primary-hover: #3b82f6;
  --background: #111827;
  --foreground: #f3f4f6;
  --muted: #9ca3af;
  --border: #374151;
  --card: #1f2937;
  --code-bg: #374151;
  --link: #60a5fa;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: var(--background);
  color: var(--foreground);
  line-height: 1.6;
}

.app-container {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 300px;
  border-right: 1px solid var(--border);
  background: var(--card);
  overflow-y: auto;
  flex-shrink: 0;
}

.sidebar-header {
  padding: 1.5rem;
  border-bottom: 1px solid var(--border);
}

.sidebar-title {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0 0 0.25rem 0;
  color: var(--foreground);
}

.sidebar-description {
  font-size: 0.875rem;
  color: var(--muted);
  margin: 0;
}

.sidebar-nav {
  padding: 1rem;
}

.nav-section {
  margin-bottom: 1.5rem;
}

.nav-title {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--muted);
  margin: 0 0 0.5rem 0;
  padding: 0 0.75rem;
}

.nav-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.nav-item {
  margin: 0.125rem 0;
}

.nav-link {
  display: block;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  color: var(--foreground);
  text-decoration: none;
  font-size: 0.875rem;
  transition: background 0.15s, color 0.15s;
}

.nav-link:hover {
  background: var(--background);
  color: var(--primary);
}

.nav-link.active {
  background: var(--primary);
  color: white;
}

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 3rem 4rem;
  max-width: 900px;
  margin: 0 auto;
}

.page-title {
  font-size: 2rem;
  font-weight: 700;
  margin: 0 0 1rem 0;
  color: var(--foreground);
}

.page-meta {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border);
  color: var(--muted);
  font-size: 0.875rem;
}

.tag {
  display: inline-block;
  padding: 0.125rem 0.5rem;
  background: var(--primary);
  color: white;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 500;
}

.prose {
  color: var(--foreground);
}

.prose h1, .prose h2, .prose h3, .prose h4, .prose h5, .prose h6 {
  color: var(--foreground);
  font-weight: 600;
  line-height: 1.3;
  margin-top: 2rem;
  margin-bottom: 1rem;
}

.prose h1 { font-size: 1.875rem; }
.prose h2 { font-size: 1.5rem; }
.prose h3 { font-size: 1.25rem; }
.prose h4 { font-size: 1.125rem; }

.prose p {
  margin: 1rem 0;
}

.prose a {
  color: var(--link);
  text-decoration: underline;
}

.prose a:hover {
  color: var(--primary-hover);
}

.prose code {
  background: var(--code-bg);
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  font-size: 0.875em;
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
}

.prose pre {
  background: var(--code-bg);
  padding: 1rem;
  border-radius: 0.5rem;
  overflow-x: auto;
  margin: 1rem 0;
}

.prose pre code {
  background: none;
  padding: 0;
}

.prose blockquote {
  border-left: 4px solid var(--primary);
  padding: 0.5rem 1rem;
  margin: 1rem 0;
  background: var(--card);
  border-radius: 0 0.375rem 0.375rem 0;
}

.prose ul, .prose ol {
  padding-left: 1.5rem;
  margin: 1rem 0;
}

.prose li {
  margin: 0.5rem 0;
}

.prose table {
  width: 100%;
  border-collapse: collapse;
  margin: 1rem 0;
}

.prose th, .prose td {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border);
  text-align: left;
}

.prose th {
  background: var(--card);
  font-weight: 600;
}

.prose img {
  max-width: 100%;
  border-radius: 0.5rem;
}

.prose hr {
  border: none;
  border-top: 1px solid var(--border);
  margin: 2rem 0;
}

.backlinks {
  margin-top: 3rem;
  padding-top: 2rem;
  border-top: 1px solid var(--border);
}

.backlinks-title {
  font-size: 1rem;
  font-weight: 600;
  margin: 0 0 1rem 0;
  color: var(--muted);
}

.backlinks-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.backlink-item {
  padding: 0.75rem;
  background: var(--card);
  border-radius: 0.5rem;
  margin-bottom: 0.5rem;
}

.backlink-title {
  font-weight: 600;
  color: var(--link);
  text-decoration: none;
}

.backlink-context {
  font-size: 0.875rem;
  color: var(--muted);
  margin-top: 0.25rem;
}

.graph-container {
  width: 100%;
  height: 600px;
  background: var(--card);
  border-radius: 0.5rem;
  overflow: hidden;
}

.footer {
  margin-top: 3rem;
  padding-top: 2rem;
  border-top: 1px solid var(--border);
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
}

@media (max-width: 768px) {
  .sidebar {
    display: none;
  }
  
  .main-content {
    padding: 1.5rem;
  }
}

.theme-toggle {
  position: fixed;
  top: 1rem;
  right: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  cursor: pointer;
  color: var(--foreground);
  font-size: 0.875rem;
  z-index: 100;
}

.theme-toggle:hover {
  background: var(--background);
}

.search-box {
  width: 100%;
  padding: 0.5rem 0.75rem;
  background: var(--background);
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  color: var(--foreground);
  font-size: 0.875rem;
  margin-bottom: 1rem;
}

.search-box:focus {
  outline: none;
  border-color: var(--primary);
}

.wikilink {
  color: var(--link);
  text-decoration: underline;
}
`;

const STATIC_JS = `
(function() {
  const themeToggle = document.getElementById('theme-toggle');
  const html = document.documentElement;

  const savedTheme = localStorage.getItem('theme');
  if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
    html.classList.add('dark');
  }

  if (themeToggle) {
    themeToggle.addEventListener('click', () => {
      html.classList.toggle('dark');
      localStorage.setItem('theme', html.classList.contains('dark') ? 'dark' : 'light');
    });
  }

  const searchBox = document.getElementById('search-box');
  if (searchBox) {
    searchBox.addEventListener('input', (e) => {
      const query = e.target.value.toLowerCase();
      const navItems = document.querySelectorAll('.nav-item');
      
      navItems.forEach(item => {
        const link = item.querySelector('.nav-link');
        const text = link.textContent.toLowerCase();
        item.style.display = text.includes(query) ? '' : 'none';
      });
    });
  }

  document.querySelectorAll('a[data-wikilink]').forEach(link => {
    link.addEventListener('click', (e) => {
      const target = link.getAttribute('data-wikilink');
      const docLink = document.querySelector(\`a[data-doc-id="\${target}"]\`);
      if (docLink) {
        e.preventDefault();
        docLink.click();
      }
    });
  });
})();
`;

export class ExportService {
  private repoPath: string;
  private processor: ReturnType<typeof createMarkdownProcessor>;
  private onProgress?: (progress: ExportProgress) => void;

  constructor(repoPath: string, onProgress?: (progress: ExportProgress) => void) {
    this.repoPath = repoPath;
    this.processor = createMarkdownProcessor();
    this.onProgress = onProgress;
  }

  private reportProgress(current: number, total: number, message: string): void {
    if (this.onProgress) {
      this.onProgress({ current, total, message });
    }
  }

  private async ensureDir(dirPath: string): Promise<void> {
    try {
      await fs.access(dirPath);
    } catch {
      await fs.mkdir(dirPath, { recursive: true });
    }
  }

  private generateDocumentTree(documents: Document[]): Array<{ title: string; id: string; path: string; level: number }> {
    return documents
      .filter(d => d.title)
      .sort((a, b) => a.title.localeCompare(b.title))
      .map(doc => ({
        title: doc.title,
        id: doc.id,
        path: `${generateId(doc.title)}.html`,
        level: 0,
      }));
  }

  private async renderDocument(doc: Document, allDocs: Document[]): Promise<string> {
    let content = await this.processor.render(doc.content || '', doc.hash);

    allDocs.forEach(targetDoc => {
      const targetId = generateId(targetDoc.title);
      const wikilinkRegex = new RegExp(`href="app://open/${targetDoc.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`, 'g');
      content = content.replace(wikilinkRegex, `href="${targetId}.html" data-doc-id="${targetDoc.title}"`);
    });

    return content;
  }

  private generatePageHtml(
    title: string,
    content: string,
    sidebarHtml: string,
    docId: string,
    options: ExportOptions,
    extraHead?: string,
    extraBody?: string
  ): string {
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${escapeHtml(title)} | ${escapeHtml(options.siteTitle || '知识库')}</title>
  <meta name="description" content="${escapeHtml(options.siteDescription || '')}">
  <style>${STATIC_CSS}</style>
  ${extraHead || ''}
</head>
<body>
  <button id="theme-toggle" class="theme-toggle">🌓 切换主题</button>
  <div class="app-container">
    <aside class="sidebar">
      <div class="sidebar-header">
        <h1 class="sidebar-title">${escapeHtml(options.siteTitle || '知识库')}</h1>
        <p class="sidebar-description">${escapeHtml(options.siteDescription || '')}</p>
      </div>
      <nav class="sidebar-nav">
        <input id="search-box" class="search-box" type="search" placeholder="搜索文档..." />
        ${sidebarHtml}
      </nav>
    </aside>
    <main class="main-content">
      ${content}
    </main>
  </div>
  ${extraBody || ''}
  <script>${STATIC_JS}</script>
</body>
</html>`;
  }

  private generateSidebarHtml(docs: Document[], activeDocId?: string): string {
    const tree = this.generateDocumentTree(docs);
    const graphActive = activeDocId === '__graph__';

    let html = `
      <div class="nav-section">
        <h3 class="nav-title">导航</h3>
        <ul class="nav-list">
          <li class="nav-item">
            <a href="index.html" class="nav-link ${activeDocId === 'index' ? 'active' : ''}">🏠 首页</a>
          </li>
          <li class="nav-item">
            <a href="graph.html" class="nav-link ${graphActive ? 'active' : ''}">🕸️ 知识图谱</a>
          </li>
        </ul>
      </div>
      <div class="nav-section">
        <h3 class="nav-title">文档</h3>
        <ul class="nav-list">
    `;

    tree.forEach(item => {
      const isActive = item.id === activeDocId;
      html += `
          <li class="nav-item">
            <a href="${item.path}" class="nav-link ${isActive ? 'active' : ''}" data-doc-id="${item.id}">
              ${escapeHtml(item.title)}
            </a>
          </li>
      `;
    });

    html += `
        </ul>
      </div>
    `;

    return html;
  }

  private generateGraphPage(docs: Document[], options: ExportOptions): string {
    const graph = buildGraphFromDocuments(docs.map(d => ({ ...d, content: d.content || '' })));
    const sidebarHtml = this.generateSidebarHtml(docs, '__graph__');

    const graphData = JSON.stringify({
      nodes: graph.nodes,
      links: graph.links,
    });

    const graphHead = `
  <script src="https://d3js.org/d3.v7.min.js"></script>
  <style>
    .graph-node { cursor: pointer; }
    .graph-link { stroke-opacity: 0.6; }
    .graph-label { font-size: 11px; pointer-events: none; }
  </style>
`;

    const graphBody = `
  <script>
    const graphData = ${graphData};
    
    (function() {
      const width = 800;
      const height = 600;
      
      const svg = d3.select('#knowledge-graph');
      
      const link = svg.append('g')
        .selectAll('line')
        .data(graphData.links)
        .enter()
        .append('line')
        .attr('class', 'graph-link')
        .attr('stroke', '#4ade80')
        .attr('stroke-width', 1.5);
      
      const node = svg.append('g')
        .selectAll('circle')
        .data(graphData.nodes)
        .enter()
        .append('circle')
        .attr('class', 'graph-node')
        .attr('r', 8)
        .attr('fill', d => d.type === 'document' ? '#22c55e' : '#a855f7')
        .attr('stroke', '#fff')
        .attr('stroke-width', 2);
      
      const label = svg.append('g')
        .selectAll('text')
        .data(graphData.nodes)
        .enter()
        .append('text')
        .attr('class', 'graph-label')
        .attr('dy', 20)
        .attr('text-anchor', 'middle')
        .attr('fill', 'var(--foreground)')
        .text(d => d.label);
      
      const simulation = d3.forceSimulation(graphData.nodes)
        .force('link', d3.forceLink(graphData.links).id(d => d.id).distance(100))
        .force('charge', d3.forceManyBody().strength(-300))
        .force('center', d3.forceCenter(width / 2, height / 2));
      
      simulation.on('tick', () => {
        link
          .attr('x1', d => d.source.x)
          .attr('y1', d => d.source.y)
          .attr('x2', d => d.target.x)
          .attr('y2', d => d.target.y);
        
        node
          .attr('cx', d => d.x)
          .attr('cy', d => d.y);
        
        label
          .attr('x', d => d.x)
          .attr('y', d => d.y);
      });
      
      node.on('click', (event, d) => {
        if (d.type === 'document') {
          const link = document.querySelector(\`a[data-doc-id="\${d.id}"]\`);
          if (link) link.click();
        }
      });
      
      node.call(d3.drag()
        .on('start', (event, d) => {
          if (!event.active) simulation.alphaTarget(0.3).restart();
          d.fx = d.x;
          d.fy = d.y;
        })
        .on('drag', (event, d) => {
          d.fx = event.x;
          d.fy = event.y;
        })
        .on('end', (event, d) => {
          if (!event.active) simulation.alphaTarget(0);
          d.fx = null;
          d.fy = null;
        })
      );
    })();
  </script>
`;

    const content = `
      <h1 class="page-title">知识图谱</h1>
      <div id="graph-container" class="graph-container">
        <svg id="knowledge-graph" width="100%" height="100%" viewBox="0 0 800 600"></svg>
      </div>
      <p class="page-meta">
        共 ${graph.nodes.length} 个节点，${graph.links.length} 条连线
      </p>
`;

    return this.generatePageHtml('知识图谱', content, sidebarHtml, '__graph__', options, graphHead, graphBody);
  }

  private generateIndexPage(docs: Document[], options: ExportOptions): string {
    const sidebarHtml = this.generateSidebarHtml(docs, 'index');

    const recentDocs = [...docs]
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
      .slice(0, 10);

    let content = `
      <h1 class="page-title">${escapeHtml(options.siteTitle || '知识库')}</h1>
      <p class="page-meta">
        共 ${docs.length} 篇文档 · 最后更新于 ${new Date().toLocaleDateString('zh-CN')}
      </p>
      
      <div class="prose">
        <p>${escapeHtml(options.siteDescription || '欢迎访问知识库')}</p>
        
        <h2>📚 最近更新</h2>
        <ul>
    `;

    recentDocs.forEach(doc => {
      const docPath = `${generateId(doc.title)}.html`;
      const date = new Date(doc.updatedAt).toLocaleDateString('zh-CN');
      content += `
          <li>
            <a href="${docPath}">${escapeHtml(doc.title)}</a>
            <span style="color: var(--muted); font-size: 0.875em; margin-left: 0.5rem;">
              (${date})
            </span>
          </li>
      `;
    });

    content += `
        </ul>
        
        <h2>🏷️ 标签统计</h2>
    `;

    const tagCounts: Record<string, number> = {};
    docs.forEach(doc => {
      doc.tags.forEach(tag => {
        tagCounts[tag] = (tagCounts[tag] || 0) + 1;
      });
    });

    const sortedTags = Object.entries(tagCounts).sort((a, b) => b[1] - a[1]);

    content += '<div>';
    sortedTags.forEach(([tag, count]) => {
      content += `
        <span class="tag" style="margin-right: 0.5rem; margin-bottom: 0.5rem;">
          #${escapeHtml(tag)} (${count})
        </span>
      `;
    });
    content += '</div></div>';

    content += `
      <div class="footer">
        <p>使用 KnowledgeForge 生成 · ${new Date().getFullYear()}</p>
      </div>
    `;

    return this.generatePageHtml('首页', content, sidebarHtml, 'index', options);
  }

  private async generateDocumentPage(
    doc: Document,
    allDocs: Document[],
    options: ExportOptions
  ): Promise<string> {
    const sidebarHtml = this.generateSidebarHtml(allDocs, doc.id);
    const content = await this.renderDocument(doc, allDocs);

    const backlinks = allDocs.filter(d => {
      const regex = new RegExp(`\\[\\[${doc.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\]`);
      return regex.test(d.content || '');
    });

    let pageContent = `
      <h1 class="page-title">${escapeHtml(doc.title)}</h1>
      <div class="page-meta">
        <span>📅 更新于 ${new Date(doc.updatedAt).toLocaleDateString('zh-CN')}</span>
        <span>📝 ${doc.wordCount.toLocaleString()} 字</span>
        ${doc.tags.length > 0 ? doc.tags.map(t => `<span class="tag">#${escapeHtml(t)}</span>`).join('') : ''}
      </div>
      
      <article class="prose">
        ${content}
      </article>
    `;

    if (backlinks.length > 0) {
      pageContent += `
        <div class="backlinks">
          <h3 class="backlinks-title">🔗 反向链接 (${backlinks.length})</h3>
          <ul class="backlinks-list">
      `;

      for (const bl of backlinks) {
        const blPath = `${generateId(bl.title)}.html`;
        const regex = new RegExp(`\\[\\[${doc.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\]`);
        const match = bl.content?.match(new RegExp(`.{0,50}${regex.source}.{0,50}`));
        const context = match ? match[0] : '';

        pageContent += `
          <li class="backlink-item">
            <a href="${blPath}" class="backlink-title">${escapeHtml(bl.title)}</a>
            <div class="backlink-context">${escapeHtml(context)}</div>
          </li>
        `;
      }

      pageContent += `
          </ul>
        </div>
      `;
    }

    pageContent += `
      <div class="footer">
        <p>使用 KnowledgeForge 生成</p>
      </div>
    `;

    return this.generatePageHtml(doc.title, pageContent, sidebarHtml, doc.id, options);
  }

  async exportStaticSite(documents: Document[], options: ExportOptions): Promise<void> {
    this.reportProgress(0, documents.length + 3, '开始导出...');

    await this.ensureDir(options.outputPath);

    let filteredDocs = documents;

    if (options.includeTags && options.includeTags.length > 0) {
      filteredDocs = filteredDocs.filter(doc =>
        doc.tags.some(tag => options.includeTags!.includes(tag))
      );
    }

    if (options.excludeTags && options.excludeTags.length > 0) {
      filteredDocs = filteredDocs.filter(doc =>
        !doc.tags.some(tag => options.excludeTags!.includes(tag))
      );
    }

    this.reportProgress(1, documents.length + 3, '生成首页...');
    const indexHtml = this.generateIndexPage(filteredDocs, options);
    await fs.writeFile(path.join(options.outputPath, 'index.html'), indexHtml, 'utf-8');

    this.reportProgress(2, documents.length + 3, '生成知识图谱页...');
    const graphHtml = this.generateGraphPage(filteredDocs, options);
    await fs.writeFile(path.join(options.outputPath, 'graph.html'), graphHtml, 'utf-8');

    for (let i = 0; i < filteredDocs.length; i++) {
      const doc = filteredDocs[i];
      this.reportProgress(i + 3, documents.length + 3, `生成文档: ${doc.title}`);

      try {
        const docHtml = await this.generateDocumentPage(doc, filteredDocs, options);
        const docPath = path.join(options.outputPath, `${generateId(doc.title)}.html`);
        await fs.writeFile(docPath, docHtml, 'utf-8');
      } catch (error) {
        console.error(`Failed to export document ${doc.title}:`, error);
      }
    }

    this.reportProgress(documents.length + 3, documents.length + 3, '导出完成！');
  }
}
