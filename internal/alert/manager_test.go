package alert

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockDedupStore struct {
	duplicates  map[string]bool
	mu          sync.Mutex
	dedupCalls  int
	isDupCalls  int
	err         error
	lastTTL     time.Duration
	lastDedupKey string
}

func newMockDedupStore() *mockDedupStore {
	return &mockDedupStore{duplicates: make(map[string]bool)}
}

func (m *mockDedupStore) IsDuplicate(key string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.isDupCalls++
	if m.err != nil {
		return false, m.err
	}
	return m.duplicates[key], nil
}

func (m *mockDedupStore) Deduplicate(key string, value string, ttl time.Duration) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.dedupCalls++
	if m.err != nil {
		return false, m.err
	}
	exists := m.duplicates[key]
	m.duplicates[key] = true
	m.lastTTL = ttl
	m.lastDedupKey = key
	return exists, nil
}

type mockAlertChannel struct {
	sentAlerts []*models.AlertEvent
	mu         sync.Mutex
	sendErr    error
	sendCount  int
	callErrs   []error
	callIndex  int
}

func newMockAlertChannel() *mockAlertChannel {
	return &mockAlertChannel{}
}

func (m *mockAlertChannel) Send(alert *models.AlertEvent) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sendCount++
	if m.callErrs != nil {
		if m.callIndex < len(m.callErrs) {
			err := m.callErrs[m.callIndex]
			m.callIndex++
			return err
		}
	}
	if m.sendErr != nil {
		return m.sendErr
	}
	m.sentAlerts = append(m.sentAlerts, alert)
	return nil
}

func (m *mockAlertChannel) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySend(m, alert, maxRetries)
}

func (m *mockAlertChannel) getSentAlerts() []*models.AlertEvent {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := make([]*models.AlertEvent, len(m.sentAlerts))
	copy(result, m.sentAlerts)
	return result
}

func (m *mockAlertChannel) getSendCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sendCount
}

func newTestAlertManager(dedup DedupStore, channel AlertChannel) *AlertManager {
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	am.channels["mock"] = channel
	return am
}

func TestAlertManager_HandleAlert_SendsToChannel(t *testing.T) {
	dedup := newMockDedupStore()
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	alert := testfixtures.NewAlertEvent()
	am.handleAlert(alert)

	time.Sleep(50 * time.Millisecond)

	sent := ch.getSentAlerts()
	require.Len(t, sent, 1)
	assert.Equal(t, alert.ID, sent[0].ID)
	assert.Equal(t, alert.Title, sent[0].Title)
	assert.Equal(t, alert.Severity, sent[0].Severity)
}

func TestAlertManager_Deduplication(t *testing.T) {
	dedup := newMockDedupStore()
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.1"
	})

	am.handleAlert(alert)
	am.handleAlert(alert)

	time.Sleep(50 * time.Millisecond)

	sent := ch.getSentAlerts()
	assert.Len(t, sent, 1, "duplicate alert should not be sent again")
	assert.Equal(t, 2, dedup.isDupCalls)
}

func TestAlertManager_DifferentAlertsNotDeduplicated(t *testing.T) {
	dedup := newMockDedupStore()
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	alert1 := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.1"
	})
	alert2 := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.2"
	})

	am.handleAlert(alert1)
	am.handleAlert(alert2)

	time.Sleep(50 * time.Millisecond)

	sent := ch.getSentAlerts()
	assert.Len(t, sent, 2, "different alerts should both be sent")
}

func TestAlertManager_SeverityLevels(t *testing.T) {
	severityTests := []struct {
		severity string
	}{
		{severity: "critical"},
		{severity: "warning"},
		{severity: "info"},
	}

	for _, tt := range severityTests {
		t.Run(tt.severity, func(t *testing.T) {
			dedup := newMockDedupStore()
			ch := newMockAlertChannel()
			am := newTestAlertManager(dedup, ch)

			alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
				a.Severity = tt.severity
				a.SourceIP = fmt.Sprintf("10.0.0.%s", tt.severity)
			})
			am.handleAlert(alert)

			time.Sleep(50 * time.Millisecond)

			sent := ch.getSentAlerts()
			require.Len(t, sent, 1)
			assert.Equal(t, tt.severity, sent[0].Severity)
		})
	}
}

func TestDingTalkPayload_Format(t *testing.T) {
	var receivedBody json.RawMessage
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		receivedBody = body
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	dt := &DingTalkChannel{
		Webhook: srv.URL + "?access_token=test",
		Secret:  "testsecret",
	}

	alert := testfixtures.NewAlertEvent()
	err := dt.Send(alert)
	require.NoError(t, err)

	var payload map[string]interface{}
	require.NoError(t, json.Unmarshal(receivedBody, &payload))

	assert.Equal(t, "markdown", payload["msgtype"])

	markdown, ok := payload["markdown"].(map[string]interface{})
	require.True(t, ok, "markdown field should be an object")
	assert.Contains(t, markdown["title"], strings.ToUpper(alert.Severity))
	assert.Contains(t, markdown["title"], alert.Title)
}

