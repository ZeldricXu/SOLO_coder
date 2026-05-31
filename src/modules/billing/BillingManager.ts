import { UsageRecord, UsageAggregation, Invoice, InvoiceItem, BillingCycle, Payment, UsageLimit, BillingConfig } from '../../types/billing';
import { generateId, getCurrentTimestamp } from '../../common/utils';
import { NotFoundError, AppError } from '../../common/errors';

export class BillingManager {
  private usageRecords: Map<string, UsageRecord>;
  private invoices: Map<string, Invoice>;
  private billingCycles: Map<string, BillingCycle>;
  private payments: Map<string, Payment>;
  private usageLimits: Map<string, UsageLimit>;
  private config: BillingConfig;

  constructor(config: BillingConfig) {
    this.usageRecords = new Map();
    this.invoices = new Map();
    this.billingCycles = new Map();
    this.payments = new Map();
    this.usageLimits = new Map();
    this.config = config;
  }

  recordUsage(
    tenantId: string,
    resourceType: UsageRecord['resourceType'],
    quantity: number,
    metadata: Record<string, unknown> = {}
  ): UsageRecord {
    const record: UsageRecord = {
      id: generateId('usage'),
      tenantId,
      resourceType,
      quantity,
      timestamp: getCurrentTimestamp(),
      metadata
    };

    this.usageRecords.set(record.id, record);
    this.checkUsageLimits(tenantId, resourceType, quantity);

    return record;
  }

  recordApiCall(tenantId: string, endpoint?: string, duration?: number): UsageRecord {
    return this.recordUsage(tenantId, 'api_calls', 1, { endpoint, duration });
  }

  recordStorageUsage(tenantId: string, storageGb: number): UsageRecord {
    return this.recordUsage(tenantId, 'storage_gb', storageGb);
  }

  recordComputeUsage(tenantId: string, computeUnits: number): UsageRecord {
    return this.recordUsage(tenantId, 'compute_units', computeUnits);
  }

  private checkUsageLimits(tenantId: string, resourceType: string, quantity: number): void {
    const limit = this.usageLimits.get(`${tenantId}:${resourceType}`);
    if (!limit) return;

    const currentUsage = this.getCurrentUsage(tenantId, resourceType);
    const totalUsage = currentUsage + quantity;

    if (totalUsage >= limit.hardLimit) {
      throw new AppError(
        `用量已达上限: ${resourceType}`,
        'USAGE_LIMIT_EXCEEDED',
        429,
        { current: totalUsage, limit: limit.hardLimit }
      );
    }

    for (const alert of limit.alerts) {
      if (!alert.notified && totalUsage >= limit.softLimit * alert.threshold) {
        alert.notified = true;
        console.log(`用量告警: 租户 ${tenantId} 的 ${resourceType} 用量已达 ${(alert.threshold * 100).toFixed(0)}%`);
      }
    }
  }

  getCurrentUsage(tenantId: string, resourceType: string): number {
    const now = Date.now();
    const cycleStart = now - this.config.cycleDays * 24 * 60 * 60 * 1000;

    return Array.from(this.usageRecords.values())
      .filter(r =>
        r.tenantId === tenantId &&
        r.resourceType === resourceType &&
        new Date(r.timestamp).getTime() > cycleStart
      )
      .reduce((sum, r) => sum + r.quantity, 0);
  }

  aggregateUsage(tenantId: string, period: 'hourly' | 'daily' | 'monthly' = 'daily'): UsageAggregation {
    const now = new Date();
    let startDate: Date;

    switch (period) {
      case 'hourly':
        startDate = new Date(now.getTime() - 60 * 60 * 1000);
        break;
      case 'daily':
        startDate = new Date(now.getTime() - 24 * 60 * 60 * 1000);
        break;
      case 'monthly':
        startDate = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
        break;
    }

    const records = Array.from(this.usageRecords.values()).filter(
      r => r.tenantId === tenantId && new Date(r.timestamp) >= startDate
    );

    return {
      tenantId,
      period,
      startDate: startDate.toISOString(),
      endDate: now.toISOString(),
      apiCalls: records.filter(r => r.resourceType === 'api_calls').reduce((sum, r) => sum + r.quantity, 0),
      storageGb: Math.max(...records.filter(r => r.resourceType === 'storage_gb').map(r => r.quantity), 0),
      computeUnits: records.filter(r => r.resourceType === 'compute_units').reduce((sum, r) => sum + r.quantity, 0),
      bandwidthGb: records.filter(r => r.resourceType === 'bandwidth_gb').reduce((sum, r) => sum + r.quantity, 0)
    };
  }

