import { ConfigData, GitCommitRecord, KeyHistoryEntry } from '../types';
export interface CommitOptions {
    authorName?: string;
    authorEmail?: string;
    operator?: string;
    autoInit?: boolean;
}
export interface FileSnapshot {
    [environment: string]: ConfigData;
}
export declare class GitTracker {
    private repoPath;
    private configDir;
    private git;
    constructor(repoPath: string);
    private initGit;
    ensureInitialized(options?: CommitOptions): Promise<void>;
    private hasCommits;
    private formatAuthor;
    saveEnvironmentSnapshot(environment: string, data: ConfigData): string;
    saveAllSnapshots(snapshot: FileSnapshot): string[];
    loadEnvironmentSnapshot(environment: string, commitHash?: string): Promise<ConfigData | null>;
    private loadSnapshotAtCommit;
    commitChanges(message: string, options?: CommitOptions): Promise<GitCommitRecord | null>;
    log(options?: {
        environment?: string;
        key?: string;
        since?: number;
        until?: number;
        limit?: number;
    }): Promise<GitCommitRecord[]>;
    getKeyHistory(environment: string, key: string, limit?: number): Promise<KeyHistoryEntry[]>;
    diffCommits(commitA: string, commitB: string, environment?: string): Promise<{
        file: string;
        changes: string;
    }[]>;
    getLastCommitHash(environment?: string): Promise<string | null>;
    formatCommitRecord(record: GitCommitRecord): Promise<string>;
    formatKeyHistory(history: KeyHistoryEntry[], key: string, environment: string): Promise<string>;
    private getValueByPath;
    getRepoPath(): string;
    getConfigDir(): string;
}
