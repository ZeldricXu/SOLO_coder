import { EditorView } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import {
  createEditorState,
  createEditorView,
  setEditorValue,
  getEditorValue,
  getCursorPosition,
  scrollToLine,
  insertAtCursor,
  setWysiwyg,
  updateEditorTheme,
  type EditorConfig,
} from '@core/editor';

export type EditorModeType = 'source' | 'preview' | 'split' | 'wysiwyg';

export interface EditorCoreRef {
  view: EditorView | null;
  getValue: () => string;
  setValue: (value: string) => void;
  getCursor: () => { line: number; column: number };
  scrollToLine: (line: number) => void;
  insertAtCursor: (text: string) => void;
  execCommand: (command: (state: EditorState, dispatch: any) => boolean) => void;
  setWysiwyg: (enabled: boolean) => void;
  updateTheme: (theme: EditorConfig['theme']) => void;
  getScrollTop: () => number;
  getScrollDom: () => HTMLElement | null;
  focus: () => void;
}

export interface EditorCoreProps {
  initialContent?: string;
  config: EditorConfig;
  onMount?: (ref: EditorCoreRef) => void;
  onContentChange?: (content: string) => void;
  onScroll?: (scrollTop: number) => void;
  onCursorChange?: (pos: { line: number; column: number }) => void;
  className?: string;
}

export class EditorCore {
  private container: HTMLDivElement | null = null;
  private view: EditorView | null = null;
  private config: EditorConfig;
  private onContentChange?: (content: string) => void;
  private onScroll?: (scrollTop: number) => void;
  private onCursorChange?: (pos: { line: number; column: number }) => void;

  constructor(config: EditorConfig) {
    this.config = config;
  }

  mount(container: HTMLDivElement, initialContent: string = ''): EditorCoreRef {
    this.container = container;

    const state = createEditorState(initialContent, {
      ...this.config,
      onChange: (newContent: string) => {
        this.onContentChange?.(newContent);
        this.config.onChange?.(newContent);
      },
      onScroll: (scrollTop: number) => {
        this.onScroll?.(scrollTop);
        this.config.onScroll?.(scrollTop);
      },
    });

    this.view = createEditorView(container, state);

    const updateCursor = () => {
      if (this.view) {
        const pos = getCursorPosition(this.view);
        this.onCursorChange?.(pos);
      }
    };

    this.view.dom.addEventListener('keyup', updateCursor);
    this.view.dom.addEventListener('click', updateCursor);

    const ref: EditorCoreRef = {
      view: this.view,
      getValue: () => this.getValue(),
      setValue: (value: string) => this.setValue(value),
      getCursor: () => this.getCursor(),
      scrollToLine: (line: number) => this.scrollToLine(line),
      insertAtCursor: (text: string) => this.insertAtCursor(text),
      execCommand: (command) => this.execCommand(command),
      setWysiwyg: (enabled: boolean) => this.setWysiwyg(enabled),
      updateTheme: (theme: EditorConfig['theme']) => this.updateTheme(theme),
      getScrollTop: () => this.getScrollTop(),
      getScrollDom: () => this.getScrollDom(),
      focus: () => this.focus(),
    };

    return ref;
  }

  setCallbacks(callbacks: {
    onContentChange?: (content: string) => void;
    onScroll?: (scrollTop: number) => void;
    onCursorChange?: (pos: { line: number; column: number }) => void;
  }): void {
    this.onContentChange = callbacks.onContentChange;
    this.onScroll = callbacks.onScroll;
    this.onCursorChange = callbacks.onCursorChange;
  }

  getValue(): string {
    if (!this.view) return '';
    return getEditorValue(this.view);
  }

  setValue(value: string): void {
    if (!this.view) return;
    const currentValue = getEditorValue(this.view);
    if (currentValue !== value) {
      setEditorValue(this.view, value);
    }
  }

  getCursor(): { line: number; column: number } {
    if (!this.view) return { line: 1, column: 1 };
    return getCursorPosition(this.view);
  }

  scrollToLine(line: number): void {
    if (!this.view) return;
    scrollToLine(this.view, line);
  }

  insertAtCursor(text: string): void {
    if (!this.view) return;
    insertAtCursor(this.view, text);
  }

  execCommand(command: (state: EditorState, dispatch: any) => boolean): void {
    if (!this.view) return;
    const view = this.view;
    command(view.state, view.dispatch.bind(view));
    view.focus();
  }

  setWysiwyg(enabled: boolean): void {
    if (!this.view) return;
    setWysiwyg(this.view, enabled);
  }

  updateTheme(theme: EditorConfig['theme']): void {
    if (!this.view) return;
    updateEditorTheme(this.view, theme || 'light');
  }

  getScrollTop(): number {
    if (!this.view) return 0;
    return this.view.scrollDOM.scrollTop;
  }

  getScrollDom(): HTMLElement | null {
    if (!this.view) return null;
    return this.view.scrollDOM;
  }

  focus(): void {
    if (!this.view) return;
    this.view.focus();
  }

  destroy(): void {
    if (this.view) {
      this.view.destroy();
      this.view = null;
    }
    this.container = null;
  }
}

export const useEditorCore = () => {
  return { EditorCore };
};
