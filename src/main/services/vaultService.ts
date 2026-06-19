import chokidar from 'chokidar';
import fs from 'fs';
import path from 'path';
import os from 'os';
import matter from 'gray-matter';
import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { SearchService } from './searchService';
import type { Note, NoteLink } from '../../shared/types';
import { BrowserWindow, dialog, shell } from 'electron';

let watcher: chokidar.FSWatcher | null = null;
let vaultPath: string = '';
let watcherError: string | null = null;
let isWatching: boolean = false;

const WIKILINK_REGEX = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;

const PERMISSION_ERROR_CODES = ['EACCES', 'EPERM', 'ELOOP', 'ENOSPC', 'EMFILE'];

const MACOS_PROTECTED_PATHS = [
  'Documents',
  'Downloads',
  'Desktop',
  'Movies',
  'Music',
  'Pictures',
];

export const VaultService = {
  init(vault: string) {
    vaultPath = vault;
    watcherError = null;
    isWatching = false;
    
    if (watcher) {
      watcher.close();
      watcher = null;
    }
    
    watcher = chokidar.watch('**/*.md', {
      cwd: vaultPath,
      ignoreInitial: false,
      depth: 10,
      ignored: [
        /(^|[\/\\])\../,
        'node_modules',
        '.git',
        '.trash',
        '.obsidian',
      ],
    });
    
    watcher.on('add', (filePath) => this.handleFileAdd(filePath));
    watcher.on('change', (filePath) => this.handleFileChange(filePath));
    watcher.on('unlink', (filePath) => this.handleFileDelete(filePath));
    watcher.on('ready', () => {
      console.log('Vault watcher ready');
      isWatching = true;
      watcherError = null;
    });
    
    watcher.on('error', (error) => {
      console.error('Vault watcher error:', error);
      const errorCode = (error as any).code || '';
      const isPermissionError = PERMISSION_ERROR_CODES.includes(errorCode);
      
      watcherError = error.message;
      isWatching = false;
      
      if (isPermissionError) {
        this.showPermissionDialog();
      }
      
      const mainWindow = BrowserWindow.getAllWindows()[0];
      if (mainWindow) {
        mainWindow.webContents.send('vault:watcher-error', {
          message: error.message,
          code: errorCode,
          isPermissionError,
        });
      }
    });
    
    return this;
  },

  getVaultPath(): string {
    return vaultPath;
  },

  setVaultPath(p: string): boolean {
    if (!fs.existsSync(p)) {
      return false;
    }
    this.init(p);
    return true;
  },

  getWatcherStatus(): { watching: boolean; error?: string } {
    return {
      watching: isWatching,
      error: watcherError || undefined,
    };
  },

  isMacOSProtectedPath(targetPath: string): boolean {
    if (process.platform !== 'darwin') {
      return false;
    }
    
    const homedir = os.homedir();
    const normalizedPath = path.normalize(targetPath);
    
    for (const protectedDir of MACOS_PROTECTED_PATHS) {
      const protectedPath = path.join(homedir, protectedDir);
      if (normalizedPath.startsWith(protectedPath + path.sep) || normalizedPath === protectedPath) {
        return true;
      }
    }
    
    return false;
  },

  isPermissionError(error: Error | NodeJS.ErrnoException): boolean {
    const code = (error as any).code || '';
    return PERMISSION_ERROR_CODES.includes(code);
  },

  checkPermissions(targetPath: string): { 
    accessible: boolean; 
    isProtectedPath: boolean; 
    error?: string 
  } {
    const isProtected = this.isMacOSProtectedPath(targetPath);
    
    try {
      fs.accessSync(targetPath, fs.constants.R_OK | fs.constants.W_OK);
      return {
        accessible: true,
        isProtectedPath: isProtected,
      };
    } catch (err: any) {
      return {
        accessible: false,
        isProtectedPath: isProtected,
        error: err.message,
      };
    }
  },

  async showPermissionDialog(): Promise<void> {
    const mainWindow = BrowserWindow.getAllWindows()[0];
    if (!mainWindow) return;
    
    const result = await dialog.showMessageBox(mainWindow, {
      type: 'warning',
      title: '磁盘访问权限不足',
      message: '应用需要完全磁盘访问权限才能正常监视文件变化',
      detail: '您的 vault 位于受保护的文件夹中。请在系统设置中授予本应用完全磁盘访问权限，或选择不受保护的文件夹作为 vault 位置。\n\n如何授予权限：\n1. 打开系统设置 > 隐私与安全性 > 完全磁盘访问\n2. 或 打开系统设置 > 隐私与安全性 > 文件与文件夹\n3. 开启本应用的开关',
      buttons: ['打开系统设置', '稍后再说'],
      defaultId: 0,
      cancelId: 1,
    });
    
    if (result.response === 0) {
      this.openSystemSettings();
    }
  },

  openSystemSettings(): void {
    if (process.platform === 'darwin') {
      shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles');
    }
  },

  async requestPermissions(targetPath: string): Promise<boolean> {
    const result = this.checkPermissions(targetPath);
    
    if (result.accessible) {
      return true;
    }
    
    if (this.isMacOSProtectedPath(targetPath)) {
      await this.showPermissionDialog();
    }
    
    return false;
  },

  rescan() {
    const notes = NoteService.getAll();
    SearchService.init(notes);
  },

  handleFileAdd(relativePath: string) {
    const fullPath = path.join(vaultPath, relativePath);
    try {
      const note = this.parseMarkdownFile(fullPath, relativePath);
      const existing = NoteService.getByPath(relativePath);
      
      let savedNote: Note;
      if (existing) {
        savedNote = NoteService.update(existing.id, {
          title: note.title,
          content: note.content,
          frontmatter: note.frontmatter,
          tags: note.tags,
        })!;
      } else {
        savedNote = NoteService.create({
          title: note.title,
          path: relativePath,
          content: note.content,
          frontmatter: note.frontmatter,
          tags: note.tags,
        });
      }
      
      this.extractAndSaveLinks(savedNote);
      SearchService.addNote(savedNote);
      
      return savedNote;
    } catch (err) {
      console.error(`Error adding file ${relativePath}:`, err);
      return null;
    }
  },

  handleFileChange(relativePath: string) {
    const fullPath = path.join(vaultPath, relativePath);
    try {
      const note = this.parseMarkdownFile(fullPath, relativePath);
      const existing = NoteService.getByPath(relativePath);
      
      if (existing) {
        const updated = NoteService.update(existing.id, {
          title: note.title,
          content: note.content,
          frontmatter: note.frontmatter,
          tags: note.tags,
        })!;
        
        LinkService.clearLinksForNote(existing.id);
        this.extractAndSaveLinks(updated);
        SearchService.updateNote(updated);
        
        return updated;
      }
      return null;
    } catch (err) {
      console.error(`Error updating file ${relativePath}:`, err);
      return null;
    }
  },

  handleFileDelete(relativePath: string) {
    try {
      const note = NoteService.getByPath(relativePath);
      if (note) {
        NoteService.delete(note.id);
        SearchService.removeNote(note.id);
      }
    } catch (err) {
      console.error(`Error deleting file ${relativePath}:`, err);
    }
  },

  parseMarkdownFile(fullPath: string, relativePath: string): {
    title: string;
    content: string;
    frontmatter: Record<string, any>;
    tags: string[];
  } {
    const rawContent = fs.readFileSync(fullPath, 'utf-8');
    const { data: frontmatter, content } = matter(rawContent);
    
    const title = frontmatter.title || this.extractTitle(content) || path.basename(relativePath, '.md');
    const tags = frontmatter.tags || [];
    
    return {
      title,
      content,
      frontmatter,
      tags: Array.isArray(tags) ? tags : [tags],
    };
  },

  extractTitle(content: string): string {
    const match = content.match(/^#\s+(.+)$/m);
    return match ? match[1].trim() : '';
  },

  extractAndSaveLinks(note: Note) {
    const content = note.content;
    const links: NoteLink[] = [];
    
    const noteDir = path.dirname(note.path);
    
    let match;
    WIKILINK_REGEX.lastIndex = 0;
    
    while ((match = WIKILINK_REGEX.exec(content)) !== null) {
      const targetName = match[1].trim();
      const linkText = (match[2] || targetName).trim();
      
      let targetPath = '';
      if (targetName.endsWith('.md')) {
        targetPath = path.join(noteDir, targetName);
      } else {
        targetPath = path.join(noteDir, targetName + '.md');
      }
      targetPath = path.normalize(targetPath);
      
      const context = this.extractLinkContext(content, match.index, 100);
      
      const targetNote = NoteService.getByPath(targetPath);
      
      links.push({
        id: '',
        sourceId: note.id,
        targetId: targetNote?.id || '',
        sourcePath: note.path,
        targetPath: targetPath,
        linkText,
        context,
        createdAt: Date.now(),
      });
    }
    
    LinkService.clearLinksForNote(note.id);
    
    for (const link of links) {
      LinkService.addLink(link);
    }
    
    if (links.length > 0) {
      LinkService.updateTargetIdsForPath(note.path, note.id);
    }
    
    return links;
  },

  extractLinkContext(content: string, linkIndex: number, surrounding: number = 80): string {
    const start = Math.max(0, linkIndex - surrounding);
    const end = Math.min(content.length, linkIndex + surrounding);
    
    let context = content.slice(start, end);
    context = context.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
    
    if (start > 0) context = '...' + context;
    if (end < content.length) context = context + '...';
    
    return context;
  },

  close() {
    if (watcher) {
      watcher.close();
      watcher = null;
    }
    isWatching = false;
    watcherError = null;
  },
  
  getAllFiles(): string[] {
    const allNotes = NoteService.getAll();
    return allNotes.map(n => n.path);
  },
};
