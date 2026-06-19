import fs from 'fs';
import path from 'path';
import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { app, dialog } from 'electron';
import { marked } from 'marked';

export const ExportService = {
  async exportNote(id: string, format: 'txt' | 'html' | 'pdf'): Promise<string> {
    const note = NoteService.getById(id);
    if (!note) {
      throw new Error('Note not found');
    }
    
    const defaultName = `${note.title.replace(/[<>:"/\\|?*]/g, '_')}.${format}`;
    
    const result = await dialog.showSaveDialog({
      title: `导出为 ${format.toUpperCase()}`,
      defaultPath: defaultName,
      filters: this.getFiltersForFormat(format),
    });
    
    if (result.canceled || !result.filePath) {
      return '';
    }
    
    const outputPath = result.filePath;
    
    switch (format) {
      case 'txt':
        return this.exportTxt(note, outputPath);
      case 'html':
        return this.exportHtml(note, outputPath);
      case 'pdf':
        return this.exportPdf(note, outputPath);
      default:
        throw new Error(`Unsupported format: ${format}`);
    }
  },

  exportTxt(note: any, outputPath: string): string {
    let content = '';
    if (note.frontmatter && Object.keys(note.frontmatter).length > 0) {
      content += '---\n';
      for (const [key, value] of Object.entries(note.frontmatter)) {
        content += `${key}: ${value}\n`;
      }
      content += '---\n\n';
    }
    content += note.content;
    
    fs.writeFileSync(outputPath, content, 'utf-8');
    return outputPath;
  },

  exportHtml(note: any, outputPath: string): string {
    const htmlContent = marked.parse(note.content) as string;
    
    const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${note.title}</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      max-width: 800px;
      margin: 40px auto;
      padding: 0 20px;
      line-height: 1.6;
      color: #333;
    }
    h1, h2, h3, h4, h5, h6 { margin-top: 1.5em; line-height: 1.2; }
    pre { background: #f5f5f5; padding: 16px; border-radius: 8px; overflow-x: auto; }
    code { background: #f0f0f0; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
    pre code { background: none; padding: 0; }
    blockquote { border-left: 4px solid #ddd; margin: 1em 0; padding-left: 16px; color: #666; }
    img { max-width: 100%; height: auto; }
    a { color: #0366d6; text-decoration: none; }
    a:hover { text-decoration: underline; }
    table { border-collapse: collapse; width: 100%; }
    th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
    th { background: #f5f5f5; }
    .frontmatter { background: #fafafa; border: 1px solid #eee; padding: 16px; border-radius: 8px; margin-bottom: 24px; }
    .frontmatter-title { font-weight: 600; margin-bottom: 8px; color: #666; font-size: 0.9em; }
    .frontmatter-item { display: flex; gap: 8px; font-size: 0.9em; }
    .frontmatter-key { color: #888; min-width: 80px; }
  </style>
</head>
<body>
${note.frontmatter && Object.keys(note.frontmatter).length > 0 ? `
  <div class="frontmatter">
    <div class="frontmatter-title">元数据</div>
    ${Object.entries(note.frontmatter).map(([key, value]) => `
      <div class="frontmatter-item">
        <span class="frontmatter-key">${key}:</span>
        <span class="frontmatter-value">${value}</span>
      </div>
    `).join('')}
  </div>
` : ''}
<article class="markdown-body">
  ${htmlContent}
</article>
</body>
</html>`;
    
    fs.writeFileSync(outputPath, html, 'utf-8');
    return outputPath;
  },

  async exportPdf(note: any, outputPath: string): Promise<string> {
    const { BrowserWindow } = require('electron');
    
    const win = new BrowserWindow({
      width: 800,
      height: 600,
      show: false,
      webPreferences: {
        offscreen: true,
      },
    });
    
    const htmlContent = marked.parse(note.content) as string;
    const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>${note.title}</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      padding: 40px;
      line-height: 1.6;
      color: #333;
    }
    h1, h2, h3, h4, h5, h6 { margin-top: 1.5em; line-height: 1.2; }
    pre { background: #f5f5f5; padding: 16px; border-radius: 8px; overflow-x: auto; }
    code { background: #f0f0f0; padding: 2px 6px; border-radius: 4px; }
    pre code { background: none; padding: 0; }
    blockquote { border-left: 4px solid #ddd; margin: 1em 0; padding-left: 16px; color: #666; }
    img { max-width: 100%; }
  </style>
</head>
<body>
  <h1>${note.title}</h1>
  ${htmlContent}
</body>
</html>`;
    
    await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
    
    const pdfBuffer = await win.webContents.printToPDF({
      printBackground: true,
      pageSize: 'A4',
      margins: {
        top: 0.75,
        bottom: 0.75,
        left: 0.75,
        right: 0.75,
      },
    });
    
    fs.writeFileSync(outputPath, pdfBuffer);
    win.close();
    
    return outputPath;
  },

  async exportDomain(noteIds: string[], format: 'markdown'): Promise<string> {
    const result = await dialog.showSaveDialog({
      title: '导出知识域',
      defaultPath: 'knowledge-domain',
      filters: [{ name: 'Markdown 打包', extensions: ['zip'] }],
    });
    
    if (result.canceled || !result.filePath) {
      return '';
    }
    
    const outputDir = path.dirname(result.filePath);
    const baseName = path.basename(result.filePath, '.zip');
    const exportDir = path.join(outputDir, baseName);
    
    if (!fs.existsSync(exportDir)) {
      fs.mkdirSync(exportDir, { recursive: true });
    }
    
    const graphData = LinkService.getGraphData();
    const domainNodes = new Set(noteIds);
    
    const queue = [...noteIds];
    const visited = new Set(noteIds);
    
    while (queue.length > 0) {
      const id = queue.shift()!;
      const neighbors = graphData.edges
        .filter(e => e.source === id || e.target === id)
        .map(e => e.source === id ? e.target : e.source);
      
      for (const neighbor of neighbors) {
        if (!visited.has(neighbor)) {
          visited.add(neighbor);
          domainNodes.add(neighbor);
          if (domainNodes.size < 50) {
            queue.push(neighbor);
          }
        }
      }
    }
    
    for (const nodeId of domainNodes) {
      const note = NoteService.getById(nodeId);
      if (note) {
        const noteDir = path.dirname(note.path);
        const fullDir = path.join(exportDir, noteDir);
        
        if (!fs.existsSync(fullDir)) {
          fs.mkdirSync(fullDir, { recursive: true });
        }
        
        const filePath = path.join(exportDir, note.path);
        let content = '';
        
        if (note.frontmatter && Object.keys(note.frontmatter).length > 0) {
          content += '---\n';
          for (const [key, value] of Object.entries(note.frontmatter)) {
            content += `${key}: ${value}\n`;
          }
          content += '---\n\n';
        }
        content += note.content;
        
        fs.writeFileSync(filePath, content, 'utf-8');
      }
    }
    
    const indexContent = `# 知识域导出\n\n共导出 ${domainNodes.size} 篇笔记\n\n## 笔记列表\n\n${[...domainNodes].map(id => {
      const note = NoteService.getById(id);
      return note ? `- [${note.title}](${note.path})` : '';
    }).filter(Boolean).join('\n')}\n`;
    
    fs.writeFileSync(path.join(exportDir, 'INDEX.md'), indexContent, 'utf-8');
    
    return exportDir;
  },

  async exportGraphPNG(svgData: string): Promise<string> {
    const result = await dialog.showSaveDialog({
      title: '导出图谱为 PNG',
      defaultPath: 'knowledge-graph.png',
      filters: [{ name: 'PNG 图片', extensions: ['png'] }],
    });
    
    if (result.canceled || !result.filePath) {
      return '';
    }
    
    const { BrowserWindow } = require('electron');
    
    const win = new BrowserWindow({
      width: 1200,
      height: 800,
      show: false,
      webPreferences: {
        offscreen: true,
      },
    });
    
    const html = `<!DOCTYPE html>
<html>
<head>
  <style>
    body { margin: 0; padding: 0; background: white; }
    #svg-container { width: 100%; height: 100%; }
    svg { width: 100%; height: 100%; display: block; }
  </style>
</head>
<body>
  <div id="svg-container">${svgData}</div>
</body>
</html>`;
    
    await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
    
    const pngDataUrl = await win.webContents.executeJavaScript(`
      (function() {
        return new Promise((resolve, reject) => {
          const container = document.getElementById('svg-container');
          const svg = container.querySelector('svg');
          
          if (!svg) {
            reject(new Error('SVG element not found'));
            return;
          }
          
          const svgRect = svg.getBoundingClientRect();
          const width = svgRect.width;
          const height = svgRect.height;
          
          const svgData = new XMLSerializer().serializeToString(svg);
          const svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
          const url = URL.createObjectURL(svgBlob);
          
          const img = new Image();
          img.onload = function() {
            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;
            
            const ctx = canvas.getContext('2d');
            if (!ctx) {
              URL.revokeObjectURL(url);
              reject(new Error('Canvas context not available'));
              return;
            }
            
            ctx.fillStyle = 'white';
            ctx.fillRect(0, 0, width, height);
            ctx.drawImage(img, 0, 0, width, height);
            
            const dataUrl = canvas.toDataURL('image/png');
            URL.revokeObjectURL(url);
            resolve(dataUrl);
          };
          
          img.onerror = function() {
            URL.revokeObjectURL(url);
            reject(new Error('Failed to load SVG image'));
          };
          
          img.src = url;
        });
      })();
    `);
    
    const base64Data = pngDataUrl.replace(/^data:image\/png;base64,/, '');
    const pngBuffer = Buffer.from(base64Data, 'base64');
    
    fs.writeFileSync(result.filePath, pngBuffer);
    win.close();
    
    return result.filePath;
  },

  getFiltersForFormat(format: string): any[] {
    switch (format) {
      case 'txt':
        return [{ name: '文本文件', extensions: ['txt'] }];
      case 'html':
        return [{ name: 'HTML 文件', extensions: ['html'] }];
      case 'pdf':
        return [{ name: 'PDF 文件', extensions: ['pdf'] }];
      default:
        return [];
    }
  },
};
