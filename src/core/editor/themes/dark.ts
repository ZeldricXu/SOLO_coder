import { EditorView } from '@codemirror/view';
import { Extension } from '@codemirror/state';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

export const darkThemeColors = {
  background: '#0f172a',
  foreground: '#e2e8f0',
  caret: '#60a5fa',
  selection: '#1e3a5f',
  lineHighlight: '#1e293b',
  gutterBackground: '#0b1220',
  gutterForeground: '#64748b',
  gutterBorder: '#334155',
  selectionMatch: '#2563eb40',
  activeLineGutter: '#94a3b8',
  cursorBorderColor: '#60a5fa',
};

export const darkSyntaxColors = {
  comment: '#64748b',
  name: '#e2e8f0',
  propertyName: '#c4b5fd',
  string: '#34d399',
  number: '#fb923c',
  keyword: '#60a5fa',
  operator: '#a78bfa',
  punctuation: '#94a3b8',
  heading: '#f8fafc',
  link: '#60a5fa',
  url: '#34d399',
  list: '#fb923c',
  quote: '#64748b',
  code: '#c4b5fd',
  codeMark: '#334155',
  emphasis: '#e2e8f0',
  strong: '#f8fafc',
  strikethrough: '#475569',
  tableHeader: '#cbd5e1',
  tableCell: '#94a3b8',
  inlineCode: '#c4b5fd',
  inlineCodeBg: '#1e293b',
};

export const darkHighlightStyle = HighlightStyle.define([
  { tag: t.comment, color: darkSyntaxColors.comment, fontStyle: 'italic' },
  { tag: t.name, color: darkSyntaxColors.name },
  { tag: t.propertyName, color: darkSyntaxColors.propertyName },
  { tag: t.attributeName, color: darkSyntaxColors.propertyName },
  { tag: t.string, color: darkSyntaxColors.string },
  { tag: t.docString, color: darkSyntaxColors.string },
  { tag: t.character, color: darkSyntaxColors.string },
  { tag: t.number, color: darkSyntaxColors.number },
  { tag: t.integer, color: darkSyntaxColors.number },
  { tag: t.float, color: darkSyntaxColors.number },
  { tag: t.keyword, color: darkSyntaxColors.keyword, fontWeight: '500' },
  { tag: t.operator, color: darkSyntaxColors.operator },
  { tag: t.punctuation, color: darkSyntaxColors.punctuation },
  { tag: t.derefOperator, color: darkSyntaxColors.operator },
  { tag: t.heading, color: darkSyntaxColors.heading, fontWeight: 'bold' },
  { tag: t.heading1, color: darkSyntaxColors.heading, fontSize: '1.5em', fontWeight: 'bold' },
  { tag: t.heading2, color: darkSyntaxColors.heading, fontSize: '1.25em', fontWeight: 'bold' },
  { tag: t.heading3, color: darkSyntaxColors.heading, fontSize: '1.1em', fontWeight: 'bold' },
  { tag: t.link, color: darkSyntaxColors.link, textDecoration: 'underline' },
  { tag: t.url, color: darkSyntaxColors.url },
  { tag: t.list, color: darkSyntaxColors.list },
  { tag: t.quote, color: darkSyntaxColors.quote, fontStyle: 'italic' },
  { tag: t.code, color: darkSyntaxColors.code, backgroundColor: darkSyntaxColors.inlineCodeBg },
  { tag: t.codeMark, color: darkSyntaxColors.codeMark },
  { tag: t.monospace, color: darkSyntaxColors.code, fontFamily: 'var(--font-mono, monospace)' },
  { tag: t.emphasis, color: darkSyntaxColors.emphasis, fontStyle: 'italic' },
  { tag: t.strong, color: darkSyntaxColors.strong, fontWeight: 'bold' },
  { tag: t.strikethrough, color: darkSyntaxColors.strikethrough, textDecoration: 'line-through' },
  { tag: t.tableHeader, color: darkSyntaxColors.tableHeader, fontWeight: 'bold' },
  { tag: t.tableCell, color: darkSyntaxColors.tableCell },
  { tag: t.tagName, color: darkSyntaxColors.keyword },
  { tag: t.typeName, color: darkSyntaxColors.propertyName },
  { tag: t.className, color: darkSyntaxColors.propertyName },
  { tag: t.definition(t.variableName), color: darkSyntaxColors.name, fontWeight: 'bold' },
  { tag: t.constant(t.name), color: darkSyntaxColors.number },
  { tag: t.function(t.name), color: darkSyntaxColors.keyword },
  { tag: t.macro, color: darkSyntaxColors.keyword },
  { tag: t.atom, color: darkSyntaxColors.number },
  { tag: t.bool, color: darkSyntaxColors.number },
  { tag: t.labelName, color: darkSyntaxColors.keyword },
  { tag: t.inserted, color: '#34d399' },
  { tag: t.deleted, color: '#f87171' },
  { tag: t.changed, color: '#60a5fa' },
  { tag: t.meta, color: darkSyntaxColors.comment },
  { tag: t.documentMeta, color: darkSyntaxColors.comment, fontWeight: 'bold' },
]);

