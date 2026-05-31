package featureflags

import (
	"context"
	"depguard/test/testutils"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockCache struct {
	deletedKeys []string
	delErr      error
	mu          sync.Mutex
}

func newMockCache() *mockCache {
	return &mockCache{
		deletedKeys: make([]string, 0),
	}
}

func (m *mockCache) Del(ctx context.Context, keys ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.delErr != nil {
		return m.delErr
	}
	m.deletedKeys = append(m.deletedKeys, keys...)
	return nil
}

func (m *mockCache) getDeletedCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.deletedKeys)
}

func (m *mockCache) wasKeyDeleted(key string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, k := range m.deletedKeys {
		if k == key {
			return true
		}
	}
	return false
}

func TestCreateFlag_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should create feature flag successfully", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("new-ui").
			WithPercentageRule(50, 1).
			Build()

		created, err := svc.CreateFlag(context.Background(), flag)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
		assert.Equal(t, "new-ui", created.Key)
		assert.True(t, created.Enabled)

		var found FeatureFlag
		err = db.First(&found, "id = ?", created.ID).Error
		assert.NoError(t, err)
		assert.Equal(t, "new-ui", found.Key)
	})

	t.Run("should create flag with multiple rules", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("premium-feature").
			WithUserRule([]string{"user-1", "user-2"}, 2).
			WithPercentageRule(10, 1).
			Build()

		created, err := svc.CreateFlag(context.Background(), flag)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
		assert.Len(t, created.Rules, 2)
	})
}

func TestGetFlag_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should get existing flag", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().WithKey("get-test").Build()
		created, _ := svc.CreateFlag(context.Background(), flag)

		found, err := svc.GetFlag(context.Background(), created.ID)

		assert.NoError(t, err)
		assert.Equal(t, created.ID, found.ID)
		assert.Equal(t, "get-test", found.Key)
	})

	t.Run("should list all flags", func(t *testing.T) {
		for i := 0; i < 5; i++ {
			flag := testutils.NewFlagBuilder().WithKey(fmt.Sprintf("list-flag-%d", i)).Build()
			_, _ = svc.CreateFlag(context.Background(), flag)
		}

		flags, err := svc.ListFlags(context.Background())

		assert.NoError(t, err)
		assert.GreaterOrEqual(t, len(flags), 5)
	})
}

func TestEvaluateFlag_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should return true when flag is enabled with 100%", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("full-rollout").
			WithPercentageRule(100, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("user-1").
			Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.True(t, result.Enabled)
		assert.Equal(t, created.Key, result.FlagKey)
	})

	t.Run("should return false when flag is disabled", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("disabled-flag").
			Disabled().
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.False(t, result.Enabled)
	})

	t.Run("should return true for user in whitelist", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("whitelist-flag").
			WithUserRule([]string{"allowed-user"}, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("allowed-user").
			Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.True(t, result.Enabled)
	})

	t.Run("should return false for user not in whitelist", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("exclusive-flag").
			WithUserRule([]string{"vip-user"}, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("regular-user").
			Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.False(t, result.Enabled)
	})

	t.Run("should evaluate conditional rules", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("region-flag").
			WithConditionRule("region", "eq", []interface{}{"us-east"}, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("user-1").
			WithAttr("region", "us-east").
			Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.True(t, result.Enabled)
	})
}

func TestEvaluateFlag_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should return error for non-existent flag", func(t *testing.T) {
		ctx := testutils.NewEvalContextBuilder().Build()

		_, err := svc.EvaluateFlag(context.Background(), "non-existent", ctx)

		assert.Error(t, err)
	})

	t.Run("should handle empty user gracefully", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("percentage-test").
			WithPercentageRule(0, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().Build()

		result, err := svc.EvaluateFlag(context.Background(), created.Key, ctx)

		assert.NoError(t, err)
		assert.False(t, result.Enabled)
	})
}

