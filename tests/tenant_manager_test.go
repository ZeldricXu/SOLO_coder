package tests

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session130/internal/tenant"
)

func TestManager_CreateTenant_Success(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)

	require.NoError(t, err)
	require.NotNil(t, tenant)
	assert.NotEmpty(t, tenant.TenantID)
	assert.Equal(t, "Test Corp", tenant.Name)
	assert.Equal(t, "admin@test.com", tenant.AdminEmail)
	assert.Equal(t, tenant.StatusActive, tenant.Status)
	assert.Equal(t, tenant.PlanStandard, tenant.BillingPlan)
	assert.NotNil(t, tenant.Config)
	assert.NotZero(t, tenant.CreatedAt)
	assert.NotZero(t, tenant.UpdatedAt)
}

func TestManager_CreateTenant_MissingName(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.CreateTenant("", "admin@test.com", tenant.PlanStandard)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "tenant name is required")
}

func TestManager_CreateTenant_MissingEmail(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.CreateTenant("Test Corp", "", tenant.PlanStandard)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "admin email is required")
}

func TestManager_CreateTenant_AllBillingPlans(t *testing.T) {
	testCases := []struct {
		name     string
		plan     tenant.BillingPlan
		minQuota int64
		maxUsers int
	}{
		{"Free Plan", tenant.PlanFree, 10, 5},
		{"Standard Plan", tenant.PlanStandard, 100, 50},
		{"Premium Plan", tenant.PlanPremium, 1000, 500},
		{"Enterprise Plan", tenant.PlanEnterprise, 10000, 10000},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			manager := tenant.NewManager()

			tenant, err := manager.CreateTenant("Test Corp", "admin@test.com", tc.plan)

			require.NoError(t, err)
			require.NotNil(t, tenant)
			assert.Equal(t, tc.plan, tenant.BillingPlan)
			assert.Equal(t, tc.minQuota, tenant.ResourceQuota.MaxStorageGB)
			assert.Equal(t, tc.maxUsers, tenant.ResourceQuota.MaxUsers)
		})
	}
}

func TestManager_CreateTenant_DefaultPlan(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.CreateTenant("Test Corp", "admin@test.com", "unknown_plan")

	require.NoError(t, err)
	require.NotNil(t, tenant)
	assert.Equal(t, tenant.PlanFree, tenant.BillingPlan)
	assert.Equal(t, int64(10), tenant.ResourceQuota.MaxStorageGB)
}

func TestManager_GetTenant_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	retrieved, err := manager.GetTenant(created.TenantID)

	require.NoError(t, err)
	require.NotNil(t, retrieved)
	assert.Equal(t, created.TenantID, retrieved.TenantID)
	assert.Equal(t, created.Name, retrieved.Name)
}

func TestManager_GetTenant_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.GetTenant("nonexistent_id")

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_GetTenant_Deleted(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	err = manager.DeleteTenant(created.TenantID)
	require.NoError(t, err)

	tenant, err := manager.GetTenant(created.TenantID)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "has been deleted")
}

func TestManager_UpdateTenantConfig_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	updated, err := manager.UpdateTenantConfig(created.TenantID, map[string]interface{}{
		"feature_flag": true,
		"max_workers":  10,
	})

	require.NoError(t, err)
	require.NotNil(t, updated)
	assert.Equal(t, true, updated.Config["feature_flag"])
	assert.Equal(t, 10, updated.Config["max_workers"])
	assert.Greater(t, updated.UpdatedAt, created.UpdatedAt)
}

func TestManager_UpdateTenantConfig_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.UpdateTenantConfig("nonexistent", map[string]interface{}{})

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_UpdateTenantConfig_SuspendedTenant(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	_, err = manager.SuspendTenant(created.TenantID)
	require.NoError(t, err)

	tenant, err := manager.UpdateTenantConfig(created.TenantID, map[string]interface{}{
		"feature_flag": true,
	})

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "is not active")
}

func TestManager_UpdateBillingPlan_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	updated, err := manager.UpdateBillingPlan(created.TenantID, tenant.PlanPremium)

	require.NoError(t, err)
	require.NotNil(t, updated)
	assert.Equal(t, tenant.PlanPremium, updated.BillingPlan)
	assert.Equal(t, int64(1000), updated.ResourceQuota.MaxStorageGB)
	assert.Equal(t, 500, updated.ResourceQuota.MaxUsers)
	assert.Equal(t, 6000, updated.RateLimit.RequestsPerMinute)
}

