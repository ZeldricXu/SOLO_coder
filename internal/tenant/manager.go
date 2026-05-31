package tenant

import (
	"errors"
	"fmt"
	"sync"
)

type IsolationLevel string

const (
	IsolationShared    IsolationLevel = "shared"
	IsolationSchema    IsolationLevel = "schema"
	IsolationDatabase  IsolationLevel = "database"
	IsolationInstance  IsolationLevel = "instance"
)

type ResourceQuota struct {
	MaxCPU        float64 `json:"max_cpu"`
	MaxMemory     float64 `json:"max_memory_mb"`
	MaxStorage    float64 `json:"max_storage_gb"`
	MaxAPICalls   int     `json:"max_api_calls"`
	MaxUsers      int     `json:"max_users"`
	MaxWorkOrders int     `json:"max_work_orders"`
}

type TenantConfig struct {
	Theme          map[string]string `json:"theme"`
	Language       string            `json:"language"`
	TimeZone       string            `json:"timezone"`
	DateFormat     string            `json:"date_format"`
	CustomFields   map[string]string `json:"custom_fields"`
	Notifications  map[string]bool   `json:"notifications"`
}

type Tenant struct {
	ID             string         `json:"id"`
	Name           string         `json:"name"`
	IsolationLevel IsolationLevel `json:"isolation_level"`
	Quota          ResourceQuota  `json:"quota"`
	Config         TenantConfig   `json:"config"`
	Status         string         `json:"status"`
	SchemaName     string         `json:"schema_name,omitempty"`
	DatabaseName   string         `json:"database_name,omitempty"`
}

type TenantManager struct {
	mu      sync.RWMutex
	tenants map[string]*Tenant
	usage   map[string]*ResourceUsage
}

type ResourceUsage struct {
	CPUUsage      float64 `json:"cpu_usage"`
	MemoryUsage   float64 `json:"memory_usage_mb"`
	StorageUsage  float64 `json:"storage_usage_gb"`
	APICallCount  int     `json:"api_call_count"`
	UserCount     int     `json:"user_count"`
	WorkOrderCount int    `json:"work_order_count"`
}

func NewTenantManager() *TenantManager {
	return &TenantManager{
		tenants: make(map[string]*Tenant),
		usage:   make(map[string]*ResourceUsage),
	}
}

func (tm *TenantManager) CreateTenant(id, name string, level IsolationLevel, quota ResourceQuota) (*Tenant, error) {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	if _, exists := tm.tenants[id]; exists {
		return nil, fmt.Errorf("tenant %s already exists", id)
	}
	tenant := &Tenant{
		ID:             id,
		Name:           name,
		IsolationLevel: level,
		Quota:          quota,
		Config: TenantConfig{
			Theme:         map[string]string{"primary": "#1890ff"},
			Language:      "zh-CN",
			TimeZone:      "Asia/Shanghai",
			DateFormat:    "2006-01-02",
			CustomFields:  make(map[string]string),
			Notifications: map[string]bool{"email": true, "sms": false},
		},
		Status: "active",
	}
	switch level {
	case IsolationSchema:
		tenant.SchemaName = "tenant_" + id
	case IsolationDatabase:
		tenant.DatabaseName = "db_tenant_" + id
	case IsolationInstance:
		tenant.DatabaseName = "db_tenant_" + id
		tenant.SchemaName = "public"
	}
	tm.tenants[id] = tenant
	tm.usage[id] = &ResourceUsage{}
	return tenant, nil
}

func (tm *TenantManager) GetTenant(id string) (*Tenant, error) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	t, ok := tm.tenants[id]
	if !ok {
		return nil, fmt.Errorf("tenant %s not found", id)
	}
	return t, nil
}

func (tm *TenantManager) DeleteTenant(id string) error {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	if _, ok := tm.tenants[id]; !ok {
		return fmt.Errorf("tenant %s not found", id)
	}
	delete(tm.tenants, id)
	delete(tm.usage, id)
	return nil
}

