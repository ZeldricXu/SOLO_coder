package windowing

import (
	"fmt"
	"sync"
	"time"

	"log-pipeline/pkg/models"
	"log-pipeline/pkg/utils"
)

const shardCount = 32

type windowShard struct {
	mu      sync.RWMutex
	windows map[string]*SlidingWindow
}

type sessionShard struct {
	mu       sync.RWMutex
	sessions map[string]*SessionWindow
}

type WindowStateStore struct {
	windowShards [shardCount]*windowShard
	sessionShards [shardCount]*sessionShard
}

func NewWindowStateStore() *WindowStateStore {
	s := &WindowStateStore{}
	for i := 0; i < shardCount; i++ {
		s.windowShards[i] = &windowShard{windows: make(map[string]*SlidingWindow)}
		s.sessionShards[i] = &sessionShard{sessions: make(map[string]*SessionWindow)}
	}
	return s
}

func windowShardIndex(key string) int {
	hash := 0
	for _, c := range key {
		hash = hash*31 + int(c)
	}
	return int(uint(hash) % uint(shardCount))
}

func (s *WindowStateStore) GetOrCreateSlidingWindow(windowKey string, windowStart time.Time, windowEnd time.Time, key string) *SlidingWindow {
	idx := windowShardIndex(windowKey)
	shard := s.windowShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()

	window, exists := shard.windows[windowKey]
	if !exists {
		window = &SlidingWindow{
			ID:         utils.GenerateID(),
			Start:      windowStart,
			End:        windowEnd,
			Key:        key,
			Logs:       make([]*models.LogEntry, 0),
			LevelCount: make(map[string]int64),
		}
		shard.windows[windowKey] = window
	}

	return window
}

func (s *WindowStateStore) GetOrCreateSession(sessionKey string, key string, timestamp time.Time) *SessionWindow {
	idx := windowShardIndex(sessionKey)
	shard := s.sessionShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()

	session, exists := shard.sessions[sessionKey]
	if !exists {
		session = &SessionWindow{
			ID:         utils.GenerateID(),
			SessionKey: key,
			LastActive: timestamp,
			Logs:       make([]*models.LogEntry, 0),
		}
		shard.sessions[sessionKey] = session
	}

	return session
}

func (s *WindowStateStore) AddLogToSlidingWindow(windowKey string, log *models.LogEntry, level string) {
	idx := windowShardIndex(windowKey)
	shard := s.windowShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()

	window, exists := shard.windows[windowKey]
	if !exists {
		return
	}

	window.Logs = append(window.Logs, log)
	window.Count++
	window.LevelCount[level]++
}

func (s *WindowStateStore) AddLogToSession(sessionKey string, log *models.LogEntry) {
	idx := windowShardIndex(sessionKey)
	shard := s.sessionShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()

	session, exists := shard.sessions[sessionKey]
	if !exists {
		return
	}

	session.Logs = append(session.Logs, log)
	session.Count++
	session.LastActive = log.Timestamp
}

func (s *WindowStateStore) FlushExpiredSlidingWindows(now time.Time) []*SlidingWindow {
	var expired []*SlidingWindow
	var toDelete []struct {
		idx int
		key string
	}

	for i, shard := range s.windowShards {
		shard.mu.RLock()
		for key, window := range shard.windows {
			if window.End.Before(now) {
				expired = append(expired, window)
				toDelete = append(toDelete, struct {
					idx int
					key string
				}{i, key})
			}
		}
		shard.mu.RUnlock()
	}

	for _, td := range toDelete {
		shard := s.windowShards[td.idx]
		shard.mu.Lock()
		delete(shard.windows, td.key)
		shard.mu.Unlock()
	}

	return expired
}

func (s *WindowStateStore) FlushExpiredSessions(timeout time.Duration, now time.Time) []*SessionWindow {
	var expired []*SessionWindow
	var toDelete []struct {
		idx int
		key string
	}

	for i, shard := range s.sessionShards {
		shard.mu.RLock()
		for key, session := range shard.sessions {
			if now.Sub(session.LastActive) > timeout {
				expired = append(expired, session)
				toDelete = append(toDelete, struct {
					idx int
					key string
				}{i, key})
			}
		}
		shard.mu.RUnlock()
	}

	for _, td := range toDelete {
		shard := s.sessionShards[td.idx]
		shard.mu.Lock()
		delete(shard.sessions, td.key)
		shard.mu.Unlock()
	}

	return expired
}

func (s *WindowStateStore) Count401InWindows(ip string, windowStart time.Time, pattern func(string) bool) int64 {
	count := int64(0)
	for _, shard := range s.windowShards {
		shard.mu.RLock()
		for _, window := range shard.windows {
			if window.Key == ip && window.Start.After(windowStart) {
				for _, l := range window.Logs {
					if pattern(l.Message) {
						count++
					}
				}
			}
		}
		shard.mu.RUnlock()
	}
	return count
}

func (s *WindowStateStore) DeleteSlidingWindow(key string) {
	idx := windowShardIndex(key)
	shard := s.windowShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()
	delete(shard.windows, key)
}

func (s *WindowStateStore) DeleteSession(key string) {
	idx := windowShardIndex(key)
	shard := s.sessionShards[idx]
	shard.mu.Lock()
	defer shard.mu.Unlock()
	delete(shard.sessions, key)
}

func (s *WindowStateStore) SlidingWindowCount() int {
	count := 0
	for _, shard := range s.windowShards {
		shard.mu.RLock()
		count += len(shard.windows)
		shard.mu.RUnlock()
	}
	return count
}

func (s *WindowStateStore) SessionCount() int {
	count := 0
	for _, shard := range s.sessionShards {
		shard.mu.RLock()
		count += len(shard.sessions)
		shard.mu.RUnlock()
	}
	return count
}

func (s *WindowStateStore) GetSlidingWindow(key string) (*SlidingWindow, bool) {
	idx := windowShardIndex(key)
	shard := s.windowShards[idx]
	shard.mu.RLock()
	defer shard.mu.RUnlock()

	w, ok := shard.windows[key]
	return w, ok
}

func (s *WindowStateStore) GetSession(key string) (*SessionWindow, bool) {
	idx := windowShardIndex(key)
	shard := s.sessionShards[idx]
	shard.mu.RLock()
	defer shard.mu.RUnlock()

	sess, ok := shard.sessions[key]
	return sess, ok
}

func (s *WindowStateStore) AllSlidingWindows() map[string]*SlidingWindow {
	result := make(map[string]*SlidingWindow)
	for _, shard := range s.windowShards {
		shard.mu.RLock()
		for k, v := range shard.windows {
			result[k] = v
		}
		shard.mu.RUnlock()
	}
	return result
}

func (s *WindowStateStore) AllSessions() map[string]*SessionWindow {
	result := make(map[string]*SessionWindow)
	for _, shard := range s.sessionShards {
		shard.mu.RLock()
		for k, v := range shard.sessions {
			result[k] = v
		}
		shard.mu.RUnlock()
	}
	return result
}

func makeSlidingWindowKey(key string, windowStartUnix int64, windowSizeStr string) string {
	return fmt.Sprintf("sliding:%s:%d:%s", key, windowStartUnix, windowSizeStr)
}

func makeSessionKey(key string) string {
	return fmt.Sprintf("session:%s", key)
}