func TestManager_UpdateBillingPlan_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.UpdateBillingPlan("nonexistent", tenant.PlanPremium)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_SuspendTenant_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	suspended, err := manager.SuspendTenant(created.TenantID)

	require.NoError(t, err)
	require.NotNil(t, suspended)
	assert.Equal(t, tenant.StatusSuspended, suspended.Status)
	assert.Greater(t, suspended.UpdatedAt, created.UpdatedAt)
}

func TestManager_SuspendTenant_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.SuspendTenant("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_SuspendTenant_Deleted(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	err = manager.DeleteTenant(created.TenantID)
	require.NoError(t, err)

	tenant, err := manager.SuspendTenant(created.TenantID)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "has been deleted")
}

func TestManager_ActivateTenant_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	_, err = manager.SuspendTenant(created.TenantID)
	require.NoError(t, err)

	activated, err := manager.ActivateTenant(created.TenantID)

	require.NoError(t, err)
	require.NotNil(t, activated)
	assert.Equal(t, tenant.StatusActive, activated.Status)
}

func TestManager_ActivateTenant_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.ActivateTenant("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_DeleteTenant_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	err = manager.DeleteTenant(created.TenantID)

	require.NoError(t, err)

	retrieved, err := manager.GetTenant(created.TenantID)
	assert.Error(t, err)
	assert.Nil(t, retrieved)
}

func TestManager_DeleteTenant_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	err := manager.DeleteTenant("nonexistent")

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_CheckRateLimit_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	for i := 0; i < 5; i++ {
		allowed, err := manager.CheckRateLimit(created.TenantID)
		require.NoError(t, err)
		assert.True(t, allowed)
	}
}

func TestManager_CheckRateLimit_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	allowed, err := manager.CheckRateLimit("nonexistent")

	assert.Error(t, err)
	assert.False(t, allowed)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_CheckRateLimit_SuspendedTenant(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.SuspendTenant(created.TenantID)
	require.NoError(t, err)

	allowed, err := manager.CheckRateLimit(created.TenantID)

	assert.Error(t, err)
	assert.False(t, allowed)
	assert.Contains(t, err.Error(), "is not active")
}

func TestManager_CheckRateLimit_MinuteLimitExceeded(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	limit := created.RateLimit.RequestsPerMinute
	for i := 0; i < limit; i++ {
		allowed, err := manager.CheckRateLimit(created.TenantID)
		require.NoError(t, err)
		require.True(t, allowed, "Request %d should be allowed", i)
	}

	allowed, err := manager.CheckRateLimit(created.TenantID)
	require.NoError(t, err)
	assert.False(t, allowed, "Request over limit should be denied")
}

func TestManager_CheckRateLimit_PlanLimits(t *testing.T) {
	testCases := []struct {
		name          string
		plan          tenant.BillingPlan
		minuteLimit   int
		expectedAllow int
	}{
		{"Free Plan", tenant.PlanFree, 60, 60},
		{"Standard Plan", tenant.PlanStandard, 600, 600},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			manager := tenant.NewManager()

			created, err := manager.CreateTenant("Test Corp", "admin@test.com", tc.plan)
			require.NoError(t, err)
			assert.Equal(t, tc.minuteLimit, created.RateLimit.RequestsPerMinute)
		})
	}
}