  generateInvoice(tenantId: string, periodStart?: string, periodEnd?: string): Invoice {
    const now = getCurrentTimestamp();
    const cycle = this.getOrCreateBillingCycle(tenantId);

    const usage = this.aggregateUsage(tenantId, 'monthly');

    const items: InvoiceItem[] = [];

    if (usage.apiCalls > 0) {
      items.push({
        id: generateId('inv_item'),
        description: 'API 调用次数',
        quantity: usage.apiCalls,
        unitPrice: this.config.pricePerApiCall,
        total: usage.apiCalls * this.config.pricePerApiCall,
        resourceType: 'api_calls'
      });
    }

    if (usage.storageGb > 0) {
      items.push({
        id: generateId('inv_item'),
        description: '存储空间',
        quantity: usage.storageGb,
        unitPrice: this.config.pricePerStorageGb,
        total: usage.storageGb * this.config.pricePerStorageGb,
        resourceType: 'storage_gb'
      });
    }

    if (usage.computeUnits > 0) {
      items.push({
        id: generateId('inv_item'),
        description: '计算资源',
        quantity: usage.computeUnits,
        unitPrice: this.config.pricePerComputeUnit,
        total: usage.computeUnits * this.config.pricePerComputeUnit,
        resourceType: 'compute_units'
      });
    }

    const subtotal = items.reduce((sum, item) => sum + item.total, 0);
    const tax = subtotal * 0.06;
    const total = subtotal + tax;

    const invoice: Invoice = {
      id: generateId('inv'),
      tenantId,
      invoiceNumber: `INV-${new Date().getFullYear()}${String(new Date().getMonth() + 1).padStart(2, '0')}-${Math.floor(Math.random() * 10000).toString().padStart(4, '0')}`,
      periodStart: periodStart || cycle.cycleStart,
      periodEnd: periodEnd || cycle.cycleEnd,
      items,
      subtotal,
      tax,
      total,
      currency: this.config.currency,
      status: 'draft',
      issuedAt: now,
      dueDate: new Date(new Date(now).getTime() + 30 * 24 * 60 * 60 * 1000).toISOString(),
      createdAt: now
    };

    this.invoices.set(invoice.id, invoice);
    return invoice;
  }

  private getOrCreateBillingCycle(tenantId: string): BillingCycle {
    const activeCycle = Array.from(this.billingCycles.values()).find(
      c => c.tenantId === tenantId && c.status === 'active'
    );

    if (activeCycle) return activeCycle;

    const now = new Date();
    const cycleEnd = new Date(now.getTime() + this.config.cycleDays * 24 * 60 * 60 * 1000);

    const cycle: BillingCycle = {
      id: generateId('cycle'),
      tenantId,
      cycleStart: now.toISOString(),
      cycleEnd: cycleEnd.toISOString(),
      status: 'active',
      createdAt: getCurrentTimestamp()
    };

    this.billingCycles.set(cycle.id, cycle);
    return cycle;
  }

  getInvoice(invoiceId: string): Invoice {
    const invoice = this.invoices.get(invoiceId);
    if (!invoice) {
      throw new NotFoundError(`发票不存在: ${invoiceId}`);
    }
    return invoice;
  }

