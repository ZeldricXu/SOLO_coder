package repository

import (
	"gorm.io/gorm"
	"session187/internal/billing"
	"session187/internal/common"
	"session187/pkg/errors"
)

type PlanRepository interface {
	Create(plan *billing.BillingPlan) (*billing.BillingPlan, error)
	GetDefault() (*billing.BillingPlan, error)
	GetByID(id string) (*billing.BillingPlan, error)
	List() ([]billing.BillingPlan, error)
}

type GormPlanRepository struct {
	db *gorm.DB
}

func NewPlanRepository(db *gorm.DB) PlanRepository {
	return &GormPlanRepository{db: db}
}

func (r *GormPlanRepository) Create(plan *billing.BillingPlan) (*billing.BillingPlan, error) {
	if plan.ID == "" {
		plan.ID = common.GenerateID("pln")
	}
	if plan.Status == "" {
		plan.Status = "active"
	}
	now := common.TimeNowUTC()
	plan.CreatedAt = now
	plan.UpdatedAt = now
	if plan.IsDefault {
		r.db.Model(&billing.BillingPlan{}).Where("is_default = ?", true).Update("is_default", false)
	}
	if err := r.db.Create(plan).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建计费计划失败", err.Error())
	}
	return plan, nil
}

func (r *GormPlanRepository) GetDefault() (*billing.BillingPlan, error) {
	var plan billing.BillingPlan
	err := r.db.Where("is_default = ? AND status = ?", true, "active").First(&plan).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return r.getDefaultFallbackPlan(), nil
		}
		return nil, errors.NewWithDetail(500, "获取默认计费计划失败", err.Error())
	}
	return &plan, nil
}

func (r *GormPlanRepository) getDefaultFallbackPlan() *billing.BillingPlan {
	return &billing.BillingPlan{
		ID:        "default",
		Name:      "Standard",
		BasePrice: 99.0,
		Currency:  "CNY",
		PricingRules: []billing.PricingRule{
			{ResourceType: "storage", UnitPrice: 0.01, Unit: "GB", FreeTier: 10},
			{ResourceType: "requests", UnitPrice: 0.0001, Unit: "1000_requests", FreeTier: 10000},
			{ResourceType: "bandwidth", UnitPrice: 0.5, Unit: "GB", FreeTier: 100},
		},
	}
}

func (r *GormPlanRepository) GetByID(id string) (*billing.BillingPlan, error) {
	var plan billing.BillingPlan
	err := r.db.Where("id = ?", id).First(&plan).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询计费计划失败", err.Error())
	}
	return &plan, nil
}

func (r *GormPlanRepository) List() ([]billing.BillingPlan, error) {
	var plans []billing.BillingPlan
	err := r.db.Where("status = ?", "active").Find(&plans).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询计费计划列表失败", err.Error())
	}
	return plans, nil
}