func TestManager_CheckResourceQuota_WithinQuota(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		StorageUsedGB: 5,
		CPUUsedCores:  0.5,
		MemoryUsedGB:  1,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.True(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedStorage(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		StorageUsedGB: 15,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedMemory(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		MemoryUsedGB: 3,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	allowed, err := manager.CheckResourceQuota("nonexistent")

	assert.Error(t, err)
	assert.False(t, allowed)
}

func TestManager_RecordUsage_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	usage := tenant.ResourceUsage{
		StorageUsedGB:    50,
		CPUUsedCores:     2,
		MemoryUsedGB:     4,
		APIRequestsCount: 50000,
		ActiveUsers:      25,
		ActiveConnections: 50,
		BandwidthUsedGB:  500,
	}

	updated, err := manager.RecordUsage(created.TenantID, usage)

	require.NoError(t, err)
	require.NotNil(t, updated)
	assert.Equal(t, int64(50), updated.CurrentUsage.StorageUsedGB)
	assert.Equal(t, 2.0, updated.CurrentUsage.CPUUsedCores)
	assert.Equal(t, 4.0, updated.CurrentUsage.MemoryUsedGB)
}

func TestManager_RecordUsage_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.RecordUsage("nonexistent", tenant.ResourceUsage{})

	assert.Error(t, err)
	assert.Nil(t, tenant)
}

func TestManager_GetUsageStats_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		StorageUsedGB: 50,
		CPUUsedCores:  2,
		MemoryUsedGB:  4,
	})
	require.NoError(t, err)

	stats, err := manager.GetUsageStats(created.TenantID)

	require.NoError(t, err)
	require.NotNil(t, stats)
	assert.Equal(t, created.TenantID, stats["tenant_id"])
	assert.Equal(t, tenant.PlanStandard, stats["billing_plan"])
	assert.InDelta(t, 50.0, stats["storage_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["memory_percent"], 0.1)
}

func TestManager_GetUsageStats_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	stats, err := manager.GetUsageStats("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, stats)
}

func TestManager_ListTenants_All(t *testing.T) {
	manager := tenant.NewManager()

	_, _ = manager.CreateTenant("Tenant A", "a@test.com", tenant.PlanFree)
	_, _ = manager.CreateTenant("Tenant B", "b@test.com", tenant.PlanStandard)
	_, _ = manager.CreateTenant("Tenant C", "c@test.com", tenant.PlanPremium)

	tenants := manager.ListTenants("")

	assert.Len(t, tenants, 3)
}

func TestManager_ListTenants_ByStatus(t *testing.T) {
	manager := tenant.NewManager()

	t1, _ := manager.CreateTenant("Tenant A", "a@test.com", tenant.PlanFree)
	t2, _ := manager.CreateTenant("Tenant B", "b@test.com", tenant.PlanStandard)
	_, _ = manager.SuspendTenant(t2.TenantID)

	activeTenants := manager.ListTenants(tenant.StatusActive)
	assert.Len(t, activeTenants, 1)
	assert.Equal(t, t1.TenantID, activeTenants[0].TenantID)

	suspendedTenants := manager.ListTenants(tenant.StatusSuspended)
	assert.Len(t, suspendedTenants, 1)
	assert.Equal(t, t2.TenantID, suspendedTenants[0].TenantID)
}

func TestManager_ListTenants_Empty(t *testing.T) {
	manager := tenant.NewManager()

	tenants := manager.ListTenants("")

	assert.Empty(t, tenants)
}

func TestManager_GetTenantByIsolationKey_Success(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	retrieved, err := manager.GetTenantByIsolationKey(created.TenantID)

	require.NoError(t, err)
	require.NotNil(t, retrieved)
	assert.Equal(t, created.TenantID, retrieved.TenantID)
}

func TestManager_GetTenantByIsolationKey_NotFound(t *testing.T) {
	manager := tenant.NewManager()

	tenant, err := manager.GetTenantByIsolationKey("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "not found")
}

func TestManager_GetTenantByIsolationKey_Deleted(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	err = manager.DeleteTenant(created.TenantID)
	require.NoError(t, err)

	tenant, err := manager.GetTenantByIsolationKey(created.TenantID)

	assert.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "has been deleted")
}

func TestManager_Concurrent_CreateTenant(t *testing.T) {
	manager := tenant.NewManager()

	var wg sync.WaitGroup
	numTenants := 50
	results := make([]*tenant.Tenant, numTenants)
	errors := make([]error, numTenants)

	for i := 0; i < numTenants; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			results[idx], errors[idx] = manager.CreateTenant(
				"Concurrent Tenant",
				"concurrent@test.com",
				tenant.PlanFree,
			)
		}(i)
	}

	wg.Wait()

	successCount := 0
	for i := 0; i < numTenants; i++ {
		if errors[i] == nil && results[i] != nil {
			successCount++
		}
	}

	assert.Equal(t, numTenants, successCount)
	assert.Len(t, manager.ListTenants(""), numTenants)
}

func TestManager_Concurrent_CreateAndGetTenant(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup
	numGoroutines := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = manager.GetTenant(created.TenantID)
		}()
	}

	wg.Wait()
}

func TestManager_Concurrent_UpdateBillingPlan(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	var wg sync.WaitGroup
	plans := []tenant.BillingPlan{tenant.PlanStandard, tenant.PlanPremium, tenant.PlanEnterprise}

	for i := 0; i < 30; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, _ = manager.UpdateBillingPlan(created.TenantID, plans[idx%len(plans)])
		}(i)
	}

	wg.Wait()

	finalTenant, err := manager.GetTenant(created.TenantID)
	require.NoError(t, err)
	assert.Contains(t, plans, finalTenant.BillingPlan)
}

