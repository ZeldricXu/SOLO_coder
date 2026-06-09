import { SearchConfig } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { elasticsearchClient } from './elasticsearch-client';
import { schemaValidator } from '../content-model/schema-validator';
import { generateId } from '@utils/crypto';
import { logger } from '@utils/logger';
import { TenantContext, ContentSchema } from '@types/index';

export interface ConfigureSearchInput {
  modelId: string;
  fieldWeights: Record<string, number>;
  defaultOperator?: 'AND' | 'OR';
  fuzziness?: number;
  analyzer?: string;
}

export interface SearchInput {
  modelId: string;
  query: string;
  page?: number;
  pageSize?: number;
  filters?: Record<string, unknown>;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  highlight?: boolean;
}

export class SearchService {
  private prisma = connectionPool.getPlatformPrisma();

  async configureSearch(
    tenant: TenantContext,
    input: ConfigureSearchInput
  ): Promise<SearchConfig> {
    if (!tenant.limits.enableElasticsearch) {
      throw new Error('Elasticsearch is not enabled for this tenant');
    }

    const model = await this.prisma.contentModel.findFirst({
      where: { id: input.modelId, tenantId: tenant.tenantId, deletedAt: null },
    });

    if (!model) {
      throw new Error('Content model not found');
    }

    const schema = model.schemaJson as unknown as ContentSchema;
    const searchableFields = schemaValidator.getSearchableFields(schema);

    for (const field of Object.keys(input.fieldWeights)) {
      if (!searchableFields.some(f => f.name === field)) {
        throw new Error(`Field ${field} is not searchable`);
      }
    }

    const existingConfig = await this.prisma.searchConfig.findFirst({
      where: { tenantId: tenant.tenantId, modelId: input.modelId },
    });

    const configData = {
      fieldWeights: input.fieldWeights,
      defaultOperator: input.defaultOperator || 'AND',
      fuzziness: input.fuzziness || 1,
      analyzer: input.analyzer || 'ik_max_word',
    };

    let config: SearchConfig;

    if (existingConfig) {
      config = await this.prisma.searchConfig.update({
        where: { id: existingConfig.id },
        data: configData,
      });
    } else {
      config = await this.prisma.searchConfig.create({
        data: {
          id: generateId('sc'),
          tenantId: tenant.tenantId,
          modelId: input.modelId,
          ...configData,
        },
      });
    }

    const indexExists = await elasticsearchClient.indexExists(
      tenant.elasticIndexPrefix,
      input.modelId
    );

    if (!indexExists) {
      await elasticsearchClient.createIndex(
        tenant.elasticIndexPrefix,
        input.modelId,
        input.fieldWeights,
        input.analyzer || 'ik_max_word'
      );
    }

    logger.info(
      { tenantId: tenant.tenantId, modelId: input.modelId },
      'Configured search'
    );

    return config;
  }

  async getSearchConfig(
    tenantId: string,
    modelId: string
  ): Promise<SearchConfig | null> {
    return this.prisma.searchConfig.findFirst({
      where: { tenantId, modelId },
    });
  }

  async deleteSearchConfig(
    tenantId: string,
    modelId: string,
    elasticIndexPrefix: string
  ): Promise<void> {
    await this.prisma.searchConfig.deleteMany({
      where: { tenantId, modelId },
    });

    await elasticsearchClient.deleteIndex(elasticIndexPrefix, modelId);
  }

  async indexContent(
    tenant: TenantContext,
    modelId: string,
    contentId: string,
    data: Record<string, unknown>
  ): Promise<void> {
    if (!tenant.limits.enableElasticsearch) {
      return;
    }

    const config = await this.getSearchConfig(tenant.tenantId, modelId);
    if (!config) {
      return;
    }

    const searchableFields = Object.keys(config.fieldWeights as Record<string, number>);
    const searchDoc: Record<string, unknown> = {};

    for (const field of searchableFields) {
      if (data[field] !== undefined) {
        searchDoc[field] = data[field];
      }
    }

    searchDoc.status = (data as any).status || 'draft';

    await elasticsearchClient.indexDocument(
      tenant.elasticIndexPrefix,
      modelId,
      contentId,
      searchDoc,
      true
    );

    logger.debug(
      { tenantId: tenant.tenantId, modelId, contentId },
      'Indexed content for search'
    );
  }

  async updateIndexedContent(
    tenant: TenantContext,
    modelId: string,
    contentId: string,
    partialData: Record<string, unknown>
  ): Promise<void> {
    if (!tenant.limits.enableElasticsearch) {
      return;
    }

    const config = await this.getSearchConfig(tenant.tenantId, modelId);
    if (!config) {
      return;
    }

    const searchableFields = Object.keys(config.fieldWeights as Record<string, number>);
    const updateDoc: Record<string, unknown> = {};

    for (const field of searchableFields) {
      if (partialData[field] !== undefined) {
        updateDoc[field] = partialData[field];
      }
    }

    if ((partialData as any).status) {
      updateDoc.status = (partialData as any).status;
    }

    if (Object.keys(updateDoc).length > 0) {
      await elasticsearchClient.updateDocument(
        tenant.elasticIndexPrefix,
        modelId,
        contentId,
        updateDoc,
        true
      );
    }
  }

