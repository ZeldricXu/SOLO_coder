import { EditorState, Transaction, EditorSelection } from '@codemirror/state';

function getLineIndent(lineText: string): string {
  const match = lineText.match(/^(\s*)/);
  return match ? match[1] : '';
}

function toggleInlineMarkup(
  state: EditorState,
  dispatch: (tr: Transaction) => void,
  prefix: string,
  suffix?: string
): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const prefixLen = prefix.length;
  const suffixLen = suffix ? suffix.length : prefixLen;

  if (from === to) {
    const line = doc.lineAt(from);
    const lineText = line.text;
    const posInLine = from - line.from;
    
    const beforeStart = Math.max(0, posInLine - prefixLen);
    const before = lineText.slice(beforeStart, posInLine);
    const afterEnd = Math.min(lineText.length, posInLine + suffixLen);
    const after = lineText.slice(posInLine, afterEnd);
    
    if (before === prefix && after === (suffix || prefix)) {
      dispatch(state.update({
        changes: [
          { from: from - prefixLen, to: from, insert: '' },
          { from: from, to: from + suffixLen, insert: '' },
        ],
        selection: { anchor: from - prefixLen },
      }));
      return true;
    }
    
    dispatch(state.update({
      changes: { from, to, insert: `${prefix}${suffix || prefix}` },
      selection: { anchor: from + prefixLen },
    }));
    return true;
  }

  const selectedText = doc.sliceString(from, to);
  const beforeText = doc.sliceString(Math.max(0, from - prefixLen), from);
  const afterText = doc.sliceString(to, to + suffixLen);
  const actualSuffix = suffix || prefix;

  if (beforeText === prefix && afterText === actualSuffix) {
    dispatch(state.update({
      changes: [
        { from: from - prefixLen, to: from, insert: '' },
        { from: to, to: to + suffixLen, insert: '' },
      ],
      selection: { anchor: from - prefixLen, head: to - prefixLen },
    }));
    return true;
  }

  dispatch(state.update({
    changes: { from, to, insert: `${prefix}${selectedText}${actualSuffix}` },
    selection: { anchor: from + prefixLen, head: to + prefixLen },
  }));
  return true;
}

export function toggleBold(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  return toggleInlineMarkup(state, dispatch, '**');
}

export function toggleItalic(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  return toggleInlineMarkup(state, dispatch, '*');
}

export function toggleStrikethrough(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  return toggleInlineMarkup(state, dispatch, '~~');
}

export function toggleCode(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  return toggleInlineMarkup(state, dispatch, '`');
}

export function wrapInBlockquote(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const startLine = doc.lineAt(from);
  const endLine = doc.lineAt(to);

  const changes: { from: number; to: number; insert: string }[] = [];
  const firstLineText = startLine.text;
  const isBlockquoted = firstLineText.trimStart().startsWith('>');

  for (let i = startLine.number; i <= endLine.number; i++) {
    const line = doc.line(i);
    if (isBlockquoted) {
      const text = line.text;
      const newText = text.replace(/^>\s?/, '');
      changes.push({ from: line.from, to: line.to, insert: newText });
    } else {
      changes.push({ from: line.from, to: line.from, insert: '> ' });
    }
  }

  dispatch(state.update({ changes }));
  return true;
}

