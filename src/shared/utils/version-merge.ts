export interface DiffChange {
  type: 'insert' | 'delete' | 'equal';
  content: string;
  localIndex?: number;
  remoteIndex?: number;
}

export interface DiffBlock {
  type: 'common' | 'local-only' | 'remote-only' | 'modified';
  localContent: string[];
  remoteContent: string[];
  startLine: { local: number; remote: number };
}

export interface MergeOptions {
  preserveBlockStructure?: boolean;
  preferLocal?: boolean;
  enableSmartMerge?: boolean;
  markConflicts?: boolean;
}

export const DEFAULT_MERGE_OPTIONS: MergeOptions = {
  preserveBlockStructure: true,
  preferLocal: false,
  enableSmartMerge: true,
  markConflicts: false,
};

export interface MergeResult {
  mergedContent: string;
  blocks: DiffBlock[];
  stats: {
    totalBlocks: number;
    commonBlocks: number;
    localOnlyBlocks: number;
    remoteOnlyBlocks: number;
    modifiedBlocks: number;
    conflicts: number;
  };
  conflictsResolved: boolean;
}

export interface NoteVersion {
  note_id: string;
  title: string;
  content: string;
  tags: string[];
  version: number;
  updated_at: string;
}

export type DiffAlgorithm = 'myers' | 'hunt-szymanski' | 'simple';

export class VersionMerge {
  private options: MergeOptions;

  constructor(options: MergeOptions = DEFAULT_MERGE_OPTIONS) {
    this.options = { ...DEFAULT_MERGE_OPTIONS, ...options };
  }

  merge(
    localVersion: NoteVersion,
    remoteVersion: NoteVersion,
    options?: MergeOptions
  ): MergeResult {
    const mergeOpts = { ...this.options, ...options };

    const localLines = this.splitIntoLines(localVersion.content);
    const remoteLines = this.splitIntoLines(remoteVersion.content);

    const blocks = this.computeDiffBlocks(localLines, remoteLines);

    const mergedLines = this.blocksToLines(blocks, mergeOpts);

    const stats = this.computeStats(blocks);

    const mergedContent = this.normalizeContent(mergedLines.join('\n'));

    const mergedTags = this.mergeTags(localVersion.tags, remoteVersion.tags);

    return {
      mergedContent,
      blocks,
      stats,
      conflictsResolved: stats.conflicts === 0 || !mergeOpts.markConflicts,
    };
  }

  private splitIntoLines(content: string): string[] {
    if (!content) return [];
    return content.split(/\r?\n/);
  }

