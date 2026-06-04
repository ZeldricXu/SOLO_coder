package windowing

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"log-pipeline/pkg/models"
)

func TestNewWindowStateStore(t *testing.T) {
	store := NewWindowStateStore()
	assert.NotNil(t, store)
	assert.Equal(t, 0, store.SlidingWindowCount())
	assert.Equal(t, 0, store.SessionCount())
}

func TestWindowStateStore_GetOrCreateSlidingWindow_New(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	window := store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "test-key")

	assert.NotNil(t, window)
	assert.Equal(t, "test-key", window.Key)
	assert.Equal(t, now, window.Start)
	assert.Equal(t, now.Add(time.Minute), window.End)
	assert.Equal(t, 1, store.SlidingWindowCount())
}

func TestWindowStateStore_GetOrCreateSlidingWindow_Existing(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	w1 := store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "test-key")
	w2 := store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "test-key")

	assert.Same(t, w1, w2, "should return same window for same key")
	assert.Equal(t, 1, store.SlidingWindowCount())
}

func TestWindowStateStore_GetOrCreateSession_New(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	session := store.GetOrCreateSession("session1", "test-key", now)

	assert.NotNil(t, session)
	assert.Equal(t, "test-key", session.SessionKey)
	assert.Equal(t, 1, store.SessionCount())
}

func TestWindowStateStore_GetOrCreateSession_Existing(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	s1 := store.GetOrCreateSession("session1", "test-key", now)
	s2 := store.GetOrCreateSession("session1", "test-key", now)

	assert.Same(t, s1, s2)
	assert.Equal(t, 1, store.SessionCount())
}

func TestWindowStateStore_AddLogToSlidingWindow(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "test-key")

	log := &models.LogEntry{ID: "1", Message: "test"}
	store.AddLogToSlidingWindow("key1", log, "INFO")

	w, ok := store.GetSlidingWindow("key1")
	assert.True(t, ok)
	assert.Equal(t, int64(1), w.Count)
	assert.Equal(t, int64(1), w.LevelCount["INFO"])
}

func TestWindowStateStore_AddLogToSession(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSession("session1", "test-key", now)

	log := &models.LogEntry{ID: "1", Message: "test", Timestamp: now.Add(time.Second)}
	store.AddLogToSession("session1", log)

	s, ok := store.GetSession("session1")
	assert.True(t, ok)
	assert.Equal(t, int64(1), s.Count)
}

func TestWindowStateStore_FlushExpiredSlidingWindows(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	past := now.Add(-time.Hour)

	store.GetOrCreateSlidingWindow("expired", past, past.Add(time.Second), "key1")
	store.GetOrCreateSlidingWindow("active", now, now.Add(time.Minute), "key2")

	expired := store.FlushExpiredSlidingWindows(now)

	assert.Len(t, expired, 1)
	assert.Equal(t, "key1", expired[0].Key)
	assert.Equal(t, 1, store.SlidingWindowCount())
}

func TestWindowStateStore_FlushExpiredSessions(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	past := now.Add(-time.Hour)

	sess := store.GetOrCreateSession("expired", "key1", past)
	sess.LastActive = past

	store.GetOrCreateSession("active", "key2", now)

	expired := store.FlushExpiredSessions(time.Minute, now)

	assert.Len(t, expired, 1)
	assert.Equal(t, "key1", expired[0].SessionKey)
	assert.Equal(t, 1, store.SessionCount())
}

func TestWindowStateStore_DeleteSlidingWindow(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "test-key")
	assert.Equal(t, 1, store.SlidingWindowCount())

	store.DeleteSlidingWindow("key1")
	assert.Equal(t, 0, store.SlidingWindowCount())
}

func TestWindowStateStore_DeleteSession(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSession("session1", "test-key", now)
	assert.Equal(t, 1, store.SessionCount())

	store.DeleteSession("session1")
	assert.Equal(t, 0, store.SessionCount())
}

func TestWindowStateStore_AllSlidingWindows(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "a")
	store.GetOrCreateSlidingWindow("key2", now, now.Add(time.Minute), "b")

	all := store.AllSlidingWindows()
	assert.Len(t, all, 2)
}

func TestWindowStateStore_AllSessions(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	store.GetOrCreateSession("session1", "a", now)
	store.GetOrCreateSession("session2", "b", now)

	all := store.AllSessions()
	assert.Len(t, all, 2)
}

func TestWindowStateStore_Count401InWindows(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	windowStart := now.Add(-time.Hour)

	store.GetOrCreateSlidingWindow("key1", now, now.Add(time.Minute), "10.0.0.1")
	log := &models.LogEntry{ID: "1", Message: "401 Unauthorized", Level: "WARN"}
	store.AddLogToSlidingWindow("key1", log, "WARN")

	count := store.Count401InWindows("10.0.0.1", windowStart, func(msg string) bool {
		return strings.Contains(msg, "401")
	})
	assert.Equal(t, int64(1), count)

	count = store.Count401InWindows("10.0.0.2", windowStart, func(msg string) bool {
		return strings.Contains(msg, "401")
	})
	assert.Equal(t, int64(0), count, "should return 0 for non-matching IP")
}

func TestWindowStateStore_ConcurrentAccess(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	var wg sync.WaitGroup

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			key := fmt.Sprintf("key-%d", id)
			windowKey := "w:" + key
			store.GetOrCreateSlidingWindow(windowKey, now, now.Add(time.Minute), key)
			log := &models.LogEntry{ID: "1"}
			store.AddLogToSlidingWindow(windowKey, log, "INFO")
		}(i)
	}

	wg.Wait()
	assert.Equal(t, 100, store.SlidingWindowCount())
}

