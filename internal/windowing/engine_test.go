package windowing

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"
)

func testWindowingConfig() *config.WindowingConfig {
	return &config.WindowingConfig{
		SlidingWindowSize: time.Minute,
		SlidingStep:       time.Second * 10,
		SessionTimeout:    time.Minute * 5,
		Error401Threshold: 5,
		RedisTTL:          time.Hour,
	}
}

func TestNewWindowEngine(t *testing.T) {
	cfg := testWindowingConfig()
	we := NewWindowEngine(cfg)

	assert.NotNil(t, we)
	assert.NotNil(t, we.stateStore)
	assert.NotNil(t, we.aggChan)
	assert.NotNil(t, we.alertChan)
	assert.NotNil(t, we.ip401Pattern)
	assert.Equal(t, 1000, cap(we.aggChan))
	assert.Equal(t, 100, cap(we.alertChan))
}

func TestExtractKey_ClientIP(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	key := we.ExtractKey(log)
	assert.Equal(t, "10.0.0.1", key)
}

func TestExtractKey_RemoteAddr(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Fields = map[string]string{"remote_addr": "192.168.1.100"}
	})

	key := we.ExtractKey(log)
	assert.Equal(t, "192.168.1.100", key)
}

func TestExtractKey_Host(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Host = "host-01"
		e.Fields = map[string]string{}
	})

	key := we.ExtractKey(log)
	assert.Equal(t, "host-01", key)
}

func TestProcessLog_AddToSlidingWindow(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC)
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		e.Level = "ERROR"
	})

	we.ProcessLog(log)

	allWindows := we.stateStore.AllSlidingWindows()
	assert.Len(t, allWindows, 1)

	for _, window := range allWindows {
		assert.Equal(t, "10.0.0.1", window.Key)
		assert.Equal(t, int64(1), window.Count)
		assert.Equal(t, int64(1), window.LevelCount["ERROR"])
		assert.Len(t, window.Logs, 1)
	}
}

func TestProcessLog_AddToSession(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC)
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	we.ProcessLog(log)
	we.ProcessLog(log)

	allSessions := we.stateStore.AllSessions()
	assert.Len(t, allSessions, 1)

	for _, session := range allSessions {
		assert.Equal(t, "10.0.0.1", session.SessionKey)
		assert.Equal(t, int64(2), session.Count)
		assert.Len(t, session.Logs, 2)
	}
}

func TestProcessLog_EmptyLevel(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC)
		e.Level = ""
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	we.ProcessLog(log)

	for _, window := range we.stateStore.AllSlidingWindows() {
		assert.Equal(t, int64(1), window.LevelCount["INFO"])
	}
}

func TestCheck401Error_ThresholdReached(t *testing.T) {
	cfg := testWindowingConfig()
	cfg.Error401Threshold = 3
	cfg.SlidingWindowSize = time.Hour * 24
	we := NewWindowEngine(cfg)

	ip := "10.0.0.5"
	baseTime := time.Now()

	for i := 0; i < 5; i++ {
		log := testfixtures.New401LogEntry(ip, func(e *models.LogEntry) {
			e.Timestamp = baseTime.Add(time.Duration(i) * time.Millisecond * 10)
		})
		we.ProcessLog(log)
	}

	count := int64(0)
	for _, window := range we.stateStore.AllSlidingWindows() {
		if window.Key == ip {
			for _, l := range window.Logs {
				if strings.Contains(l.Message, "401") {
					count++
				}
			}
		}
	}
	assert.Equal(t, int64(5), count)

	select {
	case alert := <-we.Alerts():
		assert.Equal(t, "auth_failure_401", alert.AlertType)
		assert.Equal(t, ip, alert.SourceIP)
		assert.GreaterOrEqual(t, alert.Count, int64(3))
		assert.Contains(t, alert.Title, ip)
	case <-time.After(time.Second * 2):
		t.Fatal("timeout waiting for 401 alert")
	}
}

