package storage

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

func TestMockRedisStore_SetGetWindowState(t *testing.T) {
	store := NewMockRedisStore()

	type WindowState struct {
		Count    int                    `json:"count"`
		Logs     []string               `json:"logs"`
		Metadata map[string]interface{} `json:"metadata"`
	}

	state := &WindowState{
		Count:    10,
		Logs:     []string{"log1", "log2"},
		Metadata: map[string]interface{}{"key": "value"},
	}

	err := store.SetWindowState("key1", state, time.Hour)
	require.NoError(t, err)

	var result WindowState
	err = store.GetWindowState("key1", &result)
	require.NoError(t, err)
	assert.Equal(t, state.Count, result.Count)
	assert.Equal(t, state.Logs, result.Logs)
}

func TestMockRedisStore_DeleteWindowState(t *testing.T) {
	store := NewMockRedisStore()

	state := map[string]interface{}{"count": 10}
	err := store.SetWindowState("key1", state, time.Hour)
	require.NoError(t, err)

	err = store.DeleteWindowState("key1")
	require.NoError(t, err)

	var result map[string]interface{}
	err = store.GetWindowState("key1", &result)
	assert.Error(t, err)
}

func TestMockRedisStore_Deduplicate(t *testing.T) {
	store := NewMockRedisStore()

	ok, err := store.Deduplicate("alert-1", "value1", time.Hour)
	require.NoError(t, err)
	assert.True(t, ok, "first time should return true")

	ok, err = store.Deduplicate("alert-1", "value1", time.Hour)
	require.NoError(t, err)
	assert.False(t, ok, "second time should return false")
}

func TestMockRedisStore_IsDuplicate(t *testing.T) {
	store := NewMockRedisStore()

	exists, err := store.IsDuplicate("alert-1")
	require.NoError(t, err)
	assert.False(t, exists)

	store.Deduplicate("alert-1", "value1", time.Hour)

	exists, err = store.IsDuplicate("alert-1")
	require.NoError(t, err)
	assert.True(t, exists)
}

func TestMockRedisStore_CacheGetLog(t *testing.T) {
	store := NewMockRedisStore()

	entry := testfixtures.NewLogEntry()
	err := store.CacheLog(entry, time.Hour)
	require.NoError(t, err)

	result, err := store.GetLog(entry.ID)
	require.NoError(t, err)
	assert.Equal(t, entry.ID, result.ID)
	assert.Equal(t, entry.Message, result.Message)
}

func TestMockRedisStore_IncrementCounter(t *testing.T) {
	store := NewMockRedisStore()

	count, err := store.IncrementCounter("counter1")
	require.NoError(t, err)
	assert.Equal(t, int64(1), count)

	count, err = store.IncrementCounter("counter1")
	require.NoError(t, err)
	assert.Equal(t, int64(2), count)
}

func TestMockRedisStore_GetSetCounter(t *testing.T) {
	store := NewMockRedisStore()

	count, err := store.GetCounter("counter1")
	require.NoError(t, err)
	assert.Equal(t, int64(0), count)

	err = store.SetCounter("counter1", 100, time.Hour)
	require.NoError(t, err)

	count, err = store.GetCounter("counter1")
	require.NoError(t, err)
	assert.Equal(t, int64(100), count)
}

func TestMockRedisStore_AddToSet_IsMember(t *testing.T) {
	store := NewMockRedisStore()

	err := store.AddToSet("set1", "member1", "member2")
	require.NoError(t, err)

	exists, err := store.IsMember("set1", "member1")
	require.NoError(t, err)
	assert.True(t, exists)

	exists, err = store.IsMember("set1", "member3")
	require.NoError(t, err)
	assert.False(t, exists)
}

func TestMockRedisStore_LPush_RPop_LLen(t *testing.T) {
	store := NewMockRedisStore()

	err := store.LPush("list1", "value1", "value2", "value3")
	require.NoError(t, err)

	llen, err := store.LLen("list1")
	require.NoError(t, err)
	assert.Equal(t, int64(3), llen)

	val, err := store.RPop("list1")
	require.NoError(t, err)
	assert.Equal(t, "value1", val)

	val, err = store.RPop("list1")
	require.NoError(t, err)
	assert.Equal(t, "value2", val)

	llen, err = store.LLen("list1")
	require.NoError(t, err)
	assert.Equal(t, int64(1), llen)
}

func TestMockRedisStore_SetGetDelete(t *testing.T) {
	store := NewMockRedisStore()

	err := store.SetWithTTL("key1", "value1", time.Hour)
	require.NoError(t, err)

	val, err := store.Get("key1")
	require.NoError(t, err)
	assert.Equal(t, "value1", val)

	err = store.Delete("key1")
	require.NoError(t, err)

	_, err = store.Get("key1")
	assert.Error(t, err)
}

func TestMockRedisStore_Keys(t *testing.T) {
	store := NewMockRedisStore()

	store.SetWithTTL("prefix:1", "value1", time.Hour)
	store.SetWithTTL("prefix:2", "value2", time.Hour)
	store.SetWithTTL("other:1", "value3", time.Hour)

	keys, err := store.Keys("prefix:*")
	require.NoError(t, err)
	assert.Len(t, keys, 3)
}