func TestWindowStateStore_ConcurrentSessions(t *testing.T) {
	store := NewWindowStateStore()

	now := time.Now()
	var wg sync.WaitGroup

	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			key := fmt.Sprintf("key-%d", id)
			sessionKey := "s:" + key
			store.GetOrCreateSession(sessionKey, key, now)
			log := &models.LogEntry{ID: "1"}
			store.AddLogToSession(sessionKey, log)
		}(i)
	}

	wg.Wait()
	assert.Equal(t, 100, store.SessionCount())
}

func TestEventTimeTrigger_ShouldTrigger(t *testing.T) {
	trigger := NewEventTimeTrigger(time.Minute)

	now := time.Now()
	expiredWindow := &SlidingWindow{End: now.Add(-time.Second)}
	activeWindow := &SlidingWindow{End: now.Add(time.Minute)}

	assert.True(t, trigger.ShouldTrigger(expiredWindow, now))
	assert.False(t, trigger.ShouldTrigger(activeWindow, now))
}

func TestEventTimeTrigger_Name(t *testing.T) {
	trigger := NewEventTimeTrigger(time.Minute)
	assert.Equal(t, "event_time", trigger.Name())
}

func TestProcessingTimeTrigger_ShouldTrigger(t *testing.T) {
	trigger := NewProcessingTimeTrigger(time.Minute)

	now := time.Now()
	oldWindow := &SlidingWindow{Start: now.Add(-time.Hour)}
	recentWindow := &SlidingWindow{Start: now.Add(-time.Second)}

	assert.True(t, trigger.ShouldTrigger(oldWindow, now))
	assert.False(t, trigger.ShouldTrigger(recentWindow, now))
}

func TestProcessingTimeTrigger_Name(t *testing.T) {
	trigger := NewProcessingTimeTrigger(time.Minute)
	assert.Equal(t, "processing_time", trigger.Name())
}

func TestWatermarkTrigger_ShouldTrigger(t *testing.T) {
	trigger := NewWatermarkTrigger(time.Minute)

	now := time.Now()
	expiredWindow := &SlidingWindow{End: now.Add(-2 * time.Minute)}
	activeWindow := &SlidingWindow{End: now.Add(-time.Second)}

	assert.True(t, trigger.ShouldTrigger(expiredWindow, now))
	assert.False(t, trigger.ShouldTrigger(activeWindow, now))
}

func TestWatermarkTrigger_Name(t *testing.T) {
	trigger := NewWatermarkTrigger(time.Minute)
	assert.Equal(t, "watermark", trigger.Name())
}

func TestWatermarkTrigger_UpdateWatermark(t *testing.T) {
	trigger := NewWatermarkTrigger(time.Minute)

	ts := time.Now().Add(-time.Minute)
	trigger.UpdateWatermark(ts)
	assert.Equal(t, ts, trigger.Watermark())

	newTS := time.Now()
	trigger.UpdateWatermark(newTS)
	assert.Equal(t, newTS, trigger.Watermark(), "watermark should only move forward")
}

func TestWatermarkTrigger_WatermarkOnlyMovesForward(t *testing.T) {
	trigger := NewWatermarkTrigger(time.Minute)

	now := time.Now()
	trigger.UpdateWatermark(now)

	past := now.Add(-time.Hour)
	trigger.UpdateWatermark(past)
	assert.Equal(t, now, trigger.Watermark(), "watermark should not move backward")
}

func TestCompositeTrigger(t *testing.T) {
	eventTrigger := NewEventTimeTrigger(time.Minute)
	processingTrigger := NewProcessingTimeTrigger(time.Minute)
	composite := NewCompositeTrigger(eventTrigger, processingTrigger)

	now := time.Now()
	expiredWindow := &SlidingWindow{Start: now.Add(-2 * time.Hour), End: now.Add(-time.Second)}

	assert.True(t, composite.ShouldTrigger(expiredWindow, now))
	assert.Equal(t, "composite", composite.Name())
}

func TestCompositeTrigger_NoneTriggered(t *testing.T) {
	eventTrigger := NewEventTimeTrigger(time.Minute)
	processingTrigger := NewProcessingTimeTrigger(time.Hour)
	composite := NewCompositeTrigger(eventTrigger, processingTrigger)

	now := time.Now()
	activeWindow := &SlidingWindow{Start: now.Add(-time.Second), End: now.Add(time.Minute)}

	assert.False(t, composite.ShouldTrigger(activeWindow, now))
}

func TestDefaultTriggerPolicy(t *testing.T) {
	strategy := NewFixedWindowStrategy(time.Minute, time.Second*10)
	trigger := DefaultTriggerPolicy(strategy)

	_, ok := trigger.(*EventTimeTrigger)
	assert.True(t, ok, "default should be EventTimeTrigger")
}

func TestWindowEngine_WithTriggerPolicy(t *testing.T) {
	cfg := testWindowingConfig()
	strategy := NewFixedWindowStrategy(time.Minute, time.Second*10)
	trigger := NewProcessingTimeTrigger(time.Minute)

	we := NewWindowEngineWithTrigger(cfg, strategy, trigger)

	assert.Equal(t, trigger, we.GetTriggerPolicy())
	assert.Equal(t, "processing_time", we.GetTriggerPolicy().Name())
}

func TestWindowEngine_SetTriggerPolicy(t *testing.T) {
	cfg := testWindowingConfig()
	we := NewWindowEngine(cfg)

	assert.Equal(t, "event_time", we.GetTriggerPolicy().Name())

	we.SetTriggerPolicy(NewProcessingTimeTrigger(time.Minute))
	assert.Equal(t, "processing_time", we.GetTriggerPolicy().Name())
}