func TestFeishuPayload_Format(t *testing.T) {
	var receivedBody json.RawMessage
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		receivedBody = body
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	fs := &FeishuChannel{
		Webhook: srv.URL,
	}

	alert := testfixtures.NewAlertEvent()
	err := fs.Send(alert)
	require.NoError(t, err)

	var payload map[string]interface{}
	require.NoError(t, json.Unmarshal(receivedBody, &payload))

	assert.Equal(t, "interactive", payload["msg_type"])

	card, ok := payload["card"].(map[string]interface{})
	require.True(t, ok, "card field should be an object")

	header, ok := card["header"].(map[string]interface{})
	require.True(t, ok, "card should have header")

	title, ok := header["title"].(map[string]interface{})
	require.True(t, ok, "header should have title")
	assert.Equal(t, "plain_text", title["tag"])
	assert.Contains(t, title["content"], strings.ToUpper(alert.Severity))

	template, ok := header["template"].(string)
	require.True(t, ok, "header should have template")
	assert.Equal(t, "orange", template)

	elements, ok := card["elements"].([]interface{})
	require.True(t, ok, "card should have elements")
	assert.NotEmpty(t, elements)
}

type redirectTransport struct {
	target     string
	captured   json.RawMessage
	mu         sync.Mutex
	underlying http.RoundTripper
}

func (rt *redirectTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	body, err := io.ReadAll(req.Body)
	if err != nil {
		return nil, err
	}
	rt.mu.Lock()
	rt.captured = body
	rt.mu.Unlock()

	newReq, err := http.NewRequestWithContext(req.Context(), req.Method, rt.target, bytes.NewBuffer(body))
	if err != nil {
		return nil, err
	}
	newReq.Header = req.Header
	return rt.underlying.RoundTrip(newReq)
}

func (rt *redirectTransport) getCaptured() json.RawMessage {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	return rt.captured
}

func TestPagerDutyPayload_Format(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	transport := &redirectTransport{
		target:     srv.URL,
		underlying: http.DefaultTransport,
	}
	originalTransport := http.DefaultTransport
	http.DefaultTransport = transport
	defer func() { http.DefaultTransport = originalTransport }()

	pd := &PagerDutyChannel{
		Token: "test-routing-key",
	}

	alert := testfixtures.NewAlertEvent()
	err := pd.Send(alert)
	require.NoError(t, err)

	var payload map[string]interface{}
	require.NoError(t, json.Unmarshal(transport.getCaptured(), &payload))

	assert.Equal(t, "test-routing-key", payload["routing_key"])
	assert.Equal(t, "trigger", payload["event_action"])
	assert.Equal(t, alert.ID, payload["dedup_key"])

	pdPayload, ok := payload["payload"].(map[string]interface{})
	require.True(t, ok, "payload field should be an object")
	assert.Equal(t, alert.Title, pdPayload["summary"])
	assert.Equal(t, alert.SourceIP, pdPayload["source"])
	assert.Equal(t, mapSeverity(alert.Severity), pdPayload["severity"])

	details, ok := pdPayload["custom_details"].(map[string]interface{})
	require.True(t, ok, "payload should have custom_details")
	assert.Equal(t, alert.AlertType, details["alert_type"])
}

func TestMapSeverity(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"critical", "critical"},
		{"warning", "warning"},
		{"info", "info"},
		{"unknown", "error"},
	}

	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			assert.Equal(t, tt.expected, mapSeverity(tt.input))
		})
	}
}

func TestGetFeishuTemplate(t *testing.T) {
	tests := []struct {
		severity string
		expected string
	}{
		{"critical", "red"},
		{"warning", "orange"},
		{"info", "blue"},
		{"unknown", "grey"},
	}

	for _, tt := range tests {
		t.Run(tt.severity, func(t *testing.T) {
			assert.Equal(t, tt.expected, getFeishuTemplate(tt.severity))
		})
	}
}

func TestRetrySend_SuccessOnFirstTry(t *testing.T) {
	ch := newMockAlertChannel()
	alert := testfixtures.NewAlertEvent()

	err := retrySend(ch, alert, 3)

	assert.NoError(t, err)
	assert.Equal(t, 1, ch.getSendCount())
}

func TestRetrySend_RetryOn500(t *testing.T) {
	ch := &mockAlertChannel{
		callErrs: []error{
			fmt.Errorf("HTTP 500"),
			nil,
		},
	}
	alert := testfixtures.NewAlertEvent()

	err := retrySend(ch, alert, 3)

	assert.NoError(t, err)
	assert.Equal(t, 2, ch.getSendCount())
}

