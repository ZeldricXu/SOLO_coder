import git from 'isomorphic-git';
import http from '@isomorphic-git/http/node';
import fs from 'fs';
import type { GitStatus, GitCommit, GitConfig, DiffHunk, DiffLine, GitProgress } from '@shared/types';
import { normalizePath, joinPaths, relativePath } from '@shared/utils/path';
import { EventEmitter } from 'events';

export class GitService extends EventEmitter {
  private dir: string;
  private config: GitConfig;

  constructor(dir: string, config: Partial<GitConfig> = {}) {
    super();
    this.dir = dir;
    this.config = {
      userName: config.userName || 'KnowledgeForge User',
      userEmail: config.userEmail || 'user@knowledgeforge.app',
      remoteName: config.remoteName || 'origin',
      remoteUrl: config.remoteUrl,
      autoCommit: config.autoCommit ?? true,
      autoCommitInterval: config.autoCommitInterval ?? 30000,
      autoPush: config.autoPush ?? false,
    };
  }

  async init(): Promise<void> {
    try {
      await git.init({ fs, dir: this.dir });
    } catch (e) {
      if ((e as Error).message.includes('already exists')) return;
      throw e;
    }
  }

  async isRepository(): Promise<boolean> {
    try {
      await git.currentBranch({ fs, dir: this.dir });
      return true;
    } catch {
      return false;
    }
  }

  async clone(url: string, branch?: string): Promise<void> {
    await git.clone({
      fs,
      http,
      dir: this.dir,
      url,
      ref: branch,
      singleBranch: false,
      onProgress: (progress: any) => {
        this.emit('progress', {
          type: 'clone',
          phase: progress.phase,
          loaded: progress.loaded,
          total: progress.total,
        } as GitProgress);
      },
    });
  }

  async status(): Promise<GitStatus[]> {
    const matrix = await git.statusMatrix({ fs, dir: this.dir });
    const statuses: GitStatus[] = [];

    for (const [filepath, head, workdir, stage] of matrix) {
      if (filepath.startsWith('.')) continue;
      if (filepath === 'node_modules') continue;

      let status: GitStatus['status'];
      if (head === 0 && workdir === 2 && stage === 0) status = 'untracked';
      else if (head === 1 && workdir === 0 && stage === 1) status = 'deleted';
      else if (head === 1 && workdir === 2 && stage === 1) status = 'modified';
      else if (head === 1 && workdir === 2 && stage === 0) status = 'modified';
      else if (head === 0 && workdir === 2 && stage === 2) status = 'added';
      else continue;

      statuses.push({
        filepath: normalizePath(filepath),
        status,
        staged: stage !== workdir,
      });
    }

    return statuses;
  }

  async add(filepaths: string[]): Promise<void> {
    for (const filepath of filepaths) {
      const relative = relativePath(this.dir, filepath);
      await git.add({ fs, dir: this.dir, filepath: relative });
    }
  }

  async commit(message: string): Promise<string> {
    const sha = await git.commit({
      fs,
      dir: this.dir,
      message,
      author: {
        name: this.config.userName,
        email: this.config.userEmail,
      },
    });
    return sha;
  }

  async push(): Promise<void> {
    if (!this.config.remoteUrl) {
      throw new Error('Remote URL not configured');
    }

    await git.push({
      fs,
      http,
      dir: this.dir,
      remote: this.config.remoteName,
      url: this.config.remoteUrl,
      onProgress: (progress: any) => {
        this.emit('progress', {
          type: 'push',
          phase: progress.phase,
          loaded: progress.loaded,
          total: progress.total,
        } as GitProgress);
      },
    });
  }

  async pull(): Promise<void> {
    if (!this.config.remoteUrl) {
      throw new Error('Remote URL not configured');
    }

    await git.pull({
      fs,
      http,
      dir: this.dir,
      remote: this.config.remoteName,
      url: this.config.remoteUrl,
      author: {
        name: this.config.userName,
        email: this.config.userEmail,
      },
      onProgress: (progress: any) => {
        this.emit('progress', {
          type: 'pull',
          phase: progress.phase,
          loaded: progress.loaded,
          total: progress.total,
        } as GitProgress);
      },
    });
  }

