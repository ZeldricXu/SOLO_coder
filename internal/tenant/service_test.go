package tenant

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/datamigration/platform/pkg/models"
	"github.com/datamigration/platform/pkg/testutil"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func setupTestDB(t *testing.T) (*gorm.DB, *Service) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&models.Tenant{}, &models.Entity{})
	require.NoError(t, err)

	service := NewService(db)
	return db, service
}

func TestCreateTenant_Success(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	cfg := &models.TenantConfig{
		Theme:       "dark",
		Language:    "zh-CN",
		Timezone:    "Asia/Shanghai",
		Features:    map[string]bool{"advanced": true},
		CustomParams: map[string]interface{}{"env": "prod"},
	}
	quota := &models.Quota{
		MaxStorageGB:   200,
		MaxUsers:       100,
		MaxWorkflows:   50,
		MaxAPICallsDay: 50000,
	}

	tenant, err := service.CreateTenant(ctx, "TestTenant", "Test tenant", cfg, quota)
	require.NoError(t, err)
	require.NotNil(t, tenant)

	assert.NotEmpty(t, tenant.ID)
	assert.Equal(t, "TestTenant", tenant.Name)
	assert.Equal(t, "active", tenant.Status)
	assert.NotEmpty(t, tenant.Config)
	assert.NotEmpty(t, tenant.Quota)

	var loadedCfg models.TenantConfig
	err = json.Unmarshal(tenant.Config, &loadedCfg)
	require.NoError(t, err)
	assert.Equal(t, "dark", loadedCfg.Theme)

	var loadedQuota models.Quota
	err = json.Unmarshal(tenant.Quota, &loadedQuota)
	require.NoError(t, err)
	assert.Equal(t, int64(200), loadedQuota.MaxStorageGB)
}

func TestCreateTenant_Validation(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	tenant, err := service.CreateTenant(ctx, "", "Empty name", nil, nil)
	require.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "tenant name is required")
}

func TestCreateTenant_DefaultConfigAndQuota(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	tenant, err := service.CreateTenant(ctx, "DefaultTenant", "With defaults", nil, nil)
	require.NoError(t, err)

	var cfg models.TenantConfig
	err = json.Unmarshal(tenant.Config, &cfg)
	require.NoError(t, err)
	assert.Equal(t, "default", cfg.Theme)
	assert.Equal(t, "zh-CN", cfg.Language)

	var quota models.Quota
	err = json.Unmarshal(tenant.Quota, &quota)
	require.NoError(t, err)
	assert.Equal(t, int64(100), quota.MaxStorageGB)
	assert.Equal(t, int64(50), quota.MaxUsers)
}

func TestGetTenant_CacheConsistency(t *testing.T) {
	db, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "CachedTenant", "Test", nil, nil)
	require.NoError(t, err)

	firstRead, err := service.GetTenant(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, created.ID, firstRead.ID)

	secondRead, err := service.GetTenant(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, firstRead.ID, secondRead.ID)

	var count int64
	db.Model(&models.Tenant{}).Count(&count)
	assert.Equal(t, int64(1), count)
}

func TestGetTenant_NotFound(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	tenant, err := service.GetTenant(ctx, "non_existent")
	require.Error(t, err)
	assert.Nil(t, tenant)
	assert.Contains(t, err.Error(), "tenant not found")
}

func TestUpdateTenant_DataConsistency(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "ToUpdate", "Initial desc", nil, nil)
	require.NoError(t, err)

	err = service.UpdateTenant(ctx, created.ID, map[string]interface{}{
		"name":        "UpdatedName",
		"description": "Updated description",
		"status":      "inactive",
	})
	require.NoError(t, err)

	loaded, err := service.GetTenant(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, "UpdatedName", loaded.Name)
	assert.Equal(t, "Updated description", loaded.Description)
	assert.Equal(t, "inactive", loaded.Status)
}

func TestDeleteTenant_CacheInvalidation(t *testing.T) {
	db, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "ToDelete", "To be deleted", nil, nil)
	require.NoError(t, err)

	_, err = service.GetTenant(ctx, created.ID)
	require.NoError(t, err)

	err = service.DeleteTenant(ctx, created.ID)
	require.NoError(t, err)

	var count int64
	db.Model(&models.Tenant{}).Count(&count)
	assert.Equal(t, int64(0), count)

	_, err = service.GetTenant(ctx, created.ID)
	require.Error(t, err)
}

func TestGetConfig_ParseJSON(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	cfg := &models.TenantConfig{
		Theme:    "light",
		Language: "en-US",
	}
	created, err := service.CreateTenant(ctx, "ConfigTenant", "", cfg, nil)
	require.NoError(t, err)

	loadedCfg, err := service.GetConfig(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, "light", loadedCfg.Theme)
	assert.Equal(t, "en-US", loadedCfg.Language)
}