func TestUpdateFlag_ResourceRelease(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should invalidate cache when flag is updated", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().WithKey("cache-test").Build()
		created, _ := svc.CreateFlag(context.Background(), flag)

		created.Name = "Updated Name"
		_, err := svc.UpdateFlag(context.Background(), created)

		assert.NoError(t, err)
		assert.True(t, cache.wasKeyDeleted("ff:cache-test"))
	})

	t.Run("should update flag and increment version", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().WithKey("version-test").Build()
		created, _ := svc.CreateFlag(context.Background(), flag)
		initialVersion := created.Version

		created.Description = "Updated"
		updated, err := svc.UpdateFlag(context.Background(), created)

		assert.NoError(t, err)
		assert.Equal(t, initialVersion+1, updated.Version)
	})

	t.Run("should return error when updating non-existent flag", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := &FeatureFlag{
			ID:  "non-existent-id",
			Key: "invalid",
		}

		_, err := svc.UpdateFlag(context.Background(), flag)

		assert.Error(t, err)
	})
}

func TestDeleteFlag_ResourceRelease(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should delete flag and invalidate cache", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().WithKey("delete-test").Build()
		created, _ := svc.CreateFlag(context.Background(), flag)

		err := svc.DeleteFlag(context.Background(), created.ID)

		assert.NoError(t, err)
		assert.True(t, cache.wasKeyDeleted("ff:delete-test"))

		var found FeatureFlag
		err = db.First(&found, "id = ?", created.ID).Error
		assert.Error(t, err)
	})

	t.Run("should return error when deleting non-existent flag", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		err := svc.DeleteFlag(context.Background(), "non-existent")

		assert.Error(t, err)
	})

	t.Run("should handle cache error gracefully", func(t *testing.T) {
		cache := newMockCache()
		cache.delErr = fmt.Errorf("cache connection failed")
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().WithKey("cache-error-test").Build()
		created, _ := svc.CreateFlag(context.Background(), flag)

		err := svc.DeleteFlag(context.Background(), created.ID)

		assert.NoError(t, err)

		var found FeatureFlag
		err = db.First(&found, "id = ?", created.ID).Error
		assert.Error(t, err)
	})
}

func TestUserSegment_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should create user segment", func(t *testing.T) {
		segment := testutils.NewSegmentBuilder().
			WithName("Beta Testers").
			WithUsers([]string{"user-1", "user-2"}).
			Build()

		created, err := svc.CreateSegment(context.Background(), segment)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
		assert.Equal(t, "Beta Testers", created.Name)
		assert.Len(t, created.UserIDs, 2)
	})

	t.Run("should match user in segment by ID", func(t *testing.T) {
		segment := testutils.NewSegmentBuilder().
			WithName("Manual List").
			WithUsers([]string{"matched-user"}).
			Build()

		created, _ := svc.CreateSegment(context.Background(), segment)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("matched-user").
			Build()

		matches, err := svc.checkUserInSegment(context.Background(), created, ctx)

		assert.NoError(t, err)
		assert.True(t, matches)
	})

	t.Run("should match user by rule condition", func(t *testing.T) {
		segment := testutils.NewSegmentBuilder().
			WithName("US Region").
			WithRule("region", "eq", []interface{}{"us"}).
			Build()

		created, _ := svc.CreateSegment(context.Background(), segment)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("any-user").
			WithAttr("region", "us").
			Build()

		matches, err := svc.checkUserInSegment(context.Background(), created, ctx)

		assert.NoError(t, err)
		assert.True(t, matches)
	})
}

