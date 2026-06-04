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

interface NotionConfig {
  apiKey: string;
  databaseIds?: string[];
  pageIds?: string[];
}

interface NotionBlock {
  id: string;
  type: string;
  [key: string]: unknown;
}

interface NotionPage {
  id: string;
  properties: Record<string, unknown>;
  url: string;
  last_edited_time: string;
  created_time: string;
  archived: boolean;
  parent: { type: string; database_id?: string; page_id?: string };
}

interface NotionDatabase {
  id: string;
  title: Array<{ plain_text: string }>;
  url: string;
}

interface NotionSearchResponse {
  results: Array<{ object: string; id: string }>;
  has_more: boolean;
  next_cursor: string | null;
}

interface NotionBlockResponse {
  results: NotionBlock[];
  has_more: boolean;
  next_cursor: string | null;
}

export class NotionSource extends BaseSource {
  private config: NotionConfig;
  private readonly normalizer: MarkdownNormalizer;
  private readonly API_BASE = 'https://api.notion.com/v1';
  private readonly API_VERSION = '2022-06-28';

  constructor(
    source: KnowledgeSource,
    retryConfig?: Partial<RetryConfig>
  ) {
    super(source, retryConfig);
    this.config = this.parseConfig(source.config);
    this.normalizer = new MarkdownNormalizer({ sourceType: 'notion' });
  }

  get sourceType(): SourceType {
    return 'notion';
  }

  private parseConfig(config: Record<string, unknown>): NotionConfig {
    const apiKey = config.apiKey as string;

    if (!apiKey) {
      throw new Error('Notion apiKey is required');
    }

    return {
      apiKey,
      databaseIds: config.databaseIds as string[] | undefined,
      pageIds: config.pageIds as string[] | undefined,
    };
  }

  async validateConfig(): Promise<boolean> {
    try {
      const response = await this.notionRequest<NotionSearchResponse>('/search', {
        method: 'POST',
        body: JSON.stringify({ page_size: 1 }),
      });
      return Array.isArray(response.results);
    } catch {
      return false;
    }
  }

