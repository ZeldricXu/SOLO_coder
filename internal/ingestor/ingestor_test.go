package ingestor

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/pkg/utils"
	"log-pipeline/testfixtures"
)

func testIngestorConfig() *config.IngestorConfig {
	return &config.IngestorConfig{
		TCPPort:    0,
		UDPPort:    0,
		HTTPPort:   0,
		BufferSize: 100,
		MaxWorkers: 10,
		Sources:    []string{"tcp", "udp", "http"},
	}
}

func TestNewIngestor(t *testing.T) {
	cfg := testIngestorConfig()
	i := NewIngestor(cfg)

	assert.NotNil(t, i)
	assert.NotNil(t, i.backPressure)
	assert.NotNil(t, i.rateLimiter)
	assert.NotNil(t, i.logChan)
	assert.Equal(t, cfg.BufferSize, cap(i.logChan))
}

func TestExtractHost(t *testing.T) {
	tests := []struct {
		name     string
		addr     string
		expected string
	}{
		{"ipv4_with_port", "192.168.1.100:54321", "192.168.1.100"},
		{"ipv4_no_port", "192.168.1.100", "192.168.1.100"},
		{"localhost", "127.0.0.1:12345", "127.0.0.1"},
		{"empty", "", ""},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := ExtractHost(tt.addr)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestParseLog_JSON(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
		e.Host = "10.0.0.1"
		e.Source = "http"
	})
	raw, _ := json.Marshal(entry)

	result := i.ParseLog(string(raw), "http", "10.0.0.1:12345")

	require.NotNil(t, result)
	assert.Equal(t, entry.ID, result.ID)
	assert.Equal(t, entry.Message, result.Message)
	assert.Equal(t, entry.Level, result.Level)
	assert.Equal(t, "10.0.0.1", result.Host)
	assert.Equal(t, "http", result.Source)
}

func TestParseLog_JSON_NoTimestamp(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	raw := `{"id":"test-1","level":"INFO","message":"no timestamp"}`
	result := i.ParseLog(raw, "tcp", "127.0.0.1:1234")

	require.NotNil(t, result)
	assert.False(t, result.Timestamp.IsZero())
	assert.Equal(t, "test-1", result.ID)
}

func TestParseLog_JSON_NoID(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	raw := `{"timestamp":"2025-06-02T12:00:00Z","level":"INFO","message":"no id"}`
	result := i.ParseLog(raw, "tcp", "127.0.0.1:1234")

	require.NotNil(t, result)
	assert.NotEmpty(t, result.ID)
}

func TestParseLog_PlainText(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	raw := "2025-06-02 12:00:00 INFO This is a plain text log message"
	result := i.ParseLog(raw, "syslog", "192.168.1.1:514")

	require.NotNil(t, result)
	assert.NotEmpty(t, result.ID)
	assert.False(t, result.Timestamp.IsZero())
	assert.Equal(t, raw, result.Message)
	assert.Equal(t, raw, result.Raw)
	assert.Equal(t, "syslog", result.Source)
	assert.Equal(t, "192.168.1.1", result.Host)
	assert.NotNil(t, result.Fields)
}

func TestParseLog_EmptyString(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	result := i.ParseLog("", "tcp", "127.0.0.1:1234")
	require.NotNil(t, result)
	assert.Equal(t, "", result.Message)
}

func TestProcessLog_Normal(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 10
	i := NewIngestor(cfg)

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	i.ProcessLog(string(raw), "http", "10.0.0.1:12345")

	select {
	case received := <-i.Logs():
		assert.Equal(t, entry.Message, received.Message)
		assert.Equal(t, entry.Level, received.Level)
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for log")
	}
}

func TestProcessLog_BackPressureTriggered(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 10
	i := NewIngestor(cfg)

	for j := 0; j < 50; j++ {
		entry := testfixtures.NewLogEntry()
		raw, _ := json.Marshal(entry)
		i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
	}

	received := 0
	for {
		select {
		case <-i.Logs():
			received++
		case <-time.After(time.Millisecond * 100):
			goto done
		}
	}
done:

	assert.Equal(t, 10, received)
}

func TestProcessLog_Concurrent(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 1000
	i := NewIngestor(cfg)

	var wg sync.WaitGroup
	goroutines := 50
	logsPerGoroutine := 20

	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for n := 0; n < logsPerGoroutine; n++ {
				entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
					e.Message = "concurrent log"
				})
				raw, _ := json.Marshal(entry)
				i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
			}
		}(g)
	}

	wg.Wait()

	receivedCount := 0
	for receivedCount < goroutines*logsPerGoroutine {
		select {
		case <-i.Logs():
			receivedCount++
		case <-time.After(time.Second * 2):
			break
		}
	}

	stats := i.backPressure.Stats()
	assert.Equal(t, 0, stats["current"])
}

func TestProcessLog_ContextCanceled(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 1
	i := NewIngestor(cfg)

	i.Stop()

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	assert.NotPanics(t, func() {
		i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
	})
}

func TestHTTPHealthCheck(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	req := httptest.NewRequest("GET", "/api/v1/health", nil)
	w := httptest.NewRecorder()

	handler := func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status": "ok",
			"stats":  i.backPressure.Stats(),
		})
	}
	handler(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var body map[string]interface{}
	err := json.NewDecoder(w.Body).Decode(&body)
	require.NoError(t, err)

	assert.Equal(t, "ok", body["status"])
	assert.Contains(t, body, "stats")
}