  async log(maxCount: number = 50): Promise<GitCommit[]> {
    const commits = await git.log({
      fs,
      dir: this.dir,
      depth: maxCount,
    });

    return commits.map((commit) => ({
      sha: commit.oid,
      message: commit.commit.message,
      timestamp: new Date(commit.commit.timestamp * 1000),
      author: {
        name: commit.commit.author.name,
        email: commit.commit.author.email,
      },
      changes: [],
    }));
  }

  async diff(filepath: string): Promise<DiffHunk[]> {
    const relative = relativePath(this.dir, filepath);
    
    let oldContent = '';
    try {
      const oldBlob = await git.readBlob({
        fs,
        dir: this.dir,
        filepath: relative,
        oid: 'HEAD',
      });
      oldContent = Buffer.from(oldBlob.blob).toString('utf-8');
    } catch {
      // New file, no old content
    }

    const newContent = fs.readFileSync(joinPaths(this.dir, relative), 'utf-8');
    
    return this.computeDiff(oldContent, newContent);
  }

  private computeDiff(oldContent: string, newContent: string): DiffHunk[] {
    const oldLines = oldContent.split('\n');
    const newLines = newContent.split('\n');
    
    const hunks: DiffHunk[] = [];
    let oldLineNum = 1;
    let newLineNum = 1;
    let i = 0;
    let j = 0;

    while (i < oldLines.length || j < newLines.length) {
      if (i < oldLines.length && j < newLines.length && oldLines[i] === newLines[j]) {
        i++;
        j++;
        oldLineNum++;
        newLineNum++;
      } else {
        const hunkLines: DiffLine[] = [];
        const oldStart = oldLineNum;
        const newStart = newLineNum;
        let oldLinesCount = 0;
        let newLinesCount = 0;

        while (i < oldLines.length && (j >= newLines.length || oldLines[i] !== newLines[j])) {
          hunkLines.push({
            type: 'removed',
            content: oldLines[i],
            oldLineNumber: oldLineNum++,
            newLineNumber: null,
          });
          i++;
          oldLinesCount++;
        }

        while (j < newLines.length && (i >= oldLines.length || oldLines[i] !== newLines[j])) {
          hunkLines.push({
            type: 'added',
            content: newLines[j],
            oldLineNumber: null,
            newLineNumber: newLineNum++,
          });
          j++;
          newLinesCount++;
        }

        if (hunkLines.length > 0) {
          hunks.push({
            oldStart,
            oldLines: oldLinesCount,
            newStart,
            newLines: newLinesCount,
            lines: hunkLines,
          });
        }
      }
    }

    return hunks;
  }

  async autoCommit(changedFiles: string[]): Promise<string | null> {
    if (!this.config.autoCommit || changedFiles.length === 0) return null;

    const statuses = await this.status();
    const modifiedFiles = statuses.filter(s => s.status === 'modified' || s.status === 'untracked');
    
    if (modifiedFiles.length === 0) return null;

    const relativePaths = modifiedFiles.map(s => relativePath(this.dir, joinPaths(this.dir, s.filepath)));
    await this.add(relativePaths);
    
    const message = `auto-commit: ${new Date().toISOString()}`;
    const sha = await this.commit(message);

    if (this.config.autoPush && this.config.remoteUrl) {
      try {
        await this.push();
      } catch (e) {
        console.error('Auto-push failed:', e);
      }
    }

    return sha;
  }

  setConfig(config: Partial<GitConfig>): void {
    this.config = { ...this.config, ...config };
  }

  getConfig(): GitConfig {
    return { ...this.config };
  }

  async currentBranch(): Promise<string | null> {
    try {
      return await git.currentBranch({ fs, dir: this.dir, fullname: false });
    } catch {
      return null;
    }
  }

  async listBranches(): Promise<string[]> {
    try {
      const branches = await git.listBranches({ fs, dir: this.dir });
      return branches.filter(b => b !== 'HEAD');
    } catch {
      return [];
    }
  }

  async hasConflicts(): Promise<boolean> {
    const status = await this.status();
    return status.some(s => s.status === 'modified' && s.staged);
  }
}
