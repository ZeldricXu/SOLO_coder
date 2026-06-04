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

interface FeishuConfig {
  appId: string;
  appSecret: string;
  spaceId?: string;
  folderToken?: string;
}

interface FeishuTokenResponse {
  code: number;
  msg: string;
  tenant_access_token: string;
  expire: number;
}

interface FeishuDocument {
  document_id: string;
  title: string;
  type: string;
  url: string;
  edit_time: string;
  create_time: string;
  doc_token: string;
  doc_type: string;
}

interface FeishuDocumentListResponse {
  code: number;
  msg: string;
  data: {
    items: FeishuDocument[];
    page_token?: string;
    has_more: boolean;
    total?: number;
  };
}

interface FeishuDocumentContentResponse {
  code: number;
  msg: string;
  data: {
    content: string;
    document_id: string;
    title: string;
  };
}

export class FeishuSource extends BaseSource {
  private config: FeishuConfig;
  private accessToken?: string;
  private tokenExpireAt?: number;
  private readonly normalizer: MarkdownNormalizer;
  private readonly API_BASE = 'https://open.feishu.cn/open-apis';

  constructor(
    source: KnowledgeSource,
    retryConfig?: Partial<RetryConfig>
  ) {
    super(source, retryConfig);
    this.config = this.parseConfig(source.config);
    this.normalizer = new MarkdownNormalizer({ sourceType: 'feishu' });
  }

  get sourceType(): SourceType {
    return 'feishu';
  }

  private parseConfig(config: Record<string, unknown>): FeishuConfig {
    const appId = config.appId as string;
    const appSecret = config.appSecret as string;

    if (!appId || !appSecret) {
      throw new Error('Feishu appId and appSecret are required');
    }

    return {
      appId,
      appSecret,
      spaceId: config.spaceId as string | undefined,
      folderToken: config.folderToken as string | undefined,
    };
  }

  async validateConfig(): Promise<boolean> {
    try {
      await this.getAccessToken();
      return true;
    } catch {
      return false;
    }
  }

  private async getAccessToken(): Promise<string> {
    const now = Date.now();
    if (this.accessToken && this.tokenExpireAt && now < this.tokenExpireAt - 60000) {
      return this.accessToken;
    }

    return this.withRetry(async () => {
      const response = await fetch(`${this.API_BASE}/auth/v3/tenant_access_token/internal`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          app_id: this.config.appId,
          app_secret: this.config.appSecret,
        }),
      });

      const data = (await response.json()) as FeishuTokenResponse;

      if (data.code !== 0) {
        throw new Error(`Failed to get access token: ${data.msg}`);
      }

      this.accessToken = data.tenant_access_token;
      this.tokenExpireAt = Date.now() + data.expire * 1000;

      return this.accessToken;
    }, { operation: 'getAccessToken' });
  }

  private async feishuRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const token = await this.getAccessToken();

    return this.withRetry(async () => {
      const response = await fetch(`${this.API_BASE}${endpoint}`, {
        ...options,
        headers: {
          ...options.headers,
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Feishu API error: ${response.status} - ${errorText}`);
      }

      return response.json() as Promise<T>;
    }, { endpoint, ...options });
  }

  async fetchDocuments(
    options: FetchOptions = {}
  ): Promise<FetchResult<KnowledgeDocument>> {
    const { limit = 50, offset = 0, since } = options;

    let endpoint = `/docx/v1/documents?page_size=${limit}`;

    if (this.config.spaceId) {
      endpoint += `&space_id=${this.config.spaceId}`;
    }
    if (this.config.folderToken) {
      endpoint += `&folder_token=${this.config.folderToken}`;
    }

    const response = await this.feishuRequest<FeishuDocumentListResponse>(endpoint);

    if (response.code !== 0) {
      throw new Error(`Failed to fetch documents: ${response.msg}`);
    }

    const documents = response.data.items;
    const filteredDocs = since
      ? documents.filter((doc) => new Date(doc.edit_time) >= since)
      : documents;

    const knowledgeDocs: KnowledgeDocument[] = [];

    for (const doc of filteredDocs) {
      try {
        const fullDoc = await this.fetchSingleDocument(doc.document_id);
        if (fullDoc) {
          knowledgeDocs.push(fullDoc);
        }
      } catch (error) {
        console.error(`Failed to fetch document ${doc.document_id}:`, error);
      }
    }

    return {
      data: knowledgeDocs,
      hasMore: response.data.has_more,
      nextCursor: response.data.page_token,
      total: response.data.total,
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
      const endpoint = `/docx/v1/documents/${externalId}/raw_content`;
      const response = await this.feishuRequest<FeishuDocumentContentResponse>(endpoint);

      if (response.code !== 0) {
        throw new Error(`Failed to fetch document content: ${response.msg}`);
      }

      const content = response.data.content;
      const normalized = await this.normalizeContent(content);

      const doc = this.buildDocument(
        externalId,
        response.data.title,
        content,
        {},
        `https://www.feishu.cn/docx/${externalId}`,
        new Date()
      );

      return {
        ...doc,
        normalizedContent: normalized.markdown,
        headings: normalized.headings,
        internalLinks: normalized.internalLinks,
      };
    } catch (error) {
      await this.handleApiError(error, { documentId: externalId });
      return null;
    }
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