func TestCheck401Error_ThresholdNotReached(t *testing.T) {
	cfg := testWindowingConfig()
	cfg.Error401Threshold = 10
	we := NewWindowEngine(cfg)

	ip := "10.0.0.6"
	for i := 0; i < 3; i++ {
		log := testfixtures.New401LogEntry(ip, func(e *models.LogEntry) {
			e.Timestamp = time.Now()
		})
		we.ProcessLog(log)
	}

	select {
	case <-we.Alerts():
		t.Fatal("should not have alert for threshold not reached")
	case <-time.After(time.Millisecond * 100):
	}
}

func TestCheck401Error_IPExtraction(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	tests := []struct {
		name    string
		message string
		wantIP  string
	}{
		{"ip_before_401", "192.168.1.100 - - [02/Jun/2025:12:00:00 +0000] \"GET /api\" 401 123", "192.168.1.100"},
		{"ip_after_401", "Server returned 401 for request from 10.0.0.1", "10.0.0.1"},
		{"no_ip", "401 Unauthorized access", "127.0.0.1"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
				e.Message = tt.message
				e.Timestamp = time.Now()
				e.Host = "127.0.0.1"
				e.Fields = map[string]string{}
			})

			matches := we.ip401Pattern.FindStringSubmatch(log.Message)
			var ip string
			if len(matches) > 1 {
				ip = matches[1]
				if ip == "" && len(matches) > 2 {
					ip = matches[2]
				}
			}
			if ip == "" {
				ip = we.ExtractKey(log)
			}

			assert.Equal(t, tt.wantIP, ip)
		})
	}
}

func TestEmitWindows_ExpiredWindow(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	now := time.Now()
	oldTime := now.Add(-time.Hour)

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = oldTime
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		e.Level = "ERROR"
	})

	we.ProcessLog(log)

	for _, window := range we.stateStore.AllSlidingWindows() {
		window.End = oldTime.Add(time.Second)
	}

	we.EmitWindows()

	select {
	case agg := <-we.Aggregates():
		assert.Equal(t, "sliding", agg.WindowType)
		assert.Equal(t, "10.0.0.1", agg.Key)
		assert.Equal(t, int64(1), agg.Count)
		assert.Equal(t, int64(1), agg.LevelCounts["ERROR"])
		assert.NotNil(t, agg.LogSamples)
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for aggregate")
	}

	assert.Equal(t, 0, we.stateStore.SlidingWindowCount())
}

func TestEmitWindows_NonExpiredWindow(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Now()
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	we.ProcessLog(log)
	we.EmitWindows()

	select {
	case <-we.Aggregates():
		t.Fatal("should not emit non-expired window")
	case <-time.After(time.Millisecond * 100):
	}

	assert.Equal(t, 1, we.stateStore.SlidingWindowCount())
}

func TestEmitWindows_ChannelFull(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	for i := 0; i < cap(we.aggChan)+10; i++ {
		log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = time.Now().Add(-time.Hour)
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		})
		we.ProcessLog(log)
	}

	oldTime := time.Now().Add(-time.Hour)
	for _, window := range we.stateStore.AllSlidingWindows() {
		window.End = oldTime.Add(time.Second)
	}

	assert.NotPanics(t, func() {
		we.EmitWindows()
	})
}

func TestCleanupSessions_Expired(t *testing.T) {
	cfg := testWindowingConfig()
	cfg.SessionTimeout = time.Millisecond * 50
	we := NewWindowEngine(cfg)

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Now()
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		e.Level = "INFO"
	})

	we.ProcessLog(log)

	for _, session := range we.stateStore.AllSessions() {
		session.LastActive = time.Now().Add(-time.Second)
	}

	time.Sleep(time.Millisecond * 60)

	we.CleanupSessions()

	select {
	case agg := <-we.Aggregates():
		assert.Equal(t, "session", agg.WindowType)
		assert.Equal(t, "10.0.0.1", agg.Key)
		assert.Equal(t, int64(1), agg.Count)
		assert.Equal(t, int64(1), agg.LevelCounts["INFO"])
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for session aggregate")
	}

	assert.Equal(t, 0, we.stateStore.SessionCount())
}