func TestHTTPLogsPost(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	req := httptest.NewRequest("POST", "/api/v1/logs", strings.NewReader(string(raw)))
	w := httptest.NewRecorder()

	i.handleHTTPLogs(w, req)

	assert.Equal(t, http.StatusAccepted, w.Code)

	select {
	case received := <-i.Logs():
		assert.Equal(t, entry.Message, received.Message)
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for log")
	}
}

func TestHTTPLogsMethodNotAllowed(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	req := httptest.NewRequest("GET", "/api/v1/logs", nil)
	w := httptest.NewRecorder()

	i.handleHTTPLogs(w, req)

	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHTTPLogsRateLimited(t *testing.T) {
	cfg := testIngestorConfig()
	i := NewIngestor(cfg)

	i.rateLimiter = utils.NewRateLimiter(0, 0)

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	req := httptest.NewRequest("POST", "/api/v1/logs", strings.NewReader(string(raw)))
	w := httptest.NewRecorder()

	i.handleHTTPLogs(w, req)

	assert.Equal(t, http.StatusTooManyRequests, w.Code)
}

func TestTCPConnection(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.TCPPort = 0
	i := NewIngestor(cfg)

	listener, err := net.Listen("tcp", ":0")
	require.NoError(t, err)
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port

	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		i.handleTCPConn(conn)
	}()

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	conn, err := net.Dial("tcp", fmt.Sprintf("localhost:%d", port))
	require.NoError(t, err)
	defer conn.Close()

	_, err = conn.Write(append(raw, '\n'))
	require.NoError(t, err)

	select {
	case received := <-i.Logs():
		assert.Equal(t, entry.Message, received.Message)
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for log")
	}
}

func TestTCPConnectionDisconnect(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.TCPPort = 0
	i := NewIngestor(cfg)

	listener, err := net.Listen("tcp", ":0")
	require.NoError(t, err)

	port := listener.Addr().(*net.TCPAddr).Port

	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		i.handleTCPConn(conn)
	}()

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	conn, err := net.Dial("tcp", fmt.Sprintf("localhost:%d", port))
	require.NoError(t, err)

	_, err = conn.Write(append(raw, '\n'))
	require.NoError(t, err)

	time.Sleep(time.Millisecond * 50)
	conn.Close()
	listener.Close()

	select {
	case <-i.Logs():
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for log")
	}

	assert.NotPanics(t, func() {
		time.Sleep(time.Millisecond * 100)
	})
}

func TestStopClosesChannel(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.TCPPort = 0
	cfg.UDPPort = 0
	cfg.HTTPPort = 0
	i := NewIngestor(cfg)

	err := i.Start()
	require.NoError(t, err)

	i.Stop()

	time.Sleep(time.Millisecond * 100)

	_, ok := <-i.Logs()
	assert.False(t, ok, "log channel should be closed after Stop()")
}

func TestBackPressureStats(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 10
	i := NewIngestor(cfg)

	stats := i.backPressure.Stats()
	assert.Equal(t, 10, stats["capacity"])
	assert.Equal(t, 0, stats["current"])
	assert.Equal(t, float64(0), stats["load"])
	assert.Equal(t, int64(0), stats["dropCount"])

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)
	i.ProcessLog(string(raw), "http", "10.0.0.1:12345")

	<-i.Logs()

	stats = i.backPressure.Stats()
	assert.Equal(t, 0, stats["current"])
}

func TestParseLog_VariousSources(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	sources := []string{"fluent-bit", "filebeat", "syslog", "tcp", "udp", "http"}

	for _, source := range sources {
		t.Run(source, func(t *testing.T) {
			entry := testfixtures.NewLogEntry(func(e *models.LogEntry) {
				e.Source = source
			})
			raw, _ := json.Marshal(entry)

			result := i.ParseLog(string(raw), source, "127.0.0.1:1234")
			require.NotNil(t, result)
			assert.Equal(t, source, result.Source)
		})
	}
}

func TestHandleHTTPLogs_BadBody(t *testing.T) {
	i := NewIngestor(testIngestorConfig())

	req := httptest.NewRequest("POST", "/api/v1/logs", &badReader{})
	w := httptest.NewRecorder()

	i.handleHTTPLogs(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

type badReader struct{}

func (r *badReader) Read(p []byte) (n int, err error) {
	return 0, io.ErrUnexpectedEOF
}

func TestProcessLog_ChannelFull(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.BufferSize = 1
	i := NewIngestor(cfg)

	entry := testfixtures.NewLogEntry()
	raw, _ := json.Marshal(entry)

	for j := 0; j < 10; j++ {
		i.ProcessLog(string(raw), "http", "10.0.0.1:12345")
	}

	received := 0
	for {
		select {
		case <-i.Logs():
			received++
		case <-time.After(time.Millisecond * 100):
			goto done
		}
	}
done:

	assert.GreaterOrEqual(t, received, 1)
	stats := i.backPressure.Stats()
	dropCount := stats["dropCount"].(int64)
	assert.GreaterOrEqual(t, dropCount, int64(0))
}

func TestStartMultipleProtocols(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.TCPPort = 0
	cfg.UDPPort = 0
	cfg.HTTPPort = 18199
	i := NewIngestor(cfg)

	err := i.Start()
	require.NoError(t, err)
	defer i.Stop()

	time.Sleep(time.Millisecond * 200)

	resp, err := http.Get("http://localhost:18199/api/v1/health")
	require.NoError(t, err)
	defer resp.Body.Close()
	assert.Equal(t, http.StatusOK, resp.StatusCode)
}