func TestManager_Concurrent_RateLimitCheck(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	var wg sync.WaitGroup
	numGoroutines := 100
	allowedCount := 0
	var mu sync.Mutex

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			allowed, _ := manager.CheckRateLimit(created.TenantID)
			if allowed {
				mu.Lock()
				allowedCount++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	assert.GreaterOrEqual(t, allowedCount, 0)
	assert.LessOrEqual(t, allowedCount, created.RateLimit.RequestsPerMinute+1)
}

func TestManager_Concurrent_MixedOperations(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 10; i++ {
			_, _ = manager.UpdateTenantConfig(created.TenantID, map[string]interface{}{
				"update": i,
			})
			time.Sleep(1 * time.Millisecond)
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 10; i++ {
			_, _ = manager.CheckRateLimit(created.TenantID)
			time.Sleep(1 * time.Millisecond)
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 10; i++ {
			_, _ = manager.GetUsageStats(created.TenantID)
			time.Sleep(1 * time.Millisecond)
		}
	}()

	wg.Wait()

	finalTenant, err := manager.GetTenant(created.TenantID)
	require.NoError(t, err)
	require.NotNil(t, finalTenant)
}

func TestManager_TenantID_Unique(t *testing.T) {
	manager := tenant.NewManager()

	tenantIDs := make(map[string]bool)
	for i := 0; i < 100; i++ {
		t, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
		require.NoError(t, err)
		assert.False(t, tenantIDs[t.TenantID], "Tenant ID should be unique")
		tenantIDs[t.TenantID] = true
	}
}

func TestManager_DefaultQuotaForPlan_EdgeCases(t *testing.T) {
	testCases := []struct {
		name     string
		plan     tenant.BillingPlan
		maxConn  int
	}{
		{"Free Plan Connections", tenant.PlanFree, 10},
		{"Standard Plan Connections", tenant.PlanStandard, 100},
		{"Premium Plan Connections", tenant.PlanPremium, 500},
		{"Enterprise Plan Connections", tenant.PlanEnterprise, 5000},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			manager := tenant.NewManager()

			created, err := manager.CreateTenant("Test Corp", "admin@test.com", tc.plan)
			require.NoError(t, err)
			assert.Equal(t, tc.maxConn, created.ResourceQuota.MaxConnections)
		})
	}
}

func TestManager_UsageStats_ZeroDivision(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{})
	require.NoError(t, err)

	stats, err := manager.GetUsageStats(created.TenantID)
	require.NoError(t, err)
	require.NotNil(t, stats)

	assert.NotPanics(t, func() {
		_ = stats["storage_percent"]
		_ = stats["memory_percent"]
	})
}

func TestManager_CheckRateLimit_HourLimit(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	limit := created.RateLimit.RequestsPerHour
	for i := 0; i < limit; i++ {
		allowed, err := manager.CheckRateLimit(created.TenantID)
		require.NoError(t, err)
		require.True(t, allowed, "Request %d should be allowed", i)
	}

	allowed, err := manager.CheckRateLimit(created.TenantID)
	require.NoError(t, err)
	assert.False(t, allowed, "Request over hour limit should be denied")
}

func TestManager_CheckRateLimit_DayLimit(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	limit := created.RateLimit.RequestsPerDay
	for i := 0; i < limit; i++ {
		allowed, err := manager.CheckRateLimit(created.TenantID)
		require.NoError(t, err)
		require.True(t, allowed, "Request %d should be allowed", i)
	}

	allowed, err := manager.CheckRateLimit(created.TenantID)
	require.NoError(t, err)
	assert.False(t, allowed, "Request over day limit should be denied")
}