func (tm *TenantManager) UpdateConfig(id string, config TenantConfig) error {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	t, ok := tm.tenants[id]
	if !ok {
		return fmt.Errorf("tenant %s not found", id)
	}
	t.Config = config
	return nil
}

func (tm *TenantManager) UpdateQuota(id string, quota ResourceQuota) error {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	t, ok := tm.tenants[id]
	if !ok {
		return fmt.Errorf("tenant %s not found", id)
	}
	t.Quota = quota
	return nil
}

func (tm *TenantManager) CheckQuota(tenantID string, resource string, value float64) error {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	t, ok := tm.tenants[tenantID]
	if !ok {
		return fmt.Errorf("tenant %s not found", tenantID)
	}
	u := tm.usage[tenantID]
	switch resource {
	case "cpu":
		if u.CPUUsage+value > t.Quota.MaxCPU {
			return errors.New("cpu quota exceeded")
		}
	case "memory":
		if u.MemoryUsage+value > t.Quota.MaxMemory {
			return errors.New("memory quota exceeded")
		}
	case "storage":
		if u.StorageUsage+value > t.Quota.MaxStorage {
			return errors.New("storage quota exceeded")
		}
	case "api_calls":
		if u.APICallCount+int(value) > t.Quota.MaxAPICalls {
			return errors.New("api call quota exceeded")
		}
	case "users":
		if u.UserCount+int(value) > t.Quota.MaxUsers {
			return errors.New("user quota exceeded")
		}
	case "work_orders":
		if u.WorkOrderCount+int(value) > t.Quota.MaxWorkOrders {
			return errors.New("work order quota exceeded")
		}
	default:
		return fmt.Errorf("unknown resource type: %s", resource)
	}
	return nil
}

func (tm *TenantManager) RecordUsage(tenantID string, resource string, value float64) error {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	if _, ok := tm.tenants[tenantID]; !ok {
		return fmt.Errorf("tenant %s not found", tenantID)
	}
	u := tm.usage[tenantID]
	switch resource {
	case "cpu":
		u.CPUUsage += value
	case "memory":
		u.MemoryUsage += value
	case "storage":
		u.StorageUsage += value
	case "api_calls":
		u.APICallCount += int(value)
	case "users":
		u.UserCount += int(value)
	case "work_orders":
		u.WorkOrderCount += int(value)
	}
	return nil
}

func (tm *TenantManager) GetUsage(tenantID string) (*ResourceUsage, error) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	if _, ok := tm.tenants[tenantID]; !ok {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	return tm.usage[tenantID], nil
}

func (tm *TenantManager) GetDataIsolationConfig(tenantID string) (map[string]interface{}, error) {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	t, ok := tm.tenants[tenantID]
	if !ok {
		return nil, fmt.Errorf("tenant %s not found", tenantID)
	}
	config := map[string]interface{}{
		"tenant_id":       t.ID,
		"isolation_level": t.IsolationLevel,
	}
	switch t.IsolationLevel {
	case IsolationSchema:
		config["schema_name"] = t.SchemaName
		config["connection_pool"] = "shared"
	case IsolationDatabase:
		config["database_name"] = t.DatabaseName
		config["connection_pool"] = "dedicated"
	case IsolationInstance:
		config["database_name"] = t.DatabaseName
		config["schema_name"] = t.SchemaName
		config["connection_pool"] = "dedicated"
		config["compute_isolated"] = true
	default:
		config["connection_pool"] = "shared"
		config["row_level_security"] = true
	}
	return config, nil
}

func (tm *TenantManager) ListTenants() []*Tenant {
	tm.mu.RLock()
	defer tm.mu.RUnlock()
	result := make([]*Tenant, 0, len(tm.tenants))
	for _, t := range tm.tenants {
		result = append(result, t)
	}
	return result
}

func (t *Tenant) Format() string {
	return fmt.Sprintf("Tenant[%s] %s | Isolation: %s | Status: %s", t.ID, t.Name, t.IsolationLevel, t.Status)
}
