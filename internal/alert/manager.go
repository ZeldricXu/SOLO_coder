package alert

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"log-pipeline/internal/storage"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type DedupStore interface {
	IsDuplicate(key string) (bool, error)
	Deduplicate(key string, value string, ttl time.Duration) (bool, error)
}

type AlertManager struct {
	config     *config.AlertManagerConfig
	dedupStore DedupStore
	alertChan  <-chan *models.AlertEvent
	channels   map[string]AlertChannel
	mu         sync.RWMutex
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

type AlertChannel interface {
	Send(alert *models.AlertEvent) error
	SendWithRetry(alert *models.AlertEvent, maxRetries int) error
}

type DingTalkChannel struct {
	Webhook string
	Secret  string
}

type FeishuChannel struct {
	Webhook string
}

type PagerDutyChannel struct {
	Token string
}

func NewAlertManager(cfg *config.AlertManagerConfig, redisStore *storage.RedisStore) *AlertManager {
	return NewAlertManagerWithDedup(cfg, redisStore)
}

func NewAlertManagerWithDedup(cfg *config.AlertManagerConfig, dedupStore DedupStore) *AlertManager {
	ctx, cancel := context.WithCancel(context.Background())

	am := &AlertManager{
		config:     cfg,
		dedupStore: dedupStore,
		channels:   make(map[string]AlertChannel),
		ctx:        ctx,
		cancel:     cancel,
	}

	for _, chCfg := range cfg.Channels {
		am.registerNotifierFromConfig(chCfg)
	}

	return am
}

func (am *AlertManager) registerNotifierFromConfig(chCfg config.AlertChannelConfig) {
	notifier := CreateNotifierFromConfig(chCfg)
	if notifier == nil {
		return
	}

	adapter := NewNotifierAdapter(notifier)
	am.channels[notifier.Name()] = adapter
}

func (am *AlertManager) Start(alertChan <-chan *models.AlertEvent) {
	am.alertChan = alertChan
	am.wg.Add(1)
	go am.processAlerts()
}

func (am *AlertManager) Stop() {
	am.cancel()
	am.wg.Wait()
}

func (am *AlertManager) processAlerts() {
	defer am.wg.Done()

	for {
		select {
		case <-am.ctx.Done():
			return
		case alert, ok := <-am.alertChan:
			if !ok {
				return
			}
			am.handleAlert(alert)
		}
	}
}

func (am *AlertManager) handleAlert(alert *models.AlertEvent) {
	dedupKey := fmt.Sprintf("alert:%s:%s", alert.AlertType, alert.SourceIP)

	isDup, err := am.dedupStore.IsDuplicate(dedupKey)
	if err == nil && isDup {
		return
	}

	silentPeriod := am.resolveSilentPeriod(alert)
	am.dedupStore.Deduplicate(dedupKey, alert.ID, silentPeriod)
	am.sendToChannels(alert)
}

func (am *AlertManager) resolveSilentPeriod(alert *models.AlertEvent) time.Duration {
	if alert.Source != "" {
		if am.config.SourceSilentPeriods != nil {
			if period, ok := am.config.SourceSilentPeriods[alert.Source]; ok {
				return period
			}
		}
	}
	return am.config.SilentPeriod
}

func (am *AlertManager) SetSourceSilentPeriod(source string, period time.Duration) {
	if am.config.SourceSilentPeriods == nil {
		am.config.SourceSilentPeriods = make(map[string]time.Duration)
	}
	am.config.SourceSilentPeriods[source] = period
}

func (am *AlertManager) RemoveSourceSilentPeriod(source string) {
	if am.config.SourceSilentPeriods != nil {
		delete(am.config.SourceSilentPeriods, source)
	}
}

func (am *AlertManager) GetSilentPeriod(source string) time.Duration {
	if source != "" && am.config.SourceSilentPeriods != nil {
		if period, ok := am.config.SourceSilentPeriods[source]; ok {
			return period
		}
	}
	return am.config.SilentPeriod
}

func (am *AlertManager) sendToChannels(alert *models.AlertEvent) {
	am.mu.RLock()
	defer am.mu.RUnlock()

	for name, channel := range am.channels {
		go func(name string, ch AlertChannel) {
			if err := ch.SendWithRetry(alert, 3); err != nil {
				fmt.Printf("Failed to send alert to %s after retries: %v\n", name, err)
			}
		}(name, channel)
	}
}

type RetryableSender struct {
	channel    AlertChannel
	maxRetries int
}

func NewRetryableSender(channel AlertChannel, maxRetries int) *RetryableSender {
	return &RetryableSender{
		channel:    channel,
		maxRetries: maxRetries,
	}
}

func (rs *RetryableSender) Send(alert *models.AlertEvent) error {
	return rs.channel.Send(alert)
}

func (rs *RetryableSender) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySend(rs.channel, alert, maxRetries)
}

