package repository

import (
	"time"

	"gorm.io/gorm"
	"session187/internal/billing"
	"session187/internal/common"
	"session187/pkg/errors"
)

type InvoiceRepository interface {
	Create(invoice *billing.Invoice) (*billing.Invoice, error)
	Get(tenantID, invoiceID string) (*billing.Invoice, error)
	List(tenantID string, page, pageSize int) ([]billing.Invoice, int64, error)
	MarkPaid(invoiceID string) error
}

type GormInvoiceRepository struct {
	db *gorm.DB
}

func NewInvoiceRepository(db *gorm.DB) InvoiceRepository {
	return &GormInvoiceRepository{db: db}
}

func (r *GormInvoiceRepository) Create(invoice *billing.Invoice) (*billing.Invoice, error) {
	if invoice.ID == "" {
		invoice.ID = common.GenerateID("inv")
	}
	if invoice.InvoiceNumber == "" {
		invoice.InvoiceNumber = "INV-" + invoice.PeriodStart.Format("200601") + "-" + common.RandomString(6)
	}
	if invoice.Status == "" {
		invoice.Status = "draft"
	}
	now := common.TimeNowUTC()
	invoice.CreatedAt = now
	invoice.UpdatedAt = now
	if err := r.db.Create(invoice).Error; err != nil {
		return nil, errors.NewWithDetail(500, "生成账单失败", err.Error())
	}
	return invoice, nil
}

func (r *GormInvoiceRepository) Get(tenantID, invoiceID string) (*billing.Invoice, error) {
	var invoice billing.Invoice
	err := r.db.Where("id = ? AND tenant_id = ?", invoiceID, tenantID).First(&invoice).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询账单失败", err.Error())
	}
	return &invoice, nil
}

func (r *GormInvoiceRepository) List(tenantID string, page, pageSize int) ([]billing.Invoice, int64, error) {
	var invoices []billing.Invoice
	var total int64
	offset := (page - 1) * pageSize
	r.db.Model(&billing.Invoice{}).Where("tenant_id = ?", tenantID).Count(&total)
	err := r.db.Where("tenant_id = ?", tenantID).Order("created_at desc").
		Offset(offset).Limit(pageSize).Find(&invoices).Error
	if err != nil {
		return nil, 0, errors.NewWithDetail(500, "查询账单列表失败", err.Error())
	}
	return invoices, total, nil
}

func (r *GormInvoiceRepository) MarkPaid(invoiceID string) error {
	now := common.TimeNowUTC()
	return r.db.Model(&billing.Invoice{}).Where("id = ?", invoiceID).
		Updates(map[string]interface{}{
			"status":     "paid",
			"paid_at":    &now,
			"updated_at": now,
		}).Error
}
