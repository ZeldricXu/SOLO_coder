package billing

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

type ResourceType string

const (
	ResourceCPU       ResourceType = "cpu"
	ResourceMemory    ResourceType = "memory"
	ResourceStorage   ResourceType = "storage"
	ResourceAPI       ResourceType = "api_calls"
	ResourceBandwidth ResourceType = "bandwidth"
)

const (
	DefaultMaxRecordsPerTenant = 100000
	DefaultRecordTTL           = 90 * 24 * time.Hour
)

type UsageCollectorConfig struct {
	MaxRecordsPerTenant int
	RecordTTL           time.Duration
}

type UsageRecord struct {
	TenantID  string            `json:"tenant_id"`
	Resource  ResourceType      `json:"resource"`
	Quantity  float64           `json:"quantity"`
	Unit      string            `json:"unit"`
	Timestamp time.Time         `json:"timestamp"`
	Metadata  map[string]string `json:"metadata,omitempty"`
}

type PricingRule struct {
	Resource    ResourceType `json:"resource"`
	UnitPrice   float64      `json:"unit_price"`
	Currency    string       `json:"currency"`
	TierPricing []TierPrice  `json:"tier_pricing,omitempty"`
}

type TierPrice struct {
	MinQuantity float64 `json:"min_quantity"`
	MaxQuantity float64 `json:"max_quantity"`
	UnitPrice   float64 `json:"unit_price"`
}

type BillItem struct {
	Resource  ResourceType `json:"resource"`
	Quantity  float64      `json:"quantity"`
	Unit      string       `json:"unit"`
	UnitPrice float64      `json:"unit_price"`
	TotalCost float64      `json:"total_cost"`
	Currency  string       `json:"currency"`
}

type Bill struct {
	TenantID    string     `json:"tenant_id"`
	Period      string     `json:"period"`
	Items       []BillItem `json:"items"`
	TotalCost   float64    `json:"total_cost"`
	Currency    string     `json:"currency"`
	GeneratedAt time.Time  `json:"generated_at"`
	Status      string     `json:"status"`
}

type UsageCollector struct {
	mu     sync.RWMutex
	records map[string][]UsageRecord
	config  UsageCollectorConfig
}

func NewUsageCollector() *UsageCollector {
	return NewUsageCollectorWithConfig(UsageCollectorConfig{
		MaxRecordsPerTenant: DefaultMaxRecordsPerTenant,
		RecordTTL:           DefaultRecordTTL,
	})
}

func NewUsageCollectorWithConfig(config UsageCollectorConfig) *UsageCollector {
	if config.MaxRecordsPerTenant <= 0 {
		config.MaxRecordsPerTenant = DefaultMaxRecordsPerTenant
	}
	if config.RecordTTL <= 0 {
		config.RecordTTL = DefaultRecordTTL
	}
	return &UsageCollector{
		records: make(map[string][]UsageRecord),
		config:  config,
	}
}

func (uc *UsageCollector) RecordUsage(record UsageRecord) {
	uc.mu.Lock()
	defer uc.mu.Unlock()

	uc.records[record.TenantID] = append(uc.records[record.TenantID], record)

	if len(uc.records[record.TenantID]) > uc.config.MaxRecordsPerTenant {
		cutoff := time.Now().Add(-uc.config.RecordTTL)
		uc.records[record.TenantID] = uc.evictOldRecords(uc.records[record.TenantID], cutoff)
	}
}

func (uc *UsageCollector) evictOldRecords(records []UsageRecord, cutoff time.Time) []UsageRecord {
	firstValid := 0
	for i, r := range records {
		if !r.Timestamp.Before(cutoff) {
			firstValid = i
			break
		}
		if i == len(records)-1 {
			return records[len(records):]
		}
	}
	evictedCount := firstValid
	if evictedCount > 0 {
		newLen := len(records) - evictedCount
		if newLen > uc.config.MaxRecordsPerTenant {
			evictFromFront := newLen - uc.config.MaxRecordsPerTenant
			records = records[evictFromFront:]
		} else {
			records = records[firstValid:]
		}
	}
	return records
}

func (uc *UsageCollector) PurgeBefore(tenantID string, cutoff time.Time) int {
	uc.mu.Lock()
	defer uc.mu.Unlock()

	records, ok := uc.records[tenantID]
	if !ok {
		return 0
	}
	originalLen := len(records)
	uc.records[tenantID] = uc.evictOldRecords(records, cutoff)
	return originalLen - len(uc.records[tenantID])
}

