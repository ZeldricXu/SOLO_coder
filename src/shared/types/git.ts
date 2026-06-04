export interface GitStatus {
  filepath: string;
  status: 'modified' | 'added' | 'deleted' | 'untracked' | 'ignored';
  staged: boolean;
}

export interface GitCommit {
  sha: string;
  message: string;
  timestamp: Date;
  author: {
    name: string;
    email: string;
  };
  changes: GitStatus[];
}

export interface GitConfig {
  userName: string;
  userEmail: string;
  remoteUrl?: string;
  remoteName: string;
  autoCommit: boolean;
  autoCommitInterval: number;
  autoPush: boolean;
}

export interface DiffLine {
  type: 'added' | 'removed' | 'unchanged';
  content: string;
  oldLineNumber: number | null;
  newLineNumber: number | null;
}

export interface DiffHunk {
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  lines: DiffLine[];
}

export interface MergeConflict {
  filepath: string;
  hunks: Array<{
    ours: string[];
    theirs: string[];
    base?: string[];
    startLine: number;
  }>;
}

export interface GitProgress {
  type: 'push' | 'pull' | 'clone' | 'fetch';
  phase: string;
  loaded: number;
  total: number;
}
