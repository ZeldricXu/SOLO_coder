import { Tenant, ContentModel, ContentEntry, WorkflowDefinition, WorkflowNodeType, WorkflowApprovalType } from '@prisma/client';
import { generateId } from '@utils/crypto';
import { ContentSchema, TenantLimits } from '@types/index';
import { PrismaClient } from '@prisma/client';

let prisma: PrismaClient;

export const setTestPrisma = (client: PrismaClient) => {
  prisma = client;
};

const tenantLimitsMap: Record<string, TenantLimits> = {
  free: {
    maxContentModels: 5,
    maxContentEntries: 1000,
    maxVersionsPerContent: 10,
    maxApiCallsPerDay: 1000,
    rateLimitPerMinute: 60,
    maxStorageBytes: 1 * 1024 * 1024 * 1024,
    maxWebhooks: 0,
    maxWorkflowRunsPerMonth: 0,
    enableElasticsearch: false,
    enableCDN: false,
    enableWebhooks: false,
    enableWorkflows: false,
    enableVersioning: true,
  },
  starter: {
    maxContentModels: 20,
    maxContentEntries: 10000,
    maxVersionsPerContent: 50,
    maxApiCallsPerDay: 10000,
    rateLimitPerMinute: 300,
    maxStorageBytes: 10 * 1024 * 1024 * 1024,
    maxWebhooks: 5,
    maxWorkflowRunsPerMonth: 10,
    enableElasticsearch: true,
    enableCDN: true,
    enableWebhooks: true,
    enableWorkflows: true,
    enableVersioning: true,
  },
  professional: {
    maxContentModels: 100,
    maxContentEntries: 100000,
    maxVersionsPerContent: 200,
    maxApiCallsPerDay: 100000,
    rateLimitPerMinute: 1000,
    maxStorageBytes: 100 * 1024 * 1024 * 1024,
    maxWebhooks: 20,
    maxWorkflowRunsPerMonth: 1000,
    enableElasticsearch: true,
    enableCDN: true,
    enableWebhooks: true,
    enableWorkflows: true,
    enableVersioning: true,
  },
  enterprise: {
    maxContentModels: 999999,
    maxContentEntries: 999999999,
    maxVersionsPerContent: 1000,
    maxApiCallsPerDay: 1000000,
    rateLimitPerMinute: 5000,
    maxStorageBytes: 1 * 1024 * 1024 * 1024 * 1024,
    maxWebhooks: 100,
    maxWorkflowRunsPerMonth: 999999,
    enableElasticsearch: true,
    enableCDN: true,
    enableWebhooks: true,
    enableWorkflows: true,
    enableVersioning: true,
  },
};

export const createTenantFactory = (overrides: Partial<Tenant> = {}): Tenant => {
  const plan = (overrides.plan as string) || 'professional';
  const code = overrides.code || `tenant-${Date.now()}-${Math.random().toString(36).substr(2, 6)}`;

  return {
    id: generateId('tnt'),
    name: overrides.name || `Test Tenant ${code.toUpperCase()}`,
    code,
    apiKey: overrides.apiKey || `sk_test_${generateId('api')}`,
    plan: plan as any,
    status: 'active',
    dbSchema: overrides.dbSchema || `tenant_${code.replace(/-/g, '_')}`,
    elasticIndexPrefix: overrides.elasticIndexPrefix || `tenant_${code}`,
    customDomain: overrides.customDomain || `${code}.test.local`,
    config: overrides.config || {},
    limits: overrides.limits || tenantLimitsMap[plan],
    createdAt: new Date(),
    updatedAt: new Date(),
    deletedAt: null,
  } as Tenant;
};

export const createTenant = async (overrides: Partial<Tenant> = {}): Promise<Tenant> => {
  const tenant = createTenantFactory(overrides);
  return prisma.tenant.create({ data: tenant });
};

export const createArticleSchemaFactory = (): ContentSchema => ({
  type: 'object',
  title: 'Article',
  description: 'Article content model',
  properties: {
    title: {
      type: 'string',
      title: 'Title',
      minLength: 1,
      maxLength: 255,
      searchable: true,
    },
    content: {
      type: 'string',
      title: 'Content',
      format: 'textarea',
      searchable: true,
    },
    summary: {
      type: 'string',
      title: 'Summary',
      maxLength: 500,
      searchable: true,
    },
    author: {
      type: 'string',
      title: 'Author',
      searchable: true,
    },
    tags: {
      type: 'array',
      title: 'Tags',
      items: { type: 'string' },
      searchable: false,
    },
    publishedAt: {
      type: 'string',
      title: 'Published At',
      format: 'date-time',
      searchable: false,
    },
    status: {
      type: 'string',
      title: 'Status',
      enum: ['draft', 'review', 'published'],
      default: 'draft',
      searchable: false,
    },
    views: {
      type: 'integer',
      title: 'Views',
      minimum: 0,
      default: 0,
      searchable: false,
    },
    featured: {
      type: 'boolean',
      title: 'Featured',
      default: false,
      searchable: false,
    },
  },
  required: ['title', 'content'],
  additionalProperties: false,
});

