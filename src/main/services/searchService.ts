import FlexSearch from 'flexsearch';
import type { Note, SearchResult, SearchOptions } from '../../shared/types';

let index: any = null;
let notesCache: Map<string, Note> = new Map();

export const SearchService = {
  init(notes: Note[]) {
    index = new FlexSearch.Document({
      document: {
        id: 'id',
        tag: 'tags',
        field: [
          { name: 'title', boost: 3 as any },
          { name: 'content', boost: 1 as any },
          { name: 'tags', boost: 2 as any },
        ],
      },
      tokenize: 'forward',
      cache: true,
      suggest: true,
    } as any);
    
    notesCache.clear();
    
    for (const note of notes) {
      notesCache.set(note.id, note);
      index.add({
        id: note.id,
        title: note.title,
        content: note.content,
        tags: note.tags.join(' '),
      });
    }
  },

  addNote(note: Note) {
    if (!index) return;
    notesCache.set(note.id, note);
    index.add({
      id: note.id,
      title: note.title,
      content: note.content,
      tags: note.tags.join(' '),
    });
  },

  updateNote(note: Note) {
    if (!index) return;
    notesCache.set(note.id, note);
    index.update({
      id: note.id,
      title: note.title,
      content: note.content,
      tags: note.tags.join(' '),
    });
  },

  removeNote(id: string) {
    if (!index) return;
    notesCache.delete(id);
    index.remove(id);
  },

  query(q: string, options: SearchOptions = {}): SearchResult[] {
    if (!index || !q.trim()) return [];
    
    const { fields = ['title', 'content', 'tags'], limit = 50, highlight = true } = options;
    
    const searchOptions: any = {
      limit,
      suggest: true,
      field: fields,
    };
    
    const results = index.search(q, searchOptions);
    
    const scoreMap = new Map<string, number>();
    const fieldMap = new Map<string, { title?: string; content?: string; tags?: string[] }>();
    
    for (const fieldResult of results) {
      const fieldName = fieldResult.field as string;
      for (const item of fieldResult.result) {
        const id = String(item);
        const currentScore = scoreMap.get(id) || 0;
        scoreMap.set(id, currentScore + 1);
        
        if (!fieldMap.has(id)) {
          fieldMap.set(id, {});
        }
        
        const note = notesCache.get(id);
        if (note) {
          const fields = fieldMap.get(id)!;
          if (fieldName === 'title') {
            fields.title = note.title;
          } else if (fieldName === 'content') {
            fields.content = note.content;
          } else if (fieldName === 'tags') {
            fields.tags = note.tags;
          }
        }
      }
    }
    
    const searchResults: SearchResult[] = [];
    
    for (const [id, score] of scoreMap) {
      const note = notesCache.get(id);
      if (!note) continue;
      
      const result: SearchResult = {
        id,
        title: note.title,
        path: note.path,
        score,
        fields: fieldMap.get(id) || {},
      };
      
      if (highlight) {
        result.highlight = {
          title: highlightText(note.title, q),
          content: highlightContent(note.content, q),
        };
      }
      
      searchResults.push(result);
    }
    
    return searchResults.sort((a, b) => b.score - a.score);
  },

  rebuildIndex(notes: Note[]) {
    this.init(notes);
  },
};

function highlightText(text: string, query: string): string {
  if (!query.trim()) return text;
  
  const regex = new RegExp(`(${escapeRegExp(query)})`, 'gi');
  return text.replace(regex, '[[HIGHLIGHT]]$1[[/HIGHLIGHT]]');
}

function highlightContent(content: string, query: string): string {
  const lines = content.split('\n');
  const queryLower = query.toLowerCase();
  
  let bestLine = '';
  let bestIndex = -1;
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const idx = line.toLowerCase().indexOf(queryLower);
    if (idx !== -1) {
      bestLine = line;
      bestIndex = idx;
      break;
    }
  }
  
  if (bestIndex === -1) {
    return content.slice(0, 200);
  }
  
  const start = Math.max(0, bestIndex - 50);
  const end = Math.min(bestLine.length, bestIndex + query.length + 50);
  let snippet = bestLine.slice(start, end);
  
  if (start > 0) snippet = '...' + snippet;
  if (end < bestLine.length) snippet = snippet + '...';
  
  return highlightText(snippet, query);
}

function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
