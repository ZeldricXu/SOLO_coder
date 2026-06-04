import { EditorView } from '@codemirror/view';
import { Extension } from '@codemirror/state';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

export const lightThemeColors = {
  background: '#ffffff',
  foreground: '#1f2937',
  caret: '#3b82f6',
  selection: '#dbeafe',
  lineHighlight: '#f3f4f6',
  gutterBackground: '#f9fafb',
  gutterForeground: '#9ca3af',
  gutterBorder: '#e5e7eb',
  selectionMatch: '#bfdbfe',
  activeLineGutter: '#6b7280',
  cursorBorderColor: '#3b82f6',
};

export const lightSyntaxColors = {
  comment: '#6b7280',
  name: '#1f2937',
  propertyName: '#7c3aed',
  string: '#059669',
  number: '#ea580c',
  keyword: '#2563eb',
  operator: '#4338ca',
  punctuation: '#6b7280',
  heading: '#111827',
  link: '#2563eb',
  url: '#059669',
  list: '#ea580c',
  quote: '#6b7280',
  code: '#7c3aed',
  codeMark: '#e5e7eb',
  emphasis: '#1f2937',
  strong: '#111827',
  strikethrough: '#9ca3af',
  tableHeader: '#374151',
  tableCell: '#4b5563',
  inlineCode: '#7c3aed',
  inlineCodeBg: '#f3f4f6',
};

export const lightHighlightStyle = HighlightStyle.define([
  { tag: t.comment, color: lightSyntaxColors.comment, fontStyle: 'italic' },
  { tag: t.name, color: lightSyntaxColors.name },
  { tag: t.propertyName, color: lightSyntaxColors.propertyName },
  { tag: t.attributeName, color: lightSyntaxColors.propertyName },
  { tag: t.string, color: lightSyntaxColors.string },
  { tag: t.docString, color: lightSyntaxColors.string },
  { tag: t.character, color: lightSyntaxColors.string },
  { tag: t.number, color: lightSyntaxColors.number },
  { tag: t.integer, color: lightSyntaxColors.number },
  { tag: t.float, color: lightSyntaxColors.number },
  { tag: t.keyword, color: lightSyntaxColors.keyword, fontWeight: '500' },
  { tag: t.operator, color: lightSyntaxColors.operator },
  { tag: t.punctuation, color: lightSyntaxColors.punctuation },
  { tag: t.derefOperator, color: lightSyntaxColors.operator },
  { tag: t.heading, color: lightSyntaxColors.heading, fontWeight: 'bold' },
  { tag: t.heading1, color: lightSyntaxColors.heading, fontSize: '1.5em', fontWeight: 'bold' },
  { tag: t.heading2, color: lightSyntaxColors.heading, fontSize: '1.25em', fontWeight: 'bold' },
  { tag: t.heading3, color: lightSyntaxColors.heading, fontSize: '1.1em', fontWeight: 'bold' },
  { tag: t.link, color: lightSyntaxColors.link, textDecoration: 'underline' },
  { tag: t.url, color: lightSyntaxColors.url },
  { tag: t.list, color: lightSyntaxColors.list },
  { tag: t.quote, color: lightSyntaxColors.quote, fontStyle: 'italic' },
  { tag: t.code, color: lightSyntaxColors.code, backgroundColor: lightSyntaxColors.inlineCodeBg },
  { tag: t.codeMark, color: lightSyntaxColors.codeMark },
  { tag: t.monospace, color: lightSyntaxColors.code, fontFamily: 'var(--font-mono, monospace)' },
  { tag: t.emphasis, color: lightSyntaxColors.emphasis, fontStyle: 'italic' },
  { tag: t.strong, color: lightSyntaxColors.strong, fontWeight: 'bold' },
  { tag: t.strikethrough, color: lightSyntaxColors.strikethrough, textDecoration: 'line-through' },
  { tag: t.tableHeader, color: lightSyntaxColors.tableHeader, fontWeight: 'bold' },
  { tag: t.tableCell, color: lightSyntaxColors.tableCell },
  { tag: t.tagName, color: lightSyntaxColors.keyword },
  { tag: t.typeName, color: lightSyntaxColors.propertyName },
  { tag: t.className, color: lightSyntaxColors.propertyName },
  { tag: t.definition(t.variableName), color: lightSyntaxColors.name, fontWeight: 'bold' },
  { tag: t.constant(t.name), color: lightSyntaxColors.number },
  { tag: t.function(t.name), color: lightSyntaxColors.keyword },
  { tag: t.macro, color: lightSyntaxColors.keyword },
  { tag: t.atom, color: lightSyntaxColors.number },
  { tag: t.bool, color: lightSyntaxColors.number },
  { tag: t.labelName, color: lightSyntaxColors.keyword },
  { tag: t.inserted, color: '#059669' },
  { tag: t.deleted, color: '#dc2626' },
  { tag: t.changed, color: '#2563eb' },
  { tag: t.meta, color: lightSyntaxColors.comment },
  { tag: t.documentMeta, color: lightSyntaxColors.comment, fontWeight: 'bold' },
]);

