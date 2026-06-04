import { EditorState } from '@codemirror/state';
import { EditorView, Decoration, DecorationSet, ViewPlugin, ViewUpdate } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { Range } from '@codemirror/state';

const wikilinkRegex = /\[\[([^\]]+)\]\]/g;

export const wikilinkStyle = EditorView.baseTheme({
  '.cm-wikilink': {
    color: 'var(--primary-color, #3b82f6)',
    textDecoration: 'underline',
    textDecorationStyle: 'dotted',
    cursor: 'pointer',
  },
  '.cm-wikilink:hover': {
    color: 'var(--primary-hover-color, #2563eb)',
    textDecorationStyle: 'solid',
  },
  '.cm-tag': {
    color: 'var(--accent-color, #8b5cf6)',
    backgroundColor: 'color-mix(in srgb, var(--accent-color) 15%, transparent)',
    padding: '0 4px',
    borderRadius: '3px',
  },
});

function parseWikilinks(doc: string): Range<Decoration>[] {
  const decorations: Range<Decoration>[] = [];
  let match: RegExpExecArray | null;
  wikilinkRegex.lastIndex = 0;

  while ((match = wikilinkRegex.exec(doc)) !== null) {
    const from = match.index;
    const to = match.index + match[0].length;
    const target = match[1];

    const decoration = Decoration.mark({
      class: 'cm-wikilink',
      attributes: {
        'data-wikilink': target,
        title: `打开: ${target}`,
      },
    });

    decorations.push(decoration.range(from, to));
  }

  return decorations.sort((a, b) => a.from - b.from);
}

function parseTags(doc: string): Range<Decoration>[] {
  const decorations: Range<Decoration>[] = [];
  const tagRegex = /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g;
  let match: RegExpExecArray | null;

  while ((match = tagRegex.exec(doc)) !== null) {
    const from = match.index;
    const to = match.index + match[0].length;

    const lineStart = doc.lastIndexOf('\n', from) + 1;
    const lineEnd = doc.indexOf('\n', to);
    const line = doc.slice(lineStart, lineEnd === -1 ? undefined : lineEnd);
    const codeBlockStart = line.indexOf('```');
    if (codeBlockStart !== -1 && codeBlockStart < from - lineStart) continue;

    const decoration = Decoration.mark({
      class: 'cm-tag',
      attributes: {
        'data-tag': match[1],
      },
    });

    decorations.push(decoration.range(from, to));
  }

  return decorations.sort((a, b) => a.from - b.from);
}

export const wikilinkHighlight = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
      this.decorations = this.buildDecorations(view);
    }

    update(update: ViewUpdate) {
      if (update.docChanged || update.viewportChanged) {
        this.decorations = this.buildDecorations(update.view);
      }
    }

    buildDecorations(view: EditorView): DecorationSet {
      const doc = view.state.doc.toString();
      const wikilinks = parseWikilinks(doc);
      const tags = parseTags(doc);
      const all = [...wikilinks, ...tags].sort((a, b) => a.from - b.from);
      return Decoration.set(all, true);
    }
  },
  {
    decorations: (v) => v.decorations,
  }
);

export interface WikilinkClickHandler {
  (target: string): void;
}

export function wikilinkClickHandler(handler: WikilinkClickHandler) {
  return EditorView.domEventHandlers({
    click(event, view) {
      const target = event.target as HTMLElement;
      if (target.classList.contains('cm-wikilink')) {
        const linkTarget = target.getAttribute('data-wikilink');
        if (linkTarget) {
          event.preventDefault();
          handler(linkTarget);
        }
      }
    },
  });
}

export function createWikilink(state: EditorState, target: string): string {
  return `[[${target}]]`;
}

export function extractWikilinkAtCursor(state: EditorState): string | null {
  const pos = state.selection.main.head;
  const line = state.doc.lineAt(pos);
  const lineText = line.text;
  const posInLine = pos - line.from;

  const beforeText = lineText.slice(0, posInLine);
  const afterText = lineText.slice(posInLine);

  const openBracket = beforeText.lastIndexOf('[[');
  if (openBracket === -1) return null;

  const closeBracket = afterText.indexOf(']]');
  if (closeBracket === -1) return null;

  const target = lineText.slice(openBracket + 2, posInLine + closeBracket);
  return target;
}
