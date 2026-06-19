import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { SearchService } from './searchService';
import type { Note, LinkSuggestion, BrokenLink } from '../../shared/types';

function levenshteinDistance(str1: string, str2: string): number {
  const s1 = str1.toLowerCase();
  const s2 = str2.toLowerCase();
  
  if (s1 === s2) return 0;
  if (s1.length === 0) return s2.length;
  if (s2.length === 0) return s1.length;
  
  const matrix: number[][] = [];
  
  for (let i = 0; i <= s2.length; i++) {
    matrix[i] = [i];
  }
  
  for (let j = 0; j <= s1.length; j++) {
    matrix[0][j] = j;
  }
  
  for (let i = 1; i <= s2.length; i++) {
    for (let j = 1; j <= s1.length; j++) {
      const cost = s1[j - 1] === s2[i - 1] ? 0 : 1;
      matrix[i][j] = Math.min(
        matrix[i - 1][j] + 1,
        matrix[i][j - 1] + 1,
        matrix[i - 1][j - 1] + cost
      );
    }
  }
  
  return matrix[s2.length][s1.length];
}

function levenshteinSimilarity(str1: string, str2: string): number {
  const distance = levenshteinDistance(str1, str2);
  const maxLength = Math.max(str1.length, str2.length);
  if (maxLength === 0) return 1;
  return 1 - distance / maxLength;
}

function jaccardSimilarity(str1: string, str2: string): number {
  const s1 = new Set(str1.toLowerCase().split(/\s+/).filter(w => w.length > 0));
  const s2 = new Set(str2.toLowerCase().split(/\s+/).filter(w => w.length > 0));
  
  if (s1.size === 0 && s2.size === 0) return 1;
  if (s1.size === 0 || s2.size === 0) return 0;
  
  const intersection = new Set([...s1].filter(x => s2.has(x)));
  const union = new Set([...s1, ...s2]);
  
  return intersection.size / union.size;
}

function combinedSimilarity(str1: string, str2: string): number {
  const levenshtein = levenshteinSimilarity(str1, str2);
  const jaccard = jaccardSimilarity(str1, str2);
  return levenshtein * 0.6 + jaccard * 0.4;
}

const WIKILINK_REGEX = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;

function extractWikiLinks(content: string): Array<{
  target: string;
  displayText: string;
  startIndex: number;
  endIndex: number;
  originalLink: string;
}> {
  const links: Array<{
    target: string;
    displayText: string;
    startIndex: number;
    endIndex: number;
    originalLink: string;
  }> = [];
  let match;
  
  WIKILINK_REGEX.lastIndex = 0;
  
  while ((match = WIKILINK_REGEX.exec(content)) !== null) {
    const target = match[1].trim();
    const displayText = (match[2] || match[1]).trim();
    const originalLink = match[2] ? `[[${target}|${displayText}]]` : `[[${target}]]`;
    
    links.push({
      target,
      displayText,
      startIndex: match.index,
      endIndex: match.index + match[0].length,
      originalLink,
    });
  }
  
  return links;
}

function extractLinkContext(content: string, linkIndex: number, surrounding: number = 80): string {
  const start = Math.max(0, linkIndex - surrounding);
  const end = Math.min(content.length, linkIndex + surrounding);
  
  let context = content.slice(start, end);
  context = context.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
  
  if (start > 0) context = '...' + context;
  if (end < content.length) context = context + '...';
  
  return context;
}