func TestRuleConditionEvaluation(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	tests := []struct {
		name      string
		attr      string
		attrValue interface{}
		condition RuleCondition
		expected  bool
	}{
		{"eq_pass", "env", "production", RuleCondition{Attribute: "env", Operator: "eq", Values: []interface{}{"production"}}, true},
		{"eq_fail", "env", "staging", RuleCondition{Attribute: "env", Operator: "eq", Values: []interface{}{"production"}}, false},
		{"neq_pass", "env", "staging", RuleCondition{Attribute: "env", Operator: "neq", Values: []interface{}{"production"}}, true},
		{"neq_fail", "env", "production", RuleCondition{Attribute: "env", Operator: "neq", Values: []interface{}{"production"}}, false},
		{"in_pass", "role", "admin", RuleCondition{Attribute: "role", Operator: "in", Values: []interface{}{"admin", "dev"}}, true},
		{"in_fail", "role", "user", RuleCondition{Attribute: "role", Operator: "in", Values: []interface{}{"admin", "dev"}}, false},
		{"contains_pass", "email", "test@example.com", RuleCondition{Attribute: "email", Operator: "contains", Values: []interface{}{"example"}}, true},
		{"contains_fail", "email", "user@test.org", RuleCondition{Attribute: "email", Operator: "contains", Values: []interface{}{"example"}}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ctx := &EvaluationContext{
				Attributes: map[string]interface{}{tt.attr: tt.attrValue},
			}
			result := svc.evaluateCondition(ctx, tt.condition)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestPercentageRollout_Consistency(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	cache := newMockCache()
	svc := NewServiceWithDeps(db.DB, cache)

	t.Run("should be consistent for same user", func(t *testing.T) {
		flag := testutils.NewFlagBuilder().
			WithKey("consistent-test").
			WithPercentageRule(50, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		ctx := testutils.NewEvalContextBuilder().
			WithUser("consistent-user-123").
			Build()

		var firstResult bool
		for i := 0; i < 10; i++ {
			result, _ := svc.EvaluateFlag(context.Background(), created.Key, ctx)
			if i == 0 {
				firstResult = result.Enabled
			}
			assert.Equal(t, firstResult, result.Enabled, "Should be consistent across evaluations")
		}
	})
}

func TestConcurrentFlagOperations_ResourceRelease(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should release cache keys correctly during concurrent updates", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().
			WithKey("concurrent-update").
			WithPercentageRule(30, 1).
			Build()
		created, _ := svc.CreateFlag(context.Background(), flag)

		var wg sync.WaitGroup
		var errorCount int64
		var updateCount int64

		for i := 0; i < 50; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				flagCopy := *created
				flagCopy.Description = fmt.Sprintf("Update %d", idx)
				_, err := svc.UpdateFlag(context.Background(), &flagCopy)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				} else {
					atomic.AddInt64(&updateCount, 1)
				}
			}(i)
		}

		wg.Wait()

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, int64(50), atomic.LoadInt64(&updateCount))
		assert.Equal(t, 50, cache.getDeletedCount())
	})

	t.Run("should handle concurrent creates and deletes properly", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		var wg sync.WaitGroup
		var createCount int64
		var deleteCount int64
		var errorCount int64

		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				key := fmt.Sprintf("cd-flag-%d", idx)
				flag := testutils.NewFlagBuilder().
					WithKey(key).
					Build()

				created, err := svc.CreateFlag(context.Background(), flag)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
					return
				}
				atomic.AddInt64(&createCount, 1)

				err = svc.DeleteFlag(context.Background(), created.ID)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
					return
				}
				atomic.AddInt64(&deleteCount, 1)
			}(i)
		}

		wg.Wait()

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, int64(20), atomic.LoadInt64(&createCount))
		assert.Equal(t, int64(20), atomic.LoadInt64(&deleteCount))
		assert.GreaterOrEqual(t, cache.getDeletedCount(), 20)
	})
}

