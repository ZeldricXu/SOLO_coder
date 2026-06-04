import { remark } from 'remark';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import type { DiffLine, DiffChunk, DiffStats } from '../types/version';

export const cleanupWhitespace = (text: string): string => {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
};

export const normalizeMarkdown = async (markdown: string): Promise<string> => {
  const cleaned = cleanupWhitespace(markdown);
  try {
    const file = await remark()
      .use(remarkParse)
      .use(remarkGfm)
      .use(remarkStringify, {
        bullet: '-',
        fence: '`',
        fences: true,
        incrementListMarker: false,
        listItemIndent: 'one',
        quote: '"',
        strong: '*',
        tablePipeAlign: true,
      })
      .process(cleaned);
    return String(file).trim();
  } catch {
    return cleaned;
  }
};

export const normalizeMarkdownSync = (markdown: string): string => {
  let result = cleanupWhitespace(markdown);
  result = result.replace(/^######\s+/gm, '###### ');
  result = result.replace(/^#####\s+/gm, '##### ');
  result = result.replace(/^####\s+/gm, '#### ');
  result = result.replace(/^###\s+/gm, '### ');
  result = result.replace(/^##\s+/gm, '## ');
  result = result.replace(/^#\s+/gm, '# ');
  result = result.replace(/\*\*(.+?)\*\*/g, '**$1**');
  result = result.replace(/\*(.+?)\*/g, '*$1*');
  result = result.replace(/`(.+?)`/g, '`$1`');
  return result;
};

export const getLineChanges = (chunks: DiffChunk[]): number[] => {
  const changedLines = new Set<number>();
  for (const chunk of chunks) {
    for (const line of chunk.lines) {
      if (line.type === 'added' && line.lineNumberNew !== null) {
        changedLines.add(line.lineNumberNew);
      } else if (line.type === 'removed' && line.lineNumberOld !== null) {
        changedLines.add(line.lineNumberOld);
      } else if (line.type === 'modified') {
        if (line.lineNumberOld !== null) {
          changedLines.add(line.lineNumberOld);
        }
        if (line.lineNumberNew !== null) {
          changedLines.add(line.lineNumberNew);
        }
      }
    }
  }
  return Array.from(changedLines).sort((a, b) => a - b);
};

export const formatDiffStat = (stats: DiffStats): string => {
  const parts: string[] = [];
  if (stats.added > 0) {
    parts.push(`+${stats.added} 新增`);
  }
  if (stats.removed > 0) {
    parts.push(`-${stats.removed} 删除`);
  }
  if (stats.modified > 0) {
    parts.push(`~${stats.modified} 修改`);
  }
  if (parts.length === 0) {
    return '无变更';
  }
  return parts.join('，');
};

export const generateStatsFromLines = (lines: DiffLine[]): DiffStats => {
  let added = 0;
  let removed = 0;
  let modified = 0;
  let unchanged = 0;

  for (const line of lines) {
    switch (line.type) {
      case 'added':
        added++;
        break;
      case 'removed':
        removed++;
        break;
      case 'modified':
        modified++;
        break;
      case 'unchanged':
        unchanged++;
        break;
    }
  }

  return {
    added,
    removed,
    modified,
    unchanged,
    total: added + removed + modified + unchanged,
  };
};

export const mergeStats = (...statsArray: DiffStats[]): DiffStats => {
  return statsArray.reduce(
    (acc, stats) => ({
      added: acc.added + stats.added,
      removed: acc.removed + stats.removed,
      modified: acc.modified + stats.modified,
      unchanged: acc.unchanged + stats.unchanged,
      total: acc.total + stats.total,
    }),
    { added: 0, removed: 0, modified: 0, unchanged: 0, total: 0 }
  );
};

export const escapeHtml = (text: string): string => {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
};