func TestMockRedisStore_ConcurrentWindowState(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numGoroutines := 50
	numOps := 20

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				key := "key-" + string(rune(idx))
				state := map[string]interface{}{"count": j}
				store.SetWindowState(key, state, time.Hour)

				var result map[string]interface{}
				store.GetWindowState(key, &result)
			}
		}(i)
	}

	wg.Wait()
}

func TestMockRedisStore_ConcurrentDeduplicate(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numGoroutines := 100

	successCount := 0
	var mu sync.Mutex

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			ok, _ := store.Deduplicate("same-key", "value", time.Hour)
			if ok {
				mu.Lock()
				successCount++
				mu.Unlock()
			}
		}(i)
	}

	wg.Wait()

	assert.Equal(t, 1, successCount, "only one goroutine should succeed")
}

func TestMockRedisStore_ConcurrentCounter(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numGoroutines := 50
	numOps := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				store.IncrementCounter("counter1")
			}
		}()
	}

	wg.Wait()

	count, err := store.GetCounter("counter1")
	require.NoError(t, err)
	assert.Equal(t, int64(numGoroutines*numOps), count)
}

func TestMockRedisStore_ConcurrentReadWrite(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numWriters := 30
	numReaders := 30
	numOps := 50

	for i := 0; i < numWriters; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				key := "key-" + string(rune(idx))
				store.SetWithTTL(key, "value", time.Hour)
				store.IncrementCounter("counter-" + string(rune(idx)))
				store.AddToSet("set-"+string(rune(idx)), "member")
			}
		}(i)
	}

	for i := 0; i < numReaders; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numOps; j++ {
				key := "key-" + string(rune(idx%numWriters))
				store.Get(key)
				store.GetCounter("counter-" + string(rune(idx%numWriters)))
				store.IsMember("set-"+string(rune(idx%numWriters)), "member")
			}
		}(i)
	}

	wg.Wait()
}

func TestMockRedisStore_Clear(t *testing.T) {
	store := NewMockRedisStore()

	store.SetWithTTL("key1", "value1", time.Hour)
	store.SetWindowState("win1", map[string]interface{}{}, time.Hour)
	store.Deduplicate("dedup1", "value", time.Hour)
	store.IncrementCounter("counter1")
	store.AddToSet("set1", "member")
	store.LPush("list1", "value")

	store.Clear()

	_, err := store.Get("key1")
	assert.Error(t, err)

	var state map[string]interface{}
	err = store.GetWindowState("win1", &state)
	assert.Error(t, err)

	exists, _ := store.IsDuplicate("dedup1")
	assert.False(t, exists)

	count, _ := store.GetCounter("counter1")
	assert.Equal(t, int64(0), count)
}

func TestMockRedisStore_CacheLog_Concurrent(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numGoroutines := 50

	logs := make([]*models.LogEntry, numGoroutines)
	for i := 0; i < numGoroutines; i++ {
		logs[i] = testfixtures.NewLogEntry()
	}

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			store.CacheLog(logs[idx], time.Hour)
		}(i)
	}

	wg.Wait()

	for i := 0; i < numGoroutines; i++ {
		result, err := store.GetLog(logs[i].ID)
		require.NoError(t, err)
		assert.Equal(t, logs[i].ID, result.ID)
	}
}

func TestMockRedisStore_SetOperations_Concurrent(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numGoroutines := 50
	numMembers := 100

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numMembers; j++ {
				member := "member-" + string(rune(idx)) + "-" + string(rune(j))
				store.AddToSet("set1", member)
			}
		}(i)
	}

	wg.Wait()

	for i := 0; i < numGoroutines; i++ {
		for j := 0; j < numMembers; j++ {
			member := "member-" + string(rune(i)) + "-" + string(rune(j))
			exists, err := store.IsMember("set1", member)
			require.NoError(t, err)
			assert.True(t, exists, "member should exist: %s", member)
		}
	}
}

func TestMockRedisStore_ListOperations_Concurrent(t *testing.T) {
	store := NewMockRedisStore()

	var wg sync.WaitGroup
	numPushers := 20
	numPopers := 10
	numItems := 100

	totalPushed := 0
	var mu sync.Mutex

	for i := 0; i < numPushers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < numItems; j++ {
				item := "item-" + string(rune(idx)) + "-" + string(rune(j))
				store.LPush("list1", item)
				mu.Lock()
				totalPushed++
				mu.Unlock()
			}
		}(i)
	}

	wg.Wait()

	llen, err := store.LLen("list1")
	require.NoError(t, err)
	assert.Equal(t, int64(totalPushed), llen)

	totalPopped := 0
	for i := 0; i < numPopers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				_, err := store.RPop("list1")
				if err != nil {
					return
				}
				mu.Lock()
				totalPopped++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	assert.Equal(t, totalPushed, totalPopped)
}
