import FlexSearch from 'flexsearch';
import type { Note, SearchResult, SearchOptions } from '@shared/types';

let index: any = null;
let notesCache: Map<string, Note> = new Map();

const createSearchIndex = () => {
  const Document = (FlexSearch as any).Document || FlexSearch;
  return new Document({
    id: 'id',
    tag: 'tags',
    field: [
      { name: 'title', boost: 3 },
      { name: 'content', boost: 1 },
      { name: 'tags', boost: 2 },
    ],
    tokenize: 'forward',
    cache: true,
    suggest: true,
  });
};

interface InitMessage {
  type: 'init';
  notes: Note[];
}

interface SearchMessage {
  type: 'search';
  query: string;
  options?: SearchOptions;
  requestId: string;
}

interface AddNoteMessage {
  type: 'addNote';
  note: Note;
}

interface UpdateNoteMessage {
  type: 'updateNote';
  note: Note;
}

interface RemoveNoteMessage {
  type: 'removeNote';
  id: string;
}

type WorkerMessage = InitMessage | SearchMessage | AddNoteMessage | UpdateNoteMessage | RemoveNoteMessage;

self.onmessage = function (e: MessageEvent<WorkerMessage>) {
  const msg = e.data;

  if (msg.type === 'init') {
    initIndex(msg.notes);
  } else if (msg.type === 'search') {
    const results = performSearch(msg.query, msg.options);
    self.postMessage({
      type: 'searchResults',
      results,
      requestId: msg.requestId,
    });
  } else if (msg.type === 'addNote') {
    addNoteToIndex(msg.note);
  } else if (msg.type === 'updateNote') {
    updateNoteInIndex(msg.note);
  } else if (msg.type === 'removeNote') {
    removeNoteFromIndex(msg.id);
  }
};

function initIndex(notes: Note[]) {
  index = createSearchIndex();
  notesCache.clear();

  for (const note of notes) {
    notesCache.set(note.id, note);
    index.add({
      id: note.id,
      title: note.title || '',
      content: note.content || '',
      tags: (note.tags || []).join(' '),
    });
  }
}

function addNoteToIndex(note: Note) {
  if (!index) return;
  notesCache.set(note.id, note);
  index.add({
    id: note.id,
    title: note.title,
    content: note.content,
    tags: note.tags.join(' '),
  });
}

function updateNoteInIndex(note: Note) {
  if (!index) return;
  notesCache.set(note.id, note);
  index.update({
    id: note.id,
    title: note.title,
    content: note.content,
    tags: note.tags.join(' '),
  });
}

function removeNoteFromIndex(id: string) {
  if (!index) return;
  notesCache.delete(id);
  index.remove(id);
}

function performSearch(q: string, options: SearchOptions = {}): SearchResult[] {
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
}

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

export {};