func (uc *UsageCollector) PurgeAllBefore(cutoff time.Time) int {
	uc.mu.Lock()
	defer uc.mu.Unlock()

	totalEvicted := 0
	for tenantID, records := range uc.records {
		originalLen := len(records)
		uc.records[tenantID] = uc.evictOldRecords(records, cutoff)
		totalEvicted += originalLen - len(uc.records[tenantID])
	}
	return totalEvicted
}

func (uc *UsageCollector) GetUsage(tenantID string, start, end time.Time) []UsageRecord {
	uc.mu.RLock()
	defer uc.mu.RUnlock()

	var filtered []UsageRecord
	for _, r := range uc.records[tenantID] {
		if (r.Timestamp.Equal(start) || r.Timestamp.After(start)) && (r.Timestamp.Equal(end) || r.Timestamp.Before(end)) {
			filtered = append(filtered, r)
		}
	}
	return filtered
}

func (uc *UsageCollector) AggregateUsage(tenantID string, start, end time.Time) map[ResourceType]float64 {
	usage := make(map[ResourceType]float64)
	for _, r := range uc.GetUsage(tenantID, start, end) {
		usage[r.Resource] += r.Quantity
	}
	return usage
}

func (uc *UsageCollector) RecordCount(tenantID string) int {
	uc.mu.RLock()
	defer uc.mu.RUnlock()
	return len(uc.records[tenantID])
}

type BillingEngine struct {
	pricing map[ResourceType]PricingRule
}

func NewBillingEngine() *BillingEngine {
	return &BillingEngine{
		pricing: make(map[ResourceType]PricingRule),
	}
}

func (be *BillingEngine) SetPricing(rule PricingRule) {
	be.pricing[rule.Resource] = rule
}

func (be *BillingEngine) CalculateCost(resource ResourceType, quantity float64) (float64, error) {
	rule, ok := be.pricing[resource]
	if !ok {
		return 0, fmt.Errorf("no pricing rule for resource %s", resource)
	}
	if quantity == 0 {
		return 0, nil
	}
	if len(rule.TierPricing) > 0 {
		return be.calculateTieredCost(rule, quantity), nil
	}
	return rule.UnitPrice * quantity, nil
}

func (be *BillingEngine) calculateTieredCost(rule PricingRule, quantity float64) float64 {
	var total float64
	remaining := quantity
	for _, tier := range rule.TierPricing {
		if remaining <= 0 {
			break
		}
		if tier.MaxQuantity <= 0 {
			total += remaining * tier.UnitPrice
			remaining = 0
			break
		}
		tierRange := tier.MaxQuantity - tier.MinQuantity
		if remaining <= tierRange {
			total += remaining * tier.UnitPrice
			remaining = 0
		} else {
			total += tierRange * tier.UnitPrice
			remaining -= tierRange
		}
	}
	return total
}

func (be *BillingEngine) GenerateBill(tenantID string, usage map[ResourceType]float64, period string) *Bill {
	bill := &Bill{
		TenantID:    tenantID,
		Period:      period,
		Items:       []BillItem{},
		GeneratedAt: time.Now(),
		Status:      "pending",
	}
	for resource, quantity := range usage {
		rule, ok := be.pricing[resource]
		if !ok {
			continue
		}
		cost, err := be.CalculateCost(resource, quantity)
		if err != nil {
			continue
		}
		item := BillItem{
			Resource:  resource,
			Quantity:  quantity,
			Unit:      "units",
			UnitPrice: rule.UnitPrice,
			TotalCost: cost,
			Currency:  rule.Currency,
		}
		if len(rule.TierPricing) > 0 && quantity > 0 {
			item.UnitPrice = cost / quantity
		}
		bill.Items = append(bill.Items, item)
		bill.TotalCost += cost
	}
	if len(bill.Items) > 0 {
		bill.Currency = bill.Items[0].Currency
	}
	return bill
}

func (b *Bill) Format() string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Bill for Tenant: %s | Period: %s\n", b.TenantID, b.Period))
	sb.WriteString(fmt.Sprintf("Generated: %s | Status: %s\n", b.GeneratedAt.Format("2006-01-02 15:04:05"), b.Status))
	sb.WriteString("-----------------------------------------\n")
	for _, item := range b.Items {
		sb.WriteString(fmt.Sprintf("  %s: %.2f %s x %.4f = %.2f %s\n",
			item.Resource, item.Quantity, item.Unit, item.UnitPrice, item.TotalCost, item.Currency))
	}
	sb.WriteString("-----------------------------------------\n")
	sb.WriteString(fmt.Sprintf("Total: %.2f %s\n", b.TotalCost, b.Currency))
	return sb.String()
}