func TestUpdateConfig_Persistence(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "UpdateConfig", "", nil, nil)
	require.NoError(t, err)

	newCfg := &models.TenantConfig{
		Theme:       "dark",
		Language:    "ja-JP",
		Timezone:    "Asia/Tokyo",
		Features:    map[string]bool{"premium": true},
		CustomParams: map[string]interface{}{"region": "ap-northeast-1"},
	}
	err = service.UpdateConfig(ctx, created.ID, newCfg)
	require.NoError(t, err)

	loaded, err := service.GetConfig(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, "dark", loaded.Theme)
	assert.Equal(t, "ja-JP", loaded.Language)
	assert.Equal(t, "Asia/Tokyo", loaded.Timezone)
	assert.True(t, loaded.Features["premium"])
}

func TestGetQuota_ParseJSON(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	quota := &models.Quota{
		MaxStorageGB:   500,
		MaxUsers:       200,
		MaxWorkflows:   100,
		MaxAPICallsDay: 100000,
	}
	created, err := service.CreateTenant(ctx, "QuotaTenant", "", nil, quota)
	require.NoError(t, err)

	loadedQuota, err := service.GetQuota(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, int64(500), loadedQuota.MaxStorageGB)
	assert.Equal(t, int64(200), loadedQuota.MaxUsers)
	assert.Equal(t, int64(100), loadedQuota.MaxWorkflows)
	assert.Equal(t, int64(100000), loadedQuota.MaxAPICallsDay)
}