export const createProductSchemaFactory = (): ContentSchema => ({
  type: 'object',
  title: 'Product',
  description: 'Product catalog model',
  properties: {
    name: {
      type: 'string',
      title: 'Product Name',
      minLength: 1,
      maxLength: 255,
      searchable: true,
    },
    description: {
      type: 'string',
      title: 'Description',
      format: 'textarea',
      searchable: true,
    },
    sku: {
      type: 'string',
      title: 'SKU',
      searchable: true,
    },
    price: {
      type: 'number',
      title: 'Price',
      minimum: 0,
      searchable: false,
    },
    stock: {
      type: 'integer',
      title: 'Stock Quantity',
      minimum: 0,
      default: 0,
      searchable: false,
    },
    category: {
      type: 'string',
      title: 'Category',
      searchable: true,
    },
    images: {
      type: 'array',
      title: 'Images',
      items: { type: 'string', format: 'uri' },
      searchable: false,
    },
    specs: {
      type: 'object',
      title: 'Specifications',
      searchable: false,
    },
  },
  required: ['name', 'sku', 'price'],
  additionalProperties: false,
});

export const createContentModelFactory = (
  tenantId: string,
  overrides: Partial<ContentModel> = {}
): ContentModel => {
  const schema = overrides.schemaJson || createArticleSchemaFactory();
  const name = (schema as any).title || 'Article';

  return {
    id: generateId('mdl'),
    tenantId,
    name: overrides.name || name,
    code: overrides.code || name.toLowerCase().replace(/\s+/g, '_'),
    description: overrides.description || (schema as any).description,
    schemaJson: schema,
    tableName: overrides.tableName || `content_${name.toLowerCase().replace(/\s+/g, '_')}`,
    version: overrides.version || 1,
    isActive: overrides.isActive ?? true,
    createdAt: new Date(),
    updatedAt: new Date(),
    deletedAt: null,
  } as ContentModel;
};

export const createContentModel = async (
  tenantId: string,
  overrides: Partial<ContentModel> = {}
): Promise<ContentModel> => {
  const model = createContentModelFactory(tenantId, overrides);
  return prisma.contentModel.create({ data: model });
};

export const createArticleEntryFactory = (
  tenantId: string,
  modelId: string,
  overrides: Partial<ContentEntry> = {}
): ContentEntry => ({
  id: generateId('cnt'),
  tenantId,
  modelId,
  status: overrides.status || 'draft',
  version: overrides.version || 1,
  data: overrides.data || {
    title: `Test Article ${Date.now()}`,
    content: 'This is the content of the test article with detailed information.',
    summary: 'A brief summary of the test article.',
    author: 'John Doe',
    tags: ['test', 'article'],
    status: 'draft',
    views: 0,
    featured: false,
  },
  createdBy: overrides.createdBy || 'user-123',
  updatedBy: overrides.updatedBy || 'user-123',
  publishedAt: overrides.publishedAt || null,
  createdAt: new Date(),
  updatedAt: new Date(),
  deletedAt: null,
} as ContentEntry);

export const createContentEntry = async (
  tenantId: string,
  modelId: string,
  overrides: Partial<ContentEntry> = {}
): Promise<ContentEntry> => {
  const entry = createArticleEntryFactory(tenantId, modelId, overrides);
  return prisma.contentEntry.create({ data: entry });
};

export const createSerialWorkflowFactory = (
  tenantId: string,
  modelId: string
): WorkflowDefinition => {
  const nodes: any[] = [
    {
      id: 'start',
      type: WorkflowNodeType.START,
      name: 'Start',
    },
    {
      id: 'review',
      type: WorkflowNodeType.APPROVAL,
      name: 'Content Review',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-reviewer-1'],
        timeoutHours: 24,
        onTimeout: 'skip',
      },
    },
    {
      id: 'editor',
      type: WorkflowNodeType.APPROVAL,
      name: 'Editor Approval',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-editor-1'],
        timeoutHours: 24,
        onTimeout: 'skip',
      },
    },
    {
      id: 'publish',
      type: WorkflowNodeType.APPROVAL,
      name: 'Final Publish',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-publisher-1'],
        timeoutHours: 48,
        onTimeout: 'reject',
      },
    },
    {
      id: 'end',
      type: WorkflowNodeType.END,
      name: 'End',
    },
  ];

  const edges = [
    { source: 'start', target: 'review' },
    { source: 'review', target: 'editor' },
    { source: 'editor', target: 'publish' },
    { source: 'publish', target: 'end' },
  ];

  return {
    id: generateId('wfd'),
    tenantId,
    modelId,
    name: 'Serial Approval Workflow',
    description: 'Content must go through review, editor approval, and final publish',
    nodes,
    edges,
    triggerEvent: 'content.submit_for_review',
    isDefault: true,
    isActive: true,
    createdAt: new Date(),
    updatedAt: new Date(),
    deletedAt: null,
  } as WorkflowDefinition;
};

