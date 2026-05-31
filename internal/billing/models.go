package billing

import "time"

type UsageRecord struct {
	ID           string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID     string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	ResourceType string                 `json:"resource_type" gorm:"type:varchar(32);index"`
	Quantity     float64                `json:"quantity"`
	Unit         string                 `json:"unit" gorm:"type:varchar(16)"`
	Timestamp    time.Time              `json:"timestamp" gorm:"index"`
	Attributes   map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt    time.Time              `json:"created_at"`
}

type BillingPlan struct {
	ID           string        `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name         string        `json:"name" gorm:"type:varchar(64);uniqueIndex"`
	Description  string        `json:"description" gorm:"type:text"`
	PricingRules []PricingRule `json:"pricing_rules" gorm:"type:jsonb;serializer:json"`
	BasePrice    float64       `json:"base_price"`
	Currency     string        `json:"currency" gorm:"type:varchar(8);default:CNY"`
	Status       string        `json:"status" gorm:"type:varchar(32);index"`
	IsDefault    bool          `json:"is_default"`
	CreatedAt    time.Time     `json:"created_at"`
	UpdatedAt    time.Time     `json:"updated_at"`
}

type PricingRule struct {
	ResourceType string  `json:"resource_type"`
	UnitPrice    float64 `json:"unit_price"`
	Unit         string  `json:"unit"`
	FreeTier     float64 `json:"free_tier"`
	DiscountTier float64 `json:"discount_tier"`
	DiscountRate float64 `json:"discount_rate"`
}

type Invoice struct {
	ID            string        `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID      string        `json:"tenant_id" gorm:"type:varchar(64);index"`
	InvoiceNumber string        `json:"invoice_number" gorm:"type:varchar(64);uniqueIndex"`
	PlanID        string        `json:"plan_id" gorm:"type:varchar(64)"`
	PlanName      string        `json:"plan_name"`
	BasePrice     float64       `json:"base_price"`
	UsageAmount   float64       `json:"usage_amount"`
	Discount      float64       `json:"discount"`
	TotalAmount   float64       `json:"total_amount"`
	Currency      string        `json:"currency" gorm:"type:varchar(8)"`
	Status        string        `json:"status" gorm:"type:varchar(32);index"`
	PeriodStart   time.Time     `json:"period_start"`
	PeriodEnd     time.Time     `json:"period_end"`
	DueDate       time.Time     `json:"due_date"`
	PaidAt        *time.Time    `json:"paid_at"`
	Items         []InvoiceItem `json:"items" gorm:"type:jsonb;serializer:json"`
	CreatedAt     time.Time     `json:"created_at"`
	UpdatedAt     time.Time     `json:"updated_at"`
}

type InvoiceItem struct {
	ResourceType string  `json:"resource_type"`
	Description  string  `json:"description"`
	Quantity     float64 `json:"quantity"`
	UnitPrice    float64 `json:"unit_price"`
	Unit         string  `json:"unit"`
	Amount       float64 `json:"amount"`
}

func (u *UsageRecord) TableName() string {
	return "usage_records"
}

func (b *BillingPlan) TableName() string {
	return "billing_plans"
}

func (i *Invoice) TableName() string {
	return "invoices"
}
