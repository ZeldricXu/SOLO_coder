import { getPrismaClient, withTransaction, executeWithRetry } from '../../common/prisma';
import { getCacheClient, generateCacheKey, TTL } from '../../common/cache';
import { getEventBus, EventTypes } from '../../common/events';
import { getMetricsCollector } from '../../common/metrics';
import { NotFoundError, AppError } from '../../common/errors';
import { UsageRecordInput, BillingItem, InvoiceData, PaginationParams, PaginatedResult, ProcessingContext } from '../../common/types';
import { consumeQuota } from '../tenant';

const prisma = getPrismaClient();
const cache = getCacheClient();
const eventBus = getEventBus();
const metrics = getMetricsCollector();

const PRICING_RATES: Record<string, number> = {
  tickets: 0.5,
  api_calls: 0.001,
  storage: 0.01,
  agents: 25.0,
  workflows: 10.0,
  approvals: 0.1,
  documents: 0.05
};

export const recordUsage = async (
  tenantId: string,
  data: UsageRecordInput,
  context?: Partial<ProcessingContext>
) => {
  const billingPeriod = getBillingPeriod();
  
  const cacheKey = generateCacheKey('usage', 'dedup', data.eventId);
  const exists = await cache.get(cacheKey);
  if (exists) {
    return { id: exists as string, status: 'duplicate' };
  }

  await consumeQuota(tenantId, data.resourceType, data.quantity, context?.traceId);

  return executeWithRetry(async () => {
    const usage = await prisma.usageRecord.create({
      data: {
        ...data,
        tenantId,
        billingPeriod,
        attributes: data.attributes
      }
    });

    await cache.set(cacheKey, usage.id, TTL.DAY);

    eventBus.publish(EventTypes.USAGE_RECORDED, {
      usageId: usage.id,
      tenantId,
      resourceType: data.resourceType,
      quantity: data.quantity,
      billingPeriod
    }, context);

    metrics.increment('usage_records_total', 1, { resourceType: data.resourceType, tenantId });

    return usage;
  }, 3);
};

export const getUsageForPeriod = async (
  tenantId: string,
  billingPeriod?: string,
  resourceType?: string
) => {
  const period = billingPeriod || getBillingPeriod();
  const cacheKey = generateCacheKey('usage', tenantId, period, resourceType || 'all');
  
  const cached = await cache.get(cacheKey);
  if (cached) return cached;

  const where: Record<string, unknown> = { tenantId, billingPeriod: period };
  if (resourceType) where.resourceType = resourceType;

  const usage = await prisma.usageRecord.findMany({
    where,
    orderBy: { createdAt: 'desc' }
  });

  const summary = calculateUsageSummary(usage);
  const result = { records: usage, summary };

  await cache.set(cacheKey, result, TTL.MEDIUM);
  return result;
};

export const calculateUsageSummary = (records: Array<{ resourceType: string; quantity: number }>) => {
  const byResource: Record<string, number> = {};
  let totalCost = 0;

  for (const record of records) {
    byResource[record.resourceType] = (byResource[record.resourceType] || 0) + record.quantity;
    const rate = PRICING_RATES[record.resourceType] || 0;
    totalCost += record.quantity * rate;
  }

  return {
    byResource,
    totalCost,
    currency: 'USD'
  };
};

export const generateInvoice = async (
  tenantId: string,
  billingPeriod?: string,
  traceId?: string
) => {
  const period = billingPeriod || getBillingPeriod();

  return withTransaction(async (tx) => {
    const existingInvoice = await tx.invoice.findFirst({
      where: { tenantId, billingPeriod: period }
    });

    if (existingInvoice) {
      return existingInvoice;
    }

    const usage = await tx.usageRecord.findMany({
      where: { tenantId, billingPeriod: period }
    });

    const { items, totalAmount } = calculateInvoiceItems(usage);

    const invoice = await tx.invoice.create({
      data: {
        tenantId,
        billingPeriod: period,
        amount: totalAmount,
        currency: 'USD',
        dueAt: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
      }
    });

    for (const item of items) {
      await tx.invoiceItem.create({
        data: {
          invoiceId: invoice.id,
          ...item
        }
      });
    }

    eventBus.publish(EventTypes.INVOICE_GENERATED, {
      invoiceId: invoice.id,
      tenantId,
      amount: totalAmount,
      billingPeriod: period
    }, { traceId });

    await cache.del(generateCacheKey('invoice', tenantId, period));

    return invoice;
  });
};

