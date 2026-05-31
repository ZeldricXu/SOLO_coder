package tenant

import (
	"time"

	"gorm.io/gorm"
	"session187/internal/common"
	"session187/pkg/errors"
)

type Tenant struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name" gorm:"type:varchar(128);index"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	Plan        string                 `json:"plan" gorm:"type:varchar(32)"`
	Config      map[string]interface{} `json:"config" gorm:"type:jsonb"`
	Quota       *ResourceQuota         `json:"quota" gorm:"embedded;embeddedPrefix:quota_"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	ActivatedAt *time.Time             `json:"activated_at"`
	ExpiredAt   *time.Time             `json:"expired_at"`
}

type ResourceQuota struct {
	MaxStorageGB    int64 `json:"max_storage_gb"`
	MaxRequestsPerMin int `json:"max_requests_per_min"`
	MaxUsers        int   `json:"max_users"`
	MaxTasks        int   `json:"max_tasks"`
	MaxAPIKeys      int   `json:"max_api_keys"`
}

type Usage struct {
	ID           string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID     string    `json:"tenant_id" gorm:"type:varchar(64);index"`
	StorageUsed  int64     `json:"storage_used"`
	RequestCount int64     `json:"request_count"`
	UserCount    int       `json:"user_count"`
	TaskCount    int       `json:"task_count"`
	APIKeyCount  int       `json:"api_key_count"`
	PeriodStart  time.Time `json:"period_start" gorm:"index"`
	PeriodEnd    time.Time `json:"period_end" gorm:"index"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type Manager struct {
	db *gorm.DB
}

func NewManager(db *gorm.DB) *Manager {
	return &Manager{db: db}
}

func (m *Manager) CreateTenant(name, plan string, config map[string]interface{}) (*Tenant, error) {
	if config == nil {
		config = make(map[string]interface{})
	}
	tenant := &Tenant{
		ID:        common.GenerateID("tnt"),
		Name:      name,
		Status:    "active",
		Plan:      plan,
		Config:    config,
		Quota:     getDefaultQuota(plan),
		CreatedAt: common.TimeNowUTC(),
		UpdatedAt: common.TimeNowUTC(),
	}
	if err := m.db.Create(tenant).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建租户失败", err.Error())
	}
	if err := m.initUsage(tenant.ID); err != nil {
		return nil, err
	}
	return tenant, nil
}

func (m *Manager) GetTenant(tenantID string) (*Tenant, error) {
	var tenant Tenant
	if err := m.db.Where("id = ?", tenantID).First(&tenant).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrTenantNotFound
		}
		return nil, errors.NewWithDetail(500, "查询租户失败", err.Error())
	}
	return &tenant, nil
}

func (m *Manager) UpdateTenant(tenantID string, updates map[string]interface{}) (*Tenant, error) {
	tenant, err := m.GetTenant(tenantID)
	if err != nil {
		return nil, err
	}
	updates["updated_at"] = common.TimeNowUTC()
	if err := m.db.Model(tenant).Updates(updates).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新租户失败", err.Error())
	}
	return m.GetTenant(tenantID)
}

func (m *Manager) DeleteTenant(tenantID string) error {
	tenant, err := m.GetTenant(tenantID)
	if err != nil {
		return err
	}
	tenant.Status = "deleted"
	tenant.UpdatedAt = common.TimeNowUTC()
	return m.db.Save(tenant).Error
}

func (m *Manager) CheckQuota(tenantID string, resourceType string, amount int64) (bool, error) {
	tenant, err := m.GetTenant(tenantID)
	if err != nil {
		return false, err
	}
	usage, err := m.GetCurrentUsage(tenantID)
	if err != nil {
		return false, err
	}
	switch resourceType {
	case "storage":
		return usage.StorageUsed+amount <= tenant.Quota.MaxStorageGB*1024*1024*1024, nil
	case "requests":
		return usage.RequestCount+amount <= int64(tenant.Quota.MaxRequestsPerMin), nil
	case "users":
		return int64(usage.UserCount)+amount <= int64(tenant.Quota.MaxUsers), nil
	case "tasks":
		return int64(usage.TaskCount)+amount <= int64(tenant.Quota.MaxTasks), nil
	case "api_keys":
		return int64(usage.APIKeyCount)+amount <= int64(tenant.Quota.MaxAPIKeys), nil
	default:
		return false, errors.ErrBadRequest
	}
}

func (m *Manager) GetCurrentUsage(tenantID string) (*Usage, error) {
	now := common.TimeNowUTC()
	startOfMonth := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	var usage Usage
	err := m.db.Where("tenant_id = ? AND period_start <= ? AND period_end >= ?",
		tenantID, now, startOfMonth).Order("created_at desc").First(&usage).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return m.initUsage(tenantID)
		}
		return nil, errors.NewWithDetail(500, "查询用量失败", err.Error())
	}
	return &usage, nil
}

func (m *Manager) IncrementUsage(tenantID string, resourceType string, amount int64) error {
	usage, err := m.GetCurrentUsage(tenantID)
	if err != nil {
		return err
	}
	switch resourceType {
	case "storage":
		usage.StorageUsed += amount
	case "requests":
		usage.RequestCount += amount
	case "users":
		usage.UserCount += int(amount)
	case "tasks":
		usage.TaskCount += int(amount)
	case "api_keys":
		usage.APIKeyCount += int(amount)
	}
	usage.UpdatedAt = common.TimeNowUTC()
	return m.db.Save(usage).Error
}

func (m *Manager) initUsage(tenantID string) (*Usage, error) {
	now := common.TimeNowUTC()
	startOfMonth := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	endOfMonth := startOfMonth.AddDate(0, 1, 0).Add(-time.Second)
	usage := &Usage{
		ID:          common.GenerateID("usg"),
		TenantID:    tenantID,
		StorageUsed: 0,
		RequestCount: 0,
		UserCount:   0,
		TaskCount:   0,
		APIKeyCount: 0,
		PeriodStart: startOfMonth,
		PeriodEnd:   endOfMonth,
		CreatedAt:   now,
		UpdatedAt:   now,
	}
	if err := m.db.Create(usage).Error; err != nil {
		return nil, errors.NewWithDetail(500, "初始化用量失败", err.Error())
	}
	return usage, nil
}

func getDefaultQuota(plan string) *ResourceQuota {
	switch plan {
	case "enterprise":
		return &ResourceQuota{
			MaxStorageGB:       10000,
			MaxRequestsPerMin: 100000,
			MaxUsers:           1000,
			MaxTasks:           10000,
			MaxAPIKeys:         100,
		}
	case "professional":
		return &ResourceQuota{
			MaxStorageGB:       1000,
			MaxRequestsPerMin: 10000,
			MaxUsers:           100,
			MaxTasks:           1000,
			MaxAPIKeys:         20,
		}
	case "free":
		fallthrough
	default:
		return &ResourceQuota{
			MaxStorageGB:       10,
			MaxRequestsPerMin: 1000,
			MaxUsers:           5,
			MaxTasks:           100,
			MaxAPIKeys:         5,
		}
	}
}

func (t *Tenant) TableName() string {
	return "tenants"
}

func (u *Usage) TableName() string {
	return "tenant_usage"
}