func TestCleanupSessions_NotExpired(t *testing.T) {
	cfg := testWindowingConfig()
	cfg.SessionTimeout = time.Hour
	we := NewWindowEngine(cfg)

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Now()
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	we.ProcessLog(log)
	we.CleanupSessions()

	select {
	case <-we.Aggregates():
		t.Fatal("should not emit non-expired session")
	case <-time.After(time.Millisecond * 100):
	}

	assert.Equal(t, 1, we.stateStore.SessionCount())
}

func TestOutOfOrderTimestamps(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	baseTime := time.Date(2025, 6, 2, 12, 0, 30, 0, time.UTC)

	logs := []*models.LogEntry{
		testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = baseTime.Add(time.Second * 5)
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		}),
		testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = baseTime.Add(-time.Second * 5)
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		}),
		testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = baseTime
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		}),
	}

	for _, log := range logs {
		we.ProcessLog(log)
	}

	totalCount := int64(0)
	for _, window := range we.stateStore.AllSlidingWindows() {
		totalCount += window.Count
	}

	assert.Equal(t, int64(3), totalCount)
}

func TestWindowBoundary_NoDuplicates(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	windowStart := time.Date(2025, 6, 2, 12, 0, 0, 0, time.UTC)
	windowEnd := windowStart.Add(time.Minute)

	logs := []*models.LogEntry{
		testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = windowStart.Add(time.Second * 30)
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		}),
		testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = windowEnd.Add(time.Second * 5)
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		}),
	}

	for _, log := range logs {
		we.ProcessLog(log)
	}

	allWindows := we.stateStore.AllSlidingWindows()
	assert.Len(t, allWindows, 2)

	var counts []int64
	for _, window := range allWindows {
		counts = append(counts, window.Count)
	}

	assert.Contains(t, counts, int64(1))
	assert.Contains(t, counts, int64(1))
}

func TestConcurrentProcessLog_SameKey(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	var wg sync.WaitGroup
	goroutines := 20
	logsPerGoroutine := 50

	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for n := 0; n < logsPerGoroutine; n++ {
				log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Timestamp = time.Now()
					e.Fields = map[string]string{"client_ip": "10.0.0.1"}
					e.Level = "INFO"
				})
				we.ProcessLog(log)
			}
		}(g)
	}

	wg.Wait()

	totalCount := int64(0)
	for _, window := range we.stateStore.AllSlidingWindows() {
		totalCount += window.Count
	}

	assert.Equal(t, int64(goroutines*logsPerGoroutine), totalCount)
}

func TestConcurrentProcessLog_DifferentKeys(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	var wg sync.WaitGroup
	goroutines := 30

	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			ip := "10.0.0." + string(rune('1'+id))
			log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
				e.Timestamp = time.Now()
				e.Fields = map[string]string{"client_ip": ip}
			})
			we.ProcessLog(log)
		}(g)
	}

	wg.Wait()

	assert.GreaterOrEqual(t, we.stateStore.SlidingWindowCount(), 1)
}

func TestSessionTimeout_Precision(t *testing.T) {
	cfg := testWindowingConfig()
	cfg.SessionTimeout = time.Millisecond * 100
	we := NewWindowEngine(cfg)

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Now()
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	we.ProcessLog(log)

	time.Sleep(time.Millisecond * 50)
	we.CleanupSessions()

	assert.Equal(t, 1, we.stateStore.SessionCount())

	time.Sleep(time.Millisecond * 100)
	we.CleanupSessions()

	select {
	case <-we.Aggregates():
	case <-time.After(time.Second):
		t.Fatal("session should expire")
	}

	assert.Equal(t, 0, we.stateStore.SessionCount())
}