export const calculateInvoiceItems = (usage: Array<{ resourceType: string; quantity: number; unit: string }>) => {
  const aggregated: Record<string, { quantity: number; unit: string }> = {};

  for (const record of usage) {
    if (!aggregated[record.resourceType]) {
      aggregated[record.resourceType] = { quantity: 0, unit: record.unit };
    }
    aggregated[record.resourceType].quantity += record.quantity;
  }

  const items: BillingItem[] = [];
  let totalAmount = 0;

  for (const [resourceType, data] of Object.entries(aggregated)) {
    const unitPrice = PRICING_RATES[resourceType] || 0;
    const amount = data.quantity * unitPrice;
    totalAmount += amount;

    items.push({
      resourceType,
      quantity: data.quantity,
      unitPrice,
      amount,
      description: `${resourceType} usage`
    });
  }

  return { items, totalAmount };
};

export const getInvoice = async (
  invoiceId: string,
  traceId?: string
) => {
  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId },
    include: { items: true }
  });

  if (!invoice) {
    throw new NotFoundError('Invoice not found', { invoiceId }, traceId);
  }

  return invoice;
};

export const listInvoices = async (
  tenantId: string,
  params: PaginationParams
): Promise<PaginatedResult<unknown>> => {
  const [total, items] = await Promise.all([
    prisma.invoice.count({ where: { tenantId } }),
    prisma.invoice.findMany({
      where: { tenantId },
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { issuedAt: 'desc' },
      include: { items: true }
    })
  ]);

  return {
    items,
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize)
  };
};

export const payInvoice = async (
  invoiceId: string,
  paymentMethod: string,
  traceId?: string
) => {
  const invoice = await prisma.invoice.update({
    where: { id: invoiceId },
    data: {
      status: 'paid',
      paidAt: new Date()
    }
  });

  eventBus.publish(EventTypes.INVOICE_PAID, {
    invoiceId,
    amount: invoice.amount,
    paymentMethod
  }, { traceId });

  return invoice;
};

export const getBillingPeriod = (date: Date = new Date()): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
};

export const getBillingSummary = async (
  tenantId: string,
  traceId?: string
) => {
  const currentPeriod = getBillingPeriod();
  const previousPeriod = getPreviousPeriod();

  const [currentUsageResult, previousUsageResult, invoices] = await Promise.all([
    getUsageForPeriod(tenantId, currentPeriod),
    getUsageForPeriod(tenantId, previousPeriod),
    prisma.invoice.findMany({
      where: { tenantId },
      orderBy: { issuedAt: 'desc' },
      take: 12
    })
  ]);

  const pendingInvoices = invoices.filter((i: { status: string }) => i.status === 'pending');
  const totalOutstanding = pendingInvoices.reduce(
    (sum: number, i: { amount: number }) => sum + i.amount,
    0
  );

  const currentUsage = currentUsageResult as { summary: { byResource: Record<string, number>; totalCost: number; currency: string } };
  const previousUsage = previousUsageResult as { summary: { byResource: Record<string, number>; totalCost: number; currency: string } };

  return {
    currentPeriod,
    currentUsage: currentUsage.summary,
    previousPeriod,
    previousUsage: previousUsage.summary,
    totalOutstanding,
    pendingInvoices: pendingInvoices.length,
    recentInvoices: invoices.slice(0, 5),
    currency: 'USD'
  };
};

export const getPreviousPeriod = (date: Date = new Date()): string => {
  const prev = new Date(date.getFullYear(), date.getMonth() - 1, 1);
  return getBillingPeriod(prev);
};

export const estimateCost = async (
  tenantId: string,
  resourceType: string,
  quantity: number
) => {
  const rate = PRICING_RATES[resourceType] || 0;
  const estimatedCost = quantity * rate;

  const currentUsageResult = await getUsageForPeriod(tenantId);
  const currentUsage = currentUsageResult as { summary: { byResource: Record<string, number>; totalCost: number; currency: string } };
  const currentQuantity = currentUsage.summary.byResource[resourceType] || 0;
  const currentCost = currentQuantity * rate;

  return {
    resourceType,
    quantity,
    unitPrice: rate,
    estimatedCost,
    currentQuantity,
    currentCost,
    totalWithEstimate: currentCost + estimatedCost,
    currency: 'USD'
  };
};

export const createBillingPlan = async (
  name: string,
  type: string,
  price: number,
  features: Record<string, unknown>
) => {
  return prisma.billingPlan.create({
    data: {
      name,
      type,
      price,
      currency: 'USD',
      features
    }
  });
};

export const listBillingPlans = async (activeOnly: boolean = true) => {
  return prisma.billingPlan.findMany({
    where: activeOnly ? { active: true } : undefined,
    orderBy: { price: 'asc' }
  });
};

export const processUsageBatch = async (
  records: Array<{ tenantId: string; data: UsageRecordInput }>,
  context?: Partial<ProcessingContext>
) => {
  const results = [];
  for (const record of records) {
    try {
      const result = await recordUsage(record.tenantId, record.data, context);
      results.push({ ...record, status: 'success', result });
    } catch (err) {
      results.push({
        ...record,
        status: 'failed',
        error: err instanceof Error ? err.message : String(err)
      });
    }
  }
  return results;
};