func TestRetrySend_RetryOn503(t *testing.T) {
	ch := &mockAlertChannel{
		callErrs: []error{
			fmt.Errorf("HTTP 503"),
			nil,
		},
	}
	alert := testfixtures.NewAlertEvent()

	err := retrySend(ch, alert, 3)

	assert.NoError(t, err)
	assert.Equal(t, 2, ch.getSendCount())
}

func TestRetrySend_NoRetryOnNonRetryable(t *testing.T) {
	ch := &mockAlertChannel{
		sendErr: fmt.Errorf("HTTP 404"),
	}
	alert := testfixtures.NewAlertEvent()

	err := retrySend(ch, alert, 3)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "HTTP 404")
	assert.Equal(t, 1, ch.getSendCount(), "should not retry on non-retryable error")
}

func TestRetrySend_MaxRetriesExceeded(t *testing.T) {
	ch := &mockAlertChannel{
		sendErr: fmt.Errorf("HTTP 500"),
	}
	alert := testfixtures.NewAlertEvent()

	err := retrySend(ch, alert, 2)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "max retries (2) exceeded")
	assert.Contains(t, err.Error(), "HTTP 500")
}

func TestIsRetryableError(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		expected bool
	}{
		{"HTTP 500", fmt.Errorf("HTTP 500"), true},
		{"HTTP 503", fmt.Errorf("HTTP 503"), true},
		{"HTTP 404", fmt.Errorf("HTTP 404"), false},
		{"HTTP 429", fmt.Errorf("HTTP 429"), false},
		{"nil error", nil, false},
		{"connection refused", fmt.Errorf("connection refused"), false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assert.Equal(t, tt.expected, isRetryableError(tt.err))
		})
	}
}

func TestAlertManager_DedupStoreError(t *testing.T) {
	dedup := newMockDedupStore()
	dedup.err = fmt.Errorf("redis connection lost")
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	alert := testfixtures.NewAlertEvent()
	am.handleAlert(alert)

	time.Sleep(50 * time.Millisecond)

	sent := ch.getSentAlerts()
	assert.Len(t, sent, 1, "alert should still be sent when dedup store returns error")
}

func TestAlertManager_SilentPeriod(t *testing.T) {
	dedup := newMockDedupStore()
	silentPeriod := 10 * time.Minute
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: silentPeriod,
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	ch := newMockAlertChannel()
	am.channels["mock"] = ch

	alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.1"
	})
	am.handleAlert(alert)

	assert.Equal(t, silentPeriod, dedup.lastTTL, "dedup should be called with SilentPeriod TTL")
	expectedKey := fmt.Sprintf("alert:%s:%s", alert.AlertType, alert.SourceIP)
	assert.Equal(t, expectedKey, dedup.lastDedupKey)
}

func TestDingTalkChannel_Webhook500(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	dt := &DingTalkChannel{
		Webhook: srv.URL + "?access_token=test",
		Secret:  "testsecret",
	}

	alert := testfixtures.NewAlertEvent()
	err := dt.Send(alert)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "HTTP 500")
}

func TestDingTalkChannel_WebhookSuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	dt := &DingTalkChannel{
		Webhook: srv.URL + "?access_token=test",
		Secret:  "testsecret",
	}

	alert := testfixtures.NewAlertEvent()
	err := dt.Send(alert)

	assert.NoError(t, err)
}

func TestFeishuChannel_WebhookSuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	fs := &FeishuChannel{
		Webhook: srv.URL,
	}

	alert := testfixtures.NewAlertEvent()
	err := fs.Send(alert)

	assert.NoError(t, err)
}

func TestAlertManager_ConcurrentAlerts(t *testing.T) {
	dedup := newMockDedupStore()
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
				a.AlertType = "concurrent_test"
				a.SourceIP = fmt.Sprintf("10.0.0.%d", idx)
			})
			am.handleAlert(alert)
		}(i)
	}
	wg.Wait()

	require.Eventually(t, func() bool {
		return ch.getSendCount() == 50
	}, 3*time.Second, 50*time.Millisecond, "all 50 alerts should be processed")
}

func TestDedupStore_ConcurrentAccess(t *testing.T) {
	store := newMockDedupStore()
	var wg sync.WaitGroup

	for i := 0; i < 50; i++ {
		wg.Add(2)
		go func(idx int) {
			defer wg.Done()
			key := fmt.Sprintf("key:%d", idx%10)
			store.IsDuplicate(key)
		}(i)
		go func(idx int) {
			defer wg.Done()
			key := fmt.Sprintf("key:%d", idx%10)
			store.Deduplicate(key, fmt.Sprintf("val:%d", idx), time.Minute)
		}(i)
	}
	wg.Wait()

	assert.Equal(t, 50, store.isDupCalls)
	assert.Equal(t, 50, store.dedupCalls)
}

