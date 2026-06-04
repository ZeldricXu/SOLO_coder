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

interface ConfluenceConfig {
  baseUrl: string;
  username: string;
  apiToken: string;
  spaceKeys?: string[];
  pageIds?: string[];
}

interface ConfluenceSpace {
  key: string;
  name: string;
  type: string;
}

interface ConfluencePage {
  id: string;
  title: string;
  type: string;
  status: string;
  space: { key: string };
  version: {
    number: number;
    when: string;
    by: { displayName: string };
  };
  history: {
    createdDate: string;
    lastUpdated: { when: string; by: { displayName: string } };
  };
  _links: {
    webui: string;
    tinyui: string;
    self: string;
  };
  body?: {
    storage?: { value: string; representation: string };
    view?: { value: string; representation: string };
    editor2?: { value: string; representation: string };
  };
}

interface ConfluencePageListResponse {
  results: ConfluencePage[];
  start: number;
  limit: number;
  size: number;
  _links: {
    next?: string;
    base: string;
    context: string;
  };
}

interface ConfluenceSpaceListResponse {
  results: ConfluenceSpace[];
  start: number;
  limit: number;
  size: number;
  _links: {
    next?: string;
    base: string;
    context: string;
  };
}

export class ConfluenceSource extends BaseSource {
  private config: ConfluenceConfig;
  private readonly normalizer: MarkdownNormalizer;
  private readonly authHeader: string;

  constructor(
    source: KnowledgeSource,
    retryConfig?: Partial<RetryConfig>
  ) {
    super(source, retryConfig);
    this.config = this.parseConfig(source.config);
    this.normalizer = new MarkdownNormalizer({ sourceType: 'confluence' });
    this.authHeader = `Basic ${Buffer.from(
      `${this.config.username}:${this.config.apiToken}`
    ).toString('base64')}`;
  }

  get sourceType(): SourceType {
    return 'confluence';
  }

  private parseConfig(config: Record<string, unknown>): ConfluenceConfig {
    const baseUrl = config.baseUrl as string;
    const username = config.username as string;
    const apiToken = config.apiToken as string;

    if (!baseUrl || !username || !apiToken) {
      throw new Error('Confluence baseUrl, username, and apiToken are required');
    }

    return {
      baseUrl: baseUrl.replace(/\/$/, ''),
      username,
      apiToken,
      spaceKeys: config.spaceKeys as string[] | undefined,
      pageIds: config.pageIds as string[] | undefined,
    };
  }

  async validateConfig(): Promise<boolean> {
    try {
      const response = await this.confluenceRequest<ConfluenceSpaceListResponse>(
        '/wiki/rest/api/space?limit=1'
      );
      return Array.isArray(response.results);
    } catch {
      return false;
    }
  }