  private normalizeContent(content: string): string {
    return content
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  computeDiffBlocks(localLines: string[], remoteLines: string[]): DiffBlock[] {
    const blocks: DiffBlock[] = [];
    
    const myersDiff = this.myersDiff(localLines, remoteLines);
    
    let currentBlock: Partial<DiffBlock> | null = null;
    let localLine = 0;
    let remoteLine = 0;

    for (const change of myersDiff) {
      const changeType = this.classifyChange(change);

      if (!currentBlock || currentBlock.type !== changeType) {
        if (currentBlock) {
          blocks.push(currentBlock as DiffBlock);
        }
        currentBlock = {
          type: changeType,
          localContent: [],
          remoteContent: [],
          startLine: { local: localLine, remote: remoteLine },
        };
      }

      if (change.type === 'insert') {
        currentBlock.remoteContent.push(change.content);
        remoteLine++;
      } else if (change.type === 'delete') {
        currentBlock.localContent.push(change.content);
        localLine++;
      } else {
        currentBlock.localContent.push(change.content);
        currentBlock.remoteContent.push(change.content);
        localLine++;
        remoteLine++;
      }
    }

    if (currentBlock) {
      blocks.push(currentBlock as DiffBlock);
    }

    return this.refineBlocks(blocks);
  }

  private classifyChange(change: DiffChange): DiffBlock['type'] {
    if (change.type === 'equal') {
      return 'common';
    }
    if (change.type === 'insert') {
      return 'remote-only';
    }
    if (change.type === 'delete') {
      return 'local-only';
    }
    return 'common';
  }

  private refineBlocks(blocks: DiffBlock[]): DiffBlock[] {
    const refined: DiffBlock[] = [];
    
    for (let i = 0; i < blocks.length; i++) {
      const block = blocks[i];
      
      if (i + 1 < blocks.length) {
        const nextBlock = blocks[i + 1];
        
        if (
          (block.type === 'local-only' && nextBlock.type === 'remote-only') ||
          (block.type === 'remote-only' && nextBlock.type === 'local-only')
        ) {
          refined.push({
            type: 'modified',
            localContent: block.type === 'local-only' ? block.localContent : [],
            remoteContent: nextBlock.type === 'remote-only' ? nextBlock.remoteContent : [],
            startLine: block.startLine,
          });
          i++;
          continue;
        }
      }
      
      refined.push(block);
    }

    return refined;
  }

  myersDiff(localLines: string[], remoteLines: string[]): DiffChange[] {
    const changes: DiffChange[] = [];
    
    const m = localLines.length;
    const n = remoteLines.length;
    
    if (m === 0 && n === 0) {
      return [];
    }
    
    if (m === 0) {
      for (let i = 0; i < n; i++) {
        changes.push({
          type: 'insert',
          content: remoteLines[i],
          remoteIndex: i,
        });
      }
      return changes;
    }
    
    if (n === 0) {
      for (let i = 0; i < m; i++) {
        changes.push({
          type: 'delete',
          content: localLines[i],
          localIndex: i,
        });
      }
      return changes;
    }

    const lcs = this.longestCommonSubsequence(localLines, remoteLines);
    
    let i = 0;
    let j = 0;
    
    for (const commonLine of lcs) {
      while (i < m && localLines[i] !== commonLine) {
        changes.push({
          type: 'delete',
          content: localLines[i],
          localIndex: i,
        });
        i++;
      }
      
      while (j < n && remoteLines[j] !== commonLine) {
        changes.push({
          type: 'insert',
          content: remoteLines[j],
          remoteIndex: j,
        });
        j++;
      }
      
      if (i < m && j < n) {
        changes.push({
          type: 'equal',
          content: localLines[i],
          localIndex: i,
          remoteIndex: j,
        });
        i++;
        j++;
      }
    }
    
    while (i < m) {
      changes.push({
        type: 'delete',
        content: localLines[i],
        localIndex: i,
      });
      i++;
    }
    
    while (j < n) {
      changes.push({
        type: 'insert',
        content: remoteLines[j],
        remoteIndex: j,
      });
      j++;
    }
    
    return changes;
  }

  private longestCommonSubsequence(a: string[], b: string[]): string[] {
    const m = a.length;
    const n = b.length;
    
    const dp: number[][] = Array(m + 1)
      .fill(null)
      .map(() => Array(n + 1).fill(0));
    
    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (a[i - 1] === b[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }
    
    const result: string[] = [];
    let i = m;
    let j = n;
    
    while (i > 0 && j > 0) {
      if (a[i - 1] === b[j - 1]) {
        result.unshift(a[i - 1]);
        i--;
        j--;
      } else if (dp[i - 1][j] > dp[i][j - 1]) {
        i--;
      } else {
        j--;
      }
    }
    
    return result;
  }

  private blocksToLines(blocks: DiffBlock[], options: MergeOptions): string[] {
    const lines: string[] = [];

    for (const block of blocks) {
      switch (block.type) {
        case 'common':
          lines.push(...block.localContent);
          break;

        case 'local-only':
          if (options.preferLocal !== false || options.enableSmartMerge) {
            lines.push(...block.localContent);
          }
          break;

        case 'remote-only':
          if (options.preferLocal !== true || options.enableSmartMerge) {
            lines.push(...block.remoteContent);
          }
          break;

        case 'modified':
          if (options.enableSmartMerge) {
            const merged = this.smartMergeModifiedBlock(block, options);
            lines.push(...merged);
          } else if (options.preferLocal) {
            lines.push(...block.localContent);
          } else {
            lines.push(...block.remoteContent);
          }
          break;
      }
    }

    return lines;
  }

  private smartMergeModifiedBlock(block: DiffBlock, options: MergeOptions): string[] {
    const localLines = block.localContent;
    const remoteLines = block.remoteContent;
    const result: string[] = [];
    const added = new Set<string>();

    const localIsBlockStart = this.isBlockStart(localLines[0] || '');
    const remoteIsBlockStart = this.isBlockStart(remoteLines[0] || '');

    if (localIsBlockStart && remoteIsBlockStart) {
      const localBlockType = this.getBlockType(localLines[0] || '');
      const remoteBlockType = this.getBlockType(remoteLines[0] || '');

      if (localBlockType === remoteBlockType && options.preserveBlockStructure) {
        const mergedContent = this.mergeBlockContents(
          localLines.slice(1),
          remoteLines.slice(1)
        );
        result.push(localLines[0]);
        result.push(...mergedContent);
        return result;
      }
    }

    for (const line of localLines) {
      const trimmed = line.trim();
      if (trimmed && !added.has(trimmed)) {
        added.add(trimmed);
        result.push(line);
      }
    }

    for (const line of remoteLines) {
      const trimmed = line.trim();
      if (trimmed && !added.has(trimmed)) {
        added.add(trimmed);
        result.push(line);
      }
    }

    return result;
  }

  private mergeBlockContents(localLines: string[], remoteLines: string[]): string[] {
    const result: string[] = [];
    const added = new Set<string>();

    const localItems = this.extractListItems(localLines);
    const remoteItems = this.extractListItems(remoteLines);

    const allItems = new Set([...localItems.keys(), ...remoteItems.keys()]);

    for (const item of allItems) {
      if (localItems.has(item) && remoteItems.has(item)) {
        const localContent = localItems.get(item) || item;
        const remoteContent = remoteItems.get(item) || item;
        
        if (localContent === remoteContent) {
          result.push(localContent);
        } else {
          result.push(localContent);
          result.push(remoteContent);
        }
      } else if (localItems.has(item)) {
        result.push(localItems.get(item) || item);
      } else {
        result.push(remoteItems.get(item) || item);
      }
    }

    return result.length > 0 ? result : [...localLines, ...remoteLines];
  }

  private extractListItems(lines: string[]): Map<string, string> {
    const items = new Map<string, string>();
    
    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
        const content = trimmed.slice(2);
        items.set(content, line);
      } else if (trimmed.match(/^\d+\.\s/)) {
        const content = trimmed.replace(/^\d+\.\s/, '');
        items.set(content, line);
      } else if (trimmed) {
        items.set(trimmed, line);
      }
    }
    
    return items;
  }