  async removeIndexedContent(
    tenant: TenantContext,
    modelId: string,
    contentId: string
  ): Promise<void> {
    if (!tenant.limits.enableElasticsearch) {
      return;
    }

    await elasticsearchClient.deleteDocument(
      tenant.elasticIndexPrefix,
      modelId,
      contentId,
      true
    );
  }

  async search(
    tenant: TenantContext,
    input: SearchInput
  ): Promise<{
    total: number;
    page: number;
    pageSize: number;
    pages: number;
    results: Array<{
      contentId: string;
      score: number;
      data: Record<string, unknown>;
      highlight?: Record<string, string[]>;
    }>;
  }> {
    if (!tenant.limits.enableElasticsearch) {
      throw new Error('Elasticsearch is not enabled for this tenant');
    }

    const config = await this.getSearchConfig(tenant.tenantId, input.modelId);
    if (!config) {
      throw new Error('Search not configured for this model');
    }

    const sort = input.sortBy
      ? [{ [input.sortBy]: input.sortOrder || 'desc' }]
      : undefined;

    const result = await elasticsearchClient.search(
      tenant.elasticIndexPrefix,
      input.modelId,
      {
        query: input.query,
        fieldWeights: config.fieldWeights as Record<string, number>,
        defaultOperator: config.defaultOperator as 'AND' | 'OR',
        fuzziness: config.fuzziness,
        analyzer: config.analyzer,
        filters: input.filters,
        sort,
        page: input.page,
        pageSize: input.pageSize,
        highlight: input.highlight,
      }
    );

    const results = result.hits.map(hit => ({
      contentId: hit._id,
      score: hit._score,
      data: hit._source,
      highlight: hit.highlight,
    }));

    return {
      total: result.total,
      page: input.page || 1,
      pageSize: input.pageSize || 20,
      pages: Math.ceil(result.total / (input.pageSize || 20)),
      results,
    };
  }

  async bulkIndexModel(
    tenant: TenantContext,
    modelId: string
  ): Promise<{ indexed: number }> {
    if (!tenant.limits.enableElasticsearch) {
      throw new Error('Elasticsearch is not enabled for this tenant');
    }

    const [model, config] = await Promise.all([
      this.prisma.contentModel.findFirst({
        where: { id: modelId, tenantId: tenant.tenantId, deletedAt: null },
      }),
      this.getSearchConfig(tenant.tenantId, modelId),
    ]);

    if (!model) throw new Error('Content model not found');
    if (!config) throw new Error('Search not configured for this model');

    const contents = await this.prisma.contentEntry.findMany({
      where: {
        tenantId: tenant.tenantId,
        modelId,
        deletedAt: null,
      },
      select: { id: true, data: true, status: true },
    });

    const searchableFields = Object.keys(config.fieldWeights as Record<string, number>);
    const documents = contents.map(content => {
      const searchDoc: Record<string, unknown> = { status: content.status };
      const data = content.data as Record<string, unknown>;

      for (const field of searchableFields) {
        if (data[field] !== undefined) {
          searchDoc[field] = data[field];
        }
      }

      return {
        contentId: content.id,
        document: searchDoc,
      };
    });

    await elasticsearchClient.reindexAll(
      tenant.elasticIndexPrefix,
      modelId,
      documents
    );

    logger.info(
      { tenantId: tenant.tenantId, modelId, count: documents.length },
      'Bulk indexed model content'
    );

    return { indexed: documents.length };
  }

  async getSearchStats(
    tenant: TenantContext,
    modelId: string
  ): Promise<Record<string, unknown>> {
    if (!tenant.limits.enableElasticsearch) {
      return {};
    }

    return elasticsearchClient.getIndexStats(tenant.elasticIndexPrefix, modelId);
  }

  async suggest(
    tenant: TenantContext,
    modelId: string,
    query: string,
    field: string,
    size = 10
  ): Promise<string[]> {
    if (!tenant.limits.enableElasticsearch) {
      return [];
    }

    const client = elasticsearchClient['getClient'](tenant.elasticIndexPrefix);
    const indexName = `${tenant.elasticIndexPrefix}_${modelId}`.toLowerCase();

    try {
      const result = await client.search({
        index: indexName,
        body: {
          suggest: {
            suggestions: {
              prefix: query,
              completion: {
                field,
                size,
                fuzzy: {
                  fuzziness: 1,
                },
              },
            },
          },
          _source: false,
        },
      });

      const suggestions = (result.suggest?.suggestions as any[])?.[0]?.options || [];
      return suggestions.map((s: any) => s.text);
    } catch (error) {
      logger.error({ error, query, field }, 'Failed to get suggestions');
      return [];
    }
  }
}

export const searchService = new SearchService();