export const LinkRepairService = {
  findSimilarNotes(title: string, threshold: number = 0.6): LinkSuggestion[] {
    const allNotes = NoteService.getAll();
    const results: LinkSuggestion[] = [];
    
    for (const note of allNotes) {
      const similarity = combinedSimilarity(title, note.title);
      
      if (similarity >= threshold) {
        results.push({
          noteId: note.id,
          title: note.title,
          path: note.path,
          similarity,
          similarityType: 'combined',
        });
      }
    }
    
    results.sort((a, b) => b.similarity - a.similarity);
    return results.slice(0, 5);
  },

  scanBrokenLinks(noteId?: string): BrokenLink[] {
    const notes = noteId 
      ? [NoteService.getById(noteId)].filter(Boolean) as Note[]
      : NoteService.getAll();
    
    const allNotes = NoteService.getAll();
    const brokenLinks: BrokenLink[] = [];
    
    for (const note of notes) {
      const links = extractWikiLinks(note.content);
      const noteDir = note.path.split('/').slice(0, -1).join('/');
      
      for (const link of links) {
        let targetPath = '';
        if (link.target.endsWith('.md')) {
          targetPath = noteDir ? `${noteDir}/${link.target}` : link.target;
        } else {
          targetPath = noteDir ? `${noteDir}/${link.target}.md` : `${link.target}.md`;
        }
        
        const targetNote = allNotes.find(n => 
          n.path === targetPath || 
          n.title.toLowerCase() === link.target.toLowerCase()
        );
        
        if (!targetNote) {
          const suggestions = this.findSimilarNotes(link.target);
          const context = extractLinkContext(note.content, link.startIndex, 100);
          
          brokenLinks.push({
            id: `${note.id}-${link.startIndex}`,
            sourceId: note.id,
            sourcePath: note.path,
            targetText: link.target,
            originalLink: link.originalLink,
            displayText: link.displayText,
            context,
            suggestions,
          });
        }
      }
    }
    
    return brokenLinks;
  },

  updateLinkTarget(sourceNoteId: string, oldTarget: string, newTargetId: string): { success: boolean; newContent?: string } {
    const sourceNote = NoteService.getById(sourceNoteId);
    if (!sourceNote) return { success: false };
    
    const newTargetNote = NoteService.getById(newTargetId);
    if (!newTargetNote) return { success: false };
    
    const links = extractWikiLinks(sourceNote.content);
    let newContent = sourceNote.content;
    let updated = false;
    
    for (let i = links.length - 1; i >= 0; i--) {
      const link = links[i];
      if (link.target === oldTarget) {
        const newTargetTitle = newTargetNote.title;
        const newLinkText = link.displayText !== link.target 
          ? `[[${newTargetTitle}|${link.displayText}]]`
          : `[[${newTargetTitle}]]`;
        
        newContent = 
          newContent.slice(0, link.startIndex) + 
          newLinkText + 
          newContent.slice(link.endIndex);
        
        updated = true;
      }
    }
    
    if (updated) {
      const updatedNote = NoteService.update(sourceNoteId, {
        content: newContent,
      });
      
      if (updatedNote) {
        LinkService.clearLinksForNote(sourceNoteId);
        this.extractAndSaveLinks(updatedNote);
        SearchService.updateNote(updatedNote);
        return { success: true, newContent };
      }
    }
    
    return { success: false };
  },

  extractAndSaveLinks(note: Note) {
    const content = note.content;
    const WIKILINK_REGEX_LOCAL = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;
    const links: any[] = [];
    const path = require('path');
    
    const noteDir = path.dirname(note.path);
    
    let match;
    WIKILINK_REGEX_LOCAL.lastIndex = 0;
    
    while ((match = WIKILINK_REGEX_LOCAL.exec(content)) !== null) {
      const targetName = match[1].trim();
      const linkText = (match[2] || targetName).trim();
      
      let targetPath = '';
      if (targetName.endsWith('.md')) {
        targetPath = path.join(noteDir, targetName);
      } else {
        targetPath = path.join(noteDir, targetName + '.md');
      }
      targetPath = path.normalize(targetPath);
      
      const context = extractLinkContext(content, match.index, 100);
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
    
    for (const link of links) {
      LinkService.addLink(link);
    }
    
    if (links.length > 0) {
      LinkService.updateTargetIdsForPath(note.path, note.id);
    }
    
    return links;
  },

  migrateBacklinks(oldNoteId: string, newNoteId: string): number {
    const backlinks = LinkService.getBacklinks(oldNoteId);
    let migratedCount = 0;
    
    const newNote = NoteService.getById(newNoteId);
    if (!newNote) return 0;
    
    for (const backlink of backlinks) {
      const sourceNote = NoteService.getById(backlink.sourceId);
      if (!sourceNote) continue;
      
      const oldTargetTitle = backlink.linkText;
      const success = this.updateLinkTarget(sourceNote.id, oldTargetTitle, newNoteId);
      if (success) {
        migratedCount++;
      }
    }
    
    return migratedCount;
  },
};
