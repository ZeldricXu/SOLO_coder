import { BaseSource } from './BaseSource';
import {
  KnowledgeDocument,
  FetchOptions,
  FetchResult,
  SourceType,
  KnowledgeSource,
  RetryConfig,
  HeadingNode,
} from './types';
import { MarkdownNormalizer } from './MarkdownNormalizer';
import * as fs from 'fs';
import * as path from 'path';
import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);

interface GithubWikiConfig {
  repoUrl: string;
  authToken?: string;
  username?: string;
  cloneDir?: string;
  branch?: string;
  includeExtensions?: string[];
  excludePatterns?: string[];
}

interface WikiFile {
  path: string;
  content: string;
  lastModified: Date;
  createdAt?: Date;
  sha?: string;
}

interface GitCommitInfo {
  sha: string;
  date: Date;
  author: string;
  message: string;
}

export class GithubWikiSource extends BaseSource {
  private config: GithubWikiConfig;
  private readonly normalizer: MarkdownNormalizer;
  private cloneDirPath: string;
  private readonly DEFAULT_EXTENSIONS = ['.md', '.markdown', '.mdown', '.mkd'];

  constructor(
    source: KnowledgeSource,
    retryConfig?: Partial<RetryConfig>
  ) {
    super(source, retryConfig);
    this.config = this.parseConfig(source.config);
    this.normalizer = new MarkdownNormalizer({ sourceType: 'github_wiki' });
    this.cloneDirPath = this.config.cloneDir || this.getDefaultCloneDir();
  }

  get sourceType(): SourceType {
    return 'github_wiki';
  }

  private parseConfig(config: Record<string, unknown>): GithubWikiConfig {
    const repoUrl = config.repoUrl as string;

    if (!repoUrl) {
      throw new Error('GitHub Wiki repoUrl is required');
    }

    return {
      repoUrl,
      authToken: config.authToken as string | undefined,
      username: config.username as string | undefined,
      cloneDir: config.cloneDir as string | undefined,
      branch: (config.branch as string) || 'master',
      includeExtensions: (config.includeExtensions as string[]) || this.DEFAULT_EXTENSIONS,
      excludePatterns: (config.excludePatterns as string[]) || [],
    };
  }

  private getDefaultCloneDir(): string {
    const tempDir = process.env.TMPDIR || '/tmp';
    const repoName = this.extractRepoName(this.config.repoUrl);
    return path.join(tempDir, `github-wiki-${this.source.id}-${repoName}`);
  }

  private extractRepoName(url: string): string {
    const match = url.match(/github\.com[:/]([^/]+)\/([^/.]+)/);
    if (match) {
      return `${match[1]}-${match[2]}`;
    }
    return path.basename(url).replace(/\.git$/, '');
  }

  private getAuthenticatedUrl(): string {
    if (this.config.authToken && this.config.username) {
      const encodedToken = encodeURIComponent(this.config.authToken);
      return this.config.repoUrl.replace(
        /^https?:\/\//,
        `https://${this.config.username}:${encodedToken}@`
      );
    }
    if (this.config.authToken) {
      const encodedToken = encodeURIComponent(this.config.authToken);
      return this.config.repoUrl.replace(
        /^https?:\/\//,
        `https://${encodedToken}@`
      );
    }
    return this.config.repoUrl;
  }

  async validateConfig(): Promise<boolean> {
    try {
      await this.ensureRepoCloned();
      return fs.existsSync(this.cloneDirPath);
    } catch {
      return false;
    }
  }

  private async ensureRepoCloned(): Promise<void> {
    if (fs.existsSync(this.cloneDirPath)) {
      const gitDir = path.join(this.cloneDirPath, '.git');
      if (fs.existsSync(gitDir)) {
        await this.pullLatestChanges();
        return;
      }
    }

    await this.cloneRepo();
  }

  private async cloneRepo(): Promise<void> {
    if (fs.existsSync(this.cloneDirPath)) {
      fs.rmSync(this.cloneDirPath, { recursive: true, force: true });
    }

    const url = this.getAuthenticatedUrl();

    await this.withRetry(async () => {
      await execFileAsync('git', [
        'clone',
        '--depth',
        '1',
        '--branch',
        this.config.branch,
        url,
        this.cloneDirPath,
      ]);
    }, { operation: 'cloneRepo', url: this.config.repoUrl });
  }

  private async pullLatestChanges(): Promise<void> {
    await this.withRetry(async () => {
      await execFileAsync('git', ['fetch', 'origin', this.config.branch], {
        cwd: this.cloneDirPath,
      });
      await execFileAsync('git', ['reset', '--hard', `origin/${this.config.branch}`], {
        cwd: this.cloneDirPath,
      });
    }, { operation: 'pullLatestChanges' });
  }

  private async getFileCommitInfo(filePath: string): Promise<GitCommitInfo | null> {
    try {
      const relativePath = path.relative(this.cloneDirPath, filePath);
      const { stdout } = await execFileAsync(
        'git',
        ['log', '-1', '--format=%H|%ad|%an|%s', '--date=iso-strict', '--', relativePath],
        { cwd: this.cloneDirPath }
      );

      const parts = stdout.trim().split('|');
      if (parts.length >= 4) {
        return {
          sha: parts[0],
          date: new Date(parts[1]),
          author: parts[2],
          message: parts[3],
        };
      }
    } catch {
      // Ignore errors for commit info
    }
    return null;
  }