func retrySend(ch AlertChannel, alert *models.AlertEvent, maxRetries int) error {
	var lastErr error
	backoff := 100 * time.Millisecond

	for attempt := 0; attempt <= maxRetries; attempt++ {
		err := ch.Send(alert)
		if err == nil {
			return nil
		}

		lastErr = err

		if !isRetryableError(err) {
			return err
		}

		if attempt < maxRetries {
			time.Sleep(backoff)
			backoff *= 2
		}
	}

	return fmt.Errorf("max retries (%d) exceeded: %w", maxRetries, lastErr)
}

func isRetryableError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "HTTP 500") || strings.Contains(msg, "HTTP 503")
}

func (dt *DingTalkChannel) Send(alert *models.AlertEvent) error {
	timestamp := time.Now().UnixMilli()
	sign := dt.sign(timestamp)

	url := fmt.Sprintf("%s&timestamp=%d&sign=%s", dt.Webhook, timestamp, sign)

	msg := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]string{
			"title": fmt.Sprintf("[%s] %s", strings.ToUpper(alert.Severity), alert.Title),
			"text": fmt.Sprintf(`
### **%s Alert: %s**

**Severity:** %s
**Type:** %s
**Source IP:** %s
**Count:** %d

**Description:**
%s

**Time:** %s
`,
				strings.ToUpper(alert.Severity),
				alert.Title,
				alert.Severity,
				alert.AlertType,
				alert.SourceIP,
				alert.Count,
				alert.Description,
				alert.Timestamp.Format(time.RFC3339),
			),
		},
	}

	return sendJSON(url, msg)
}

func (dt *DingTalkChannel) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySend(dt, alert, maxRetries)
}

func (dt *DingTalkChannel) sign(timestamp int64) string {
	stringToSign := fmt.Sprintf("%d\n%s", timestamp, dt.Secret)
	h := hmac.New(sha256.New, []byte(dt.Secret))
	h.Write([]byte(stringToSign))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

func (fs *FeishuChannel) Send(alert *models.AlertEvent) error {
	msg := map[string]interface{}{
		"msg_type": "interactive",
		"card": map[string]interface{}{
			"header": map[string]interface{}{
				"title": map[string]interface{}{
					"tag":     "plain_text",
					"content": fmt.Sprintf("[%s] %s", strings.ToUpper(alert.Severity), alert.Title),
				},
				"template": getFeishuTemplate(alert.Severity),
			},
			"elements": []interface{}{
				map[string]interface{}{
					"tag": "div",
					"text": map[string]interface{}{
						"tag":     "lark_md",
						"content": fmt.Sprintf("**Severity:** %s\n**Type:** %s\n**Source IP:** %s\n**Count:** %d", alert.Severity, alert.AlertType, alert.SourceIP, alert.Count),
					},
				},
				map[string]interface{}{
					"tag": "hr",
				},
				map[string]interface{}{
					"tag": "div",
					"text": map[string]interface{}{
						"tag":     "lark_md",
						"content": fmt.Sprintf("**Description:**\n%s", alert.Description),
					},
				},
			},
		},
	}

	return sendJSON(fs.Webhook, msg)
}

func (fs *FeishuChannel) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySend(fs, alert, maxRetries)
}

func getFeishuTemplate(severity string) string {
	switch severity {
	case "critical":
		return "red"
	case "warning":
		return "orange"
	case "info":
		return "blue"
	default:
		return "grey"
	}
}

func (pd *PagerDutyChannel) Send(alert *models.AlertEvent) error {
	event := map[string]interface{}{
		"routing_key": pd.Token,
		"event_action": "trigger",
		"dedup_key":   alert.ID,
		"payload": map[string]interface{}{
			"summary":   alert.Title,
			"timestamp": alert.Timestamp.Format(time.RFC3339),
			"source":    alert.SourceIP,
			"severity":  mapSeverity(alert.Severity),
			"custom_details": map[string]interface{}{
				"alert_type":  alert.AlertType,
				"count":       alert.Count,
				"description": alert.Description,
			},
		},
	}

	return sendJSON("https://events.pagerduty.com/v2/enqueue", event)
}

func (pd *PagerDutyChannel) SendWithRetry(alert *models.AlertEvent, maxRetries int) error {
	return retrySend(pd, alert, maxRetries)
}

func mapSeverity(s string) string {
	switch s {
	case "critical":
		return "critical"
	case "warning":
		return "warning"
	case "info":
		return "info"
	default:
		return "error"
	}
}

func sendJSON(url string, data interface{}) error {
	body, err := json.Marshal(data)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(body))
	if err != nil {
		return err
	}

	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	return nil
}

type RetryableHTTPError struct {
	StatusCode int
}

func (e *RetryableHTTPError) Error() string {
	return fmt.Sprintf("HTTP %d", e.StatusCode)
}