func TestCheckQuota_VariousResources(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	quota := &models.Quota{
		MaxStorageGB:   100,
		MaxUsers:       50,
		MaxWorkflows:   20,
		MaxAPICallsDay: 10000,
	}
	created, err := service.CreateTenant(ctx, "CheckQuota", "", nil, quota)
	require.NoError(t, err)

	testCases := []struct {
		name         string
		resourceType string
		current      int
		expected     bool
	}{
		{"Storage Under", "storage", 99, true},
		{"Storage At Limit", "storage", 100, false},
		{"Storage Over", "storage", 101, false},
		{"Users Under", "users", 49, true},
		{"Users At Limit", "users", 50, false},
		{"Workflows Under", "workflows", 19, true},
		{"APICalls Under", "api_calls", 9999, true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result, err := service.CheckQuota(ctx, created.ID, tc.resourceType, tc.current)
			require.NoError(t, err)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestCheckQuota_UnknownResource(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "UnknownRes", "", nil, nil)
	require.NoError(t, err)

	result, err := service.CheckQuota(ctx, created.ID, "unknown_resource", 0)
	require.Error(t, err)
	assert.False(t, result)
}

func TestTenantIsolation_ConcurrentAccess(t *testing.T) {
	db, service := setupTestDB(t)
	ctx := context.Background()

	const numTenants = 10
	var wg sync.WaitGroup
	var mu sync.Mutex
	createdTenants := make([]*models.Tenant, 0, numTenants)
	errorsMap := make(map[string]bool)

	for i := 0; i < numTenants; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			name := fmt.Sprintf("ConcurrentTenant_%d", idx)
			tenant, err := service.CreateTenant(ctx, name, "Concurrent", nil, nil)
			if err != nil {
				mu.Lock()
				errorsMap[idx] = true
				mu.Unlock()
				return
			}
			mu.Lock()
			createdTenants = append(createdTenants, tenant)
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	assert.Empty(t, errorsMap)
	assert.Equal(t, numTenants, len(createdTenants))

	var count int64
	db.Model(&models.Tenant{}).Count(&count)
	assert.Equal(t, int64(numTenants), count)

	for _, tenant := range createdTenants {
		loaded, err := service.GetTenant(ctx, tenant.ID)
		require.NoError(t, err)
		assert.Equal(t, tenant.ID, loaded.ID)
	}
}

func TestTenantCache_ConcurrentReads(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "CacheRead", "Concurrent reads", nil, nil)
	require.NoError(t, err)

	const numGoroutines = 100
	var wg sync.WaitGroup
	var mu sync.Mutex
	failures := 0

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			loaded, err := service.GetTenant(ctx, created.ID)
			if err != nil || loaded.ID != created.ID {
				mu.Lock()
				failures++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	assert.Equal(t, 0, failures)
}

func TestTenantCache_ConcurrentUpdates(t *testing.T) {
	db, service := setupTestDB(t)
	ctx := context.Background()

	created, err := service.CreateTenant(ctx, "CacheUpdate", "Concurrent updates", nil, nil)
	require.NoError(t, err)

	const numGoroutines = 50
	var wg sync.WaitGroup
	var mu sync.Mutex
	failures := 0

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			newName := fmt.Sprintf("Updated_%d", idx)
			err := service.UpdateTenant(ctx, created.ID, map[string]interface{}{
				"name": newName,
			})
			if err != nil {
				mu.Lock()
				failures++
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()

	assert.Equal(t, 0, failures)

	var finalTenant models.Tenant
	db.First(&finalTenant, "id = ?", created.ID)
	assert.NotEmpty(t, finalTenant.Name)
}

func TestScope_IsolationWithDB(t *testing.T) {
	db, service := setupTestDB(t)
	ctx := context.Background()

	tenant1, err := service.CreateTenant(ctx, "TenantA", "", nil, nil)
	require.NoError(t, err)
	tenant2, err := service.CreateTenant(ctx, "TenantB", "", nil, nil)
	require.NoError(t, err)

	factory := testutil.NewFactory()
	entity1 := factory.CreateEntity(tenant1.ID, testutil.WithEntityType("type_a"))
	entity2 := factory.CreateEntity(tenant1.ID, testutil.WithEntityType("type_b"))
	entity3 := factory.CreateEntity(tenant2.ID, testutil.WithEntityType("type_c"))

	err = db.Create(entity1).Error
	require.NoError(t, err)
	err = db.Create(entity2).Error
	require.NoError(t, err)
	err = db.Create(entity3).Error
	require.NoError(t, err)

	var tenant1Entities []models.Entity
	err = db.Scopes(Scope(tenant1.ID)).Find(&tenant1Entities).Error
	require.NoError(t, err)
	assert.Len(t, tenant1Entities, 2)

	var tenant2Entities []models.Entity
	err = db.Scopes(Scope(tenant2.ID)).Find(&tenant2Entities).Error
	require.NoError(t, err)
	assert.Len(t, tenant2Entities, 1)
	assert.Equal(t, "type_c", tenant2Entities[0].Type)
}

func TestContextFunctions(t *testing.T) {
	ctx := context.Background()

	tenantID, ok := FromContext(ctx)
	assert.False(t, ok)
	assert.Empty(t, tenantID)

	expectedID := "test_tenant_123"
	ctxWithTenant := WithTenant(ctx, expectedID)

	loadedID, ok := FromContext(ctxWithTenant)
	assert.True(t, ok)
	assert.Equal(t, expectedID, loadedID)
}

func TestConcurrentQuotaCheck(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	quota := &models.Quota{MaxAPICallsDay: 1000}
	created, err := service.CreateTenant(ctx, "QuotaCheck", "", nil, quota)
	require.NoError(t, err)

	const numGoroutines = 200
	var wg sync.WaitGroup
	var mu sync.Mutex
	trueCount := 0
	falseCount := 0
	errorCount := 0

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(current int) {
			defer wg.Done()
			result, err := service.CheckQuota(ctx, created.ID, "api_calls", current)
			mu.Lock()
			if err != nil {
				errorCount++
			} else if result {
				trueCount++
			} else {
				falseCount++
			}
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	assert.Equal(t, 0, errorCount)
	assert.Equal(t, 1000, trueCount)
	assert.Equal(t, 100, falseCount)
}

func TestListTenants_Pagination(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	for i := 0; i < 25; i++ {
		_, err := service.CreateTenant(ctx, fmt.Sprintf("ListTenant_%d", i), "", nil, nil)
		require.NoError(t, err)
	}

	tenants, total, err := service.ListTenants(ctx, 1, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(25), total)
	assert.Len(t, tenants, 10)

	tenants, total, err = service.ListTenants(ctx, 2, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(25), total)
	assert.Len(t, tenants, 10)

	tenants, total, err = service.ListTenants(ctx, 3, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(25), total)
	assert.Len(t, tenants, 5)
}

func TestListTenants_DefaultPagination(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	for i := 0; i < 5; i++ {
		_, err := service.CreateTenant(ctx, fmt.Sprintf("Default_%d", i), "", nil, nil)
		require.NoError(t, err)
	}

	tenants, total, err := service.ListTenants(ctx, 0, 0)
	require.NoError(t, err)
	assert.Equal(t, int64(5), total)
	assert.Len(t, tenants, 5)
}

func TestUpdateQuota_PreservesOtherFields(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	originalConfig := &models.TenantConfig{Theme: "dark"}
	originalQuota := &models.Quota{MaxStorageGB: 100, MaxUsers: 50}

	created, err := service.CreateTenant(ctx, "PreserveFields", "Description", originalConfig, originalQuota)
	require.NoError(t, err)

	newQuota := &models.Quota{MaxStorageGB: 500, MaxUsers: 200}
	err = service.UpdateQuota(ctx, created.ID, newQuota)
	require.NoError(t, err)

	loadedCfg, err := service.GetConfig(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, "dark", loadedCfg.Theme)

	loadedQuota, err := service.GetQuota(ctx, created.ID)
	require.NoError(t, err)
	assert.Equal(t, int64(500), loadedQuota.MaxStorageGB)
	assert.Equal(t, int64(200), loadedQuota.MaxUsers)
}
