import { Extension, EditorState } from '@codemirror/state';
import { EditorView, lineNumbers, highlightActiveLineGutter, highlightActiveLine, highlightSpecialChars, drawSelection, dropCursor, rectangularSelection, crosshairCursor, keymap } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { bracketMatching, foldGutter, foldKeymap, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import { closeBrackets, closeBracketsKeymap, completionKeymap, autocompletion } from '@codemirror/autocomplete';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { lintKeymap } from '@codemirror/lint';
import { markdown } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { lightThemeExtensions } from './themes/light';
import { darkThemeExtensions } from './themes/dark';
import { wikilinkHighlight, wikilinkStyle, wikilinkClickHandler, WikilinkClickHandler } from './extensions/wikilink';
import { markdownKeymap, historyExtension } from './extensions/keymap';
import { wysiwygExtension, setWysiwygMode, type WysiwygMode } from './extensions/wysiwyg';

export type EditorTheme = 'light' | 'dark' | 'system';

export interface EditorConfig {
  theme?: EditorTheme;
  fontSize?: number;
  lineHeight?: number;
  fontFamily?: string;
  showLineNumbers?: boolean;
  tabSize?: number;
  lineWrapping?: boolean;
  wysiwygMode?: boolean;
  onWikilinkClick?: WikilinkClickHandler;
  onChange?: (value: string) => void;
  onFocus?: () => void;
  onBlur?: () => void;
  onScroll?: (scrollTop: number) => void;
}

export function getThemeExtensions(theme: EditorTheme): Extension[] {
  if (theme === 'dark') {
    return darkThemeExtensions;
  }
  if (theme === 'light') {
    return lightThemeExtensions;
  }
  if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return darkThemeExtensions;
  }
  return lightThemeExtensions;
}

export function createEditorExtensions(config: EditorConfig = {}): Extension[] {
  const {
    theme = 'system',
    fontSize = 14,
    lineHeight = 1.6,
    fontFamily,
    showLineNumbers = true,
    tabSize = 4,
    lineWrapping = true,
    wysiwygMode = false,
    onWikilinkClick,
    onChange,
    onFocus,
    onBlur,
    onScroll,
  } = config;

  const extensions: Extension[] = [];

  extensions.push(...getThemeExtensions(theme));
  extensions.push(wysiwygExtension());

  if (showLineNumbers) {
    extensions.push(lineNumbers());
  }

  extensions.push(
    foldGutter(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    historyExtension,
    foldGutter(),
    drawSelection(),
    dropCursor(),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    bracketMatching(),
    closeBrackets(),
    autocompletion(),
    rectangularSelection(),
    crosshairCursor(),
    highlightActiveLine(),
    highlightSelectionMatches(),
    EditorState.tabSize.of(tabSize),
    wikilinkStyle,
    wikilinkHighlight,
    markdown({
      codeLanguages: languages,
      addKeymap: true,
    }),
    markdownKeymap,
    keymap.of([
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...searchKeymap,
      ...historyKeymap,
      ...foldKeymap,
      ...completionKeymap,
      ...lintKeymap,
      indentWithTab,
    ]),
  );

  if (lineWrapping) {
    extensions.push(EditorView.lineWrapping);
  }

  if (onWikilinkClick) {
    extensions.push(wikilinkClickHandler(onWikilinkClick));
  }

  if (onChange) {
    extensions.push(
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          onChange(update.state.doc.toString());
        }
      })
    );
  }

  if (onFocus) {
    extensions.push(
      EditorView.domEventHandlers({
        focus: () => onFocus(),
      })
    );
  }

  if (onBlur) {
    extensions.push(
      EditorView.domEventHandlers({
        blur: () => onBlur(),
      })
    );
  }

  if (onScroll) {
    extensions.push(
      EditorView.scrollChanged.of((view) => {
        onScroll(view.scrollDOM.scrollTop);
      })
    );
  }

  const fontConfig: Extension = EditorView.theme({
    '&': {
      fontSize: `${fontSize}px`,
      lineHeight: String(lineHeight),
      ...(fontFamily ? { fontFamily } : {}),
    },
    '.cm-scroller': {
      fontFamily: 'inherit',
    },
  });
  extensions.push(fontConfig);

  return extensions;
}

export function createEditorState(
  doc: string = '',
  config: EditorConfig = {}
): EditorState {
  return EditorState.create({
    doc,
    extensions: createEditorExtensions(config),
  });
}

export function createEditorView(
  parent: HTMLElement,
  state: EditorState
): EditorView {
  return new EditorView({
    state,
    parent,
  });
}

export function updateEditorTheme(view: EditorView, theme: EditorTheme): void {
  const currentExtensions = view.state.facet(EditorView.theme);
  const themeExtensions = getThemeExtensions(theme);
  
  view.dispatch({
    effects: EditorView.reconfigure.of([
      ...view.state.config.filter(ext => {
        return !currentExtensions.includes(ext as any);
      }),
      ...themeExtensions,
    ]),
  });
}

export function getEditorValue(view: EditorView): string {
  return view.state.doc.toString();
}

export function setEditorValue(view: EditorView, value: string): void {
  view.dispatch({
    changes: {
      from: 0,
      to: view.state.doc.length,
      insert: value,
    },
  });
}

export function insertAtCursor(view: EditorView, text: string): void {
  const { from, to } = view.state.selection.main;
  view.dispatch({
    changes: { from, to, insert: text },
    selection: { anchor: from + text.length },
  });
}

export function scrollToLine(view: EditorView, lineNumber: number): void {
  const line = view.state.doc.line(lineNumber);
  view.dispatch({
    effects: EditorView.scrollIntoView(line.from, { y: 'start', yMargin: 50 }),
  });
}

export function getCursorPosition(view: EditorView): { line: number; column: number } {
  const pos = view.state.selection.main.head;
  const line = view.state.doc.lineAt(pos);
  return {
    line: line.number,
    column: pos - line.from + 1,
  };
}

export function setWysiwyg(view: EditorView, enabled: boolean): void {
  setWysiwygMode(view, enabled);
}

export type { WysiwygMode };
