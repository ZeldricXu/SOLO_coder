import * as fs from 'fs/promises';
import * as path from 'path';
import * as JSZip from 'jszip';
import type { Document } from '@shared/types';
import { generateDocId, parseTitle, parseTags, parseWikiLinks, countWords } from '@shared/utils/markdown';
import { isMarkdownFile, joinPaths } from '@shared/utils/path';

export interface ImportOptions {
  overwriteExisting?: boolean;
  importAttachments?: boolean;
  convertInternalLinks?: boolean;
  preserveTags?: boolean;
}

export interface ImportResult {
  success: boolean;
  imported: number;
  skipped: number;
  failed: number;
  errors: string[];
  documents: Document[];
}

export interface ImportedFile {
  path: string;
  content: string;
  title: string;
  tags: string[];
}

export class ImportService {
  private repoPath: string;

  constructor(repoPath: string) {
    this.repoPath = repoPath;
  }

  async importFromZip(
    zipPath: string,
    source: 'notion' | 'yuque' | 'markdown',
    options: ImportOptions = {}
  ): Promise<ImportResult> {
    const {
      overwriteExisting = false,
      importAttachments = true,
      convertInternalLinks = true,
      preserveTags = true,
    } = options;

    const result: ImportResult = {
      success: true,
      imported: 0,
      skipped: 0,
      failed: 0,
      errors: [],
      documents: [],
    };

    try {
      const zipData = await fs.readFile(zipPath);
      const zip = await JSZip.loadAsync(zipData);

      const markdownFiles: ImportedFile[] = [];
      const attachmentFiles: Map<string, Buffer> = new Map();

      for (const [relativePath, zipEntry] of Object.entries(zip.files)) {
        if (zipEntry.dir) continue;

        const filePath = relativePath.replace(/^\/+/, '');
        
        if (isMarkdownFile(filePath)) {
          try {
            const content = await zipEntry.async('string');
            const imported = await this.parseMarkdownFile(filePath, content, source);
            markdownFiles.push(imported);
          } catch (error) {
            result.errors.push(`解析文件失败 ${filePath}: ${error}`);
            result.failed++;
          }
        } else if (importAttachments && this.isAttachment(filePath)) {
          try {
            const data = await zipEntry.async('nodebuffer');
            attachmentFiles.set(filePath, data);
          } catch (error) {
            result.errors.push(`读取附件失败 ${filePath}: ${error}`);
          }
        }
      }

      const titleToPath = new Map<string, string>();
      markdownFiles.forEach(file => {
        titleToPath.set(file.title, file.path);
      });

      for (const file of markdownFiles) {
        try {
          let content = file.content;
          
          if (convertInternalLinks) {
            content = this.convertInternalLinks(content, titleToPath, source);
          }

          if (source === 'notion') {
            content = this.cleanNotionContent(content);
          } else if (source === 'yuque') {
            content = this.cleanYuqueContent(content);
          }

          const doc = await this.saveImportedDocument(
            file,
            content,
            overwriteExisting,
            preserveTags
          );

          if (doc) {
            result.documents.push(doc);
            result.imported++;
          } else {
            result.skipped++;
          }
        } catch (error) {
          result.errors.push(`保存文件失败 ${file.path}: ${error}`);
          result.failed++;
        }
      }

      if (importAttachments && attachmentFiles.size > 0) {
        await this.saveAttachments(attachmentFiles);
      }

    } catch (error) {
      result.success = false;
      result.errors.push(`导入失败: ${error}`);
    }

    return result;
  }

  private async parseMarkdownFile(
    filePath: string,
    content: string,
    source: string
  ): Promise<ImportedFile> {
    let title = parseTitle(content);
    if (!title || title === 'Untitled') {
      const fileName = path.basename(filePath, path.extname(filePath));
      title = this.decodeFileName(fileName, source);
    }

    const tags = parseTags(content);

    return {
      path: filePath,
      content,
      title,
      tags,
    };
  }

  private decodeFileName(fileName: string, source: string): string {
    if (source === 'notion') {
      return fileName.replace(/\s[a-f0-9]{32}$/i, '').trim();
    }
    if (source === 'yuque') {
      return decodeURIComponent(fileName);
    }
    return fileName;
  }

  private cleanNotionContent(content: string): string {
    content = content.replace(/^#\s+.+\n\n/, match => {
      return '';
    });

    content = content.replace(/%%(.+?)%%/g, '<!-- $1 -->');