  private isBlockStart(line: string): boolean {
    const trimmed = line.trim();
    return (
      trimmed.startsWith('#') ||
      trimmed.startsWith('>') ||
      trimmed.startsWith('- ') ||
      trimmed.startsWith('* ') ||
      trimmed.match(/^\d+\.\s/) !== null ||
      trimmed.startsWith('```')
    );
  }

  private getBlockType(line: string): string {
    const trimmed = line.trim();
    if (trimmed.startsWith('### ')) return 'heading-three';
    if (trimmed.startsWith('## ')) return 'heading-two';
    if (trimmed.startsWith('# ')) return 'heading-one';
    if (trimmed.startsWith('> ')) return 'quote';
    if (trimmed.startsWith('```')) return 'code';
    if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) return 'list';
    if (trimmed.match(/^\d+\.\s/)) return 'numbered-list';
    return 'paragraph';
  }

  mergeTags(localTags: string[], remoteTags: string[]): string[] {
    const merged = new Set([...localTags, ...remoteTags]);
    return Array.from(merged);
  }

  private computeStats(blocks: DiffBlock[]): MergeResult['stats'] {
    let commonBlocks = 0;
    let localOnlyBlocks = 0;
    let remoteOnlyBlocks = 0;
    let modifiedBlocks = 0;
    let conflicts = 0;

    for (const block of blocks) {
      switch (block.type) {
        case 'common':
          commonBlocks++;
          break;
        case 'local-only':
          localOnlyBlocks++;
          break;
        case 'remote-only':
          remoteOnlyBlocks++;
          break;
        case 'modified':
          modifiedBlocks++;
          conflicts++;
          break;
      }
    }

    return {
      totalBlocks: blocks.length,
      commonBlocks,
      localOnlyBlocks,
      remoteOnlyBlocks,
      modifiedBlocks,
      conflicts,
    };
  }

  static mergeVersions(
    local: NoteVersion,
    remote: NoteVersion,
    options?: MergeOptions
  ): MergeResult {
    const merger = new VersionMerge(options);
    return merger.merge(local, remote, options);
  }

  static computeDiff(
    localContent: string,
    remoteContent: string
  ): DiffChange[] {
    const merger = new VersionMerge();
    const localLines = merger.splitIntoLines(localContent);
    const remoteLines = merger.splitIntoLines(remoteContent);
    return merger.myersDiff(localLines, remoteLines);
  }

  static mergeTagsArrays(tags1: string[], tags2: string[]): string[] {
    const merger = new VersionMerge();
    return merger.mergeTags(tags1, tags2);
  }
}

export default VersionMerge;
