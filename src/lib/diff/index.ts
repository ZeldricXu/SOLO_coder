import * as diff from 'diff';
import type { Change } from 'diff';
import type {
  DiffLine,
  DiffChunk,
  DiffStats,
  DiffType,
} from '../types/version';
import {
  cleanupWhitespace,
  normalizeMarkdownSync,
  generateStatsFromLines,
  escapeHtml,
} from './utils';
import { parseUnifiedDiff, generateUnifiedDiff } from './DiffParser';

export const compareLines = (
  oldText: string,
  newText: string,
  options: { ignoreWhitespace?: boolean } = {}
): Change[] => {
  let oldContent = oldText;
  let newContent = newText;

  if (options.ignoreWhitespace) {
    oldContent = cleanupWhitespace(oldContent);
    newContent = cleanupWhitespace(newContent);
  }

  return diff.diffLines(oldContent, newContent, {
    ignoreWhitespace: options.ignoreWhitespace,
    newlineIsToken: false,
  });
};

export const compareChars = (
  oldText: string,
  newText: string,
  options: { ignoreWhitespace?: boolean } = {}
): Change[] => {
  let oldContent = oldText;
  let newContent = newText;

  if (options.ignoreWhitespace) {
    oldContent = cleanupWhitespace(oldContent);
    newContent = cleanupWhitespace(newContent);
  }

  return diff.diffChars(oldContent, newContent);
};

export const compareWords = (
  oldText: string,
  newText: string,
  options: { ignoreWhitespace?: boolean } = {}
): Change[] => {
  let oldContent = oldText;
  let newContent = newText;

  if (options.ignoreWhitespace) {
    oldContent = cleanupWhitespace(oldContent);
    newContent = cleanupWhitespace(newContent);
  }

  return diff.diffWords(oldContent, newContent);
};

export const compareMarkdown = (
  oldMarkdown: string,
  newMarkdown: string,
  options: { ignoreWhitespace?: boolean; ignoreFormatting?: boolean } = {}
): Change[] => {
  let oldContent = oldMarkdown;
  let newContent = newMarkdown;

  if (options.ignoreFormatting) {
    oldContent = normalizeMarkdownSync(oldContent);
    newContent = normalizeMarkdownSync(newContent);
  }

  if (options.ignoreWhitespace) {
    oldContent = cleanupWhitespace(oldContent);
    newContent = cleanupWhitespace(newContent);
  }

  return diff.diffLines(oldContent, newContent, {
    ignoreWhitespace: options.ignoreWhitespace,
  });
};

const convertChangesToLines = (changes: Change[]): DiffLine[] => {
  const lines: DiffLine[] = [];
  let oldLineNum = 1;
  let newLineNum = 1;

  for (const change of changes) {
    const changeLines = change.value.split('\n');
    if (changeLines[changeLines.length - 1] === '') {
      changeLines.pop();
    }

    const type: DiffType = change.added
      ? 'added'
      : change.removed
        ? 'removed'
        : 'unchanged';

    for (let i = 0; i < changeLines.length; i++) {
      const lineContent = changeLines[i];
      const isLastLine = i === changeLines.length - 1;

      const line: DiffLine = {
        type,
        content: lineContent,
        lineNumberOld: null,
        lineNumberNew: null,
      };

      if (type === 'added') {
        line.lineNumberNew = newLineNum++;
      } else if (type === 'removed') {
        line.lineNumberOld = oldLineNum++;
      } else {
        line.lineNumberOld = oldLineNum++;
        line.lineNumberNew = newLineNum++;
      }

      if (isLastLine && change.value.endsWith('\n') === false) {
        line.content = lineContent + '\n';
      }

      lines.push(line);
    }
  }

  return lines;
};

const addCharLevelDiff = (lines: DiffLine[]): DiffLine[] => {
  const result: DiffLine[] = [];
  let i = 0;

  while (i < lines.length) {
    const current = lines[i];
    const next = lines[i + 1];

    if (
      current &&
      next &&
      current.type === 'removed' &&
      next.type === 'added'
    ) {
      const charChanges = diff.diffChars(current.content, next.content);

      result.push({
        ...current,
        type: 'modified',
        charChanges: charChanges.filter((c) => c.removed || !c.added),
      });

      result.push({
        ...next,
        type: 'modified',
        charChanges: charChanges.filter((c) => c.added || !c.removed),
      });

      i += 2;
    } else if (current) {
      result.push(current);
      i++;
    } else {
      i++;
    }
  }

  return result;
};

