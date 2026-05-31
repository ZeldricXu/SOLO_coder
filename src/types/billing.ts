export interface UsageRecord {
  id: string;
  tenantId: string;
  resourceType: 'api_calls' | 'storage_gb' | 'compute_units' | 'bandwidth_gb';
  quantity: number;
  timestamp: string;
  metadata: Record<string, unknown>;
}

export interface UsageAggregation {
  tenantId: string;
  period: 'hourly' | 'daily' | 'monthly';
  startDate: string;
  endDate: string;
  apiCalls: number;
  storageGb: number;
  computeUnits: number;
  bandwidthGb: number;
}

export interface Invoice {
  id: string;
  tenantId: string;
  invoiceNumber: string;
  periodStart: string;
  periodEnd: string;
  items: InvoiceItem[];
  subtotal: number;
  tax: number;
  total: number;
  currency: string;
  status: 'draft' | 'issued' | 'paid' | 'overdue' | 'cancelled';
  issuedAt: string;
  dueDate: string;
  paidAt?: string;
  createdAt: string;
}

export interface InvoiceItem {
  id: string;
  description: string;
  quantity: number;
  unitPrice: number;
  total: number;
  resourceType: string;
}

export interface PricingTier {
  resourceType: 'api_calls' | 'storage_gb' | 'compute_units' | 'bandwidth_gb';
  minQuantity: number;
  maxQuantity?: number;
  unitPrice: number;
  currency: string;
}

export interface BillingCycle {
  id: string;
  tenantId: string;
  cycleStart: string;
  cycleEnd: string;
  status: 'active' | 'closed';
  createdAt: string;
}

export interface Payment {
  id: string;
  invoiceId: string;
  tenantId: string;
  amount: number;
  currency: string;
  method: 'credit_card' | 'bank_transfer' | 'wallet';
  transactionId: string;
  status: 'pending' | 'completed' | 'failed' | 'refunded';
  createdAt: string;
  completedAt?: string;
}

export interface UsageLimit {
  tenantId: string;
  resourceType: string;
  softLimit: number;
  hardLimit: number;
  alerts: {
    threshold: number;
    notified: boolean;
  }[];
}
