import * as diff from 'diff';
import type { DiffLine, DiffChunk, DiffType } from '../types/version';

export const parseUnifiedDiff = (unifiedDiff: string): DiffChunk[] => {
  const lines = unifiedDiff.split('\n');
  const chunks: DiffChunk[] = [];
  let currentChunk: DiffChunk | null = null;
  let oldLineNum = 0;
  let newLineNum = 0;

  const chunkHeaderRegex = /^@@ -(\d+),(\d+) \+(\d+),(\d+) @@/;

  for (const line of lines) {
    const headerMatch = line.match(chunkHeaderRegex);
    if (headerMatch) {
      if (currentChunk) {
        chunks.push(currentChunk);
      }
      const oldStart = parseInt(headerMatch[1], 10);
      const oldLines = parseInt(headerMatch[2], 10);
      const newStart = parseInt(headerMatch[3], 10);
      const newLines = parseInt(headerMatch[4], 10);
      oldLineNum = oldStart;
      newLineNum = newStart;
      currentChunk = {
        oldStart,
        oldLines,
        newStart,
        newLines,
        lines: [],
      };
      continue;
    }

    if (!currentChunk) {
      continue;
    }

    let type: DiffType = 'unchanged';
    let content = line;
    let lineNumberOld: number | null = null;
    let lineNumberNew: number | null = null;

    if (line.startsWith('+')) {
      type = 'added';
      content = line.slice(1);
      lineNumberNew = newLineNum++;
    } else if (line.startsWith('-')) {
      type = 'removed';
      content = line.slice(1);
      lineNumberOld = oldLineNum++;
    } else if (line.startsWith(' ')) {
      type = 'unchanged';
      content = line.slice(1);
      lineNumberOld = oldLineNum++;
      lineNumberNew = newLineNum++;
    } else if (line === '\\ No newline at end of file') {
      continue;
    } else if (line === '') {
      continue;
    } else {
      type = 'unchanged';
      lineNumberOld = oldLineNum++;
      lineNumberNew = newLineNum++;
    }

    currentChunk.lines.push({
      type,
      content,
      lineNumberOld,
      lineNumberNew,
    });
  }

  if (currentChunk) {
    chunks.push(currentChunk);
  }

  return chunks;
};

export const generateUnifiedDiff = (
  oldText: string,
  newText: string,
  oldFileName = 'old',
  newFileName = 'new',
  contextLines = 3
): string => {
  const patch = diff.createTwoFilesPatch(
    oldFileName,
    newFileName,
    oldText,
    newText,
    undefined,
    undefined,
    { context: contextLines }
  );
  return patch;
};

export const applyPatch = (originalText: string, unifiedDiff: string): string => {
  const patches = diff.parsePatch(unifiedDiff);
  let result = originalText;

  for (const patch of patches) {
    const applyResult = diff.applyPatch(result, patch);
    if (applyResult === false) {
      throw new Error('补丁应用失败');
    }
    result = applyResult;
  }

  return result;
};

export const revertPatch = (modifiedText: string, unifiedDiff: string): string => {
  const patches = diff.parsePatch(unifiedDiff);
  const reversedPatches = patches.map((patch) => {
    const reversedHunks = patch.hunks.map((hunk) => {
      const reversedLines = hunk.lines.map((line) => {
        if (line.startsWith('+')) {
          return '-' + line.slice(1);
        }
        if (line.startsWith('-')) {
          return '+' + line.slice(1);
        }
        return line;
      });
      return {
        ...hunk,
        oldStart: hunk.newStart,
        oldLines: hunk.newLines,
        newStart: hunk.oldStart,
        newLines: hunk.oldLines,
        lines: reversedLines,
      };
    });
    return {
      ...patch,
      oldFileName: patch.newFileName,
      newFileName: patch.oldFileName,
      hunks: reversedHunks,
    };
  });

  let result = modifiedText;
  for (const patch of reversedPatches) {
    const applyResult = diff.applyPatch(result, patch);
    if (applyResult === false) {
      throw new Error('补丁还原失败');
    }
    result = applyResult;
  }

  return result;
};

export const chunksToUnifiedDiff = (
  chunks: DiffChunk[],
  oldFileName = 'old',
  newFileName = 'new'
): string => {
  let diff = `--- ${oldFileName}\n+++ ${newFileName}\n`;

  for (const chunk of chunks) {
    diff += `@@ -${chunk.oldStart},${chunk.oldLines} +${chunk.newStart},${chunk.newLines} @@\n`;
    for (const line of chunk.lines) {
      switch (line.type) {
        case 'added':
          diff += `+${line.content}\n`;
          break;
        case 'removed':
          diff += `-${line.content}\n`;
          break;
        case 'unchanged':
        case 'modified':
          diff += ` ${line.content}\n`;
          break;
      }
    }
  }

  return diff;
};