const groupLinesIntoChunks = (
  lines: DiffLine[],
  contextLines = 3
): DiffChunk[] => {
  const chunks: DiffChunk[] = [];
  const changedIndices: number[] = [];

  lines.forEach((line, index) => {
    if (line.type !== 'unchanged') {
      changedIndices.push(index);
    }
  });

  if (changedIndices.length === 0) {
    return [];
  }

  let chunkStart = Math.max(0, changedIndices[0] - contextLines);
  let chunkEnd = Math.min(lines.length - 1, changedIndices[0] + contextLines);

  for (let i = 1; i < changedIndices.length; i++) {
    const currentChanged = changedIndices[i];
    if (currentChanged <= chunkEnd + contextLines * 2 + 1) {
      chunkEnd = Math.min(lines.length - 1, currentChanged + contextLines);
    } else {
      const chunkLines = lines.slice(chunkStart, chunkEnd + 1);
      const firstLine = chunkLines[0];
      const lastLine = chunkLines[chunkLines.length - 1];

      chunks.push({
        oldStart: firstLine?.lineNumberOld ?? chunkStart + 1,
        oldLines:
          (lastLine?.lineNumberOld ?? chunkEnd + 1) -
          (firstLine?.lineNumberOld ?? chunkStart + 1) +
          1,
        newStart: firstLine?.lineNumberNew ?? chunkStart + 1,
        newLines:
          (lastLine?.lineNumberNew ?? chunkEnd + 1) -
          (firstLine?.lineNumberNew ?? chunkStart + 1) +
          1,
        lines: chunkLines,
      });

      chunkStart = Math.max(0, currentChanged - contextLines);
      chunkEnd = Math.min(lines.length - 1, currentChanged + contextLines);
    }
  }

  const chunkLines = lines.slice(chunkStart, chunkEnd + 1);
  const firstLine = chunkLines[0];
  const lastLine = chunkLines[chunkLines.length - 1];

  chunks.push({
    oldStart: firstLine?.lineNumberOld ?? chunkStart + 1,
    oldLines:
      (lastLine?.lineNumberOld ?? chunkEnd + 1) -
      (firstLine?.lineNumberOld ?? chunkStart + 1) +
      1,
    newStart: firstLine?.lineNumberNew ?? chunkStart + 1,
    newLines:
      (lastLine?.lineNumberNew ?? chunkEnd + 1) -
      (firstLine?.lineNumberNew ?? chunkStart + 1) +
      1,
    lines: chunkLines,
  });

  return chunks;
};

export const compareLinesToChunks = (
  oldText: string,
  newText: string,
  options: {
    ignoreWhitespace?: boolean;
    includeCharDiff?: boolean;
    contextLines?: number;
  } = {}
): DiffChunk[] => {
  const {
    ignoreWhitespace = false,
    includeCharDiff = true,
    contextLines = 3,
  } = options;

  const changes = compareLines(oldText, newText, { ignoreWhitespace });
  let lines = convertChangesToLines(changes);

  if (includeCharDiff) {
    lines = addCharLevelDiff(lines);
  }

  return groupLinesIntoChunks(lines, contextLines);
};

export const generateDiffStats = (
  oldText: string,
  newText: string,
  options: { ignoreWhitespace?: boolean } = {}
): DiffStats => {
  const chunks = compareLinesToChunks(oldText, newText, {
    ...options,
    includeCharDiff: false,
  });
  const allLines = chunks.flatMap((chunk) => chunk.lines);
  return generateStatsFromLines(allLines);
};

export const renderDiffToHtml = (
  chunks: DiffChunk[],
  options: { showLineNumbers?: boolean; charLevelHighlight?: boolean } = {}
): string => {
  const { showLineNumbers = true, charLevelHighlight = true } = options;

  let html = '<div class="diff-container">';

  for (const chunk of chunks) {
    html += '<div class="diff-chunk">';
    html += `<div class="diff-chunk-header">@@ -${chunk.oldStart},${chunk.oldLines} +${chunk.newStart},${chunk.newLines} @@</div>`;

    for (const line of chunk.lines) {
      const lineClass = `diff-line diff-${line.type}`;
      let contentHtml = '';

      if (charLevelHighlight && line.charChanges && line.charChanges.length > 0) {
        for (const change of line.charChanges) {
          const escaped = escapeHtml(change.value);
          if (change.added) {
            contentHtml += `<span class="diff-char-added">${escaped}</span>`;
          } else if (change.removed) {
            contentHtml += `<span class="diff-char-removed">${escaped}</span>`;
          } else {
            contentHtml += escaped;
          }
        }
      } else {
        contentHtml = escapeHtml(line.content);
      }

      html += `<div class="${lineClass}">`;
      if (showLineNumbers) {
        html += `<span class="diff-line-number-old">${line.lineNumberOld ?? ''}</span>`;
        html += `<span class="diff-line-number-new">${line.lineNumberNew ?? ''}</span>`;
      }
      html += `<span class="diff-line-content">${contentHtml}</span>`;
      html += '</div>';
    }

    html += '</div>';
  }

  html += '</div>';
  return html;
};

export { parseUnifiedDiff, generateUnifiedDiff };