func TestMultipleKeys_SlidingWindows(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	ips := []string{"10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5"}

	for _, ip := range ips {
		for j := 0; j < 3; j++ {
			log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
				e.Timestamp = time.Now()
				e.Fields = map[string]string{"client_ip": ip}
			})
			we.ProcessLog(log)
		}
	}

	keyCounts := make(map[string]int64)
	for _, window := range we.stateStore.AllSlidingWindows() {
		keyCounts[window.Key] += window.Count
	}

	for _, ip := range ips {
		assert.Equal(t, int64(3), keyCounts[ip], "IP %s should have 3 logs", ip)
	}
}

func TestLevelCountAggregation(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	levels := []string{"INFO", "ERROR", "WARN", "DEBUG", "INFO", "ERROR", "ERROR"}

	for _, level := range levels {
		log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = time.Now()
			e.Level = level
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		})
		we.ProcessLog(log)
	}

	for _, window := range we.stateStore.AllSlidingWindows() {
		assert.Equal(t, int64(2), window.LevelCount["INFO"])
		assert.Equal(t, int64(3), window.LevelCount["ERROR"])
		assert.Equal(t, int64(1), window.LevelCount["WARN"])
		assert.Equal(t, int64(1), window.LevelCount["DEBUG"])
		assert.Equal(t, int64(7), window.Count)
	}
}

func TestStop_ClosesChannels(t *testing.T) {
	logChan := make(chan *models.LogEntry, 10)
	we := NewWindowEngine(testWindowingConfig())

	we.Start(logChan)
	we.Stop()

	_, aggOk := <-we.Aggregates()
	_, alertOk := <-we.Alerts()

	assert.False(t, aggOk, "aggregate channel should be closed")
	assert.False(t, alertOk, "alert channel should be closed")
}

func TestStart_ProcessLogsFromChannel(t *testing.T) {
	logChan := make(chan *models.LogEntry, 10)
	we := NewWindowEngine(testWindowingConfig())

	we.Start(logChan)
	defer we.Stop()

	log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Timestamp = time.Now()
		e.Fields = map[string]string{"client_ip": "10.0.0.1"}
	})

	logChan <- log

	time.Sleep(time.Millisecond * 100)

	assert.GreaterOrEqual(t, we.stateStore.SlidingWindowCount(), 1)
}

func TestEmitWindows_LogSamples(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	oldTime := time.Now().Add(-time.Hour)
	for i := 0; i < 15; i++ {
		log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = oldTime
			e.Message = "log message " + string(rune('A'+i))
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		})
		we.ProcessLog(log)
	}

	for _, window := range we.stateStore.AllSlidingWindows() {
		window.End = oldTime.Add(time.Second)
	}

	we.EmitWindows()

	select {
	case agg := <-we.Aggregates():
		assert.Len(t, agg.LogSamples, 10)
		for i, sample := range agg.LogSamples {
			assert.Equal(t, "log message "+string(rune('A'+i)), sample.Message)
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for aggregate")
	}
}

func TestEmitWindows_LogSamplesLessThan10(t *testing.T) {
	we := NewWindowEngine(testWindowingConfig())

	oldTime := time.Now().Add(-time.Hour)
	for i := 0; i < 3; i++ {
		log := testfixtures.NewLogEntry(func(e *models.LogEntry) {
			e.Timestamp = oldTime
			e.Fields = map[string]string{"client_ip": "10.0.0.1"}
		})
		we.ProcessLog(log)
	}

	for _, window := range we.stateStore.AllSlidingWindows() {
		window.End = oldTime.Add(time.Second)
	}

	we.EmitWindows()

	select {
	case agg := <-we.Aggregates():
		assert.Len(t, agg.LogSamples, 3)
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for aggregate")
	}
}
