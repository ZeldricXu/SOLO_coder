import { StateField, StateEffect, EditorState, Extension } from '@codemirror/state';
import { EditorView, Decoration, DecorationSet, WidgetType } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { Range } from '@codemirror/state';

const toggleWysiwyg = StateEffect.define<boolean>();

const wysiwygState = StateField.define<{ enabled: boolean; cursorPos: number }>({
  create() {
    return { enabled: false, cursorPos: -1 };
  },
  update(value, tr) {
    for (const effect of tr.effects) {
      if (effect.is(toggleWysiwyg)) {
        return { ...value, enabled: effect.value };
      }
    }
    return {
      ...value,
      cursorPos: tr.selection?.main.head ?? -1,
    };
  },
  provide: (field) => {
    return EditorView.decorations.from(field, (value) => {
      return value.enabled ? Decoration.none : Decoration.none;
    });
  },
});

class BoldWidget extends WidgetType {
  constructor(private text: string) {
    super();
  }
  eq(other: BoldWidget) {
    return this.text === other.text;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'cm-wysiwyg-bold';
    span.style.fontWeight = 'bold';
    span.textContent = this.text;
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

class ItalicWidget extends WidgetType {
  constructor(private text: string) {
    super();
  }
  eq(other: ItalicWidget) {
    return this.text === other.text;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'cm-wysiwyg-italic';
    span.style.fontStyle = 'italic';
    span.textContent = this.text;
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

class StrikeThroughWidget extends WidgetType {
  constructor(private text: string) {
    super();
  }
  eq(other: StrikeThroughWidget) {
    return this.text === other.text;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'cm-wysiwyg-strikethrough';
    span.style.textDecoration = 'line-through';
    span.textContent = this.text;
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

class InlineCodeWidget extends WidgetType {
  constructor(private text: string) {
    super();
  }
  eq(other: InlineCodeWidget) {
    return this.text === other.text;
  }
  toDOM() {
    const code = document.createElement('code');
    code.className = 'cm-wysiwyg-inline-code';
    code.style.padding = '1px 4px';
    code.style.background = 'rgba(27, 31, 35, 0.05)';
    code.style.borderRadius = '3px';
    code.style.fontFamily = 'monospace';
    code.style.fontSize = '0.9em';
    code.textContent = this.text;
    return code;
  }
  ignoreEvent() {
    return false;
  }
}

class LinkWidget extends WidgetType {
  constructor(private text: string, private url: string) {
    super();
  }
  eq(other: LinkWidget) {
    return this.text === other.text && this.url === other.url;
  }
  toDOM() {
    const a = document.createElement('a');
    a.className = 'cm-wysiwyg-link';
    a.href = this.url;
    a.textContent = this.text;
    a.style.color = '#0366d6';
    a.style.textDecoration = 'underline';
    return a;
  }
  ignoreEvent() {
    return false;
  }
}

class WikilinkWidget extends WidgetType {
  constructor(private text: string, private target: string) {
    super();
  }
  eq(other: WikilinkWidget) {
    return this.text === other.text && this.target === other.target;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'cm-wysiwyg-wikilink';
    span.textContent = this.text;
    span.style.color = '#0366d6';
    span.style.borderBottom = '1px solid #0366d6';
    span.style.cursor = 'pointer';
    span.setAttribute('data-wikilink', this.target);
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

class TagWidget extends WidgetType {
  constructor(private text: string) {
    super();
  }
  eq(other: TagWidget) {
    return this.text === other.text;
  }
  toDOM() {
    const span = document.createElement('span');
    span.className = 'cm-wysiwyg-tag';
    span.textContent = `#${this.text}`;
    span.style.color = '#6f42c1';
    span.style.background = 'rgba(111, 66, 193, 0.1)';
    span.style.padding = '1px 6px';
    span.style.borderRadius = '10px';
    span.style.fontSize = '0.85em';
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

const inlineMarkdownRegex = {
  bold: /\*\*([^*]+)\*\*/g,
  italic: /\*([^*]+)\*/g,
  italicAlt: /_([^_]+)_/g,
  strike: /~~([^~]+)~~/g,
  code: /`([^`]+)`/g,
  link: /\[([^\]]+)\]\(([^)]+)\)/g,
  wikilink: /\[\[([^\]]+)\]\]/g,
  tag: /#([a-zA-Z0-9_\u4e00-\u9fa5]+)/g,
};

function isCursorInRange(cursorPos: number, from: number, to: number): boolean {
  return cursorPos >= from && cursorPos <= to;
}

function shouldRenderRange(from: number, to: number, cursorPos: number, lineText: string): boolean {
  if (cursorPos === -1) return true;

  if (isCursorInRange(cursorPos, from, to)) {
    return false;
  }

  if (lineText.trim().startsWith('#')) {
    return false;
  }

  if (lineText.trim().startsWith('```')) {
    return false;
  }

  if (lineText.trim().startsWith('>')) {
    const firstCharIndex = lineText.search(/[^>\s]/);
    if (firstCharIndex !== -1 && cursorPos >= firstCharIndex) {
      return true;
    }
  }

  return true;
}

const wysiwygDeco = EditorView.decorations.compute(
  ['doc', 'selection', wysiwygState],
  (state) => {
    const wysiwyg = state.field(wysiwygState);
    if (!wysiwyg.enabled) {
      return Decoration.none;
    }

    const cursorPos = state.selection.main.head;
    const decorations: Range<Decoration>[] = [];
    const replacedRanges: Array<{ from: number; to: number }> = [];

    const isOverlapping = (from: number, to: number): boolean => {
      return replacedRanges.some(
        (range) =>
          (from >= range.from && from <= range.to) ||
          (to >= range.from && to <= range.to) ||
          (from <= range.from && to >= range.to)
      );
    };

    for (let i = 1; i <= state.doc.lines; i++) {
      const line = state.doc.line(i);
      const lineText = line.text;

      let match: RegExpExecArray | null;

      inlineMarkdownRegex.wikilink.lastIndex = 0;
      while ((match = inlineMarkdownRegex.wikilink.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const fullMatch = match[1];
        let target = fullMatch;
        let displayText = fullMatch;
        const pipeIndex = fullMatch.indexOf('|');
        if (pipeIndex !== -1) {
          target = fullMatch.slice(0, pipeIndex);
          displayText = fullMatch.slice(pipeIndex + 1);
        }

        const deco = Decoration.replace({
          widget: new WikilinkWidget(displayText, target),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.link.lastIndex = 0;
      while ((match = inlineMarkdownRegex.link.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new LinkWidget(match[1], match[2]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.code.lastIndex = 0;
      while ((match = inlineMarkdownRegex.code.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new InlineCodeWidget(match[1]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.bold.lastIndex = 0;
      while ((match = inlineMarkdownRegex.bold.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new BoldWidget(match[1]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.italic.lastIndex = 0;
      while ((match = inlineMarkdownRegex.italic.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new ItalicWidget(match[1]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.strike.lastIndex = 0;
      while ((match = inlineMarkdownRegex.strike.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new StrikeThroughWidget(match[1]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }

      inlineMarkdownRegex.tag.lastIndex = 0;
      while ((match = inlineMarkdownRegex.tag.exec(lineText)) !== null) {
        const from = line.from + match.index;
        const to = from + match[0].length;

        if (!shouldRenderRange(from, to, cursorPos, lineText) || isOverlapping(from, to)) continue;

        const deco = Decoration.replace({
          widget: new TagWidget(match[1]),
          inclusive: true,
          block: false,
        });
        decorations.push(deco.range(from, to));
        replacedRanges.push({ from, to });
      }
    }

    return Decoration.set(decorations.sort((a, b) => a.from - b.from));
  }
);

const wysiwygTheme = EditorView.theme({
  '.cm-wysiwyg-bold': {
    fontWeight: 'bold',
  },
  '.cm-wysiwyg-italic': {
    fontStyle: 'italic',
  },
  '.cm-wysiwyg-strikethrough': {
    textDecoration: 'line-through',
  },
  '.cm-wysiwyg-inline-code': {
    padding: '1px 4px',
    background: 'rgba(27, 31, 35, 0.05)',
    borderRadius: '3px',
    fontFamily: 'monospace',
    fontSize: '0.9em',
  },
  '.dark .cm-wysiwyg-inline-code': {
    background: 'rgba(255, 255, 255, 0.1)',
  },
  '.cm-wysiwyg-link': {
    color: '#0366d6',
    textDecoration: 'underline',
    cursor: 'pointer',
  },
  '.cm-wysiwyg-wikilink': {
    color: '#0366d6',
    borderBottom: '1px solid #0366d6',
    cursor: 'pointer',
  },
  '.cm-wysiwyg-tag': {
    color: '#6f42c1',
    background: 'rgba(111, 66, 193, 0.1)',
    padding: '1px 6px',
    borderRadius: '10px',
    fontSize: '0.85em',
  },
  '.dark .cm-wysiwyg-link, .dark .cm-wysiwyg-wikilink': {
    color: '#58a6ff',
  },
  '.dark .cm-wysiwyg-tag': {
    color: '#bc8cff',
    background: 'rgba(188, 140, 255, 0.1)',
  },
});

export function wysiwygExtension(): Extension {
  return [wysiwygState, wysiwygDeco, wysiwygTheme];
}

export function enableWysiwyg(view: EditorView): void {
  view.dispatch({
    effects: toggleWysiwyg.of(true),
  });
}

export function disableWysiwyg(view: EditorView): void {
  view.dispatch({
    effects: toggleWysiwyg.of(false),
  });
}

export function setWysiwygMode(view: EditorView, enabled: boolean): void {
  view.dispatch({
    effects: toggleWysiwyg.of(enabled),
  });
}

export type WysiwygMode = 'source' | 'split' | 'wysiwyg';