func TestManager_CheckResourceQuota_ExceedCPU(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		CPUUsedCores: 2.0,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedBandwidth(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		BandwidthUsedGB: 200,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedAPIRequests(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		APIRequestsCount: 20000,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedUsers(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		ActiveUsers: 10,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_ExceedConnections(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		ActiveConnections: 20,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.False(t, allowed)
}

func TestManager_CheckResourceQuota_AtBoundary(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanFree)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		StorageUsedGB: 10,
		CPUUsedCores:  1.0,
		MemoryUsedGB:  2.0,
	})
	require.NoError(t, err)

	allowed, err := manager.CheckResourceQuota(created.TenantID)

	require.NoError(t, err)
	assert.True(t, allowed)
}

func TestManager_Concurrent_CheckRateLimitHighVolume(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup
	numGoroutines := 500
	allowedCount := 0
	var mu sync.Mutex

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			allowed, _ := manager.CheckRateLimit(created.TenantID)
			if allowed {
				mu.Lock()
				allowedCount++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	assert.GreaterOrEqual(t, allowedCount, 0)
	assert.LessOrEqual(t, allowedCount, created.RateLimit.RequestsPerMinute+10)
}

func TestManager_Concurrent_RecordUsage(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup
	numGoroutines := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			usage := tenant.ResourceUsage{
				StorageUsedGB: int64(idx),
				CPUUsedCores:  float64(idx) * 0.1,
				MemoryUsedGB:  float64(idx) * 0.2,
			}
			_, _ = manager.RecordUsage(created.TenantID, usage)
		}(i)
	}

	wg.Wait()

	finalTenant, err := manager.GetTenant(created.TenantID)
	require.NoError(t, err)
	require.NotNil(t, finalTenant)
}

func TestManager_Concurrent_MixedCRUDOperations(t *testing.T) {
	manager := tenant.NewManager()

	var wg sync.WaitGroup

	createdTenants := make([]*tenant.Tenant, 10)
	var createMu sync.Mutex

	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			t, err := manager.CreateTenant(
				fmt.Sprintf("Tenant %d", idx),
				fmt.Sprintf("admin%d@test.com", idx),
				tenant.PlanFree,
			)
			if err == nil {
				createMu.Lock()
				createdTenants[idx] = t
				createMu.Unlock()
			}
		}(i)
	}

	wg.Wait()

	for i := 0; i < 10; i++ {
		if createdTenants[i] != nil {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				_, _ = manager.UpdateTenantConfig(createdTenants[idx].TenantID, map[string]interface{}{
					"updated": true,
				})
			}(i)

			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				_, _ = manager.GetTenant(createdTenants[idx].TenantID)
			}(i)
		}
	}

	wg.Wait()

	tenants := manager.ListTenants("")
	assert.Len(t, tenants, 10)
}

func TestManager_DeleteTenant_ConcurrentOperations(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = manager.DeleteTenant(created.TenantID)
	}()

	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = manager.GetTenant(created.TenantID)
		}()

		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = manager.CheckRateLimit(created.TenantID)
		}()
	}

	wg.Wait()
}

func TestManager_CreateTenant_EmptyPlan(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", "")
	require.NoError(t, err)
	require.NotNil(t, created)
	assert.Equal(t, tenant.PlanFree, created.BillingPlan)
}

func TestManager_UpdateTenantConfig_EmptyConfig(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	updated, err := manager.UpdateTenantConfig(created.TenantID, map[string]interface{}{})
	require.NoError(t, err)
	require.NotNil(t, updated)
	assert.Equal(t, created.Config, updated.Config)
}

func TestManager_ListTenants_InvalidStatus(t *testing.T) {
	manager := tenant.NewManager()

	_, _ = manager.CreateTenant("Tenant A", "a@test.com", tenant.PlanFree)
	_, _ = manager.CreateTenant("Tenant B", "b@test.com", tenant.PlanStandard)

	tenants := manager.ListTenants("invalid_status")
	assert.Empty(t, tenants)
}

func TestManager_UsageStats_AllResources(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	_, err = manager.RecordUsage(created.TenantID, tenant.ResourceUsage{
		StorageUsedGB:    50,
		CPUUsedCores:     2,
		MemoryUsedGB:     4,
		APIRequestsCount: 50000,
		ActiveUsers:      25,
		ActiveConnections: 50,
		BandwidthUsedGB:  500,
	})
	require.NoError(t, err)

	stats, err := manager.GetUsageStats(created.TenantID)
	require.NoError(t, err)
	require.NotNil(t, stats)

	assert.InDelta(t, 50.0, stats["storage_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["memory_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["api_requests_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["users_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["connections_percent"], 0.1)
	assert.InDelta(t, 50.0, stats["bandwidth_percent"], 0.1)
}

func TestManager_Concurrent_GetUsageStats(t *testing.T) {
	manager := tenant.NewManager()

	created, err := manager.CreateTenant("Test Corp", "admin@test.com", tenant.PlanStandard)
	require.NoError(t, err)

	var wg sync.WaitGroup
	numGoroutines := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			stats, _ := manager.GetUsageStats(created.TenantID)
			assert.NotNil(t, stats)
		}()
	}

	wg.Wait()
}
