import { create } from 'zustand';
import { createEditor, Transforms, Range, Editor, Point, BaseEditor } from 'slate';
import { withReact, ReactEditor } from 'slate-react';
import { withHistory } from 'slate-history';
import { withWikiLinks } from '../utils/slateConverter';

type EditorInstance = BaseEditor & ReactEditor;

interface EditorState {
  editorInstance: EditorInstance;
  currentNoteId: string | null;
  autocompletePos: { top: number; left: number } | null;
  autocompleteSearch: string;
  wikiLinkStart: Point | null;
  isDragging: boolean;
  cursorPosition: Point | null;
  documentContent: string;
  _subscriptions: Set<() => void>;

  setAutocompletePos: (pos: { top: number; left: number } | null) => void;
  setAutocompleteSearch: (search: string) => void;
  setWikiLinkStart: (point: Point | null) => void;
  setIsDragging: (dragging: boolean) => void;
  setCursorPosition: (point: Point | null) => void;
  updateContent: (content: string) => void;
  resetEditor: (noteId: string | null) => void;
  handleLinkAutocomplete: (editor: ReactEditor) => void;
  handleSelectLink: (target: string) => void;
  insertImage: (src: string, alt?: string) => void;
  clearSubscriptions: () => void;
  dispose: () => void;
  addSubscription: (cb: () => void) => () => void;
}

export const useEditorStore = create<EditorState>((set, get) => ({
  editorInstance: withWikiLinks(withReact(withHistory(createEditor()))) as any,
  currentNoteId: null,
  autocompletePos: null,
  autocompleteSearch: '',
  wikiLinkStart: null,
  isDragging: false,
  cursorPosition: null,
  documentContent: '',
  _subscriptions: new Set(),

  setAutocompletePos: (pos) => set({ autocompletePos: pos }),
  setAutocompleteSearch: (search) => set({ autocompleteSearch: search }),
  setWikiLinkStart: (point) => set({ wikiLinkStart: point }),
  setIsDragging: (dragging) => set({ isDragging: dragging }),
  setCursorPosition: (point) => set({ cursorPosition: point }),
  updateContent: (content) => set({ documentContent: content }),

  resetEditor: (noteId) => {
    set({
      currentNoteId: noteId,
      autocompletePos: null,
      autocompleteSearch: '',
      wikiLinkStart: null,
      cursorPosition: null,
      documentContent: '',
      isDragging: false,
    });
  },

  clearSubscriptions: () => {
    const { _subscriptions } = get();
    _subscriptions.forEach(cb => cb());
    _subscriptions.clear();
  },

  dispose: () => {
    get().clearSubscriptions();
    set({
      currentNoteId: null,
      autocompletePos: null,
      autocompleteSearch: '',
      wikiLinkStart: null,
      cursorPosition: null,
      documentContent: '',
      isDragging: false,
    });
  },

  addSubscription: (cb) => {
    const { _subscriptions } = get();
    _subscriptions.add(cb);
    return () => {
      _subscriptions.delete(cb);
    };
  },

  handleLinkAutocomplete: (editor) => {
    const { selection } = editor;
    if (!selection || !Range.isCollapsed(selection)) {
      set({ autocompletePos: null });
      return;
    }

    const { anchor } = selection;
    const { path, offset } = anchor;

    const textNode = Editor.node(editor, path)[0] as any;
    const text = textNode.text || '';
    const beforeCursor = text.slice(0, offset);

    const bracketMatch = beforeCursor.match(/\[\[([^\[\]]*)$/);

    if (bracketMatch) {
      const searchText = bracketMatch[1];
      set({ autocompleteSearch: searchText, wikiLinkStart: anchor });

      try {
        const domRange = ReactEditor.toDOMRange(editor, {
          anchor: { path, offset: offset - bracketMatch[0].length },
          focus: anchor,
        });
        const rect = domRange.getBoundingClientRect();
        set({
          autocompletePos: {
            top: rect.bottom + 4,
            left: rect.left,
          },
        });
      } catch {
        set({ autocompletePos: null });
      }
    } else {
      set({ autocompletePos: null, wikiLinkStart: null });
    }
  },

  handleSelectLink: (target) => {
    const { editorInstance, wikiLinkStart, autocompleteSearch } = get();
    if (!wikiLinkStart) return;

    const editor = editorInstance as any;
    const { selection } = editor;
    if (!selection) return;

    const { anchor } = selection;
    const startOffset = anchor.offset - autocompleteSearch.length - 2;

    Transforms.delete(editor, {
      at: {
        anchor: { ...anchor, offset: Math.max(0, startOffset) },
        focus: anchor,
      },
    });

    const linkNode = {
      type: 'wiki-link',
      target,
      displayText: target,
      children: [{ text: target }],
    };

    Transforms.insertNodes(editor, linkNode as any);
    Transforms.move(editor, { distance: 1, unit: 'offset' });

    set({ autocompletePos: null, wikiLinkStart: null });
  },

  insertImage: (src, alt = '') => {
    const { editorInstance } = get();
    const editor = editorInstance as any;
    const { selection } = editor;
    if (!selection) return;

    const imageElement = {
      type: 'image',
      src,
      alt,
      children: [{ text: '' }],
    };

    Transforms.insertNodes(editor, imageElement as any);
    Transforms.move(editor);
  },
}));
