import { Client } from '@elastic/elasticsearch';
import { config } from '@config/index';
import { logger } from '@utils/logger';

class ElasticsearchClientManager {
  private clients: Map<string, Client> = new Map();

  getClient(tenantIndexPrefix: string): Client {
    if (!this.clients.has(tenantIndexPrefix)) {
      this.clients.set(tenantIndexPrefix, this.createClient(tenantIndexPrefix));
    }
    return this.clients.get(tenantIndexPrefix)!;
  }

  private createClient(tenantIndexPrefix: string): Client {
    logger.debug(`Creating Elasticsearch client for prefix: ${tenantIndexPrefix}`);

    const options: any = {
      node: config.elasticsearchNode,
      maxRetries: 3,
      requestTimeout: 30000,
      sniffOnStart: false,
    };

    if (config.elasticsearchUsername && config.elasticsearchPassword) {
      options.auth = {
        username: config.elasticsearchUsername,
        password: config.elasticsearchPassword,
      };
    }

    return new Client(options);
  }

  async indexExists(tenantIndexPrefix: string, modelId: string): Promise<boolean> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    try {
      const result = await client.indices.exists({ index: indexName });
      return result;
    } catch (error) {
      logger.error({ error, indexName }, 'Failed to check index existence');
      return false;
    }
  }

  async createIndex(
    tenantIndexPrefix: string,
    modelId: string,
    fieldWeights: Record<string, number>,
    analyzer = 'ik_max_word'
  ): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    const properties: Record<string, any> = {
      id: { type: 'keyword' },
      content_id: { type: 'keyword' },
      model_id: { type: 'keyword' },
      status: { type: 'keyword' },
      created_at: { type: 'date' },
      updated_at: { type: 'date' },
    };

    for (const [field, weight] of Object.entries(fieldWeights)) {
      properties[field] = {
        type: 'text',
        analyzer,
        fields: {
          keyword: { type: 'keyword', ignore_above: 256 },
        },
        boost: weight,
      };
    }

    const settings = {
      analysis: {
        analyzer: {
          ik_max_word: {
            type: 'custom',
            tokenizer: 'ik_max_word',
            filter: ['lowercase'],
          },
          ik_smart: {
            type: 'custom',
            tokenizer: 'ik_smart',
            filter: ['lowercase'],
          },
        },
      },
      number_of_shards: 3,
      number_of_replicas: 1,
    };

    try {
      await client.indices.create({
        index: indexName,
        body: {
          settings,
          mappings: {
            properties,
          },
        },
      });
      logger.info({ indexName }, 'Created Elasticsearch index');
    } catch (error: any) {
      if (error.meta?.body?.error?.type === 'resource_already_exists_exception') {
        logger.info({ indexName }, 'Index already exists');
      } else {
        logger.error({ error, indexName }, 'Failed to create index');
        throw error;
      }
    }
  }

  async deleteIndex(tenantIndexPrefix: string, modelId: string): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    try {
      await client.indices.delete({ index: indexName });
      logger.info({ indexName }, 'Deleted Elasticsearch index');
    } catch (error) {
      logger.error({ error, indexName }, 'Failed to delete index');
    }
  }

  async indexDocument(
    tenantIndexPrefix: string,
    modelId: string,
    contentId: string,
    document: Record<string, unknown>,
    refresh = false
  ): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    const body = {
      content_id: contentId,
      model_id: modelId,
      ...document,
      updated_at: new Date().toISOString(),
    };

    try {
      await client.index({
        index: indexName,
        id: contentId,
        body,
        refresh,
      });
    } catch (error) {
      logger.error({ error, indexName, contentId }, 'Failed to index document');
      throw error;
    }
  }

  async updateDocument(
    tenantIndexPrefix: string,
    modelId: string,
    contentId: string,
    partialDoc: Record<string, unknown>,
    refresh = false
  ): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    try {
      await client.update({
        index: indexName,
        id: contentId,
        body: {
          doc: {
            ...partialDoc,
            updated_at: new Date().toISOString(),
          },
        },
        refresh,
      });
    } catch (error) {
      logger.error({ error, indexName, contentId }, 'Failed to update document');
      throw error;
    }
  }

  async deleteDocument(
    tenantIndexPrefix: string,
    modelId: string,
    contentId: string,
    refresh = false
  ): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    try {
      await client.delete({
        index: indexName,
        id: contentId,
        refresh,
      });
    } catch (error) {
      logger.error({ error, indexName, contentId }, 'Failed to delete document');
      throw error;
    }
  }

  async search(
    tenantIndexPrefix: string,
    modelId: string,
    options: {
      query: string;
      fieldWeights: Record<string, number>;
      defaultOperator?: 'AND' | 'OR';
      fuzziness?: number;
      analyzer?: string;
      filters?: Record<string, unknown>;
      sort?: Array<Record<string, string>>;
      page?: number;
      pageSize?: number;
      highlight?: boolean;
    }
  ): Promise<{
    total: number;
    hits: Array<{
      _id: string;
      _score: number;
      _source: Record<string, unknown>;
      highlight?: Record<string, string[]>;
    }>;
    aggregations?: Record<string, unknown>;
  }> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    const {
      query,
      fieldWeights,
      defaultOperator = 'AND',
      fuzziness = 1,
      analyzer = 'ik_max_word',
      filters = {},
      sort = [{ created_at: 'desc' }],
      page = 1,
      pageSize = 20,
      highlight = true,
    } = options;

    const shouldClauses = Object.entries(fieldWeights).map(([field, weight]) => ({
      match: {
        [field]: {
          query,
          boost: weight,
          fuzziness,
          operator: defaultOperator,
          analyzer,
        },
      },
    }));

    const filterClauses = Object.entries(filters).map(([field, value]) => ({
      term: { [field]: value },
    }));

    const highlightConfig = highlight
      ? {
          fields: Object.keys(fieldWeights).reduce((acc, field) => {
            acc[field] = {
              pre_tags: ['<em>'],
              post_tags: ['</em>'],
              fragment_size: 150,
              number_of_fragments: 3,
            };
            return acc;
          }, {} as Record<string, unknown>),
        }
      : undefined;

    const body: any = {
      query: {
        bool: {
          should: shouldClauses,
          minimum_should_match: 1,
          filter: filterClauses.length > 0 ? filterClauses : undefined,
        },
      },
      sort,
      from: (page - 1) * pageSize,
      size: pageSize,
      highlight: highlightConfig,
      track_total_hits: true,
    };

    try {
      const result = await client.search({
        index: indexName,
        body,
      });

      const hits = result.hits.hits.map((hit: any) => ({
        _id: hit._id,
        _score: hit._score,
        _source: hit._source,
        highlight: hit.highlight,
      }));

      return {
        total: (result.hits.total as any).value,
        hits,
        aggregations: result.aggregations as Record<string, unknown>,
      };
    } catch (error) {
      logger.error({ error, indexName, query }, 'Failed to search');
      throw error;
    }
  }

  async bulkIndex(
    tenantIndexPrefix: string,
    modelId: string,
    documents: Array<{
      contentId: string;
      document: Record<string, unknown>;
    }>
  ): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    const body = documents.flatMap(doc => [
      { index: { _index: indexName, _id: doc.contentId } },
      {
        content_id: doc.contentId,
        model_id: modelId,
        ...doc.document,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
    ]);

    try {
      await client.bulk({ body, refresh: true });
      logger.info({ indexName, count: documents.length }, 'Bulk indexed documents');
    } catch (error) {
      logger.error({ error, indexName }, 'Failed to bulk index');
      throw error;
    }
  }

  async reindexAll(
    tenantIndexPrefix: string,
    modelId: string,
    documents: Array<{
      contentId: string;
      document: Record<string, unknown>;
    }>
  ): Promise<void> {
    await this.deleteIndex(tenantIndexPrefix, modelId);
    await this.bulkIndex(tenantIndexPrefix, modelId, documents);
  }

  async refreshIndex(tenantIndexPrefix: string, modelId: string): Promise<void> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    await client.indices.refresh({ index: indexName });
  }

  async getIndexStats(tenantIndexPrefix: string, modelId: string): Promise<Record<string, unknown>> {
    const client = this.getClient(tenantIndexPrefix);
    const indexName = this.getIndexName(tenantIndexPrefix, modelId);

    try {
      const stats = await client.indices.stats({ index: indexName });
      return {
        docCount: stats._all?.primaries?.docs?.count || 0,
        sizeInBytes: stats._all?.primaries?.store?.size_in_bytes || 0,
        queryCount: stats._all?.total?.search?.query_total || 0,
        indexCount: stats._all?.total?.indexing?.index_total || 0,
      };
    } catch (error) {
      logger.error({ error, indexName }, 'Failed to get index stats');
      return {};
    }
  }

  private getIndexName(tenantIndexPrefix: string, modelId: string): string {
    return `${tenantIndexPrefix}_${modelId}`.toLowerCase().replace(/[^a-z0-9_-]/g, '_');
  }

  async closeAll(): Promise<void> {
    logger.info('Closing all Elasticsearch clients');
    for (const [prefix, client] of this.clients) {
      await client.close();
      logger.debug({ prefix }, 'Closed Elasticsearch client');
    }
    this.clients.clear();
  }
}

export const elasticsearchClient = new ElasticsearchClientManager();
