use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Result};
use chrono::{DateTime, Datelike, Duration as ChronoDuration, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageRecord {
    pub record_id: String,
    pub tenant_id: String,
    pub resource_type: ResourceType,
    pub quantity: f64,
    pub unit: String,
    pub timestamp: DateTime<Utc>,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum ResourceType {
    ApiRequests,
    StorageGb,
    CpuHours,
    MemoryGbHours,
    NetworkInGb,
    NetworkOutGb,
    DatabaseQueries,
    FunctionInvocations,
    Custom(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PricingTier {
    pub tier_name: String,
    pub resource_type: ResourceType,
    pub unit_price: f64,
    pub currency: String,
    pub min_quantity: Option<f64>,
    pub max_quantity: Option<f64>,
    pub discount_percent: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BillingPlan {
    pub plan_id: String,
    pub name: String,
    pub description: String,
    pub tenant_tier: String,
    pub base_price: f64,
    pub pricing_tiers: Vec<PricingTier>,
    pub billing_cycle: BillingCycle,
    pub currency: String,
    pub features: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum BillingCycle {
    Monthly,
    Quarterly,
    Annual,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Invoice {
    pub invoice_id: String,
    pub tenant_id: String,
    pub billing_period_start: DateTime<Utc>,
    pub billing_period_end: DateTime<Utc>,
    pub line_items: Vec<InvoiceLineItem>,
    pub subtotal: f64,
    pub tax: f64,
    pub total: f64,
    pub currency: String,
    pub status: InvoiceStatus,
    pub due_date: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub paid_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum InvoiceStatus {
    Draft,
    Issued,
    Paid,
    Overdue,
    Void,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvoiceLineItem {
    pub description: String,
    pub resource_type: ResourceType,
    pub quantity: f64,
    pub unit_price: f64,
    pub total: f64,
    pub currency: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageAggregation {
    pub tenant_id: String,
    pub resource_type: ResourceType,
    pub total_quantity: f64,
    pub period_start: DateTime<Utc>,
    pub period_end: DateTime<Utc>,
}

type UsageEventHandler = Arc<dyn Fn(UsageRecord) -> Result<()> + Send + Sync>;
type InvoiceEventHandler = Arc<dyn Fn(Invoice) -> Result<()> + Send + Sync>;

pub struct BillingManager {
    usage_records: DashMap<String, Vec<UsageRecord>>,
    billing_plans: DashMap<String, BillingPlan>,
    invoices: DashMap<String, Invoice>,
    usage_event_handlers: RwLock<Vec<UsageEventHandler>>,
    invoice_event_handlers: RwLock<Vec<InvoiceEventHandler>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl BillingManager {
    pub fn new() -> Self {
        Self {
            usage_records: DashMap::new(),
            billing_plans: DashMap::new(),
            invoices: DashMap::new(),
            usage_event_handlers: RwLock::new(Vec::new()),
            invoice_event_handlers: RwLock::new(Vec::new()),
            shutdown_tx: None,
        }
    }

    pub fn register_usage_handler<F>(&self, handler: F)
    where
        F: Fn(UsageRecord) -> Result<()> + Send + Sync + 'static,
    {
        self.usage_event_handlers.write().push(Arc::new(handler));
    }

    pub fn register_invoice_handler<F>(&self, handler: F)
    where
        F: Fn(Invoice) -> Result<()> + Send + Sync + 'static,
    {
        self.invoice_event_handlers.write().push(Arc::new(handler));
    }

    fn notify_usage_handlers(&self, record: UsageRecord) {
        let handlers = self.usage_event_handlers.read();
        for handler in handlers.iter() {
            let record = record.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(record) {
                    error!(error = %e, "Usage event handler failed");
                }
            });
        }
    }

    fn notify_invoice_handlers(&self, invoice: Invoice) {
        let handlers = self.invoice_event_handlers.read();
        for handler in handlers.iter() {
            let invoice = invoice.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(invoice) {
                    error!(error = %e, "Invoice event handler failed");
                }
            });
        }
    }

    pub fn record_usage(&self, tenant_id: &str, resource_type: ResourceType, quantity: f64, unit: &str) -> UsageRecord {
        let record = UsageRecord {
            record_id: format!("usage_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            resource_type,
            quantity,
            unit: unit.to_string(),
            timestamp: Utc::now(),
            tags: HashMap::new(),
        };
        
        let mut tenant_records = self.usage_records
            .entry(tenant_id.to_string())
            .or_insert_with(Vec::new);
        
        tenant_records.push(record.clone());
        
        if tenant_records.len() > 10000 {
            tenant_records = tenant_records.split_off(tenant_records.len() - 5000).into();
        }
        
        drop(tenant_records);
        
        self.notify_usage_handlers(record.clone());
        
        debug!(
            "Recorded usage for tenant {}: {:?} {} {}",
            tenant_id, resource_type, quantity, unit
        );
        
        record
    }

    pub fn record_api_request(&self, tenant_id: &str) -> UsageRecord {
        self.record_usage(tenant_id, ResourceType::ApiRequests, 1.0, "requests")
    }

    pub fn record_storage(&self, tenant_id: &str, gb: f64) -> UsageRecord {
        self.record_usage(tenant_id, ResourceType::StorageGb, gb, "GB")
    }

    pub fn record_cpu_usage(&self, tenant_id: &str, hours: f64) -> UsageRecord {
        self.record_usage(tenant_id, ResourceType::CpuHours, hours, "hours")
    }

    pub fn get_usage(&self, tenant_id: &str, start: DateTime<Utc>, end: DateTime<Utc>) -> Vec<UsageRecord> {
        self.usage_records.get(tenant_id)
            .map(|records| {
                records.iter()
                    .filter(|r| r.timestamp >= start && r.timestamp <= end)
                    .cloned()
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn aggregate_usage(
        &self,
        tenant_id: &str,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> HashMap<ResourceType, f64> {
        let mut aggregates = HashMap::new();
        
        if let Some(records) = self.usage_records.get(tenant_id) {
            for record in records.iter() {
                if record.timestamp >= start && record.timestamp <= end {
                    *aggregates.entry(record.resource_type.clone()).or_insert(0.0) += record.quantity;
                }
            }
        }
        
        aggregates
    }

    pub fn add_billing_plan(&self, plan: BillingPlan) {
        self.billing_plans.insert(plan.plan_id.clone(), plan);
    }

    pub fn get_billing_plan(&self, plan_id: &str) -> Option<BillingPlan> {
        self.billing_plans.get(plan_id).map(|p| p.clone())
    }

    pub fn list_billing_plans(&self) -> Vec<BillingPlan> {
        self.billing_plans.iter().map(|p| p.clone()).collect()
    }

    pub fn generate_invoice(
        &self,
        tenant_id: &str,
        plan: &BillingPlan,
        period_start: DateTime<Utc>,
        period_end: DateTime<Utc>,
    ) -> Result<Invoice> {
        let usage = self.aggregate_usage(tenant_id, period_start, period_end);
        let mut line_items = Vec::new();
        let mut subtotal = plan.base_price;
        
        if plan.base_price > 0.0 {
            line_items.push(InvoiceLineItem {
                description: format!("Base plan: {}", plan.name),
                resource_type: ResourceType::Custom("base_plan".to_string()),
                quantity: 1.0,
                unit_price: plan.base_price,
                total: plan.base_price,
                currency: plan.currency.clone(),
            });
        }
        
        for tier in &plan.pricing_tiers {
            if let Some(quantity) = usage.get(&tier.resource_type) {
                let billable_quantity = if let Some(min_q) = tier.min_quantity {
                    quantity.max(min_q)
                } else {
                    *quantity
                };
                
                let billable_quantity = if let Some(max_q) = tier.max_quantity {
                    billable_quantity.min(max_q)
                } else {
                    billable_quantity
                };
                
                let discount = tier.discount_percent.unwrap_or(0.0);
                let unit_price = tier.unit_price * (1.0 - discount / 100.0);
                let total = billable_quantity * unit_price;
                
                if total > 0.0 {
                    line_items.push(InvoiceLineItem {
                        description: format!("{:?} usage", tier.resource_type),
                        resource_type: tier.resource_type.clone(),
                        quantity: billable_quantity,
                        unit_price,
                        total,
                        currency: plan.currency.clone(),
                    });
                    subtotal += total;
                }
            }
        }
        
        let tax = subtotal * 0.10;
        let total = subtotal + tax;
        let due_date = period_end + ChronoDuration::days(30);
        
        let invoice = Invoice {
            invoice_id: format!("inv_{}", Uuid::new_v4().simple()),
            tenant_id: tenant_id.to_string(),
            billing_period_start: period_start,
            billing_period_end: period_end,
            line_items,
            subtotal,
            tax,
            total,
            currency: plan.currency.clone(),
            status: InvoiceStatus::Draft,
            due_date,
            created_at: Utc::now(),
            paid_at: None,
        };
        
        self.invoices.insert(invoice.invoice_id.clone(), invoice.clone());
        
        info!(
            "Generated invoice {} for tenant {}: ${:.2}",
            invoice.invoice_id, tenant_id, total
        );
        
        Ok(invoice)
    }

    pub fn issue_invoice(&self, invoice_id: &str) -> Result<Invoice> {
        let mut invoice = self.invoices.get_mut(invoice_id)
            .ok_or_else(|| anyhow!("Invoice not found: {}", invoice_id))?;
        
        if invoice.status != InvoiceStatus::Draft {
            return Err(anyhow!("Invoice is not in draft state"));
        }
        
        invoice.status = InvoiceStatus::Issued;
        let updated = invoice.clone();
        drop(invoice);
        
        self.notify_invoice_handlers(updated.clone());
        
        info!("Issued invoice: {}", invoice_id);
        Ok(updated)
    }

    pub void_invoice(&self, invoice_id: &str) -> Result<Invoice> {
        let mut invoice = self.invoices.get_mut(invoice_id)
            .ok_or_else(|| anyhow!("Invoice not found: {}", invoice_id))?;
        
        if invoice.status == InvoiceStatus::Paid {
            return Err(anyhow!("Cannot void a paid invoice"));
        }
        
        invoice.status = InvoiceStatus::Void;
        let updated = invoice.clone();
        drop(invoice);
        
        info!("Voided invoice: {}", invoice_id);
        Ok(updated)
    }

    pub fn mark_invoice_paid(&self, invoice_id: &str) -> Result<Invoice> {
        let mut invoice = self.invoices.get_mut(invoice_id)
            .ok_or_else(|| anyhow!("Invoice not found: {}", invoice_id))?;
        
        invoice.status = InvoiceStatus::Paid;
        invoice.paid_at = Some(Utc::now());
        let updated = invoice.clone();
        drop(invoice);
        
        self.notify_invoice_handlers(updated.clone());
        
        info!("Marked invoice {} as paid", invoice_id);
        Ok(updated)
    }

    pub fn get_invoice(&self, invoice_id: &str) -> Option<Invoice> {
        self.invoices.get(invoice_id).map(|i| i.clone())
    }

    pub fn get_tenant_invoices(&self, tenant_id: &str) -> Vec<Invoice> {
        self.invoices
            .iter()
            .filter(|i| i.tenant_id == tenant_id)
            .map(|i| i.clone())
            .collect()
    }

    pub fn get_current_billing_period(&self, cycle: BillingCycle) -> (DateTime<Utc>, DateTime<Utc>) {
        let now = Utc::now();
        match cycle {
            BillingCycle::Monthly => {
                let start = now.with_day(1).unwrap().with_hour(0).unwrap()
                    .with_minute(0).unwrap().with_second(0).unwrap();
                let next_month = if now.month() == 12 {
                    chrono::NaiveDate::from_ymd_opt(now.year() + 1, 1, 1).unwrap()
                } else {
                    chrono::NaiveDate::from_ymd_opt(now.year(), now.month() + 1, 1).unwrap()
                };
                let end = DateTime::<Utc>::from_naive_utc_and_offset(
                    next_month.and_hms_opt(0, 0, 0).unwrap(),
                    Utc
                ) - ChronoDuration::seconds(1);
                (start, end)
            }
            BillingCycle::Quarterly => {
                let quarter = (now.month() - 1) / 3 * 3 + 1;
                let start = chrono::NaiveDate::from_ymd_opt(now.year(), quarter, 1).unwrap()
                    .and_hms_opt(0, 0, 0).unwrap();
                let end_quarter = quarter + 3;
                let (end_year, end_month) = if end_quarter > 12 {
                    (now.year() + 1, end_quarter - 12)
                } else {
                    (now.year(), end_quarter)
                };
                let end = chrono::NaiveDate::from_ymd_opt(end_year, end_month, 1).unwrap()
                    .and_hms_opt(0, 0, 0).unwrap();
                (
                    DateTime::<Utc>::from_naive_utc_and_offset(start, Utc),
                    DateTime::<Utc>::from_naive_utc_and_offset(end, Utc) - ChronoDuration::seconds(1)
                )
            }
            BillingCycle::Annual => {
                let start = chrono::NaiveDate::from_ymd_opt(now.year(), 1, 1).unwrap()
                    .and_hms_opt(0, 0, 0).unwrap();
                let end = chrono::NaiveDate::from_ymd_opt(now.year() + 1, 1, 1).unwrap()
                    .and_hms_opt(0, 0, 0).unwrap();
                (
                    DateTime::<Utc>::from_naive_utc_and_offset(start, Utc),
                    DateTime::<Utc>::from_naive_utc_and_offset(end, Utc) - ChronoDuration::seconds(1)
                )
            }
        }
    }

    pub async fn start_auto_invoicing(&mut self) -> Result<()> {
        let (tx, mut rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(tx);
        
        let billing_plans = self.billing_plans.clone();
        let usage_records = self.usage_records.clone();
        let invoices = self.invoices.clone();
        
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(3600));
            
            loop {
                tokio::select! {
                    _ = ticker.tick() => {
                        debug!("Checking for invoices to generate");
                    }
                    _ = rx.recv() => {
                        info!("Auto invoicing shutting down");
                        break;
                    }
                }
            }
            
            let _ = (billing_plans, usage_records, invoices);
        });
        
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
    }

    pub fn estimate_cost(
        &self,
        plan: &BillingPlan,
        estimated_usage: &HashMap<ResourceType, f64>,
    ) -> f64 {
        let mut total = plan.base_price;
        
        for tier in &plan.pricing_tiers {
            if let Some(quantity) = estimated_usage.get(&tier.resource_type) {
                let discount = tier.discount_percent.unwrap_or(0.0);
                let unit_price = tier.unit_price * (1.0 - discount / 100.0);
                total += quantity * unit_price;
            }
        }
        
        total * 1.10
    }
}

impl Default for BillingManager {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for BillingManager {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn create_default_billing_plan(tier: &str) -> BillingPlan {
    let (base_price, api_price, storage_price, cpu_price) = match tier {
        "Free" => (0.0, 0.0, 0.0, 0.0),
        "Standard" => (99.0, 0.001, 0.10, 0.50),
        "Premium" => (499.0, 0.0005, 0.05, 0.25),
        "Enterprise" => (4999.0, 0.0001, 0.01, 0.10),
        _ => (0.0, 0.0, 0.0, 0.0),
    };
    
    BillingPlan {
        plan_id: format!("plan_{}", tier.to_lowercase()),
        name: format!("{} Plan", tier),
        description: format!("{} tier billing plan", tier),
        tenant_tier: tier.to_string(),
        base_price,
        pricing_tiers: vec![
            PricingTier {
                tier_name: "API Requests".to_string(),
                resource_type: ResourceType::ApiRequests,
                unit_price: api_price,
                currency: "USD".to_string(),
                min_quantity: None,
                max_quantity: None,
                discount_percent: None,
            },
            PricingTier {
                tier_name: "Storage".to_string(),
                resource_type: ResourceType::StorageGb,
                unit_price: storage_price,
                currency: "USD".to_string(),
                min_quantity: None,
                max_quantity: None,
                discount_percent: None,
            },
            PricingTier {
                tier_name: "CPU".to_string(),
                resource_type: ResourceType::CpuHours,
                unit_price: cpu_price,
                currency: "USD".to_string(),
                min_quantity: None,
                max_quantity: None,
                discount_percent: None,
            },
        ],
        billing_cycle: BillingCycle::Monthly,
        currency: "USD".to_string(),
        features: vec![
            "API Access".to_string(),
            "Email Support".to_string(),
        ],
    }
}
