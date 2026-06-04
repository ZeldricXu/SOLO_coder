import fs from 'fs/promises';
import fsSync from 'fs';
import chokidar from 'chokidar';
import { joinPaths, isPathSafe, ensureMarkdownExt, isMarkdownFile, normalizePath, getBasename, getUniqueFileName } from '@shared/utils/path';
import { EventEmitter } from 'events';

export interface FileChangeEvent {
  type: 'add' | 'change' | 'delete';
  path: string;
}

export class FileService extends EventEmitter {
  private repoPath: string;
  private watcher: chokidar.FSWatcher | null = null;

  constructor(repoPath: string) {
    super();
    this.repoPath = repoPath;
  }

  private validatePath(filePath: string): void {
    if (!isPathSafe(this.repoPath, filePath)) {
      throw new Error('Invalid path: path traversal detected');
    }
  }

  async ensureRepo(): Promise<void> {
    try {
      await fs.access(this.repoPath);
    } catch {
      await fs.mkdir(this.repoPath, { recursive: true });
    }
  }

  async readFile(filePath: string): Promise<string> {
    this.validatePath(filePath);
    const normalizedPath = normalizePath(filePath);
    return await fs.readFile(normalizedPath, 'utf-8');
  }

  async writeFile(filePath: string, content: string): Promise<string> {
    this.validatePath(filePath);
    const normalizedPath = ensureMarkdownExt(normalizePath(filePath));
    const dir = normalizePath(require('path').dirname(normalizedPath));
    
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(normalizedPath, content, 'utf-8');
    
    return normalizedPath;
  }

  async deleteFile(filePath: string): Promise<void> {
    this.validatePath(filePath);
    const normalizedPath = normalizePath(filePath);
    await fs.unlink(normalizedPath);
  }

  async renameFile(oldPath: string, newPath: string): Promise<string> {
    this.validatePath(oldPath);
    this.validatePath(newPath);
    const normalizedOld = normalizePath(oldPath);
    const normalizedNew = ensureMarkdownExt(normalizePath(newPath));
    await fs.rename(normalizedOld, normalizedNew);
    return normalizedNew;
  }

  async exists(filePath: string): Promise<boolean> {
    try {
      this.validatePath(filePath);
      await fs.access(normalizePath(filePath));
      return true;
    } catch {
      return false;
    }
  }

  async listFiles(dirPath?: string): Promise<string[]> {
    const basePath = dirPath ? normalizePath(joinPaths(this.repoPath, dirPath)) : this.repoPath;
    this.validatePath(basePath);
    
    const files: string[] = [];
    
    async function scan(dir: string): Promise<void> {
      const entries = await fs.readdir(dir, { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = joinPaths(dir, entry.name);
        
        if (entry.name.startsWith('.')) continue;
        if (entry.name === 'node_modules') continue;
        
        if (entry.isDirectory()) {
          await scan(fullPath);
        } else if (entry.isFile() && isMarkdownFile(entry.name)) {
          files.push(normalizePath(fullPath));
        }
      }
    }
    
    try {
      await scan(basePath);
    } catch (e) {
      // Directory might not exist yet
    }
    
    return files;
  }

  async createDocument(title: string, content: string, subDir?: string): Promise<string> {
    const baseName = getBasename(title).toLowerCase().replace(/\s+/g, '-');
    const dir = subDir ? joinPaths(this.repoPath, subDir) : this.repoPath;
    await fs.mkdir(dir, { recursive: true });
    
    const fileName = getUniqueFileName(dir, baseName);
    const fullPath = joinPaths(dir, fileName);
    
    await fs.writeFile(fullPath, content, 'utf-8');
    return fullPath;
  }

  async readDir(dirPath?: string): Promise<Array<{ name: string; path: string; type: 'file' | 'directory' }>> {
    const basePath = dirPath ? normalizePath(joinPaths(this.repoPath, dirPath)) : this.repoPath;
    this.validatePath(basePath);
    
    const entries = await fs.readdir(basePath, { withFileTypes: true });
    const result: Array<{ name: string; path: string; type: 'file' | 'directory' }> = [];
    
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue;
      if (entry.name === 'node_modules') continue;
      
      const fullPath = joinPaths(basePath, entry.name);
      const relativePath = normalizePath(require('path').relative(this.repoPath, fullPath));
      
      if (entry.isDirectory()) {
        result.push({ name: entry.name, path: relativePath, type: 'directory' });
      } else if (entry.isFile() && isMarkdownFile(entry.name)) {
        result.push({ name: entry.name, path: relativePath, type: 'file' });
      }
    }
    
    return result.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }

  async startWatcher(): Promise<void> {
    if (this.watcher) {
      await this.watcher.close();
      this.watcher = null;
    }
    
    this.watcher = chokidar.watch(this.repoPath, {
      ignored: [/(^|[\/\\])\../, 'node_modules', '.git/**'],
      ignoreInitial: true,
      awaitWriteFinish: {
        stabilityThreshold: 500,
        pollInterval: 100,
      },
    });
    
    this.watcher.on('add', (path) => {
      if (isMarkdownFile(path)) {
        this.emit('change', { type: 'add', path: normalizePath(path) } as FileChangeEvent);
      }
    });
    
    this.watcher.on('change', (path) => {
      if (isMarkdownFile(path)) {
        this.emit('change', { type: 'change', path: normalizePath(path) } as FileChangeEvent);
      }
    });
    
    this.watcher.on('unlink', (path) => {
      if (isMarkdownFile(path)) {
        this.emit('change', { type: 'delete', path: normalizePath(path) } as FileChangeEvent);
      }
    });
  }

  async stopWatcher(): Promise<void> {
    if (this.watcher) {
      await this.watcher.close();
      this.watcher = null;
    }
  }

  getRepoPath(): string {
    return this.repoPath;
  }
}