export const createParallelWorkflowFactory = (
  tenantId: string,
  modelId: string
): WorkflowDefinition => {
  const nodes: any[] = [
    {
      id: 'start',
      type: WorkflowNodeType.START,
      name: 'Start',
    },
    {
      id: 'parallel-gate',
      type: WorkflowNodeType.PARALLEL,
      name: 'Parallel Review',
      config: {
        completionType: 'all',
        branches: [
          { nodeId: 'legal-review' },
          { nodeId: 'tech-review' },
          { nodeId: 'business-review' },
        ],
      },
    },
    {
      id: 'legal-review',
      type: WorkflowNodeType.APPROVAL,
      name: 'Legal Review',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-legal'],
        timeoutHours: 48,
        onTimeout: 'skip',
      },
    },
    {
      id: 'tech-review',
      type: WorkflowNodeType.APPROVAL,
      name: 'Technical Review',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-tech-1', 'user-tech-2'],
        timeoutHours: 24,
        onTimeout: 'skip',
      },
    },
    {
      id: 'business-review',
      type: WorkflowNodeType.APPROVAL,
      name: 'Business Review',
      config: {
        approvalType: WorkflowApprovalType.PERCENTAGE,
        approvalPercentage: 50,
        approvers: ['user-biz-1', 'user-biz-2', 'user-biz-3'],
        timeoutHours: 72,
        onTimeout: 'reject',
      },
    },
    {
      id: 'join',
      type: WorkflowNodeType.PARALLEL,
      name: 'Join Parallel',
      config: {
        join: true,
      },
    },
    {
      id: 'condition',
      type: WorkflowNodeType.CONDITION,
      name: 'Urgent Check',
      config: {
        expression: 'content.urgent === true',
        trueBranch: 'publish-fast',
        falseBranch: 'publish-normal',
      },
    },
    {
      id: 'publish-fast',
      type: WorkflowNodeType.APPROVAL,
      name: 'Urgent Publish',
      config: {
        approvalType: WorkflowApprovalType.ONE,
        approvers: ['user-manager'],
        timeoutHours: 2,
        onTimeout: 'reject',
      },
    },
    {
      id: 'publish-normal',
      type: WorkflowNodeType.APPROVAL,
      name: 'Normal Publish',
      config: {
        approvalType: WorkflowApprovalType.ALL,
        approvers: ['user-manager', 'user-director'],
        timeoutHours: 24,
        onTimeout: 'skip',
      },
    },
    {
      id: 'end',
      type: WorkflowNodeType.END,
      name: 'End',
    },
  ];

  const edges = [
    { source: 'start', target: 'parallel-gate' },
    { source: 'legal-review', target: 'join' },
    { source: 'tech-review', target: 'join' },
    { source: 'business-review', target: 'join' },
    { source: 'join', target: 'condition' },
    { source: 'publish-fast', target: 'end' },
    { source: 'publish-normal', target: 'end' },
  ];

  return {
    id: generateId('wfd'),
    tenantId,
    modelId,
    name: 'Parallel Approval with Conditions',
    description: 'Parallel reviews from legal, tech, and business with conditional publish',
    nodes,
    edges,
    triggerEvent: 'content.submit_for_review',
    isDefault: true,
    isActive: true,
    createdAt: new Date(),
    updatedAt: new Date(),
    deletedAt: null,
  } as WorkflowDefinition;
};

export const createWorkflowDefinition = async (
  tenantId: string,
  modelId: string,
  type: 'serial' | 'parallel' = 'serial'
): Promise<WorkflowDefinition> => {
  const workflow = type === 'serial'
    ? createSerialWorkflowFactory(tenantId, modelId)
    : createParallelWorkflowFactory(tenantId, modelId);
  return prisma.workflowDefinition.create({ data: workflow });
};

export const createApiRequest = (apiKey: string, path: string, method = 'GET', body?: any) => ({
  method,
  url: `http://test.local${path}`,
  headers: {
    'x-api-key': apiKey,
    'content-type': 'application/json',
    'x-request-id': `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
  },
  body,
  hostname: 'test.local',
  routerPath: path,
});

export const createHostRequest = (host: string, path: string, method = 'GET') => ({
  method,
  url: `http://${host}${path}`,
  headers: {
    host,
    'content-type': 'application/json',
  },
  hostname: host,
  routerPath: path,
});

export const getTenantLimits = (plan: string): TenantLimits => {
  return tenantLimitsMap[plan] || tenantLimitsMap.free;
};
