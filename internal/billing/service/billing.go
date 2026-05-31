package service

import (
	"time"

	"session187/internal/billing"
	billingConfig "session187/internal/billing/config"
	"session187/internal/billing/repository"
)

type BillingService interface {
	CreateBillingPlan(plan *billing.BillingPlan) (*billing.BillingPlan, error)
	GetDefaultPlan() (*billing.BillingPlan, error)
	GenerateInvoice(tenantID string, periodStart, periodEnd time.Time, scenario string) (*billing.Invoice, error)
	GetInvoice(tenantID, invoiceID string) (*billing.Invoice, error)
	ListInvoices(tenantID string, page, pageSize int) ([]billing.Invoice, int64, error)
	MarkInvoicePaid(invoiceID string) error
	GetConfigManager() billingConfig.DynamicConfigManager
}

type billingServiceImpl struct {
	planRepo      repository.PlanRepository
	invoiceRepo   repository.InvoiceRepository
	usageRepo     repository.UsageRepository
	configManager billingConfig.DynamicConfigManager
}

func NewBillingService(
	planRepo repository.PlanRepository,
	invoiceRepo repository.InvoiceRepository,
	usageRepo repository.UsageRepository,
	configManager billingConfig.DynamicConfigManager,
) BillingService {
	return &billingServiceImpl{
		planRepo:      planRepo,
		invoiceRepo:   invoiceRepo,
		usageRepo:     usageRepo,
		configManager: configManager,
	}
}

func (s *billingServiceImpl) GetConfigManager() billingConfig.DynamicConfigManager {
	return s.configManager
}

func (s *billingServiceImpl) CreateBillingPlan(plan *billing.BillingPlan) (*billing.BillingPlan, error) {
	return s.planRepo.Create(plan)
}

func (s *billingServiceImpl) GetDefaultPlan() (*billing.BillingPlan, error) {
	return s.planRepo.GetDefault()
}

func (s *billingServiceImpl) GenerateInvoice(tenantID string, periodStart, periodEnd time.Time, scenario string) (*billing.Invoice, error) {
	usage, err := s.usageRepo.GetSummary(tenantID, periodStart, periodEnd)
	if err != nil {
		return nil, err
	}
	cfg, err := s.configManager.GetConfig(scenario, tenantID)
	if err != nil {
		return nil, err
	}
	plan, err := s.planRepo.GetDefault()
	if err != nil {
		return nil, err
	}
	items := s.calculateInvoiceItems(cfg, usage)
	usageAmount := s.calculateUsageAmount(items)
	discount := s.calculateDiscount(cfg, usage, usageAmount)
	totalAmount := cfg.BasePrice + usageAmount - discount
	dueDate := periodEnd.AddDate(0, 0, cfg.InvoiceSettings.DueDays)
	invoice := &billing.Invoice{
		TenantID:      tenantID,
		PlanID:        plan.ID,
		PlanName:      plan.Name,
		BasePrice:     cfg.BasePrice,
		UsageAmount:   usageAmount,
		Discount:      discount,
		TotalAmount:   totalAmount,
		Currency:      cfg.Currency,
		Status:        "draft",
		PeriodStart:   periodStart,
		PeriodEnd:     periodEnd,
		DueDate:       dueDate,
		Items:         items,
	}
	return s.invoiceRepo.Create(invoice)
}

func (s *billingServiceImpl) calculateInvoiceItems(cfg *billingConfig.BillingConfig, usage map[string]float64) []billing.InvoiceItem {
	var items []billing.InvoiceItem
	for _, rule := range cfg.PricingRules {
		qty := usage[rule.ResourceType]
		if qty <= 0 {
			continue
		}
		billableQty := qty - rule.FreeTier
		if billableQty <= 0 {
			billableQty = 0
		}
		amount := billableQty * rule.UnitPrice
		if billableQty > rule.DiscountTier {
			discountedQty := billableQty - rule.DiscountTier
			amount = rule.DiscountTier*rule.UnitPrice + discountedQty*rule.UnitPrice*(1-rule.DiscountRate)
		}
		if amount > 0 {
			items = append(items, billing.InvoiceItem{
				ResourceType: rule.ResourceType,
				Description:  rule.ResourceType + " usage",
				Quantity:     qty,
				UnitPrice:    rule.UnitPrice,
				Unit:         rule.Unit,
				Amount:       amount,
			})
		}
	}
	return items
}

func (s *billingServiceImpl) calculateDiscount(cfg *billingConfig.BillingConfig, usage map[string]float64, usageAmount float64) float64 {
	var totalDiscount float64
	for _, policy := range cfg.DiscountPolicies {
		if totalDiscount >= policy.MaxDiscount && policy.MaxDiscount > 0 {
			continue
		}
		var discountAmount float64
		switch policy.Type {
		case "volume":
			totalUsage := s.calculateTotalUsage(usage)
			if totalUsage > 1000 {
				discountAmount = usageAmount * policy.DiscountRate
			}
		case "loyalty":
			discountAmount = usageAmount * policy.DiscountRate
		}
		if policy.MaxDiscount > 0 && discountAmount > policy.MaxDiscount {
			discountAmount = policy.MaxDiscount
		}
		totalDiscount += discountAmount
	}
	return totalDiscount
}

func (s *billingServiceImpl) calculateTotalUsage(usage map[string]float64) float64 {
	var total float64
	for _, v := range usage {
		total += v
	}
	return total
}

func (s *billingServiceImpl) calculateUsageAmount(items []billing.InvoiceItem) float64 {
	var total float64
	for _, item := range items {
		total += item.Amount
	}
	return total
}

func (s *billingServiceImpl) GetInvoice(tenantID, invoiceID string) (*billing.Invoice, error) {
	return s.invoiceRepo.Get(tenantID, invoiceID)
}

func (s *billingServiceImpl) ListInvoices(tenantID string, page, pageSize int) ([]billing.Invoice, int64, error) {
	return s.invoiceRepo.List(tenantID, page, pageSize)
}

func (s *billingServiceImpl) MarkInvoicePaid(invoiceID string) error {
	return s.invoiceRepo.MarkPaid(invoiceID)
}
