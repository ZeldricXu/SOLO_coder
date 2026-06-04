import { EditorState, Transaction } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, redo, undo } from '@codemirror/commands';
import { indentLess, indentMore, insertNewlineAndIndent } from '@codemirror/commands';
import { 
  toggleBold, 
  toggleItalic, 
  toggleStrikethrough, 
  toggleCode,
  wrapInBlockquote,
  toggleHeadingLevel,
  insertCodeBlock,
  insertLink,
  insertImage,
  insertList,
  insertTaskList,
  insertTable,
  insertHorizontalRule,
} from './markdownCommands';

export interface EditorCommand {
  name: string;
  key: string;
  macKey?: string;
  description: string;
  run: (state: EditorState, dispatch: (tr: Transaction) => void, view?: EditorView) => boolean;
}

export const editorCommands: EditorCommand[] = [
  {
    name: 'bold',
    key: 'Ctrl-b',
    macKey: 'Cmd-b',
    description: '切换粗体',
    run: toggleBold,
  },
  {
    name: 'italic',
    key: 'Ctrl-i',
    macKey: 'Cmd-i',
    description: '切换斜体',
    run: toggleItalic,
  },
  {
    name: 'strikethrough',
    key: 'Ctrl-Shift-s',
    macKey: 'Cmd-Shift-s',
    description: '切换删除线',
    run: toggleStrikethrough,
  },
  {
    name: 'code',
    key: 'Ctrl-e',
    macKey: 'Cmd-e',
    description: '切换行内代码',
    run: toggleCode,
  },
  {
    name: 'quote',
    key: 'Ctrl-q',
    macKey: 'Cmd-q',
    description: '插入引用',
    run: wrapInBlockquote,
  },
  {
    name: 'heading1',
    key: 'Ctrl-1',
    macKey: 'Cmd-1',
    description: '一级标题',
    run: (state, dispatch) => toggleHeadingLevel(state, dispatch, 1),
  },
  {
    name: 'heading2',
    key: 'Ctrl-2',
    macKey: 'Cmd-2',
    description: '二级标题',
    run: (state, dispatch) => toggleHeadingLevel(state, dispatch, 2),
  },
  {
    name: 'heading3',
    key: 'Ctrl-3',
    macKey: 'Cmd-3',
    description: '三级标题',
    run: (state, dispatch) => toggleHeadingLevel(state, dispatch, 3),
  },
  {
    name: 'heading4',
    key: 'Ctrl-4',
    macKey: 'Cmd-4',
    description: '四级标题',
    run: (state, dispatch) => toggleHeadingLevel(state, dispatch, 4),
  },
  {
    name: 'codeBlock',
    key: 'Ctrl-Shift-c',
    macKey: 'Cmd-Shift-c',
    description: '插入代码块',
    run: insertCodeBlock,
  },
  {
    name: 'link',
    key: 'Ctrl-k',
    macKey: 'Cmd-k',
    description: '插入链接',
    run: insertLink,
  },
  {
    name: 'image',
    key: 'Ctrl-Shift-i',
    macKey: 'Cmd-Shift-i',
    description: '插入图片',
    run: insertImage,
  },
  {
    name: 'unorderedList',
    key: 'Ctrl-u',
    macKey: 'Cmd-u',
    description: '无序列表',
    run: (state, dispatch) => insertList(state, dispatch, 'unordered'),
  },
  {
    name: 'orderedList',
    key: 'Ctrl-o',
    macKey: 'Cmd-o',
    description: '有序列表',
    run: (state, dispatch) => insertList(state, dispatch, 'ordered'),
  },
  {
    name: 'taskList',
    key: 'Ctrl-t',
    macKey: 'Cmd-t',
    description: '任务列表',
    run: (state, dispatch) => insertTaskList(state, dispatch),
  },
  {
    name: 'table',
    key: 'Ctrl-Shift-t',
    macKey: 'Cmd-Shift-t',
    description: '插入表格',
    run: insertTable,
  },
  {
    name: 'horizontalRule',
    key: 'Ctrl-h',
    macKey: 'Cmd-h',
    description: '分割线',
    run: insertHorizontalRule,
  },
  {
    name: 'save',
    key: 'Ctrl-s',
    macKey: 'Cmd-s',
    description: '保存',
    run: (state, dispatch, view) => {
      if (view) {
        view.dom.dispatchEvent(new CustomEvent('editor-save'));
      }
      return true;
    },
  },
  {
    name: 'find',
    key: 'Ctrl-f',
    macKey: 'Cmd-f',
    description: '查找',
    run: (state, dispatch, view) => {
      if (view) {
        view.dom.dispatchEvent(new CustomEvent('editor-find'));
      }
      return true;
    },
  },
  {
    name: 'replace',
    key: 'Ctrl-h',
    macKey: 'Cmd-Alt-f',
    description: '替换',
    run: (state, dispatch, view) => {
      if (view) {
        view.dom.dispatchEvent(new CustomEvent('editor-replace'));
      }
      return true;
    },
  },
  {
    name: 'insertWikilink',
    key: 'Ctrl-[',
    macKey: 'Cmd-[',
    description: '插入双向链接',
    run: (state, dispatch) => {
      const { from, to } = state.selection.main;
      const selectedText = state.doc.sliceString(from, to);
      const wikilink = selectedText ? `[[${selectedText}]]` : '[[]]';
      dispatch(state.update({
        changes: { from, to, insert: wikilink },
        selection: { anchor: from + wikilink.length - 2 },
      }));
      return true;
    },
  },
  {
    name: 'togglePreview',
    key: 'Ctrl-Shift-p',
    macKey: 'Cmd-Shift-p',
    description: '切换预览模式',
    run: (state, dispatch, view) => {
      if (view) {
        view.dom.dispatchEvent(new CustomEvent('toggle-preview'));
      }
      return true;
    },
  },
  {
    name: 'undo',
    key: 'Ctrl-z',
    macKey: 'Cmd-z',
    description: '撤销',
    run: (state, dispatch) => undo(state, dispatch),
  },
  {
    name: 'redo',
    key: 'Ctrl-y',
    macKey: 'Cmd-Shift-z',
    description: '重做',
    run: (state, dispatch) => redo(state, dispatch),
  },
  {
    name: 'indentMore',
    key: 'Tab',
    description: '增加缩进',
    run: (state, dispatch) => indentMore(state, dispatch),
  },
  {
    name: 'indentLess',
    key: 'Shift-Tab',
    description: '减少缩进',
    run: (state, dispatch) => indentLess(state, dispatch),
  },
  {
    name: 'newlineAndIndent',
    key: 'Enter',
    description: '换行并缩进',
    run: (state, dispatch) => insertNewlineAndIndent(state, dispatch),
  },
];

function isMac(): boolean {
  return typeof navigator !== 'undefined' && navigator.platform.toUpperCase().indexOf('MAC') >= 0;
}

export function createEditorKeymap(customCommands?: EditorCommand[]) {
  const allCommands = [...editorCommands, ...(customCommands || [])];
  const isMacPlatform = isMac();

  const bindings = allCommands.map(cmd => {
    const key = isMacPlatform && cmd.macKey ? cmd.macKey : cmd.key;
    return {
      key,
      run: cmd.run,
      preventDefault: true,
    };
  });

  return keymap.of([...bindings, ...defaultKeymap, ...historyKeymap]);
}

export const markdownKeymap = createEditorKeymap();
export const historyExtension = history();
