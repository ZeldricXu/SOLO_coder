package tenant

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"session130/internal/logger"
)

type TenantStatus string

const (
	StatusActive    TenantStatus = "active"
	StatusSuspended TenantStatus = "suspended"
	StatusDeleted   TenantStatus = "deleted"
)

type BillingPlan string

const (
	PlanFree       BillingPlan = "free"
	PlanStandard   BillingPlan = "standard"
	PlanPremium    BillingPlan = "premium"
	PlanEnterprise BillingPlan = "enterprise"
)

type Tenant struct {
	TenantID       string                 `json:"tenant_id"`
	Name           string                 `json:"name"`
	Status         TenantStatus           `json:"status"`
	BillingPlan    BillingPlan            `json:"billing_plan"`
	AdminEmail     string                 `json:"admin_email"`
	Config         map[string]interface{} `json:"config"`
	ResourceQuota  ResourceQuota          `json:"resource_quota"`
	CurrentUsage   ResourceUsage          `json:"current_usage"`
	RateLimit      RateLimitConfig        `json:"rate_limit"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type ResourceQuota struct {
	MaxStorageGB     int64 `json:"max_storage_gb"`
	MaxCPUCores      int   `json:"max_cpu_cores"`
	MaxMemoryGB      int   `json:"max_memory_gb"`
	MaxAPIRequests   int64 `json:"max_api_requests"`
	MaxUsers         int   `json:"max_users"`
	MaxConnections   int   `json:"max_connections"`
	MaxBandwidthGB   int64 `json:"max_bandwidth_gb"`
}

type ResourceUsage struct {
	StorageUsedGB    int64   `json:"storage_used_gb"`
	CPUUsedCores     float64 `json:"cpu_used_cores"`
	MemoryUsedGB     float64 `json:"memory_used_gb"`
	APIRequestsCount int64   `json:"api_requests_count"`
	ActiveUsers      int     `json:"active_users"`
	ActiveConnections int    `json:"active_connections"`
	BandwidthUsedGB  int64   `json:"bandwidth_used_gb"`
}

type RateLimitConfig struct {
	RequestsPerMinute   int `json:"requests_per_minute"`
	RequestsPerHour     int `json:"requests_per_hour"`
	RequestsPerDay      int `json:"requests_per_day"`
	BurstSize           int `json:"burst_size"`
}

type RateLimitState struct {
	mu              sync.Mutex
	minuteRequests  int64
	hourRequests    int64
	dayRequests     int64
	minuteStart     time.Time
	hourStart       time.Time
	dayStart        time.Time
}

type Manager struct {
	mu           sync.RWMutex
	tenants      map[string]*Tenant
	rateLimits   map[string]*RateLimitState
	requestCount map[string]int64
}

var (
	instance *Manager
	once     sync.Once
)

func NewManager() *Manager {
	return &Manager{
		tenants:      make(map[string]*Tenant),
		rateLimits:   make(map[string]*RateLimitState),
		requestCount: make(map[string]int64),
	}
}

func GetManager() *Manager {
	once.Do(func() {
		instance = NewManager()
	})
	return instance
}

func generateTenantID(name string) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%d", name, time.Now().UnixNano())))
	return "tnt_" + hex.EncodeToString(h.Sum(nil))[:12]
}

func defaultQuotaForPlan(plan BillingPlan) ResourceQuota {
	switch plan {
	case PlanFree:
		return ResourceQuota{
			MaxStorageGB:     10,
			MaxCPUCores:      1,
			MaxMemoryGB:      2,
			MaxAPIRequests:   10000,
			MaxUsers:         5,
			MaxConnections:   10,
			MaxBandwidthGB:   100,
		}
	case PlanStandard:
		return ResourceQuota{
			MaxStorageGB:     100,
			MaxCPUCores:      4,
			MaxMemoryGB:      8,
			MaxAPIRequests:   100000,
			MaxUsers:         50,
			MaxConnections:   100,
			MaxBandwidthGB:   1000,
		}
	case PlanPremium:
		return ResourceQuota{
			MaxStorageGB:     1000,
			MaxCPUCores:      16,
			MaxMemoryGB:      32,
			MaxAPIRequests:   1000000,
			MaxUsers:         500,
			MaxConnections:   500,
			MaxBandwidthGB:   10000,
		}
	case PlanEnterprise:
		return ResourceQuota{
			MaxStorageGB:     10000,
			MaxCPUCores:      64,
			MaxMemoryGB:      128,
			MaxAPIRequests:   10000000,
			MaxUsers:         10000,
			MaxConnections:   5000,
			MaxBandwidthGB:   100000,
		}
	default:
		return defaultQuotaForPlan(PlanFree)
	}
}

func defaultRateLimitForPlan(plan BillingPlan) RateLimitConfig {
	switch plan {
	case PlanFree:
		return RateLimitConfig{
			RequestsPerMinute: 60,
			RequestsPerHour:   1000,
			RequestsPerDay:    10000,
			BurstSize:         30,
		}
	case PlanStandard:
		return RateLimitConfig{
			RequestsPerMinute: 600,
			RequestsPerHour:   10000,
			RequestsPerDay:    100000,
			BurstSize:         300,
		}
	case PlanPremium:
		return RateLimitConfig{
			RequestsPerMinute: 6000,
			RequestsPerHour:   100000,
			RequestsPerDay:    1000000,
			BurstSize:         3000,
		}
	case PlanEnterprise:
		return RateLimitConfig{
			RequestsPerMinute: 60000,
			RequestsPerHour:   1000000,
			RequestsPerDay:    10000000,
			BurstSize:         30000,
		}
	default:
		return defaultRateLimitForPlan(PlanFree)
	}
}

func (m *Manager) CreateTenant(name, adminEmail string, plan BillingPlan) (*Tenant, error) {
	if name == "" {
		return nil, errors.New("tenant name is required")
	}
	if adminEmail == "" {
		return nil, errors.New("admin email is required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	tenantID := generateTenantID(name)
	now := time.Now()

	tenant := &Tenant{
		TenantID:    tenantID,
		Name:        name,
		Status:      StatusActive,
		BillingPlan: plan,
		AdminEmail:  adminEmail,
		Config:      make(map[string]interface{}),
		ResourceQuota:  defaultQuotaForPlan(plan),
		CurrentUsage:   ResourceUsage{},
		RateLimit:      defaultRateLimitForPlan(plan),
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	m.tenants[tenantID] = tenant
	m.rateLimits[tenantID] = &RateLimitState{}
	m.requestCount[tenantID] = 0

	logger.Info("", "tenant created", map[string]interface{}{
		"tenant_id": tenantID,
		"name":      name,
		"plan":      plan,
	})

	return tenant, nil
}

func (m *Manager) GetTenant(tenantID string) (*Tenant, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	if tenant.Status == StatusDeleted {
		return nil, fmt.Errorf("tenant %s has been deleted", tenantID)
	}
	return tenant, nil
}

func (m *Manager) UpdateTenantConfig(tenantID string, config map[string]interface{}) (*Tenant, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	if tenant.Status != StatusActive {
		return nil, fmt.Errorf("tenant %s is not active", tenantID)
	}

	for k, v := range config {
		tenant.Config[k] = v
	}
	tenant.UpdatedAt = time.Now()

	logger.Info("", "tenant config updated", map[string]interface{}{
		"tenant_id": tenantID,
	})

	return tenant, nil
}

func (m *Manager) UpdateBillingPlan(tenantID string, plan BillingPlan) (*Tenant, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}

	tenant.BillingPlan = plan
	tenant.ResourceQuota = defaultQuotaForPlan(plan)
	tenant.RateLimit = defaultRateLimitForPlan(plan)
	tenant.UpdatedAt = time.Now()

	logger.Info("", "tenant billing plan updated", map[string]interface{}{
		"tenant_id": tenantID,
		"new_plan":  plan,
	})

	return tenant, nil
}

func (m *Manager) SuspendTenant(tenantID string) (*Tenant, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	if tenant.Status == StatusDeleted {
		return nil, fmt.Errorf("tenant %s has been deleted", tenantID)
	}

	tenant.Status = StatusSuspended
	tenant.UpdatedAt = time.Now()

	logger.Info("", "tenant suspended", map[string]interface{}{
		"tenant_id": tenantID,
	})

	return tenant, nil
}

func (m *Manager) ActivateTenant(tenantID string) (*Tenant, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	if tenant.Status == StatusDeleted {
		return nil, fmt.Errorf("tenant %s has been deleted", tenantID)
	}

	tenant.Status = StatusActive
	tenant.UpdatedAt = time.Now()

	logger.Info("", "tenant activated", map[string]interface{}{
		"tenant_id": tenantID,
	})

	return tenant, nil
}

func (m *Manager) DeleteTenant(tenantID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return fmt.Errorf("tenant %s not found", tenantID)
	}

	tenant.Status = StatusDeleted
	tenant.UpdatedAt = time.Now()

	logger.Info("", "tenant deleted", map[string]interface{}{
		"tenant_id": tenantID,
	})

	return nil
}

func (m *Manager) CheckRateLimit(tenantID string) (bool, error) {
	m.mu.RLock()
	tenant, exists := m.tenants[tenantID]
	rl := m.rateLimits[tenantID]
	m.mu.RUnlock()

	if !exists {
		return false, fmt.Errorf("tenant %s not found", tenantID)
	}
	if tenant.Status != StatusActive {
		return false, fmt.Errorf("tenant %s is not active", tenantID)
	}
	if rl == nil {
		rl = &RateLimitState{}
		m.mu.Lock()
		m.rateLimits[tenantID] = rl
		m.mu.Unlock()
	}

	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()

	if now.Sub(rl.minuteStart) > time.Minute {
		rl.minuteStart = now
		rl.minuteRequests = 0
	}
	if now.Sub(rl.hourStart) > time.Hour {
		rl.hourStart = now
		rl.hourRequests = 0
	}
	if now.Sub(rl.dayStart) > 24*time.Hour {
		rl.dayStart = now
		rl.dayRequests = 0
	}

	if rl.minuteRequests >= int64(tenant.RateLimit.RequestsPerMinute) {
		return false, nil
	}
	if rl.hourRequests >= int64(tenant.RateLimit.RequestsPerHour) {
		return false, nil
	}
	if rl.dayRequests >= int64(tenant.RateLimit.RequestsPerDay) {
		return false, nil
	}

	rl.minuteRequests++
	rl.hourRequests++
	rl.dayRequests++

	m.mu.Lock()
	m.requestCount[tenantID]++
	m.mu.Unlock()

	return true, nil
}

func (m *Manager) CheckResourceQuota(tenantID string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return false, fmt.Errorf("tenant %s not found", tenantID)
	}

	if tenant.CurrentUsage.StorageUsedGB > tenant.ResourceQuota.MaxStorageGB {
		return false, nil
	}
	if tenant.CurrentUsage.MemoryUsedGB > float64(tenant.ResourceQuota.MaxMemoryGB) {
		return false, nil
	}
	if tenant.CurrentUsage.APIRequestsCount > tenant.ResourceQuota.MaxAPIRequests {
		return false, nil
	}
	if tenant.CurrentUsage.ActiveUsers > tenant.ResourceQuota.MaxUsers {
		return false, nil
	}
	if tenant.CurrentUsage.ActiveConnections > tenant.ResourceQuota.MaxConnections {
		return false, nil
	}
	if tenant.CurrentUsage.BandwidthUsedGB > tenant.ResourceQuota.MaxBandwidthGB {
		return false, nil
	}

	return true, nil
}

func (m *Manager) RecordUsage(tenantID string, usage ResourceUsage) (*Tenant, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}

	tenant.CurrentUsage = usage
	tenant.UpdatedAt = time.Now()

	return tenant, nil
}

func (m *Manager) GetUsageStats(tenantID string) (map[string]interface{}, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	tenant, exists := m.tenants[tenantID]
	if !exists {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}

	usagePercent := func(used float64, max int64) float64 {
		if max == 0 {
			return 0
		}
		return (used / float64(max)) * 100
	}

	return map[string]interface{}{
		"tenant_id": tenantID,
		"billing_plan": tenant.BillingPlan,
		"storage_percent": usagePercent(float64(tenant.CurrentUsage.StorageUsedGB), tenant.ResourceQuota.MaxStorageGB),
		"memory_percent": usagePercent(tenant.CurrentUsage.MemoryUsedGB, int64(tenant.ResourceQuota.MaxMemoryGB)),
		"api_requests_percent": usagePercent(float64(tenant.CurrentUsage.APIRequestsCount), tenant.ResourceQuota.MaxAPIRequests),
		"users_percent": usagePercent(float64(tenant.CurrentUsage.ActiveUsers), int64(tenant.ResourceQuota.MaxUsers)),
		"connections_percent": usagePercent(float64(tenant.CurrentUsage.ActiveConnections), int64(tenant.ResourceQuota.MaxConnections)),
		"bandwidth_percent": usagePercent(float64(tenant.CurrentUsage.BandwidthUsedGB), tenant.ResourceQuota.MaxBandwidthGB),
		"total_requests": m.requestCount[tenantID],
	}, nil
}

func (m *Manager) ListTenants(status TenantStatus) []*Tenant {
	m.mu.RLock()
	defer m.mu.RUnlock()

	tenants := make([]*Tenant, 0, len(m.tenants))
	for _, tenant := range m.tenants {
		if status == "" || tenant.Status == status {
			tenants = append(tenants, tenant)
		}
	}
	return tenants
}

func (m *Manager) GetTenantByIsolationKey(isolationKey string) (*Tenant, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, tenant := range m.tenants {
		if tenant.TenantID == isolationKey {
			if tenant.Status == StatusDeleted {
				return nil, fmt.Errorf("tenant %s has been deleted", isolationKey)
			}
			return tenant, nil
		}
	}
	return nil, fmt.Errorf("tenant with isolation key %s not found", isolationKey)
}