  listInvoices(tenantId?: string, status?: Invoice['status']): Invoice[] {
    let invoices = Array.from(this.invoices.values());

    if (tenantId) {
      invoices = invoices.filter(i => i.tenantId === tenantId);
    }

    if (status) {
      invoices = invoices.filter(i => i.status === status);
    }

    return invoices.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  issueInvoice(invoiceId: string): Invoice {
    const invoice = this.getInvoice(invoiceId);
    invoice.status = 'issued';
    invoice.issuedAt = getCurrentTimestamp();
    this.invoices.set(invoiceId, invoice);
    return invoice;
  }

  processPayment(invoiceId: string, amount: number, method: Payment['method'], transactionId: string): Payment {
    const invoice = this.getInvoice(invoiceId);

    const payment: Payment = {
      id: generateId('pay'),
      invoiceId,
      tenantId: invoice.tenantId,
      amount,
      currency: invoice.currency,
      method,
      transactionId,
      status: 'completed',
      createdAt: getCurrentTimestamp(),
      completedAt: getCurrentTimestamp()
    };

    this.payments.set(payment.id, payment);

    const totalPaid = Array.from(this.payments.values())
      .filter(p => p.invoiceId === invoiceId && p.status === 'completed')
      .reduce((sum, p) => sum + p.amount, 0);

    if (totalPaid >= invoice.total) {
      invoice.status = 'paid';
      invoice.paidAt = getCurrentTimestamp();
      this.invoices.set(invoiceId, invoice);
    }

    return payment;
  }

  getPayment(paymentId: string): Payment {
    const payment = this.payments.get(paymentId);
    if (!payment) {
      throw new NotFoundError(`支付记录不存在: ${paymentId}`);
    }
    return payment;
  }

  listPayments(tenantId?: string, invoiceId?: string): Payment[] {
    let payments = Array.from(this.payments.values());

    if (tenantId) {
      payments = payments.filter(p => p.tenantId === tenantId);
    }

    if (invoiceId) {
      payments = payments.filter(p => p.invoiceId === invoiceId);
    }

    return payments.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  setUsageLimit(
    tenantId: string,
    resourceType: string,
    softLimit: number,
    hardLimit: number
  ): UsageLimit {
    const key = `${tenantId}:${resourceType}`;

    const limit: UsageLimit = {
      tenantId,
      resourceType,
      softLimit,
      hardLimit,
      alerts: [
        { threshold: 0.8, notified: false },
        { threshold: 0.9, notified: false },
        { threshold: 1.0, notified: false }
      ]
    };

    this.usageLimits.set(key, limit);
    return limit;
  }

  getUsageLimit(tenantId: string, resourceType: string): UsageLimit | undefined {
    return this.usageLimits.get(`${tenantId}:${resourceType}`);
  }

  getBillingCycle(tenantId: string): BillingCycle | undefined {
    return Array.from(this.billingCycles.values()).find(
      c => c.tenantId === tenantId && c.status === 'active'
    );
  }

  closeBillingCycle(tenantId: string): BillingCycle {
    const cycle = this.getBillingCycle(tenantId);
    if (!cycle) {
      throw new NotFoundError(`没有找到活跃的计费周期: ${tenantId}`);
    }

    cycle.status = 'closed';
    cycle.cycleEnd = getCurrentTimestamp();
    this.billingCycles.set(cycle.id, cycle);

    this.generateInvoice(tenantId, cycle.cycleStart, cycle.cycleEnd);

    return cycle;
  }

  getTenantBillingSummary(tenantId: string) {
    const cycle = this.getBillingCycle(tenantId);
    const usage = cycle ? this.aggregateUsage(tenantId, 'monthly') : null;
    const currentInvoice = Array.from(this.invoices.values())
      .filter(i => i.tenantId === tenantId && (i.status === 'draft' || i.status === 'issued'))
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];

    const apiUsage = usage ? this.getCurrentUsage(tenantId, 'api_calls') : 0;
    const storageUsage = usage ? this.getCurrentUsage(tenantId, 'storage_gb') : 0;
    const computeUsage = usage ? this.getCurrentUsage(tenantId, 'compute_units') : 0;

    const estimatedCost =
      apiUsage * this.config.pricePerApiCall +
      storageUsage * this.config.pricePerStorageGb +
      computeUsage * this.config.pricePerComputeUnit;

    return {
      cycle,
      usage,
      currentInvoice,
      currentUsage: {
        apiCalls: apiUsage,
        storageGb: storageUsage,
        computeUnits: computeUsage
      },
      estimatedCost,
      currency: this.config.currency
    };
  }

  getStats() {
    return {
      totalUsageRecords: this.usageRecords.size,
      totalInvoices: this.invoices.size,
      totalPayments: this.payments.size,
      totalRevenue: Array.from(this.payments.values())
        .filter(p => p.status === 'completed')
        .reduce((sum, p) => sum + p.amount, 0),
      pendingInvoices: Array.from(this.invoices.values()).filter(i => i.status === 'issued').length,
      overdueInvoices: Array.from(this.invoices.values()).filter(
        i => i.status === 'issued' && new Date(i.dueDate) < new Date()
      ).length
    };
  }
}