export const lightTheme = EditorView.theme(
  {
    '&': {
      color: lightThemeColors.foreground,
      backgroundColor: lightThemeColors.background,
      fontFamily: 'var(--font-sans, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif)',
      fontSize: '14px',
      lineHeight: '1.6',
    },
    '.cm-content': {
      caretColor: lightThemeColors.caret,
      padding: '16px 0',
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: lightThemeColors.cursorBorderColor,
      borderLeftWidth: '2px',
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: lightThemeColors.selection,
    },
    '.cm-panels': {
      backgroundColor: lightThemeColors.gutterBackground,
      color: lightThemeColors.foreground,
    },
    '.cm-panels.cm-panels-top': {
      borderBottom: `1px solid ${lightThemeColors.gutterBorder}`,
    },
    '.cm-panels.cm-panels-bottom': {
      borderTop: `1px solid ${lightThemeColors.gutterBorder}`,
    },
    '.cm-searchMatch': {
      backgroundColor: '#fef08a',
      outline: '1px solid #fbbf24',
    },
    '.cm-searchMatch.cm-searchMatch-selected': {
      backgroundColor: '#fde047',
    },
    '.cm-activeLine': {
      backgroundColor: lightThemeColors.lineHighlight,
    },
    '.cm-activeLineGutter': {
      backgroundColor: lightThemeColors.lineHighlight,
      color: lightThemeColors.activeLineGutter,
    },
    '.cm-selectionMatch': {
      backgroundColor: lightThemeColors.selectionMatch,
    },
    '.cm-gutters': {
      backgroundColor: lightThemeColors.gutterBackground,
      color: lightThemeColors.gutterForeground,
      border: 'none',
      borderRight: `1px solid ${lightThemeColors.gutterBorder}`,
    },
    '.cm-lineNumbers': {
      minWidth: '3em',
      padding: '0 8px',
      fontSize: '12px',
    },
    '.cm-foldPlaceholder': {
      backgroundColor: 'transparent',
      border: 'none',
      color: lightSyntaxColors.comment,
    },
    '.cm-tooltip': {
      border: `1px solid ${lightThemeColors.gutterBorder}`,
      backgroundColor: lightThemeColors.background,
      borderRadius: '6px',
      boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
    },
    '.cm-tooltip-autocomplete': {
      '& > ul > li[aria-selected]': {
        backgroundColor: lightThemeColors.selection,
        color: lightThemeColors.foreground,
      },
    },
    '.cm-snippetField': {
      backgroundColor: lightThemeColors.selection,
    },
    '.cm-snippetFieldBackground': {
      backgroundColor: lightThemeColors.selection,
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
  { dark: false }
);

export const lightThemeExtensions: Extension[] = [
  lightTheme,
  syntaxHighlighting(lightHighlightStyle),
];
