import chokidar from 'chokidar';
import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';
import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { SearchService } from './searchService';
import type { Note, NoteLink } from '../../shared/types';

let watcher: chokidar.FSWatcher | null = null;
let vaultPath: string = '';

const WIKILINK_REGEX = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;

export const VaultService = {
  init(vault: string) {
    vaultPath = vault;
    
    if (watcher) {
      watcher.close();
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
  },
  
  getAllFiles(): string[] {
    const allNotes = NoteService.getAll();
    return allNotes.map(n => n.path);
  },
};