  private async confluenceRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    return this.withRetry(async () => {
      const url = endpoint.startsWith('http')
        ? endpoint
        : `${this.config.baseUrl}${endpoint}`;

      const response = await fetch(url, {
        ...options,
        headers: {
          ...options.headers,
          'Authorization': this.authHeader,
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Confluence API error: ${response.status} - ${errorText}`);
      }

      return response.json() as Promise<T>;
    }, { endpoint, ...options });
  }

  private async getAllPages(): Promise<ConfluencePage[]> {
    const pages: ConfluencePage[] = [];

    if (this.config.pageIds) {
      for (const pageId of this.config.pageIds) {
        try {
          const page = await this.confluenceRequest<ConfluencePage>(
            `/wiki/rest/api/content/${pageId}?expand=space,version,history,_links`
          );
          if (page.status === 'current') {
            pages.push(page);
          }
        } catch (error) {
          console.error(`Failed to fetch page ${pageId}:`, error);
        }
      }
    }

    const spaces = this.config.spaceKeys || (await this.getAllSpaces());

    for (const space of spaces) {
      const spaceKey = typeof space === 'string' ? space : space.key;
      const spacePages = await this.getPagesFromSpace(spaceKey);
      pages.push(...spacePages);
    }

    const uniquePages = Array.from(
      new Map(pages.map((p) => [p.id, p])).values()
    );

    return uniquePages;
  }

  private async getAllSpaces(): Promise<string[]> {
    const spaceKeys: string[] = [];
    let start = 0;
    const limit = 50;
    let hasMore = true;

    while (hasMore) {
      const response = await this.confluenceRequest<ConfluenceSpaceListResponse>(
        `/wiki/rest/api/space?type=global&start=${start}&limit=${limit}`
      );

      for (const space of response.results) {
        spaceKeys.push(space.key);
      }

      hasMore = !!response._links.next;
      start += limit;
    }

    return spaceKeys;
  }

  private async getPagesFromSpace(spaceKey: string): Promise<ConfluencePage[]> {
    const pages: ConfluencePage[] = [];
    let start = 0;
    const limit = 50;
    let hasMore = true;

    while (hasMore) {
      const response = await this.confluenceRequest<ConfluencePageListResponse>(
        `/wiki/rest/api/content?type=page&spaceKey=${spaceKey}&status=current&expand=space,version,history,_links&start=${start}&limit=${limit}`
      );

      pages.push(...response.results);

      hasMore = !!response._links.next;
      start += limit;
    }

    return pages;
  }

  async fetchDocuments(
    options: FetchOptions = {}
  ): Promise<FetchResult<KnowledgeDocument>> {
    const { since } = options;

    const pages = await this.getAllPages();

    const filteredPages = since
      ? pages.filter(
          (page) => new Date(page.history.lastUpdated.when) >= since
        )
      : pages;

    const knowledgeDocs: KnowledgeDocument[] = [];

    for (const page of filteredPages) {
      try {
        const fullDoc = await this.fetchSingleDocument(page.id);
        if (fullDoc) {
          knowledgeDocs.push(fullDoc);
        }
      } catch (error) {
        console.error(`Failed to fetch document ${page.id}:`, error);
      }
    }

    return {
      data: knowledgeDocs,
      hasMore: false,
      total: pages.length,
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
      const page = await this.confluenceRequest<ConfluencePage>(
        `/wiki/rest/api/content/${externalId}?expand=space,version,history,body.storage,body.view,body.editor2,_links`
      );

      const content = this.extractPageContent(page);
      const markdown = await this.convertToMarkdown(content);
      const normalized = await this.normalizeContent(markdown);

      const metadata = this.extractMetadata(page);
      const tags = this.extractTags(page);
      const url = `${this.config.baseUrl}${page._links.webui}`;

      const doc = this.buildDocument(
        externalId,
        page.title,
        markdown,
        metadata,
        url,
        new Date(page.history.lastUpdated.when)
      );

      return {
        ...doc,
        normalizedContent: normalized.markdown,
        headings: normalized.headings,
        internalLinks: normalized.internalLinks,
        tags,
        createdAt: new Date(page.history.createdDate),
      };
    } catch (error) {
      await this.handleApiError(error, { pageId: externalId });
      return null;
    }
  }

  private extractPageContent(page: ConfluencePage): string {
    if (page.body?.editor2?.value) {
      return page.body.editor2.value;
    }
    if (page.body?.storage?.value) {
      return page.body.storage.value;
    }
    if (page.body?.view?.value) {
      return page.body.view.value;
    }
    return '';
  }

  private async convertToMarkdown(content: string): Promise<string> {
    if (!content) return '';

    let markdown = content;

    markdown = this.convertStorageFormatToMarkdown(markdown);

    markdown = this.convertWikiMarkupToMarkdown(markdown);

    return markdown;
  }

  private convertStorageFormatToMarkdown(content: string): string {
    let markdown = content;

    markdown = markdown.replace(/<h([1-6])[^>]*>(.*?)<\/h[1-6]>/g, (_, level, text) => {
      return `${'#'.repeat(parseInt(level))} ${this.stripHtml(text)}\n\n`;
    });

    markdown = markdown.replace(/<p[^>]*>(.*?)<\/p>/g, (_, text) => {
      const stripped = this.stripHtml(text);
      return stripped ? `${stripped}\n\n` : '';
    });

    markdown = markdown.replace(/<strong[^>]*>(.*?)<\/strong>/g, '**$1**');
    markdown = markdown.replace(/<b[^>]*>(.*?)<\/b>/g, '**$1**');
    markdown = markdown.replace(/<em[^>]*>(.*?)<\/em>/g, '*$1*');
    markdown = markdown.replace(/<i[^>]*>(.*?)<\/i>/g, '*$1*');
    markdown = markdown.replace(/<code[^>]*>(.*?)<\/code>/g, '`$1`');
    markdown = markdown.replace(/<s[^>]*>(.*?)<\/s>/g, '~~$1~~');
    markdown = markdown.replace(/<strike[^>]*>(.*?)<\/strike>/g, '~~$1~~');

    markdown = markdown.replace(
      /<ac:structured-macro ac:name="code"[^>]*>[\s\S]*?<ac:parameter ac:name="language">([^<]*)<\/ac:parameter>[\s\S]*?<ac:plain-text-body><!\[CDATA\[([\s\S]*?)\]\]><\/ac:plain-text-body>[\s\S]*?<\/ac:structured-macro>/g,
      (_, lang, code) => {
        return `\`\`\`${lang}\n${code}\n\`\`\`\n\n`;
      }
    );

    markdown = markdown.replace(
      /<ac:structured-macro ac:name="note"[^>]*>[\s\S]*?<ac:rich-text-body>([\s\S]*?)<\/ac:rich-text-body>[\s\S]*?<\/ac:structured-macro>/g,
      (_, text) => {
        return `> **Note:** ${this.stripHtml(text)}\n\n`;
      }
    );

    markdown = markdown.replace(
      /<ac:structured-macro ac:name="warning"[^>]*>[\s\S]*?<ac:rich-text-body>([\s\S]*?)<\/ac:rich-text-body>[\s\S]*?<\/ac:structured-macro>/g,
      (_, text) => {
        return `> **Warning:** ${this.stripHtml(text)}\n\n`;
      }
    );

    markdown = markdown.replace(
      /<ac:structured-macro ac:name="info"[^>]*>[\s\S]*?<ac:rich-text-body>([\s\S]*?)<\/ac:rich-text-body>[\s\S]*?<\/ac:structured-macro>/g,
      (_, text) => {
        return `> **Info:** ${this.stripHtml(text)}\n\n`;
      }
    );

    markdown = markdown.replace(
      /<ac:structured-macro ac:name="tip"[^>]*>[\s\S]*?<ac:rich-text-body>([\s\S]*?)<\/ac:rich-text-body>[\s\S]*?<\/ac:structured-macro>/g,
      (_, text) => {
        return `> **Tip:** ${this.stripHtml(text)}\n\n`;
      }
    );

    markdown = markdown.replace(/<ul[^>]*>([\s\S]*?)<\/ul>/g, (_, content) => {
      return this.convertListItems(content, '-') + '\n';
    });

    markdown = markdown.replace(/<ol[^>]*>([\s\S]*?)<\/ol>/g, (_, content) => {
      return this.convertListItems(content, '1.') + '\n';
    });

    markdown = markdown.replace(/<li[^>]*>(.*?)<\/li>/g, '$1\n');

    markdown = markdown.replace(
      /<ac:link[^>]*>[\s\S]*?<ri:page ri:content-title="([^"]+)"[^>]*>[\s\S]*?<ac:plain-text-link-body><!\[CDATA\[([\s\S]*?)\]\]><\/ac:plain-text-link-body>[\s\S]*?<\/ac:link>/g,
      (_, title, text) => {
        return `[${text || title}](${title})`;
      }
    );

    markdown = markdown.replace(
      /<a[^>]*href="([^"]+)"[^>]*>(.*?)<\/a>/g,
      (_, href, text) => {
        return `[${this.stripHtml(text)}](${href})`;
      }
    );

    markdown = markdown.replace(/<br\s*\/?>/g, '\n');
    markdown = markdown.replace(/<hr\s*\/?>/g, '---\n\n');

    markdown = markdown.replace(/<div[^>]*>(.*?)<\/div>/g, '$1\n');
    markdown = markdown.replace(/<span[^>]*>(.*?)<\/span>/g, '$1');

    markdown = markdown.replace(/&nbsp;/g, ' ');
    markdown = markdown.replace(/&amp;/g, '&');
    markdown = markdown.replace(/&lt;/g, '<');
    markdown = markdown.replace(/&gt;/g, '>');
    markdown = markdown.replace(/&quot;/g, '"');
    markdown = markdown.replace(/&#39;/g, "'");

    return markdown;
  }

  private convertListItems(content: string, prefix: string): string {
    const items = content
      .split(/<li[^>]*>/)
      .filter((item) => item.trim())
      .map((item) => item.replace(/<\/li>/g, '').trim())
      .filter((item) => item);

    return items.map((item) => `${prefix} ${this.stripHtml(item)}`).join('\n');
  }

  private stripHtml(html: string): string {
    return html.replace(/<[^>]*>/g, '').trim();
  }

  private convertWikiMarkupToMarkdown(content: string): string {
    let markdown = content;

    markdown = markdown.replace(/^h([1-6])\. (.*)$/gm, (_, level, text) => {
      return `${'#'.repeat(parseInt(level))} ${text}`;
    });

    markdown = markdown.replace(/\*([^*]+)\*/g, '**$1**');
    markdown = markdown.replace(/_([^_]+)_/g, '*$1*');
    markdown = markdown.replace(/\{\{([^}]+)\}\}/g, '`$1`');
    markdown = markdown.replace(/\~([^~]+)\~/g, '~~$1~~');

    markdown = markdown.replace(
      /\{code:([^}]*)\}([\s\S]*?)\{code\}/g,
      (_, lang, code) => {
        return `\`\`\`${lang}\n${code}\n\`\`\``;
      }
    );

    markdown = markdown.replace(/\{code\}([\s\S]*?)\{code\}/g, '```\n$1\n```');

    markdown = markdown.replace(/\{quote\}([\s\S]*?)\{quote\}/g, '> $1');
    markdown = markdown.replace(/\{noformat\}([\s\S]*?)\{noformat\}/g, '```\n$1\n```');

    markdown = markdown.replace(
      /\[([^|\]]+)\|([^\]]+)\]/g,
      '[$1]($2)'
    );

    markdown = markdown.replace(/^- (.*)$/gm, '- $1');
    markdown = markdown.replace(/^\* (.*)$/gm, '- $1');
    markdown = markdown.replace(/^# (.*)$/gm, '1. $1');

    markdown = markdown.replace(/\{toc\}[\s\S]*/g, '');
    markdown = markdown.replace(/\{toc:([^}]*)\}[\s\S]*/g, '');

    return markdown;
  }

  private extractTags(page: ConfluencePage): string[] {
    const tags: string[] = [];

    tags.push(page.space.key);

    return tags;
  }

  private extractMetadata(page: ConfluencePage): Record<string, unknown> {
    return {
      spaceKey: page.space.key,
      version: page.version.number,
      createdBy: page.history.lastUpdated.by.displayName,
      lastUpdatedBy: page.history.lastUpdated.by.displayName,
      createdDate: page.history.createdDate,
      lastUpdatedDate: page.history.lastUpdated.when,
      tinyUrl: `${this.config.baseUrl}${page._links.tinyui}`,
    };
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
}