export function toggleHeadingLevel(
  state: EditorState,
  dispatch: (tr: Transaction) => void,
  level: number
): boolean {
  const { from } = state.selection.main;
  const doc = state.doc;
  const line = doc.lineAt(from);
  const lineText = line.text;

  const headingMatch = lineText.match(/^(#{1,6})\s/);
  const currentLevel = headingMatch ? headingMatch[1].length : 0;
  const prefix = '#'.repeat(level) + ' ';

  let newText: string;
  if (currentLevel === level) {
    newText = lineText.replace(/^#{1,6}\s/, '');
  } else if (currentLevel > 0) {
    newText = lineText.replace(/^#{1,6}\s/, prefix);
  } else {
    newText = prefix + lineText;
  }

  dispatch(state.update({
    changes: { from: line.from, to: line.to, insert: newText },
    selection: { anchor: from + (newText.length - lineText.length) },
  }));
  return true;
}

export function insertCodeBlock(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const selectedText = doc.sliceString(from, to);
  
  const codeBlock = `\`\`\`\n${selectedText || ''}\n\`\`\``;
  const cursorPos = from + 4;

  dispatch(state.update({
    changes: { from, to, insert: codeBlock },
    selection: { anchor: cursorPos },
  }));
  return true;
}

export function insertLink(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const selectedText = doc.sliceString(from, to);
  
  const link = selectedText ? `[${selectedText}](url)` : '[](url)';
  const cursorPos = from + link.length - 4;

  dispatch(state.update({
    changes: { from, to, insert: link },
    selection: { anchor: cursorPos, head: cursorPos + 3 },
  }));
  return true;
}

export function insertImage(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const selectedText = doc.sliceString(from, to);
  
  const image = `![${selectedText || 'alt text'}](url)`;
  const cursorPos = from + image.length - 4;

  dispatch(state.update({
    changes: { from, to, insert: image },
    selection: { anchor: cursorPos, head: cursorPos + 3 },
  }));
  return true;
}

export function insertList(
  state: EditorState,
  dispatch: (tr: Transaction) => void,
  type: 'unordered' | 'ordered'
): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const startLine = doc.lineAt(from);
  const endLine = doc.lineAt(to);

  const changes: { from: number; to: number; insert: string }[] = [];
  let counter = 1;

  for (let i = startLine.number; i <= endLine.number; i++) {
    const line = doc.line(i);
    const indent = getLineIndent(line.text);
    const prefix = type === 'unordered' ? `${indent}- ` : `${indent}${counter}. `;
    
    if (line.text.trim() === '') {
      changes.push({ from: line.from, to: line.to, insert: prefix });
    } else {
      const listPattern = type === 'unordered' 
        ? /^(\s*)[-*+]\s/ 
        : /^(\s*)\d+\.\s/;
      
      if (listPattern.test(line.text)) {
        const newText = line.text.replace(listPattern, type === 'unordered' ? '' : (_, indent) => indent);
        changes.push({ from: line.from, to: line.to, insert: newText });
      } else {
        changes.push({ from: line.from, to: line.from, insert: prefix });
      }
    }
    counter++;
  }

  dispatch(state.update({ changes }));
  return true;
}

export function insertTaskList(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from, to } = state.selection.main;
  const doc = state.doc;
  const startLine = doc.lineAt(from);
  const endLine = doc.lineAt(to);

  const changes: { from: number; to: number; insert: string }[] = [];

  for (let i = startLine.number; i <= endLine.number; i++) {
    const line = doc.line(i);
    const indent = getLineIndent(line.text);
    const prefix = `${indent}- [ ] `;
    
    if (line.text.trim() === '') {
      changes.push({ from: line.from, to: line.to, insert: prefix });
    } else {
      const taskPattern = /^(\s*)- \[[ xX]\]\s/;
      if (taskPattern.test(line.text)) {
        const newText = line.text.replace(taskPattern, (_, indent) => indent);
        changes.push({ from: line.from, to: line.to, insert: newText });
      } else {
        changes.push({ from: line.from, to: line.from, insert: prefix });
      }
    }
  }

  dispatch(state.update({ changes }));
  return true;
}

export function insertTable(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from } = state.selection.main;
  const table = `| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n| 内容 | 内容 | 内容 |\n`;

  dispatch(state.update({
    changes: { from, to: from, insert: table },
    selection: { anchor: from + 2 },
  }));
  return true;
}

export function insertHorizontalRule(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from } = state.selection.main;
  const doc = state.doc;
  const line = doc.lineAt(from);
  
  let insert = '\n---\n';
  if (line.text.trim() === '') {
    insert = '---\n';
  }

  dispatch(state.update({
    changes: { from, to: from, insert },
    selection: { anchor: from + insert.length },
  }));
  return true;
}

export function toggleTaskChecked(state: EditorState, dispatch: (tr: Transaction) => void): boolean {
  const { from } = state.selection.main;
  const doc = state.doc;
  const line = doc.lineAt(from);
  const lineText = line.text;

  const taskMatch = lineText.match(/^(\s*)- \[([ xX])\]\s/);
  if (!taskMatch) return false;

  const [, indent, status] = taskMatch;
  const newStatus = status === ' ' ? 'x' : ' ';
  const newText = lineText.replace(/^(\s*)- \[[ xX]\]\s/, `${indent}- [${newStatus}] `);

  dispatch(state.update({
    changes: { from: line.from, to: line.to, insert: newText },
  }));
  return true;
}