func TestAlertManager_StartStop(t *testing.T) {
	dedup := newMockDedupStore()
	ch := newMockAlertChannel()
	am := newTestAlertManager(dedup, ch)

	alertChan := make(chan *models.AlertEvent, 1)
	am.Start(alertChan)

	alert := testfixtures.NewAlertEvent()
	alertChan <- alert

	time.Sleep(100 * time.Millisecond)

	assert.NotPanics(t, func() {
		am.Stop()
	})
}

func TestAlertManager_SourceSilentPeriod_OverrideGlobal(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
		SourceSilentPeriods: map[string]time.Duration{
			"prod-core": 1 * time.Minute,
		},
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	ch := newMockAlertChannel()
	am.channels["mock"] = ch

	alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.1"
		a.Source = "prod-core"
	})
	am.handleAlert(alert)

	assert.Equal(t, 1*time.Minute, dedup.lastTTL, "should use source-specific silent period")
}

func TestAlertManager_SourceSilentPeriod_FallbackToGlobal(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
		SourceSilentPeriods: map[string]time.Duration{
			"prod-core": 1 * time.Minute,
		},
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	ch := newMockAlertChannel()
	am.channels["mock"] = ch

	alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.2"
		a.Source = "edge-node-1"
	})
	am.handleAlert(alert)

	assert.Equal(t, 5*time.Minute, dedup.lastTTL, "should use global silent period when source not in override map")
}

func TestAlertManager_SourceSilentPeriod_EmptySource(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
		SourceSilentPeriods: map[string]time.Duration{
			"prod-core": 1 * time.Minute,
		},
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	ch := newMockAlertChannel()
	am.channels["mock"] = ch

	alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
		a.AlertType = "auth_failure_401"
		a.SourceIP = "10.0.0.3"
		a.Source = ""
	})
	am.handleAlert(alert)

	assert.Equal(t, 5*time.Minute, dedup.lastTTL, "should use global silent period when source is empty")
}

func TestAlertManager_SetSourceSilentPeriod(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
	}
	am := NewAlertManagerWithDedup(cfg, dedup)

	am.SetSourceSilentPeriod("new-source", 30*time.Second)

	assert.Equal(t, 30*time.Second, am.GetSilentPeriod("new-source"))
	assert.Equal(t, 5*time.Minute, am.GetSilentPeriod("other-source"))
}

func TestAlertManager_RemoveSourceSilentPeriod(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
		SourceSilentPeriods: map[string]time.Duration{
			"temp-source": 1 * time.Minute,
		},
	}
	am := NewAlertManagerWithDedup(cfg, dedup)

	assert.Equal(t, 1*time.Minute, am.GetSilentPeriod("temp-source"))

	am.RemoveSourceSilentPeriod("temp-source")

	assert.Equal(t, 5*time.Minute, am.GetSilentPeriod("temp-source"), "should fall back to global after removal")
}

func TestAlertManager_GetSilentPeriod_NoSource(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
	}
	am := NewAlertManagerWithDedup(cfg, dedup)

	assert.Equal(t, 5*time.Minute, am.GetSilentPeriod(""))
	assert.Equal(t, 5*time.Minute, am.GetSilentPeriod("any-source"))
}

func TestAlertManager_SourceSilentPeriod_MultipleSources(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels:     []config.AlertChannelConfig{},
		SilentPeriod: 5 * time.Minute,
		SourceSilentPeriods: map[string]time.Duration{
			"prod-core":   1 * time.Minute,
			"edge-node":   5 * time.Minute,
			"dev-service": 30 * time.Minute,
		},
	}
	am := NewAlertManagerWithDedup(cfg, dedup)
	ch := newMockAlertChannel()
	am.channels["mock"] = ch

	testCases := []struct {
		source   string
		expected time.Duration
	}{
		{"prod-core", 1 * time.Minute},
		{"edge-node", 5 * time.Minute},
		{"dev-service", 30 * time.Minute},
		{"unknown", 5 * time.Minute},
	}

	for _, tc := range testCases {
		t.Run(tc.source, func(t *testing.T) {
			dedup2 := newMockDedupStore()
			am2 := NewAlertManagerWithDedup(cfg, dedup2)
			am2.channels["mock"] = ch

			alert := testfixtures.NewAlertEvent(func(a *models.AlertEvent) {
				a.AlertType = "auth_failure_401"
				a.SourceIP = "10.0.0.1"
				a.Source = tc.source
			})
			am2.handleAlert(alert)

			assert.Equal(t, tc.expected, dedup2.lastTTL, "silent period for source=%s", tc.source)
		})
	}
}