    content = content.replace(/\[([^\]]+)\]\([^)]*\/([^)]+)\.md\)/g, '[[$2|$1]]');

    return content.trim();
  }

  private cleanYuqueContent(content: string): string {
    content = content.replace(/^---\n[\s\S]*?\n---\n/, '');

    content = content.replace(/<br\s*\/?>/gi, '\n');

    content = content.replace(/<a name="[^"]*"><\/a>/gi, '');

    content = content.replace(/\[([^\]]+)\]\([^)]*#([^)]+)\)/g, '[[$2|$1]]');

    return content.trim();
  }

  private convertInternalLinks(
    content: string,
    titleToPath: Map<string, string>,
    source: string
  ): string {
    if (source === 'notion') {
      content = content.replace(/\[([^\]]+)\]\(([^)]+)\.md\)/g, (match, linkText, linkPath) => {
        const targetTitle = path.basename(linkPath, '.md');
        const decodedTitle = this.decodeFileName(targetTitle, source);
        if (titleToPath.has(decodedTitle)) {
          return `[[${decodedTitle}|${linkText}]]`;
        }
        return match;
      });
    }

    if (source === 'yuque') {
      content = content.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (match, linkText, linkPath) => {
        if (linkPath.endsWith('.md') || linkPath.includes('/') && !linkPath.startsWith('http')) {
          const targetTitle = path.basename(linkPath, '.md');
          const decodedTitle = decodeURIComponent(targetTitle);
          if (titleToPath.has(decodedTitle)) {
            return `[[${decodedTitle}|${linkText}]]`;
          }
        }
        return match;
      });
    }

    return content;
  }

  private isAttachment(filePath: string): boolean {
    const ext = path.extname(filePath).toLowerCase();
    const imageExts = ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp'];
    const docExts = ['.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx'];
    const otherExts = ['.zip', '.tar', '.gz', '.rar'];
    return [...imageExts, ...docExts, ...otherExts].includes(ext);
  }

  private async saveAttachments(files: Map<string, Buffer>): Promise<void> {
    const attachmentsDir = joinPaths(this.repoPath, 'attachments');
    try {
      await fs.access(attachmentsDir);
    } catch {
      await fs.mkdir(attachmentsDir, { recursive: true });
    }

    for (const [filePath, data] of files) {
      const fileName = path.basename(filePath);
      const targetPath = joinPaths(attachmentsDir, fileName);
      try {
        await fs.writeFile(targetPath, data);
      } catch (error) {
        console.error(`保存附件失败 ${fileName}:`, error);
      }
    }
  }

  private async saveImportedDocument(
    file: ImportedFile,
    content: string,
    overwriteExisting: boolean,
    preserveTags: boolean
  ): Promise<Document | null> {
    const docId = generateDocId(file.title);
    const filename = `${file.title}.md`;
    const filePath = joinPaths(this.repoPath, filename);

    try {
      await fs.access(filePath);
      if (!overwriteExisting) {
        return null;
      }
    } catch {
    }

    const tags = preserveTags ? file.tags : [];
    const now = new Date();
    const wordCount = countWords(content);

    const doc: Document = {
      id: docId,
      title: file.title,
      content,
      tags,
      filename,
      filePath,
      wordCount,
      hash: '',
      createdAt: now,
      updatedAt: now,
      backlinks: [],
      outline: [],
    };

    await fs.writeFile(filePath, content, 'utf-8');

    return doc;
  }

  async importFromDirectory(
    dirPath: string,
    options: ImportOptions = {}
  ): Promise<ImportResult> {
    const result: ImportResult = {
      success: true,
      imported: 0,
      skipped: 0,
      failed: 0,
      errors: [],
      documents: [],
    };

    try {
      const files = await this.scanDirectory(dirPath);
      const titleToPath = new Map<string, string>();

      for (const file of files) {
        titleToPath.set(file.title, file.path);
      }

      for (const file of files) {
        try {
          let content = file.content;
          if (options.convertInternalLinks) {
            content = this.convertInternalLinks(content, titleToPath, 'markdown');
          }

          const doc = await this.saveImportedDocument(
            file,
            content,
            options.overwriteExisting || false,
            options.preserveTags !== false
          );

          if (doc) {
            result.documents.push(doc);
            result.imported++;
          } else {
            result.skipped++;
          }
        } catch (error) {
          result.errors.push(`导入文件失败 ${file.path}: ${error}`);
          result.failed++;
        }
      }
    } catch (error) {
      result.success = false;
      result.errors.push(`目录导入失败: ${error}`);
    }

    return result;
  }

  private async scanDirectory(dirPath: string, basePath: string = ''): Promise<ImportedFile[]> {
    const files: ImportedFile[] = [];
    const entries = await fs.readdir(dirPath, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = joinPaths(dirPath, entry.name);
      const relativePath = joinPaths(basePath, entry.name);

      if (entry.isDirectory()) {
        if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
        const subFiles = await this.scanDirectory(fullPath, relativePath);
        files.push(...subFiles);
      } else if (isMarkdownFile(entry.name)) {
        try {
          const content = await fs.readFile(fullPath, 'utf-8');
          const title = parseTitle(content) || path.basename(entry.name, '.md');
          const tags = parseTags(content);
          
          files.push({
            path: relativePath,
            content,
            title,
            tags,
          });
        } catch (error) {
          console.error(`读取文件失败 ${fullPath}:`, error);
        }
      }
    }

    return files;
  }
}