  private async notionRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    return this.withRetry(async () => {
      const response = await fetch(`${this.API_BASE}${endpoint}`, {
        ...options,
        headers: {
          ...options.headers,
          'Authorization': `Bearer ${this.config.apiKey}`,
          'Notion-Version': this.API_VERSION,
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Notion API error: ${response.status} - ${errorText}`);
      }

      return response.json() as Promise<T>;
    }, { endpoint, ...options });
  }

  private async getAllPages(): Promise<NotionPage[]> {
    const pages: NotionPage[] = [];

    if (this.config.databaseIds) {
      for (const databaseId of this.config.databaseIds) {
        const databasePages = await this.queryDatabase(databaseId);
        pages.push(...databasePages);
      }
    }

    if (this.config.pageIds) {
      for (const pageId of this.config.pageIds) {
        try {
          const page = await this.notionRequest<NotionPage>(`/pages/${pageId}`);
          if (!page.archived) {
            pages.push(page);
          }
        } catch (error) {
          console.error(`Failed to fetch page ${pageId}:`, error);
        }
      }
    }

    if (!this.config.databaseIds && !this.config.pageIds) {
      let cursor: string | null = null;
      do {
        const response = await this.notionRequest<NotionSearchResponse>('/search', {
          method: 'POST',
          body: JSON.stringify({
            filter: { value: 'page', property: 'object' },
            page_size: 100,
            start_cursor: cursor || undefined,
          }),
        });

        for (const result of response.results) {
          try {
            const page = await this.notionRequest<NotionPage>(`/pages/${result.id}`);
            if (!page.archived) {
              pages.push(page);
            }
          } catch (error) {
            console.error(`Failed to fetch page ${result.id}:`, error);
          }
        }

        cursor = response.next_cursor;
      } while (cursor);
    }

    return pages;
  }

  private async queryDatabase(databaseId: string): Promise<NotionPage[]> {
    const pages: NotionPage[] = [];
    let cursor: string | null = null;

    do {
      const response = await this.notionRequest<{
        results: NotionPage[];
        has_more: boolean;
        next_cursor: string | null;
      }>(`/databases/${databaseId}/query`, {
        method: 'POST',
        body: JSON.stringify({
          page_size: 100,
          start_cursor: cursor || undefined,
        }),
      });

      pages.push(...response.results.filter((p) => !p.archived));
      cursor = response.next_cursor;
    } while (cursor);

    return pages;
  }

  async fetchDocuments(
    options: FetchOptions = {}
  ): Promise<FetchResult<KnowledgeDocument>> {
    const { since } = options;

    const pages = await this.getAllPages();

    const filteredPages = since
      ? pages.filter((page) => new Date(page.last_edited_time) >= since)
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
      const page = await this.notionRequest<NotionPage>(`/pages/${externalId}`);
      const blocks = await this.getAllBlocks(externalId);
      const content = await this.blocksToMarkdown(blocks);
      const title = this.extractPageTitle(page);
      const normalized = await this.normalizeContent(content);

      const tags = this.extractTagsFromProperties(page.properties);
      const metadata = this.extractMetadata(page);

      const doc = this.buildDocument(
        externalId,
        title,
        content,
        metadata,
        page.url,
        new Date(page.last_edited_time)
      );

      return {
        ...doc,
        normalizedContent: normalized.markdown,
        headings: normalized.headings,
        internalLinks: normalized.internalLinks,
        tags,
      };
    } catch (error) {
      await this.handleApiError(error, { pageId: externalId });
      return null;
    }
  }

  private async getAllBlocks(blockId: string): Promise<NotionBlock[]> {
    const blocks: NotionBlock[] = [];
    let cursor: string | null = null;

    do {
      const response = await this.notionRequest<NotionBlockResponse>(
        `/blocks/${blockId}/children?page_size=100${cursor ? `&start_cursor=${cursor}` : ''}`
      );

      for (const block of response.results) {
        blocks.push(block);
        if ((block as unknown as { has_children: boolean }).has_children) {
          const children = await this.getAllBlocks(block.id);
          blocks.push(...children);
        }
      }

      cursor = response.next_cursor;
    } while (cursor);

    return blocks;
  }

  private async blocksToMarkdown(blocks: NotionBlock[]): Promise<string> {
    const markdownParts: string[] = [];

    for (const block of blocks) {
      const part = await this.blockToMarkdown(block);
      if (part) {
        markdownParts.push(part);
      }
    }

    return markdownParts.join('\n\n');
  }

  private async blockToMarkdown(block: NotionBlock): Promise<string> {
    const type = block.type;
    const blockData = block[type] as Record<string, unknown> | undefined;

    if (!blockData) return '';

    switch (type) {
      case 'paragraph':
        return this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        );

      case 'heading_1':
        return `# ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'heading_2':
        return `## ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'heading_3':
        return `### ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'bulleted_list_item':
        return `- ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'numbered_list_item':
        return `1. ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'to_do': {
        const checked = blockData.checked as boolean;
        return `- [${checked ? 'x' : ' '}] ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;
      }

      case 'toggle':
        return `<details><summary>${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}</summary>\n\n</details>`;

      case 'quote':
        return `> ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;

      case 'callout': {
        const emoji = (blockData.icon as { emoji?: string })?.emoji || '💡';
        return `> ${emoji} ${this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        )}`;
      }

      case 'code': {
        const language = (blockData.language as string) || 'text';
        const code = this.richTextToMarkdown(
          (blockData.rich_text as Array<Record<string, unknown>>) || []
        );
        return `\`\`\`${language}\n${code}\n\`\`\``;
      }

      case 'divider':
        return '---';

      case 'bookmark':
      case 'embed':
      case 'link_preview':
        return `[${(blockData.url as string) || 'Link'}](${
          (blockData.url as string) || ''
        })`;

      case 'image': {
        const imageBlock = blockData as {
          type: string;
          file?: { url: string };
          external?: { url: string };
          caption?: Array<{ plain_text: string }>;
        };
        const url =
          imageBlock.type === 'external'
            ? imageBlock.external?.url
            : imageBlock.file?.url;
        const caption =
          imageBlock.caption?.map((c) => c.plain_text).join('') || '';
        return `![${caption}](${url || ''})`;
      }

      case 'table': {
        const tableBlock = blockData as {
          table_width: number;
          has_column_header: boolean;
          has_row_header: boolean;
        };
        const childBlocks = await this.getAllBlocks(block.id);
        return this.tableBlocksToMarkdown(
          childBlocks,
          tableBlock.table_width,
          tableBlock.has_column_header
        );
      }

      case 'table_row': {
        const tableRow = blockData as {
          cells: Array<Array<{ plain_text: string }>>;
        };
        return `| ${tableRow.cells
          .map((cell) => cell.map((c) => c.plain_text).join(''))
          .join(' | ')} |`;
      }

      default:
        return '';
    }
  }

  private tableBlocksToMarkdown(
    blocks: NotionBlock[],
    width: number,
    hasHeader: boolean
  ): string {
    const rows: string[] = [];

    for (const block of blocks) {
      if (block.type === 'table_row') {
        const rowData = (block.table_row as {
          cells: Array<Array<{ plain_text: string }>>;
        })?.cells;
        if (rowData) {
          const row = `| ${rowData
            .map((cell) => cell.map((c) => c.plain_text).join(''))
            .join(' | ')} |`;
          rows.push(row);
        }
      }
    }

    if (rows.length === 0) return '';

    if (hasHeader && rows.length > 1) {
      const separator = `| ${Array(width).fill('---').join(' | ')} |`;
      rows.splice(1, 0, separator);
    }

    return rows.join('\n');
  }

  private richTextToMarkdown(richText: Array<Record<string, unknown>>): string {
    return richText
      .map((text) => {
        const content = (text.plain_text as string) || '';
        const annotations = text.annotations as Record<string, boolean> | undefined;
        const link = text.href as string | undefined;

        let result = content;

        if (annotations) {
          if (annotations.bold) result = `**${result}**`;
          if (annotations.italic) result = `*${result}*`;
          if (annotations.strikethrough) result = `~~${result}~~`;
          if (annotations.underline) result = `<u>${result}</u>`;
          if (annotations.code) result = `\`${result}\``;
        }

        if (link) {
          result = `[${result}](${link})`;
        }

        return result;
      })
      .join('');
  }

  private extractPageTitle(page: NotionPage): string {
    const properties = page.properties;

    for (const prop of Object.values(properties)) {
      const propData = prop as { type: string; title?: Array<{ plain_text: string }> };
      if (propData.type === 'title' && propData.title) {
        return propData.title.map((t) => t.plain_text).join('') || 'Untitled';
      }
    }

    return 'Untitled';
  }

  private extractTagsFromProperties(
    properties: Record<string, unknown>
  ): string[] {
    const tags: string[] = [];

    for (const [key, value] of Object.entries(properties)) {
      const prop = value as { type: string; multi_select?: Array<{ name: string }>; select?: { name: string } };

      if (prop.type === 'multi_select' && prop.multi_select) {
        tags.push(...prop.multi_select.map((s) => s.name));
      }
      if (prop.type === 'select' && prop.select) {
        tags.push(prop.select.name);
      }
    }

    return [...new Set(tags)];
  }

  private extractMetadata(page: NotionPage): Record<string, unknown> {
    return {
      parent: page.parent,
      createdTime: page.created_time,
      lastEditedTime: page.last_edited_time,
      properties: page.properties,
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