func TestEvaluateBulk_ResourceRelease(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should evaluate multiple flags efficiently", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		for i := 0; i < 10; i++ {
			flag := testutils.NewFlagBuilder().
				WithKey(fmt.Sprintf("bulk-flag-%d", i)).
				WithPercentageRule(float64((i%2)*100), 1).
				Build()
			_, _ = svc.CreateFlag(context.Background(), flag)
		}

		flagKeys := []string{"bulk-flag-0", "bulk-flag-1", "bulk-flag-2", "bulk-flag-3"}
		ctx := testutils.NewEvalContextBuilder().
			WithUser("bulk-user").
			Build()

		results, err := svc.EvaluateBulk(context.Background(), flagKeys, ctx)

		assert.NoError(t, err)
		assert.Len(t, results, 4)
	})

	t.Run("should handle missing flags in bulk evaluation", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().
			WithKey("existing-bulk").
			WithPercentageRule(100, 1).
			Build()
		_, _ = svc.CreateFlag(context.Background(), flag)

		flagKeys := []string{"existing-bulk", "non-existent-flag"}
		ctx := testutils.NewEvalContextBuilder().Build()

		results, err := svc.EvaluateBulk(context.Background(), flagKeys, ctx)

		assert.NoError(t, err)
		assert.Len(t, results, 2)
	})
}

func TestRolloutEvents_ResourceTracking(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should record rollout events for audit", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().
			WithKey("audit-flag").
			WithPercentageRule(50, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		var wg sync.WaitGroup
		for i := 0; i < 100; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				ctx := testutils.NewEvalContextBuilder().
					WithUser(fmt.Sprintf("audit-user-%d", idx)).
					Build()
				_, _ = svc.EvaluateFlag(context.Background(), created.Key, ctx)
			}(i)
		}
		wg.Wait()
	})

	t.Run("should record update events", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		flag := testutils.NewFlagBuilder().
			WithKey("update-audit").
			WithPercentageRule(25, 1).
			Build()

		created, _ := svc.CreateFlag(context.Background(), flag)

		created.Enabled = false
		_, err := svc.UpdateFlag(context.Background(), created)

		assert.NoError(t, err)
	})
}

func TestStressTest_FlagOperations(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should handle high concurrency with proper resource cleanup", func(t *testing.T) {
		cache := newMockCache()
		svc := NewServiceWithDeps(db.DB, cache)

		var wg sync.WaitGroup
		var totalOps int64 = 0
		start := time.Now()

		for i := 0; i < 10; i++ {
			wg.Add(4)

			go func() {
				defer wg.Done()
				for j := 0; j < 25; j++ {
					flag := testutils.NewFlagBuilder().
						WithKey(fmt.Sprintf("stress-%d", time.Now().UnixNano())).
						Build()
					_, _ = svc.CreateFlag(context.Background(), flag)
					atomic.AddInt64(&totalOps, 1)
				}
			}()

			go func() {
				defer wg.Done()
				for j := 0; j < 25; j++ {
					_, _ = svc.ListFlags(context.Background())
					atomic.AddInt64(&totalOps, 1)
				}
			}()

			go func() {
				defer wg.Done()
				flag := testutils.NewFlagBuilder().
					WithKey(fmt.Sprintf("eval-stress-%d", time.Now().UnixNano())).
					WithPercentageRule(50, 1).
					Build()
				created, _ := svc.CreateFlag(context.Background(), flag)
				for j := 0; j < 50; j++ {
					ctx := testutils.NewEvalContextBuilder().
						WithUser(fmt.Sprintf("stress-user-%d", j)).
						Build()
					_, _ = svc.EvaluateFlag(context.Background(), created.Key, ctx)
					atomic.AddInt64(&totalOps, 1)
				}
			}()

			go func() {
				defer wg.Done()
				for j := 0; j < 10; j++ {
					segment := testutils.NewSegmentBuilder().
						WithName(fmt.Sprintf("Seg %d", time.Now().UnixNano())).
						Build()
					_, _ = svc.CreateSegment(context.Background(), segment)
					atomic.AddInt64(&totalOps, 1)
				}
			}()
		}

		wg.Wait()

		elapsed := time.Since(start)
		assert.GreaterOrEqual(t, atomic.LoadInt64(&totalOps), int64(1000))
		assert.Less(t, elapsed, 30*time.Second)
	})
}