export const darkTheme = EditorView.theme(
  {
    '&': {
      color: darkThemeColors.foreground,
      backgroundColor: darkThemeColors.background,
      fontFamily: 'var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif)',
      fontSize: '14px',
      lineHeight: '1.6',
    },
    '.cm-content': {
      caretColor: darkThemeColors.caret,
      padding: '16px 0',
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: darkThemeColors.cursorBorderColor,
      borderLeftWidth: '2px',
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: darkThemeColors.selection,
    },
    '.cm-panels': {
      backgroundColor: darkThemeColors.gutterBackground,
      color: darkThemeColors.foreground,
    },
    '.cm-panels.cm-panels-top': {
      borderBottom: `1px solid ${darkThemeColors.gutterBorder}`,
    },
    '.cm-panels.cm-panels-bottom': {
      borderTop: `1px solid ${darkThemeColors.gutterBorder}`,
    },
    '.cm-searchMatch': {
      backgroundColor: '#854d0e',
      outline: '1px solid #d97706',
    },
    '.cm-searchMatch.cm-searchMatch-selected': {
      backgroundColor: '#a16207',
    },
    '.cm-activeLine': {
      backgroundColor: darkThemeColors.lineHighlight,
    },
    '.cm-activeLineGutter': {
      backgroundColor: darkThemeColors.lineHighlight,
      color: darkThemeColors.activeLineGutter,
    },
    '.cm-selectionMatch': {
      backgroundColor: darkThemeColors.selectionMatch,
    },
    '.cm-gutters': {
      backgroundColor: darkThemeColors.gutterBackground,
      color: darkThemeColors.gutterForeground,
      border: 'none',
      borderRight: `1px solid ${darkThemeColors.gutterBorder}`,
    },
    '.cm-lineNumbers': {
      minWidth: '3em',
      padding: '0 8px',
      fontSize: '12px',
    },
    '.cm-foldPlaceholder': {
      backgroundColor: 'transparent',
      border: 'none',
      color: darkSyntaxColors.comment,
    },
    '.cm-tooltip': {
      border: `1px solid ${darkThemeColors.gutterBorder}`,
      backgroundColor: darkThemeColors.background,
      borderRadius: '6px',
      boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.3)',
    },
    '.cm-tooltip-autocomplete': {
      '& > ul > li[aria-selected]': {
        backgroundColor: darkThemeColors.selection,
        color: darkThemeColors.foreground,
      },
    },
    '.cm-snippetField': {
      backgroundColor: darkThemeColors.selection,
    },
    '.cm-snippetFieldBackground': {
      backgroundColor: darkThemeColors.selection,
    },
    '.cm-line': {
      padding: '0 16px',
    },
    '.cm-scroller': {
      fontFamily: 'inherit',
    },
    '.cm-editor.cm-focused': {
      outline: 'none',
    },
    '.cm-underline': {
      textDecoration: 'underline',
    },
    '.cm-strikethrough': {
      textDecoration: 'line-through',
    },
  },
  { dark: true }
);

export const darkThemeExtensions: Extension[] = [
  darkTheme,
  syntaxHighlighting(darkHighlightStyle),
];