  private async getAllFiles(): Promise<WikiFile[]> {
    await this.ensureRepoCloned();

    const files: WikiFile[] = [];
    const excludedPatterns = this.config.excludePatterns || [];

    const walk = async (dir: string): Promise<void> => {
      const entries = fs.readdirSync(dir, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        const relativePath = path.relative(this.cloneDirPath, fullPath);

        if (excludedPatterns.some((pattern) => relativePath.match(pattern))) {
          continue;
        }

        if (entry.isDirectory()) {
          if (entry.name === '.git') continue;
          await walk(fullPath);
        } else if (entry.isFile()) {
          const ext = path.extname(entry.name).toLowerCase();
          if (this.config.includeExtensions?.includes(ext)) {
            try {
              const content = fs.readFileSync(fullPath, 'utf-8');
              const stat = fs.statSync(fullPath);
              const commitInfo = await this.getFileCommitInfo(fullPath);

              files.push({
                path: relativePath,
                content,
                lastModified: commitInfo?.date || stat.mtime,
                createdAt: stat.birthtime,
                sha: commitInfo?.sha,
              });
            } catch (error) {
              console.error(`Failed to read file ${fullPath}:`, error);
            }
          }
        }
      }
    };

    await walk(this.cloneDirPath);
    return files;
  }

  private getExternalId(filePath: string): string {
    return path.relative(this.cloneDirPath, filePath).replace(/\\/g, '/');
  }

  private getTitle(filePath: string): string {
    const relativePath = path.relative(this.cloneDirPath, filePath);
    const name = path.basename(relativePath, path.extname(relativePath));
    const dir = path.dirname(relativePath);

    const title = name.replace(/[-_]/g, ' ');
    if (dir === '.' || dir === '') {
      return title;
    }
    return `${dir.replace(/[-_/]/g, ' / ')} / ${title}`;
  }

  private getUrl(filePath: string): string {
    const relativePath = path.relative(this.cloneDirPath, filePath);
    const wikiPath = relativePath.replace(/\\/g, '/').replace(/\.[^/.]+$/, '');
    const baseUrl = this.config.repoUrl.replace(/\.git$/, '');
    return `${baseUrl}/wiki/${encodeURIComponent(wikiPath)}`;
  }

  async fetchDocuments(
    options: FetchOptions = {}
  ): Promise<FetchResult<KnowledgeDocument>> {
    const { since } = options;

    const files = await this.getAllFiles();

    const filteredFiles = since
      ? files.filter((file) => file.lastModified >= since)
      : files;

    const knowledgeDocs: KnowledgeDocument[] = [];

    for (const file of filteredFiles) {
      try {
        const fullPath = path.join(this.cloneDirPath, file.path);
        const doc = await this.fetchSingleDocument(this.getExternalId(fullPath));
        if (doc) {
          knowledgeDocs.push(doc);
        }
      } catch (error) {
        console.error(`Failed to fetch document ${file.path}:`, error);
      }
    }

    return {
      data: knowledgeDocs,
      hasMore: false,
      total: files.length,
    };
  }

  async fetchIncremental(
    since: Date,
    options: FetchOptions = {}
  ): Promise<FetchResult<KnowledgeDocument>> {
    return this.fetchDocuments({ ...options, since });
  }

  async fetchSingleDocument(
    externalId: string
  ): Promise<KnowledgeDocument | null> {
    try {
      const filePath = path.join(this.cloneDirPath, externalId);

      if (!fs.existsSync(filePath)) {
        return null;
      }

      const content = fs.readFileSync(filePath, 'utf-8');
      const title = this.getTitle(filePath);
      const url = this.getUrl(filePath);
      const stat = fs.statSync(filePath);
      const commitInfo = await this.getFileCommitInfo(filePath);
      const normalized = await this.normalizeContent(content);

      const metadata = {
        filePath: externalId,
        sha: commitInfo?.sha,
        lastCommitAuthor: commitInfo?.author,
        lastCommitMessage: commitInfo?.message,
        fileSize: stat.size,
      };

      const tags = this.extractTagsFromPath(externalId);

      const doc = this.buildDocument(
        externalId,
        title,
        content,
        metadata,
        url,
        commitInfo?.date || stat.mtime
      );

      return {
        ...doc,
        normalizedContent: normalized.markdown,
        headings: normalized.headings,
        internalLinks: normalized.internalLinks,
        tags,
        createdAt: stat.birthtime,
      };
    } catch (error) {
      await this.handleApiError(error, { filePath: externalId });
      return null;
    }
  }

  private extractTagsFromPath(filePath: string): string[] {
    const tags: string[] = [];
    const dir = path.dirname(filePath);

    if (dir !== '.' && dir !== '') {
      const parts = dir.split(/[\\/]/);
      parts.forEach((part) => {
        if (part) {
          tags.push(part.replace(/[-_]/g, ' '));
        }
      });
    }

    return tags;
  }

  async normalizeContent(
    content: string,
    metadata?: Record<string, unknown>
  ): Promise<{
    markdown: string;
    headings: HeadingNode[];
    internalLinks: string[];
  }> {
    const normalized = await this.normalizer.normalize(content);

    return {
      markdown: normalized.markdown,
      headings: normalized.headings,
      internalLinks: normalized.internalLinks,
    };
  }

  cleanup(): void {
    if (fs.existsSync(this.cloneDirPath)) {
      try {
        fs.rmSync(this.cloneDirPath, { recursive: true, force: true });
      } catch (error) {
        console.error('Failed to cleanup clone directory:', error);
      }
    }
  }
}
